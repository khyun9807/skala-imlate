package com.skala.imlate.registration.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.skala.imlate.common.util.NumericTextOrder;
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
     * 반·기숙사 호수 허용 문자 — <b>숫자만</b>(운영 요청).
     *
     * <p>길이는 설정값({@code max-name-length} / {@code max-room-length})으로 따로 검사한다.
     */
    private static final Pattern DIGITS_ONLY_FIELD = Pattern.compile("^[0-9]+$");

    /**
     * 이름 허용 문자 — <b>글자만</b>(한글 완성형·영문). 숫자·기호·<b>공백</b>은 받지 않는다.
     *
     * <p>낱자(ㄱ, ㅏ 같은 자모)는 제외한다 — 이름이 될 수 없고, 조합이 덜 끝난 입력이 그대로 저장되면
     * 나중에 취소할 때 같은 값을 다시 입력하지 못한다.
     *
     * <p><b>글자 사이 공백은 허용하지 않는다(운영 요청).</b> {@code "Alice Kim"}, {@code "남궁 민수"} 처럼
     * 띄어 쓴 이름은 거부되며 붙여서 입력해야 한다. 같은 사람이 어떤 날은 띄고 어떤 날은 붙여 쓰면
     * 서로 다른 사람으로 잡혀 중복 등록·취소 불가로 이어지는데, 그 흔들림을 없애는 쪽을 택했다.
     *
     * <p>앞뒤 공백은 정규화가 지워 주므로 여기까지 오지 않는다 — 막는 것은 <u>가운데</u> 공백뿐이다.
     */
    private static final Pattern NAME_FIELD = Pattern.compile("^[가-힣A-Za-z]+$");

    /** 연속 공백을 한 칸으로 줄이기 위한 패턴. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private final ReturnRegistrationRepository registrationRepository;
    private final RegistrationWalRepository walRepository;
    private final RegistrationWriter registrationWriter;
    private final RegistrationWindowPolicy windowPolicy;
    private final CancelPasswordHasher passwordHasher;
    private final CancelAttemptGuard cancelAttemptGuard;
    private final Clock clock;
    private final int maxNameLength;
    private final int maxRoomLength;
    private final int passwordLength;

    /** 취소 비밀번호 형식(숫자만). 자릿수는 설정값으로 따로 검사한다. */
    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]+$");

    /**
     * 취소 실패 시 항상 내려주는 단일 문구.
     *
     * <p>"등록이 없음"과 "비밀번호가 다름"을 구분해 주면 그 차이로 <u>누가 오늘 등록했는지</u>를
     * 밖에서 알아낼 수 있다. 명단은 사감만 보는 정보이므로 실패는 언제나 같은 말로 답한다.
     */
    private static final String CANCEL_REJECTED_MESSAGE =
            "등록 정보 또는 비밀번호가 일치하지 않습니다. 반·이름·호수와 등록할 때 정한 비밀번호를 다시 확인해 주세요.";

    /**
     * @param registrationRepository 등록 저장소
     * @param walRepository          Redis WAL 저장소
     * @param registrationWriter     INSERT/상태변경 전용 트랜잭션 경계
     * @param windowPolicy           등록 창 정책
     * @param passwordHasher         취소 비밀번호 해시·검증기
     * @param cancelAttemptGuard     취소 비밀번호 대입 시도 제한
     * @param properties             {@code imlate.registration.*} 설정
     * @param clock                  서비스 기준 시계
     */
    public RegistrationService(ReturnRegistrationRepository registrationRepository,
                               RegistrationWalRepository walRepository,
                               RegistrationWriter registrationWriter,
                               RegistrationWindowPolicy windowPolicy,
                               CancelPasswordHasher passwordHasher,
                               CancelAttemptGuard cancelAttemptGuard,
                               ImlateProperties properties,
                               Clock clock) {
        this.registrationRepository = registrationRepository;
        this.walRepository = walRepository;
        this.registrationWriter = registrationWriter;
        this.windowPolicy = windowPolicy;
        this.passwordHasher = passwordHasher;
        this.cancelAttemptGuard = cancelAttemptGuard;
        this.clock = clock;
        this.maxNameLength = properties.registration().maxNameLength();
        this.maxRoomLength = properties.registration().maxRoomLength();
        this.passwordLength = properties.registration().cancel().passwordLength();
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
        String className = normalizeDigits(command.className(), "반", maxNameLength);
        String studentName = normalizeName(command.studentName(), "이름", maxNameLength);
        String roomNumber = normalizeDigits(command.roomNumber(), "기숙사 호수", maxRoomLength);
        String password = validatePassword(command.cancelPassword());
        String clientIp = (command.clientIp() == null || command.clientIp().isBlank())
                ? ClientIpResolver.UNKNOWN : command.clientIp();

        // 비밀번호는 WAL 을 쓰기 전에 미리 해시해 둔다. 해시 계산(PBKDF2)이 실패하면
        // 등록 자체가 성립하지 않으므로 WAL 에 흔적을 남기지 않는 편이 맞다.
        String passwordHash = passwordHasher.hash(password);

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
            ReturnRegistration row = existing.get();

            // 5-a) 취소했던 사람이 다시 등록하는 경우 — 되살린다.
            //      유니크 제약이 취소된 행에도 그대로 걸려 있어 새 행을 만들 수 없으므로,
            //      "이미 등록됨"으로 답하면 취소한 사람이 영영 재등록할 수 없게 된다.
            if (row.isCancelled()) {
                ReturnRegistration revived = registrationWriter.reactivate(row.getId(), passwordHash, registeredAt);
                if (revived != null) {
                    log.info("Registration reactivated after cancel date={} class={} room={}",
                            date, className, roomNumber);
                    // 새 등록으로 취급한다(201). 사용자 입장에서 방금 한 것은 "등록"이다.
                    return new RegistrationResult(revived, false);
                }
                // 되살릴 행이 사라졌다면 아래 INSERT 경로로 자연스럽게 흘려보낸다.
                log.warn("Reactivate target vanished — INSERT 경로로 진행한다. date={} class={}", date, className);
            } else {
                log.info("Duplicate registration (pre-check) date={} class={} room={}", date, className, roomNumber);
                return new RegistrationResult(row, true);
            }
        }

        // 5) DB INSERT — 독립 트랜잭션. 유니크 충돌은 여기서만 발생하고 바깥 흐름은 오염되지 않는다.
        try {
            ReturnRegistration saved = registrationWriter.insert(ReturnRegistration.create(
                    date, className, studentName, roomNumber, walId,
                    registeredAt, LocalDateTime.now(clock), passwordHash));
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
                ReturnRegistration row = raced.get();
                // 선행 조회 직후 다른 요청이 취소해 버린 경우. 선행 조회 때와 같은 판단을 여기서도 한다.
                if (row.isCancelled()) {
                    ReturnRegistration revived =
                            registrationWriter.reactivate(row.getId(), passwordHash, registeredAt);
                    if (revived != null) {
                        log.info("Registration reactivated after cancel (unique conflict) date={} class={}",
                                date, className);
                        return new RegistrationResult(revived, false);
                    }
                }
                log.info("Duplicate registration (unique conflict) date={} class={} room={}", date, className, roomNumber);
                return new RegistrationResult(row, true);
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
     * 등록을 취소한다(소프트 삭제). 반·이름·호수로 등록을 찾고 비밀번호로 본인임을 확인한다.
     *
     * <p><b>처리 순서와 그 이유</b>
     * <ol>
     *   <li>등록 창 확인 — 마감(21:45) 뒤에는 취소할 수 없다. 명단은 21:50 에 사감에게 이미 나갔고,
     *       그 뒤 취소를 허용하면 사감이 들고 있는 종이와 시스템이 어긋난다.
     *       늦게 마음이 바뀐 경우는 문자에 적힌 문의처로 안내한다.</li>
     *   <li>입력 검증 — 형식이 틀린 요청은 시도 횟수에서 빼주지 않는다(대입에 쓸 수 없는 요청이므로).</li>
     *   <li>시도 횟수 확인 — 상한을 넘었으면 비밀번호를 <b>보기 전에</b> 거절한다.
     *       확인 후에 세면 상한을 넘은 뒤에도 계속 대입할 수 있다.</li>
     *   <li>등록 조회 + 비밀번호 대조 — 어느 쪽이 틀렸든 결과와 문구가 같다(존재 여부 비노출).</li>
     *   <li>취소 반영 후 실패 누적 초기화.</li>
     * </ol>
     *
     * @param command 취소 커맨드(정규화 전 입력)
     * @return 취소 결과
     * @throws ApiException 검증 실패(VALIDATION_FAILED), 등록 창 밖(REGISTRATION_CLOSED),
     *                      대조 실패(CANCEL_REJECTED), 시도 초과·Redis 장애(CANCEL_LOCKED)
     */
    public CancelResult cancel(CancelCommand command) {
        if (command == null) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "취소 정보가 비어 있습니다.");
        }

        // 1) 등록 창 확인 — 마감 뒤 취소는 받지 않는다.
        windowPolicy.requireOpen();
        LocalDate date = windowPolicy.targetDate();

        // 2) 정규화 후 재검증
        String className = normalizeDigits(command.className(), "반", maxNameLength);
        String studentName = normalizeName(command.studentName(), "이름", maxNameLength);
        String roomNumber = normalizeDigits(command.roomNumber(), "기숙사 호수", maxRoomLength);
        String password = validatePassword(command.password());

        // 3) 시도 횟수 확인. Redis 를 읽을 수 없어도 여기서 막힌다(fail-closed, CancelAttemptGuard 참고).
        String personKey = cancelAttemptGuard.personKey(className, studentName, roomNumber);
        if (!cancelAttemptGuard.allowAttempt(date, personKey)) {
            throw ApiException.of(ErrorCode.CANCEL_LOCKED,
                    "취소 시도가 " + cancelAttemptGuard.maxAttempts() + "회를 넘어 오늘은 더 시도할 수 없습니다. "
                            + "문자에 안내된 문의처로 연락해 주세요.");
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Optional<ReturnRegistration> found = findExisting(date, className, studentName, roomNumber);

        // 4) 대조. 아래 세 경우는 모두 같은 결과·같은 문구다 —
        //    (a) 그런 등록이 없다  (b) 비밀번호가 없는 행이다(V2 이전·WAL 복구분)  (c) 비밀번호가 틀렸다
        //    셋을 구분해 주면 응답 차이만으로 "오늘 누가 등록했는지"를 알아낼 수 있다.
        ReturnRegistration row = found.orElse(null);
        boolean verified = row != null
                && row.hasCancelPassword()
                && passwordHasher.matches(password, row.getCancelPasswordHash());
        if (!verified) {
            long attempts = cancelAttemptGuard.recordFailure(date, personKey, now);
            log.info("Cancel rejected date={} class={} room={} attempts={} clientIp={}",
                    date, className, roomNumber, attempts, command.clientIp());
            throw ApiException.of(ErrorCode.CANCEL_REJECTED, CANCEL_REJECTED_MESSAGE);
        }

        // 5) 취소 반영. 이미 취소된 상태였다면 시각을 덮어쓰지 않고 멱등 응답한다
        //    (비밀번호는 이미 통과했으므로 본인이 새로고침한 경우다).
        boolean changed = registrationWriter.cancel(row.getId(), now);
        cancelAttemptGuard.reset(date, personKey);
        LocalDateTime cancelledAt = changed ? now : row.getCancelledAt();
        log.info("Registration cancelled date={} class={} room={} changed={}", date, className, roomNumber, changed);
        return new CancelResult(date, cancelledAt == null ? now : cancelledAt, !changed);
    }

    /**
     * 해당 일자의 <b>유효한</b> 등록 명단을 반 → 이름 순으로 조회한다. (notification 모듈이 호출한다)
     *
     * <p>취소된 등록은 제외한다 — 사감에게 가는 명단과 조회 페이지가 모두 이 메서드를 쓴다.
     *
     * @param date 복귀 대상일
     * @return 정렬된 명단(없으면 빈 목록)
     */
    @Transactional(readOnly = true)
    public List<ReturnRegistration> findByDate(LocalDate date) {
        List<ReturnRegistration> rows = registrationRepository
                .findByRegistrationDateAndCancelledAtIsNullOrderByClassNameAscStudentNameAsc(date);

        // 반을 숫자 크기 순으로 다시 정렬한다.
        //
        // DB 의 ORDER BY 는 VARCHAR 사전순이라 반이 숫자가 된 뒤로는 1, 10, 11, 2, 3 … 순이 된다.
        // 반이 9개까지는 티가 안 나다가 10개가 되는 순간 사감 명단이 조용히 뒤죽박죽이 된다.
        // 정렬은 애플리케이션에서 한다 — 하루 최대 200여 행이라 비용이 없고,
        // DB 방언(CAST 문법)에 기대지 않아 로컬 H2·운영 MySQL 이 똑같이 동작한다.
        List<ReturnRegistration> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparing(ReturnRegistration::getClassName, NumericTextOrder.INSTANCE)
                .thenComparing(ReturnRegistration::getStudentName)
                .thenComparing(ReturnRegistration::getRoomNumber, NumericTextOrder.INSTANCE));
        return sorted;
    }

    /**
     * 해당 일자의 <b>유효한</b> 등록 인원 수(취소분 제외). (notification 모듈이 호출한다)
     *
     * @param date 복귀 대상일
     * @return 등록 인원 수
     */
    @Transactional(readOnly = true)
    public long countByDate(LocalDate date) {
        return registrationRepository.countByRegistrationDateAndCancelledAtIsNull(date);
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

    /**
     * 숫자만 받는 필드(반·기숙사 호수)를 정규화·검증한다.
     *
     * @param raw        원본 입력
     * @param fieldLabel 오류 문구에 쓸 이름
     * @param maxLength  최대 길이
     * @return 검증된 값
     */
    private static String normalizeDigits(String raw, String fieldLabel, int maxLength) {
        String value = normalizeNonEmpty(raw, fieldLabel, maxLength);
        if (!DIGITS_ONLY_FIELD.matcher(value).matches()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    fieldLabel + "은(는) 숫자만 입력해 주세요.");
        }
        return value;
    }

    /**
     * 글자만 받는 필드(이름)를 정규화·검증한다.
     *
     * @param raw        원본 입력
     * @param fieldLabel 오류 문구에 쓸 이름
     * @param maxLength  최대 길이
     * @return 검증된 값
     */
    private static String normalizeName(String raw, String fieldLabel, int maxLength) {
        String value = normalizeNonEmpty(raw, fieldLabel, maxLength);
        if (!NAME_FIELD.matcher(value).matches()) {
            // 공백 때문에 걸린 것인지 따로 알려 준다 — "한글·영문만" 이라고만 하면
            // 한글로 띄어 쓴 사람은 무엇이 잘못됐는지 알 수 없다.
            String reason = value.indexOf(' ') >= 0
                    ? "은(는) 띄어쓰기 없이 붙여서 입력해 주세요."
                    : "에는 한글·영문만 사용할 수 있습니다.";
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, fieldLabel + reason);
        }
        return value;
    }

    /** 정규화 후 비어 있지 않은지, 길이가 올바른지까지만 검사한다(문자 집합은 호출자가 본다). */
    private static String normalizeNonEmpty(String raw, String fieldLabel, int maxLength) {
        String value = normalize(raw);
        if (value.isEmpty()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, fieldLabel + "을(를) 입력해 주세요.");
        }
        if (value.length() > maxLength) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    fieldLabel + "은(는) " + maxLength + "자 이내로 입력해 주세요.");
        }
        return value;
    }

    /**
     * 취소 비밀번호 형식을 검사한다(숫자 정확히 {@code passwordLength} 자리).
     *
     * <p>공백만 걷어내고 그 밖의 정규화는 하지 않는다 — 숫자에 연속 공백 축약 같은 처리는 의미가 없고,
     * 사용자가 입력한 그대로가 해시 입력이어야 등록과 취소가 같은 값을 만든다.
     *
     * @param raw 원본 입력(null 허용)
     * @return 검증된 비밀번호
     */
    private String validatePassword(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    "비밀번호 숫자 " + passwordLength + "자리를 입력해 주세요.");
        }
        if (value.length() != passwordLength || !DIGITS_ONLY.matcher(value).matches()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED,
                    "비밀번호는 숫자 " + passwordLength + "자리로 입력해 주세요.");
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
