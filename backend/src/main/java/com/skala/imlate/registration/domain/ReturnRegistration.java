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
 * <p>Lombok 을 쓰지 않으므로 생성자·getter 는 직접 작성한다. 값 변경 메서드는 두지 않는다(불변).
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

    /** JPA 전용 기본 생성자. */
    protected ReturnRegistration() {
        // JPA
    }

    private ReturnRegistration(LocalDate registrationDate, String className, String studentName,
                               String roomNumber, String walId,
                               LocalDateTime registeredAt, LocalDateTime createdAt) {
        this.registrationDate = registrationDate;
        this.className = className;
        this.studentName = studentName;
        this.roomNumber = roomNumber;
        this.walId = walId;
        this.registeredAt = registeredAt;
        this.createdAt = createdAt;
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
        return new ReturnRegistration(
                Objects.requireNonNull(registrationDate, "registrationDate"),
                Objects.requireNonNull(className, "className"),
                Objects.requireNonNull(studentName, "studentName"),
                Objects.requireNonNull(roomNumber, "roomNumber"),
                Objects.requireNonNull(walId, "walId"),
                Objects.requireNonNull(registeredAt, "registeredAt"),
                Objects.requireNonNull(createdAt, "createdAt"));
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

    @Override
    public String toString() {
        // 로그용. 개인정보가 섞이므로 INFO 이상 로그에는 직접 쓰지 않는다.
        return "ReturnRegistration{id=" + id + ", date=" + registrationDate
                + ", class=" + className + ", room=" + roomNumber + '}';
    }
}
