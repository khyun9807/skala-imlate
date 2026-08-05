package com.skala.imlate.registration.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 야간(23:30) 복귀 등록 엔티티.
 *
 * <p>{@code (registration_date, class_name, student_name, room_number)} 유니크 제약으로 같은 날 중복 등록을 막고,
 * {@code wal_id} 유니크 제약으로 Redis WAL 항목과 1:1 대응시켜 누락 복구를 가능하게 한다(R7).
 *
 * <p>Lombok 을 쓰지 않으므로 생성자·getter 는 직접 작성한다.
 *
 * <p><b>취소는 소프트 삭제다(V2).</b> 신원 정보(반·이름·호수·일자)는 끝까지 불변이고,
 * 바뀌는 것은 {@code cancelledAt} 과 {@code cancelPasswordHash} 둘뿐이다.
 * 행을 실제로 지우지 않는 이유는 Redis WAL 때문이다 — 지우면 21:50 대사가
 * "WAL 에는 있는데 DB 에 없다"고 보고 <u>취소한 등록을 되살린다</u>.
 * 상태 전이는 {@link #cancel(LocalDateTime)} / {@link #reactivate(String, LocalDateTime)} 두 개뿐이며,
 * 그 밖의 값을 바꾸는 메서드는 두지 않는다.
 */
@Entity
@Table(name = "return_registration",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_return_registration_person",
                        columnNames = {"registration_date", "class_name", "student_name", "room_number"}),
                @UniqueConstraint(name = "uk_return_registration_wal_id",
                        columnNames = {"wal_id"})
        },
        indexes = @Index(name = "idx_return_registration_date", columnList = "registration_date"))
public class ReturnRegistration {

    /** 동일인 판정 키의 구분자. {@code WalEntry#personKey()} 와 동일한 형식이어야 한다. */
    public static final String PERSON_KEY_DELIMITER = "|";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 복귀 대상일(KST 등록 시점의 날짜). */
    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    /** 반. */
    @Column(name = "class_name", nullable = false, length = 20)
    private String className;

    /** 교육생 이름. */
    @Column(name = "student_name", nullable = false, length = 20)
    private String studentName;

    /** 기숙사 호수. */
    @Column(name = "room_number", nullable = false, length = 20)
    private String roomNumber;

    /**
     * Redis WAL 항목 식별자(UUID).
     *
     * <p>스키마가 {@code CHAR(36)} 이므로 {@code columnDefinition} 을 명시한다.
     * (명시하지 않으면 {@code ddl-auto: validate} 가 VARCHAR 로 기대해 검증에 실패한다.)
     */
    @Column(name = "wal_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String walId;

    /** 등록 요청 시각(KST). */
    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    /** DB 저장 시각(KST). */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 취소 비밀번호 해시({@code CancelPasswordHasher} 형식).
     *
     * <p><b>nullable 인 이유</b> — V2 마이그레이션이 실서비스 DB 위에서 실행되므로, 이 기능 이전에
     * 등록된 행에는 값이 없다. "비밀번호 필수"는 API 검증에서 강제하고 스키마는 관대하게 둔다.
     * 값이 없는 행은 취소할 수 없다(운영진 문의로 안내한다).
     */
    @Column(name = "cancel_password_hash", length = 200)
    private String cancelPasswordHash;

    /**
     * 취소 시각(KST). {@code null} 이면 유효한 등록이다.
     *
     * <p>명단·통계 조회는 반드시 {@code cancelledAt IS NULL} 로 걸러야 한다.
     */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** JPA 전용 기본 생성자. */
    protected ReturnRegistration() {
        // JPA
    }

    private ReturnRegistration(LocalDate registrationDate, String className, String studentName,
                               String roomNumber, String walId,
                               LocalDateTime registeredAt, LocalDateTime createdAt,
                               String cancelPasswordHash) {
        this.registrationDate = registrationDate;
        this.className = className;
        this.studentName = studentName;
        this.roomNumber = roomNumber;
        this.walId = walId;
        this.registeredAt = registeredAt;
        this.createdAt = createdAt;
        this.cancelPasswordHash = cancelPasswordHash;
    }

    /**
     * 새 등록 레코드를 만든다. 입력값은 호출자(서비스)가 이미 정규화·검증한 상태여야 한다.
     *
     * @param registrationDate 복귀 대상일
     * @param className        반
     * @param studentName      이름
     * @param roomNumber       기숙사 호수
     * @param walId            Redis WAL 항목 식별자(UUID 문자열)
     * @param registeredAt     등록 요청 시각
     * @param createdAt        DB 저장 시각
     * @return 아직 영속화되지 않은 새 엔티티
     */
    public static ReturnRegistration create(LocalDate registrationDate, String className, String studentName,
                                            String roomNumber, String walId,
                                            LocalDateTime registeredAt, LocalDateTime createdAt) {
        return create(registrationDate, className, studentName, roomNumber, walId,
                registeredAt, createdAt, null);
    }

    /**
     * 취소 비밀번호 해시까지 담아 새 등록 레코드를 만든다.
     *
     * <p>해시는 {@code null} 을 허용한다 — WAL 복구 경로({@code ReconciliationService})는
     * 비밀번호를 알 수 없기 때문이다. WAL 에는 해시를 남기지 않는다(Redis 까지 새면 pepper 없는
     * 해시가 두 곳에 존재하게 되고, 복구된 행에 잘못된 해시가 붙으면 본인도 취소하지 못한다).
     * 복구된 행은 해시가 비어 취소가 불가능하며, 그 경우는 운영진 문의로 안내한다 —
     * 명단에 남아 잠기지 않는 쪽이 명단에서 사라지는 것보다 안전하다.
     *
     * @param registrationDate   복귀 대상일
     * @param className          반
     * @param studentName        이름
     * @param roomNumber         기숙사 호수
     * @param walId              Redis WAL 항목 식별자(UUID 문자열)
     * @param registeredAt       등록 요청 시각
     * @param createdAt          DB 저장 시각
     * @param cancelPasswordHash 취소 비밀번호 해시(null 허용)
     * @return 아직 영속화되지 않은 새 엔티티
     */
    public static ReturnRegistration create(LocalDate registrationDate, String className, String studentName,
                                            String roomNumber, String walId,
                                            LocalDateTime registeredAt, LocalDateTime createdAt,
                                            String cancelPasswordHash) {
        return new ReturnRegistration(
                Objects.requireNonNull(registrationDate, "registrationDate"),
                Objects.requireNonNull(className, "className"),
                Objects.requireNonNull(studentName, "studentName"),
                Objects.requireNonNull(roomNumber, "roomNumber"),
                Objects.requireNonNull(walId, "walId"),
                Objects.requireNonNull(registeredAt, "registeredAt"),
                Objects.requireNonNull(createdAt, "createdAt"),
                cancelPasswordHash);
    }

    /**
     * 등록을 취소 상태로 바꾼다(소프트 삭제).
     *
     * <p>이미 취소된 등록에 다시 호출해도 최초 취소 시각을 덮어쓰지 않는다 —
     * 재요청/새로고침이 "언제 취소했는가"라는 근거를 흐리면 안 된다.
     *
     * @param cancelledAt 취소 시각(KST)
     * @return 이번 호출로 실제 취소되었으면 true, 이미 취소 상태였으면 false
     */
    public boolean cancel(LocalDateTime cancelledAt) {
        if (this.cancelledAt != null) {
            return false;
        }
        this.cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt");
        return true;
    }

    /**
     * 취소했던 등록을 다시 유효하게 되돌린다(취소 후 재등록).
     *
     * <p>유니크 제약 {@code (일자, 반, 이름, 호수)} 때문에 같은 사람의 새 행을 만들 수 없다.
     * 그래서 재등록은 INSERT 가 아니라 기존 행의 되살리기로 처리한다.
     * 비밀번호는 이번 등록에서 새로 받은 값으로 <b>교체</b>한다(예전 비밀번호를 기억할 의무가 없다).
     *
     * @param cancelPasswordHash 새 취소 비밀번호 해시
     * @param registeredAt       재등록 요청 시각(KST)
     */
    public void reactivate(String cancelPasswordHash, LocalDateTime registeredAt) {
        this.cancelledAt = null;
        this.cancelPasswordHash = cancelPasswordHash;
        this.registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
    }

    /**
     * 동일인 판정 키({@code date|class|name|room}).
     *
     * <p>WAL ↔ DB 대사에서 {@code WalEntry.personKey()} 와 문자열 비교로 매칭하므로 형식이 완전히 같아야 한다.
     * JPA 는 필드 접근 모드로 동작하므로 이 메서드는 매핑 대상이 아니다.
     *
     * @return 동일인 판정 키
     */
    public String personKey() {
        return registrationDate + PERSON_KEY_DELIMITER + className
                + PERSON_KEY_DELIMITER + studentName
                + PERSON_KEY_DELIMITER + roomNumber;
    }

    /** 등록 PK. 저장 전에는 null. */
    public Long getId() {
        return id;
    }

    /** 복귀 대상일. */
    public LocalDate getRegistrationDate() {
        return registrationDate;
    }

    /** 반. */
    public String getClassName() {
        return className;
    }

    /** 교육생 이름. */
    public String getStudentName() {
        return studentName;
    }

    /** 기숙사 호수. */
    public String getRoomNumber() {
        return roomNumber;
    }

    /** Redis WAL 항목 식별자. */
    public String getWalId() {
        return walId;
    }

    /** 등록 요청 시각. */
    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    /** DB 저장 시각. */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** 취소 비밀번호 해시. V2 이전 등록·WAL 복구분은 null 이다. */
    public String getCancelPasswordHash() {
        return cancelPasswordHash;
    }

    /** 취소 시각. 유효한 등록이면 null. */
    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    /** 취소된 등록인지 여부. 명단·통계에서 제외해야 하는지 판단하는 기준이다. */
    public boolean isCancelled() {
        return cancelledAt != null;
    }

    /**
     * 취소 비밀번호가 설정되어 있는지 여부.
     *
     * <p>false 면 본인이라도 취소할 수 없다(V2 이전 등록 또는 WAL 복구분).
     */
    public boolean hasCancelPassword() {
        return cancelPasswordHash != null && !cancelPasswordHash.isBlank();
    }

    @Override
    public String toString() {
        // 로그용. 개인정보가 섞이므로 INFO 이상 로그에는 직접 쓰지 않는다.
        return "ReturnRegistration{id=" + id + ", date=" + registrationDate
                + ", class=" + className + ", room=" + roomNumber + '}';
    }
}
