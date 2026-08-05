package com.skala.imlate.registration.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.imlate.common.error.ApiException;
import com.skala.imlate.common.error.ErrorCode;
import com.skala.imlate.common.properties.ImlateProperties;
import com.skala.imlate.common.web.ClientIpResolver;
import com.skala.imlate.registration.domain.ReturnRegistration;
import com.skala.imlate.registration.domain.ReturnRegistrationRepository;
import com.skala.imlate.registration.wal.RegistrationWalRepository;
import com.skala.imlate.registration.wal.WalEntry;
import com.skala.imlate.registration.wal.WalStatus;

/**
 * 야간 복귀 등록 서비스. 이 시스템의 핵심 쓰기 경로다(SPEC §5.2, §5.3).
 *
 * <p><b>쓰기 순서(R7)</b>
 * <ol>
 *   <li>등록 창 확인({@link RegistrationWindowPolicy#requireOpen()}) — 마감이 무엇보다 우선한다</li>
 *   <li>입력 정규화 + 재검증 — <b>여기까지 실패하면 WAL 을 쓰지 않는다</b>.
 *       잘못된 입력이 WAL 에 남으면 대사가 쓰레기 데이터를 DB 로 복구하게 된다</li>
 *   <li>{@code walId = UUID} 생성 후 Redis WAL 에 PENDING 기록
 *       — <b>Redis 장애여도 등록은 계속 진행</b>한다(가용성 우선, WARN 로그 + WAL 미기록 표시)</li>
 *   <li>선행 중복 조회(DB READ)
 *     <ul>
 *       <li>이미 존재 → WAL 을 COMMITTED 로 표시하고 멱등 응답(duplicate=true).
 *           그 사람은 이미 DB 에 있으므로 WAL 이 담은 "등록 의도"는 충족된 것으로 본다(삭제하지 않는다)</li>
 *       <li>DB 접근 자체가 실패({@link org.springframework.dao.DataAccessException}) →
 *           <b>WAL 을 PENDING 그대로 두고</b> 예외를 전파한다(FAILED 로 바꾸지 않는다)</li>
 *     </ul>
 *   </li>
 *   <li>DB INSERT ({@link RegistrationWriter} 의 독립 트랜잭션)</li>
 *   <li>성공 → WAL 상태 COMMITTED / 유니크 충돌 → 기존 레코드 재조회 후 멱등 응답 /
 *       그 밖의 실패 → WAL 상태 FAILED 후 예외 전파</li>
 * </ol>
 *
 * <p><b>왜 WAL 이 중복 조회보다 앞인가(중요)</b> — 예전에는 "중복 조회(DB READ) → WAL append" 순서였다.
 * 그 순서에서는 <u>MySQL 이 완전히 죽으면 중복 조회 단계에서 바로 예외가 나 WAL 에 아무 흔적도 남지 않는다</u>.
 * 교육생은 500 을 받고, Redis WAL 은 비어 있으므로 22:10 대사({@code ReconciliationService})로도 복구할 수 없다.
 * 기숙사 도메인에서 명단 누락은 "교육생이 밖에서 밤을 샌다"는 뜻이므로, DB 가 완전히 죽은 순간에도
 * <b>등록 의도만큼은 WAL 에 남겨</b> 대사에서 DB 로 복구되도록 순서를 뒤집었다.
 * DB READ 실패 경로에서 WAL 을 PENDING 으로 남기는 이유도 같다 —
 * 대사는 DB 존재 여부로 복구를 판단하지만, 통계 재집계 여부는
 * {@code entry.status() != WalStatus.COMMITTED} 로 판정하므로 의미상 PENDING 이 맞다.
 *
 * <p><b>부작용(정상 동작)</b> — 같은 사람이 재제출할 때마다 새 {@code walId} 가 하나씩 쌓이므로
 * WAL 원시 항목 수({@code HLEN imlate:wal:{date}})가 DB 행 수보다 많아질 수 있다.
 * {@code ReconciliationService} 는 {@code personKey} 기준으로 dedupe 해 세므로
 * 사감에게 보고되는 "DB N / WAL N" 표기는 영향을 받지 않는다.
 *
 * <p><b>트랜잭션 경계</b> — {@code register()} 자체에는 트랜잭션을 걸지 않는다.
 * Redis I/O 구간 동안 DB 커넥션을 붙잡지 않기 위해서이며, 또한 유니크 충돌이 발생한 트랜잭션 안에서
 * 재조회를 하면 rollback-only 로 인해 커밋이 실패하기 때문이다.
 * 실제 DB 쓰기는 {@link RegistrationWriter#insert(ReturnRegistration)}(REQUIRES_NEW)에서,
 * 읽기는 Spring Data 가 메서드 단위로 여는 읽기 전용 트랜잭션에서 일어난다.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /**
     * 정규화 후 허용 문자 집합. SPEC §5.5 의 {@code ^[가-힣A-Za-z0-9 ()\-]{1,20}$} 와 같은 문자 집합이며,
     * 길이는 설정값({@code max-name-length} / {@code max-room-length})으로 따로 검사한다.
     */
    private static final Pattern ALLOWED_TEXT = Pattern.compile("^[가-힣A-Za-z0-9 ()\\-]+$");

    /** 연속 공백을 한 칸으로 줄이기 위한 패턴. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final ReturnRegistrationRepository registrationRepository;
    private final RegistrationWalRepository walRepository;
    private final RegistrationWriter registrationWriter;
    private final RegistrationWindowPolicy windowPolicy;
    private final Clock clock;
    private final int maxNameLength;
    private final int maxRoomLength;

    /**
     * @param registrationRepository 등록 저장소
     * @param walRepository          Redis WAL 저장소
     * @param registrationWriter     INSERT 전용 트랜잭션 경계
     * @param windowPolicy           등록 창 정책
     * @param properties             {@code imlate.registration.*} 설정
     * @param clock                  서비스 기준 시계
     */
    public RegistrationService(ReturnRegistrationRepository registrationRepository,
                               RegistrationWalRepository walRepository,
                               RegistrationWriter registrationWriter,
                               RegistrationWindowPolicy windowPolicy,
                               ImlateProperties properties,
                               Clock clock) {
        this.registrationRepository = registrationRepository;
        this.walRepository = walRepository;
        this.registrationWriter = registrationWriter;
        this.windowPolicy = windowPolicy;
        this.clock = clock;
        this.maxNameLength = properties.registration().maxNameLength();
        this.maxRoomLength = properties.registration().maxRoomLength();
    }

    /**
     * 등록을 처리한다. 이미 같은 사람이 등록되어 있으면 새로 만들지 않고 기존 레코드를 돌려준다(멱등).
     *
     * <p>DB 장애로 실패하더라도 검증을 통과한 요청이라면 WAL 에 PENDING 항목이 남으므로,
     * 22:10 대사에서 명단이 복구된다. 사용자는 500 을 받고 재시도하게 되지만
     * {@code personKey} 기준으로 멱등이라 중복 행은 생기지 않는다.
     *
     * @param command 등록 커맨드(정규화 전 입력)
     * @return 등록 결과. {@code duplicate=true} 면 기존 레코드
     * @throws ApiException 검증 실패(VALIDATION_FAILED), 등록 창 밖(REGISTRATION_NOT_OPEN / REGISTRATION_CLOSED)
     * @throws DataAccessException 중복 선행 조회 중 DB 접근 실패(WAL 은 PENDING 으로 남는다)
     */
    public RegistrationResult register(RegistrationCommand command) {
        if (command == null) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "등록 정보가 비어 있습니다.");
        }

        // 1) 등록 창 확인 (22:00 이후면 여기서 409). 입력이 무엇이든 마감이 우선이므로 가장 먼저 본다.
        windowPolicy.requireOpen();
        LocalDate date = windowPolicy.targetDate();

        // 2) 정규화 후 재검증 (컨트롤러의 @Valid 와 별개로 서비스 단에서 한 번 더 지킨다)
        String className = normalizeAndValidate(command.className(), "반", maxNameLength);
        String studentName = normalizeAndValidate(command.studentName(), "이름", maxNameLength);
        String roomNumber = normalizeAndValidate(command.roomNumber(), "기숙사 호수", maxRoomLength);
        String clientIp = (command.clientIp() == null || command.clientIp().isBlank())
                ? ClientIpResolver.UNKNOWN : command.clientIp();

        // 3) WAL 선행 기록(PENDING). registeredAt 은 초 단위로 잘라 응답/표시를 안정화한다.
        //    DB 를 건드리기 "전"에 남긴다 — MySQL 이 완전히 죽어도 등록 의도는 Redis 에 남아
        //    22:10 대사에서 복구된다(클래스 Javadoc 참고).
        String walId = UUID.randomUUID().toString();
        LocalDateTime registeredAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        WalEntry walEntry = new WalEntry(walId, date, className, studentName, roomNumber,
                registeredAt, WalStatus.PENDING, clientIp);
        boolean walRecorded = appendWal(walEntry);

        // 4) 선행 중복 조회 — 재제출/새로고침을 멱등 처리한다.
        //    (트랜잭션 경계: Spring Data 가 이 호출 하나에 대해 읽기 전용 트랜잭션을 연다)
        Optional<ReturnRegistration> existing;
        try {
            existing = findExisting(date, className, studentName, roomNumber);
        } catch (DataAccessException ex) {
            // DB 자체가 죽은 경우. WAL 은 PENDING 그대로 둔다(FAILED 로 바꾸지 않는다) —
            // 그래야 대사가 복구 대상으로 보고, 통계도 새 등록으로 다시 집계한다.
            log.error("Duplicate pre-check failed (DB unavailable) — WAL 을 PENDING 으로 남긴다. "
                            + "date={} class={} room={} walId={} walRecorded={}",
                    date, className, roomNumber, walId, walRecorded, ex);
            throw ex;
        }
        if (existing.isPresent()) {
            // 이미 DB 에 그 사람이 있으므로 WAL 이 담은 의도는 충족되었다. 방금 남긴 PENDING 을 정리한다.
            markWal(walRecorded, walId, date, WalStatus.COMMITTED);
            log.info("Duplicate registration (pre-check) date={} class={} room={}", date, className, roomNumber);
            return new RegistrationResult(existing.get(), true);
        }

        // 5) DB INSERT — 독립 트랜잭션. 유니크 충돌은 여기서만 발생하고 바깥 흐름은 오염되지 않는다.
        try {
            ReturnRegistration saved = registrationWriter.insert(ReturnRegistration.create(
                    date, className, studentName, roomNumber, walId,
                    registeredAt, LocalDateTime.now(clock)));
            markWal(walRecorded, walId, date, WalStatus.COMMITTED);
            log.info("Registration created id={} date={} class={} room={} walRecorded={}",
                    saved.getId(), date, className, roomNumber, walRecorded);
            return new RegistrationResult(saved, false);
        } catch (DataIntegrityViolationException ex) {
            // 동시 요청 경합으로 같은 사람이 먼저 들어간 경우. 트랜잭션이 이미 끝났으므로 안전하게 재조회한다.
            Optional<ReturnRegistration> raced = findExisting(date, className, studentName, roomNumber);
            if (raced.isPresent()) {
                // 사람 기준으로는 DB 에 확실히 존재하므로 WAL 도 COMMITTED 로 정리한다.
                markWal(walRecorded, walId, date, WalStatus.COMMITTED);
                log.info("Duplicate registration (unique conflict) date={} class={} room={}", date, className, roomNumber);
                return new RegistrationResult(raced.get(), true);
            }
            markWal(walRecorded, walId, date, WalStatus.FAILED);
            log.error("Registration insert conflicted but no existing row found date={} class={} room={}",
                    date, className, roomNumber, ex);
            throw ApiException.of(ErrorCode.INTERNAL_ERROR,
                    "등록 처리 중 충돌이 발생했습니다. 잠시 후 다시 시도해 주세요.", ex);
        } catch (RuntimeException ex) {
            markWal(walRecorded, walId, date, WalStatus.FAILED);
            log.error("Registration insert failed date={} class={} room={} walId={}",
                    date, className, roomNumber, walId, ex);
            throw ex;
        }
    }

    /**
     * 해당 일자의 등록 명단을 반 → 이름 순으로 조회한다. (notification 모듈이 호출한다)
     *
     * @param date 복귀 대상일
     * @return 정렬된 명단(없으면 빈 목록)
     */
    @Transactional(readOnly = true)
    public List<ReturnRegistration> findByDate(LocalDate date) {
        return registrationRepository.findByRegistrationDateOrderByClassNameAscStudentNameAsc(date);
    }

    /**
     * 해당 일자의 등록 인원 수. (notification 모듈이 호출한다)
     *
     * @param date 복귀 대상일
     * @return 등록 인원 수
     */
    @Transactional(readOnly = true)
    public long countByDate(LocalDate date) {
        return registrationRepository.countByRegistrationDate(date);
    }

    /**
     * 입력을 정규화한다. 앞뒤 공백 제거 후 연속 공백을 한 칸으로 줄인다.
     * 대소문자 변환은 하지 않는다(한글·영문 그대로 보존).
     *
     * @param raw 원본 입력(null 허용)
     * @return 정규화된 문자열(null 이면 빈 문자열)
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return WHITESPACE_RUN.matcher(raw.trim()).replaceAll(" ");
    }

    /** 정규화 후 비어 있지 않은지, 길이/문자 집합이 올바른지 검사한다. */
    private static String normalizeAndValidate(String raw, String fieldLabel, int maxLength) {
        String value = normalize(raw);
        if (value.isEmpty()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, fieldLabel + "을(를) 입력해 주세요.");
        }
        if (value.length() > maxLength) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    fieldLabel + "은(는) " + maxLength + "자 이내로 입력해 주세요.");
        }
        if (!ALLOWED_TEXT.matcher(value).matches()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    fieldLabel + "에는 한글·영문·숫자와 공백, 괄호, 하이픈만 사용할 수 있습니다.");
        }
        return value;
    }

    private Optional<ReturnRegistration> findExisting(LocalDate date, String className,
                                                      String studentName, String roomNumber) {
        return registrationRepository.findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
                date, className, studentName, roomNumber);
    }

    /**
     * WAL 선행 기록. Redis 장애는 등록 실패로 이어지지 않는다(가용성 우선).
     *
     * @return 실제로 WAL 에 기록되었으면 true
     */
    private boolean appendWal(WalEntry entry) {
        try {
            walRepository.append(entry);
            return true;
        } catch (RuntimeException ex) {
            log.warn("WAL append failed — 등록은 계속 진행합니다(WAL 미기록). date={} walId={} cause={}",
                    entry.registrationDate(), entry.walId(), ex.toString());
            return false;
        }
    }

    /** WAL 에 기록된 경우에만 상태를 갱신한다(내부에서 예외를 삼킨다). */
    private void markWal(boolean walRecorded, String walId, LocalDate date, WalStatus status) {
        if (walRecorded) {
            walRepository.updateStatus(walId, date, status);
        }
    }
}
