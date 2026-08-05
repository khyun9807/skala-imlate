# imlate — 기숙사 야간 복귀 등록 시스템 · 구현 계약서 (SPEC)

> 이 문서는 **모든 모듈이 반드시 따라야 하는 단일 진실 공급원(single source of truth)** 이다.
> 클래스명·패키지·시그니처·API 스키마·Redis 키·DB 스키마는 여기에 적힌 그대로 구현한다.
> 여기서 벗어나면 다른 모듈과 컴파일이 깨진다.

---

## 0. 요구사항 요약 (request.md)

| # | 요구 | 구현 위치 |
|---|---|---|
| R1 | 기숙 이용 교육생이 **22:00까지** 웹에서 23:30 복귀 등록 | `registration` 모듈 + 프론트 `/` |
| R2 | 등록 항목: **반 / 이름 / 기숙사 호수** | `ReturnRegistration` |
| R3 | **22:10** 에 사감 2명에게 문자 + 이메일 발송, 0명이면 미발송 | `notification` 모듈 스케줄러 |
| R4 | 목록에 반·이름·호수 포함, 보기 좋은 텍스트 | `CurfewNoticeRenderer` |
| R5 | 미니멀 · 전 디바이스 반응형 · 검증까지 완료 | `frontend` + Playwright |
| R6 | 이전 입력값 기억 → 자동 채움 | 프론트 localStorage |
| R7 | Redis에 WAL 1회 → DB 1회 (누락 방지) | `registration.wal` |
| R8 | 22:00 마감 후 Redis ↔ DB 대사(검증) → 조회 페이지 노출 | `ReconciliationService` + 프론트 `/lookup` |
| R9 | 조회 페이지 주소 + 안내/통계 문구를 문자·이메일로 발송 | `notification` |
| R10 | 설정 파일 분리(키/비번/AWS) | `application-*.yml` + `imlate.*` properties |
| R11 | 문자=Aligo, 메일=Amazon SES | `sms.AligoSmsSender`, `email.SesEmailSender` |
| R12 | AWS(EC2/RDS/ElastiCache) + Terraform | `infra/terraform` |
| R13 | Java/Spring, MySQL, JPA / Vue.js | 전체 |
| R14 | Rate limiter (DDoS·악의적 과다요청 대응, 성능 무해) | `ratelimit` 모듈 |
| R15 | 총 방문자수/일별 방문자수/등록수 통계 기록·활용 | `stats` 모듈 |

교육생 규모 ≈ 200명 → 피크 트래픽은 낮음. 설계는 단순·견고 우선.

---

## 1. 기술 스택 (확정)

| 영역 | 선택 | 비고 |
|---|---|---|
| 언어 | **Java 21** (`options.release = 21`) | 로컬 JDK 23으로 크로스컴파일 |
| 프레임워크 | **Spring Boot 3.4.1** | 이미 `backend/build.gradle` 작성됨 — **수정 금지** |
| 빌드 | Gradle 8.14 (wrapper 포함) | `backend/gradlew` |
| ORM | Spring Data JPA (Hibernate) | `ddl-auto: validate` (운영) |
| 마이그레이션 | **Flyway** (`db/migration/V1__init.sql`) | 운영/로컬. 테스트는 `create-drop` |
| DB | MySQL 8 (AWS RDS) / 테스트는 H2 MySQL 모드 | |
| 캐시·WAL | Redis 7 (AWS ElastiCache) | Lettuce |
| 메일 | AWS SDK v2 **SES v2** (`software.amazon.awssdk:sesv2`) | |
| 문자 | **Aligo** REST (`https://apis.aligo.in/send/`) | `RestClient` |
| 프론트 | **Vue 3 + Vite + TypeScript**, vue-router 4 | 상태관리 라이브러리 없음 |
| E2E | Playwright | 반응형 검증 |
| IaC | Terraform (AWS provider) | |

**Lombok은 사용하지 않는다.** 생성자·getter는 직접 작성한다. DTO는 `record`.

---

## 2. 디렉터리 / 파일 소유권

```
skala-imlate/
├─ README.md                      docs 담당
├─ .gitignore                     foundation 담당
├─ docker-compose.yml             foundation 담당 (mysql8 + redis7 로컬)
├─ docs/
│  ├─ SPEC.md                     (본 문서, 수정 금지)
│  ├─ ARCHITECTURE.md             docs 담당
│  ├─ API.md                      docs 담당
│  ├─ OPERATIONS.md               docs 담당
│  └─ DEPLOYMENT.md               docs 담당
├─ backend/
│  ├─ build.gradle settings.gradle gradlew*     (작성 완료 — 수정 금지)
│  └─ src/main/java/com/skala/imlate/
│     ├─ ImlateApplication.java                 (작성 완료)
│     ├─ common/…                               foundation 담당
│     ├─ registration/…                         registration 담당
│     ├─ notification/…                         notification 담당
│     ├─ ratelimit/…                            ratelimit 담당
│     └─ stats/…                                stats 담당
│  └─ src/main/resources/
│     ├─ application.yml, application-local.yml,
│     │  application-prod.yml, application-secret.yml.example   foundation 담당
│     ├─ db/migration/V1__init.sql                              foundation 담당
│     └─ redis/rate_limit_token_bucket.lua                      ratelimit 담당
│  └─ src/test/java/com/skala/imlate/…                          tests 담당
├─ frontend/                                    frontend 담당
└─ infra/terraform/                             infra 담당
```

**규칙: 자기 소유 디렉터리 밖의 파일은 생성·수정하지 않는다.** 다른 모듈이 만들 클래스는
본 문서의 시그니처를 신뢰하고 그대로 호출한다.

---

## 3. 시간 / 운영 정책

- 모든 시각은 **Asia/Seoul** 기준. 서버는 `Clock` 빈(`ClockConfig`)을 주입받아 사용한다.
  **`LocalDate.now()` / `LocalDateTime.now()` 를 인자 없이 호출하지 않는다.** 항상 `now(clock)`.
- `registrationDate` = 등록 시점의 KST 날짜(= 그날 밤 복귀 대상일).
- 등록 창: `[open-time(기본 00:00), close-time(기본 22:00))`. 22:00:00 이후 등록 거부.
- 사감 발송: `close-time + 10분` = 22:10 (cron 설정값).
- 원래 통금 22:30, 연장 통금 23:30 (안내 문구용 값).

---

## 4. 공통 모듈 계약 (`com.skala.imlate.common`) — foundation 담당

### 4.1 설정 프로퍼티 (`common.properties`)

모두 `@ConfigurationProperties`, **record 대신 불변 클래스 + 생성자 바인딩**(`@ConstructorBinding` 불필요, 단일 생성자면 자동)을 쓰되,
중첩 타입은 `record` 로 선언해도 된다. 접두어와 필드명은 아래를 그대로 따른다.

```java
package com.skala.imlate.common.properties;

@ConfigurationProperties(prefix = "imlate")
public record ImlateProperties(
        String timezone,                 // "Asia/Seoul"
        Registration registration,
        Wal wal,
        Lookup lookup,
        Admin admin
) {
    public record Registration(LocalTime openTime, LocalTime closeTime,
                               LocalTime returnTime, LocalTime curfewTime,
                               int maxNameLength, int maxRoomLength) {}
    public record Wal(String keyPrefix, int ttlDays) {}
    public record Lookup(String baseUrl, String tokenSecret, int tokenTtlHours) {}
    public record Admin(String apiKey) {}
}
```

```java
@ConfigurationProperties(prefix = "imlate.notification")
public record NotificationProperties(
        boolean enabled,
        String dispatchCron,          // "0 10 22 * * *"
        String retryCron,             // "0 25,40 22 * * *"
        int maxAttempts,              // 3
        long lockTtlSeconds,          // 300
        List<Supervisor> supervisors
) {
    public record Supervisor(String name, String phone, String email) {}
}
```

```java
@ConfigurationProperties(prefix = "imlate.sms")
public record SmsProperties(String provider,   // "aligo" | "noop"
                            Aligo aligo) {
    public record Aligo(String apiUrl, String apiKey, String userId,
                        String sender, boolean testMode, int connectTimeoutMs, int readTimeoutMs) {}
}

@ConfigurationProperties(prefix = "imlate.email")
public record EmailProperties(String provider, // "ses" | "noop"
                              Ses ses) {
    public record Ses(String region, String from, String fromName, String configurationSet) {}
}

@ConfigurationProperties(prefix = "imlate.rate-limit")
public record RateLimitProperties(boolean enabled, boolean failOpen,
                                  Rule global, Rule register, Rule lookup,
                                  List<String> trustedProxies, int localFallbackPermitsPerMinute) {
    public record Rule(long capacity, long refillTokens, long refillPeriodSeconds) {}
}

@ConfigurationProperties(prefix = "imlate.stats")
public record StatsProperties(boolean enabled, int retentionDays, String snapshotCron) {}
```

### 4.2 에러 계약 (`common.error`)

```java
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    REGISTRATION_CLOSED(HttpStatus.CONFLICT),
    REGISTRATION_NOT_OPEN(HttpStatus.CONFLICT),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);
    public HttpStatus status();
}

public class ApiException extends RuntimeException {
    public ApiException(ErrorCode code, String message);
    public ApiException(ErrorCode code, String message, Throwable cause);
    public ErrorCode code();
    // 정적 팩토리: ApiException.of(ErrorCode, String)
}

public record ErrorResponse(String code, String message, String path, OffsetDateTime timestamp,
                            List<FieldError> errors) {
    public record FieldError(String field, String message) {}
}

@RestControllerAdvice
public class GlobalExceptionHandler { /* ApiException, MethodArgumentNotValidException,
                                         ConstraintViolationException, Exception 처리 */ }
```

응답 바디 예:
```json
{ "code":"REGISTRATION_CLOSED", "message":"등록 마감 시간(22:00)이 지났습니다.",
  "path":"/api/v1/registrations", "timestamp":"2026-08-05T22:03:11+09:00", "errors":[] }
```

### 4.3 시간·토큰·유틸 (`common.config`, `common.security`)

```java
@Configuration
public class ClockConfig {
    @Bean public Clock clock(ImlateProperties props);   // Clock.system(ZoneId.of(props.timezone()))
    @Bean public ZoneId serviceZoneId(ImlateProperties props);
}
```

```java
package com.skala.imlate.common.security;

@Component
public class AccessTokenService {
    /** date + 만료시각을 HMAC-SHA256 서명한 URL-safe 토큰 생성 */
    public String issue(LocalDate date);
    /** 유효하면 true. 서명 불일치·만료·형식 오류는 false */
    public boolean verify(LocalDate date, String token);
    /** 검증 실패 시 ApiException(FORBIDDEN) 던짐 */
    public void requireValid(LocalDate date, String token);
}
```
토큰 포맷: `base64url(expEpochSeconds) + "." + base64url(hmacSha256(secret, date + ":" + exp))`

```java
@Component
public class AdminKeyGuard {
    /** X-Admin-Key 헤더 검증. 불일치 시 ApiException(UNAUTHORIZED) */
    public void require(String headerValue);
}
```

```java
package com.skala.imlate.common.web;
public final class ClientIpResolver {
    public static String resolve(HttpServletRequest request);  // X-Forwarded-For 첫 IP → 없으면 remoteAddr
}
```

### 4.4 Redis 설정 (`common.config.RedisConfig`)

```java
@Bean StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf);
@Bean("imlateObjectMapper") ObjectMapper imlateObjectMapper();  // JavaTimeModule, WRITE_DATES_AS_TIMESTAMPS off
```
WAL 값 직렬화는 `StringRedisTemplate` + `ObjectMapper` 로 직접 JSON 문자열을 다룬다
(제네릭 직렬화기 미사용 → 버전 호환 안정).

### 4.5 웹 공통

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
  // CORS: imlate.web.allowed-origins (기본 http://localhost:5173)
  // 인터셉터 등록은 각 모듈이 자기 WebMvcConfigurer 로 추가 (ratelimit, stats)
}
```
`application.yml` 에 `imlate.web.allowed-origins` 를 리스트로 둔다.

---

## 5. registration 모듈 계약 — registration 담당

패키지 `com.skala.imlate.registration`

### 5.1 엔티티

```java
package com.skala.imlate.registration.domain;

@Entity
@Table(name = "return_registration",
       uniqueConstraints = {
         @UniqueConstraint(name="uk_return_registration_person",
             columnNames={"registration_date","class_name","student_name","room_number"}),
         @UniqueConstraint(name="uk_return_registration_wal_id", columnNames={"wal_id"})
       },
       indexes = @Index(name="idx_return_registration_date", columnList="registration_date"))
public class ReturnRegistration {
    Long id; LocalDate registrationDate; String className; String studentName;
    String roomNumber; String walId; LocalDateTime registeredAt; LocalDateTime createdAt;
    // protected 기본 생성자 + 정적 팩토리 create(...) + getter만
}
```

```java
public interface ReturnRegistrationRepository extends JpaRepository<ReturnRegistration, Long> {
    List<ReturnRegistration> findByRegistrationDateOrderByClassNameAscStudentNameAsc(LocalDate date);
    Optional<ReturnRegistration> findByRegistrationDateAndClassNameAndStudentNameAndRoomNumber(
        LocalDate date, String className, String studentName, String roomNumber);
    long countByRegistrationDate(LocalDate date);
    boolean existsByWalId(String walId);
}
```

### 5.2 WAL (Redis)

```java
package com.skala.imlate.registration.wal;

public enum WalStatus { PENDING, COMMITTED, FAILED }

public record WalEntry(String walId, LocalDate registrationDate, String className,
                       String studentName, String roomNumber,
                       LocalDateTime registeredAt, WalStatus status, String clientIp) {
    public WalEntry withStatus(WalStatus s);
    /** 동일인 판정 키 */
    public String personKey();          // date|class|name|room
}

@Repository
public class RegistrationWalRepository {
    public void append(WalEntry entry);                 // HSET + EXPIRE (ttlDays)
    public void updateStatus(String walId, LocalDate date, WalStatus status);
    public List<WalEntry> findAllByDate(LocalDate date);
    public Optional<WalEntry> find(LocalDate date, String walId);
    public long countByDate(LocalDate date);
    public boolean isAvailable();                       // Redis ping 실패 시 false
}
```
키: `imlate:wal:{yyyy-MM-dd}` (HASH, field=walId, value=WalEntry JSON), TTL = `imlate.wal.ttl-days`.

**쓰기 순서(핵심 R7):**
1. `windowPolicy.requireOpen()` — 등록 창 확인(마감이면 409)
2. 입력 정규화 + 재검증 — **여기까지 실패하면 WAL 을 쓰지 않는다**(잘못된 입력이 WAL 에 남아 대사가 유령 인원을 복구하면 안 된다)
3. `walId = UUID.randomUUID()` → `walRepository.append(entry with PENDING)`
   — Redis 장애 시 로그 경고 후 **계속 진행**(가용성 우선)
4. 중복 선행 조회(DB READ)
   - 이미 존재 → `updateStatus(walId, COMMITTED)` 후 `RegistrationResult(existing, true)` (WAL 항목은 지우지 않는다)
   - `DataAccessException`(DB 장애) → **WAL 은 PENDING 그대로 두고** 예외 전파 → 500
5. DB INSERT (`RegistrationWriter#insert`, `REQUIRES_NEW`)
6. 성공 → `updateStatus(COMMITTED)` / 유니크 충돌 → 기존 레코드 재조회 후 `COMMITTED` + `duplicate=true` /
   그 밖의 실패 → `updateStatus(FAILED)` 후 예외 전파

> **왜 중복 선행 조회를 WAL append 뒤로 옮겼는가** — 선행 조회가 앞에 있으면 MySQL 이 완전히 죽었을 때
> WAL 기록에 도달하기도 전에 500 이 나서 Redis 에 아무 흔적도 남지 않고, 22:10 대사로도 복구할 수 없다.
> 기숙사 도메인에서 "명단 누락 = 교육생이 밖에서 밤을 샌다" 이므로, **DB 장애 중의 등록 의도도 WAL 에 남겨
> 대사에서 복구되게** 한다(4단계 DB 장애 시 `FAILED` 가 아니라 `PENDING` 으로 두는 이유도 같다 —
> `PENDING` 이어야 §5.4 의 통계 재집계 판정 `entry.status() != COMMITTED` 가 성립한다).

> **부작용(정상)** — WAL 원시 항목 수(`HLEN imlate:wal:{date}`)는 DB 행 수보다 많아질 수 있다.
> 같은 사람이 재제출할 때마다 3단계에서 `walId` 가 하나씩 더 쌓이기 때문이다.
> 대사(§5.4)는 `personKey` 기준으로 dedupe 해서 세므로 보고되는 `walCount` 와 사감 명단은 영향을 받지 않는다.

### 5.3 정책 / 서비스

```java
package com.skala.imlate.registration.service;

@Component
public class RegistrationWindowPolicy {
    public LocalDate targetDate();                  // KST 오늘
    public boolean isOpen();
    public void requireOpen();                      // 닫혔으면 ApiException(REGISTRATION_CLOSED/NOT_OPEN)
    public RegistrationWindow describe();           // 아래 DTO
}

public record RegistrationWindow(LocalDate date, boolean open,
                                 OffsetDateTime serverTime, OffsetDateTime opensAt,
                                 OffsetDateTime closesAt, LocalTime returnTime,
                                 LocalTime curfewTime, long secondsUntilClose) {}
```

```java
@Service
public class RegistrationService {
    @Transactional
    public RegistrationResult register(RegistrationCommand command);
    @Transactional(readOnly = true)
    public List<ReturnRegistration> findByDate(LocalDate date);
    @Transactional(readOnly = true)
    public long countByDate(LocalDate date);
}

public record RegistrationCommand(String className, String studentName,
                                  String roomNumber, String clientIp) {}
public record RegistrationResult(ReturnRegistration registration, boolean duplicate) {}
```

정규화 규칙(서비스에서 수행): 앞뒤 공백 제거, 연속 공백 1칸으로 축약, 반/호수는 대문자화 없음(한글 허용).

### 5.4 대사(Reconciliation) — R8

```java
package com.skala.imlate.registration.service;

@Service
public class ReconciliationService {
    /** WAL ↔ DB 비교. WAL에만 있는(=DB 누락) 항목은 DB로 복구 후 결과에 recovered 로 표기 */
    @Transactional
    public ReconciliationReport reconcile(LocalDate date);
    /** 복구 없이 비교만 */
    @Transactional(readOnly = true)
    public ReconciliationReport inspect(LocalDate date);
}

public record ReconciliationReport(LocalDate date, String status,   // CONSISTENT | RECOVERED | MISMATCH | WAL_UNAVAILABLE
                                   long dbCount, long walCount, long recoveredCount,
                                   List<String> walOnly,   // "1반/홍길동/302"
                                   List<String> dbOnly,
                                   OffsetDateTime checkedAt) {}
```
판정: WAL 사용 불가 → `WAL_UNAVAILABLE`. 차이 없음 → `CONSISTENT`. 복구 성공 후 일치 → `RECOVERED`.
DB에만 있는 항목이 남으면 → `MISMATCH`(WAL TTL 만료 가능성이므로 경고 수준).
`FAILED`/`PENDING` 상태 WAL 항목도 실제 DB 존재 여부로만 판단한다.

### 5.5 REST API

`POST /api/v1/registrations`
```json
요청 { "className":"1반", "studentName":"홍길동", "roomNumber":"302" }
201  { "id":12, "registrationDate":"2026-08-05", "className":"1반", "studentName":"홍길동",
       "roomNumber":"302", "registeredAt":"2026-08-05T21:03:11", "duplicate":false,
       "returnTime":"23:30" }
```
- 중복 등록이면 **200 OK + `"duplicate": true`** (기존 레코드 반환, 새로 만들지 않음)
- 마감 후 → 409 `REGISTRATION_CLOSED`
- 검증: 각 필드 `@NotBlank`, className ≤ 20자, studentName ≤ 20자, roomNumber ≤ 20자,
  제어문자 금지 정규식 `^[가-힣A-Za-z0-9 ()\\-]{1,20}$`

`GET /api/v1/registrations/window` → `RegistrationWindow` JSON (프론트가 서버시간 기준으로 카운트다운)

`GET /api/v1/registrations/summary` → `{ "date":"2026-08-05", "count":12, "open":true }` (PII 없음, 공개)

`GET /api/v1/lookup?date=2026-08-05&token=…` → **사감용 조회 페이지 데이터**
```json
{ "date":"2026-08-05", "generatedAt":"2026-08-05T22:10:00+09:00", "totalCount":12,
  "returnTime":"23:30", "curfewTime":"22:30",
  "items":[{"no":1,"className":"1반","studentName":"홍길동","roomNumber":"302",
            "registeredAt":"2026-08-05T21:03:11"}],
  "byClass":[{"className":"1반","count":5}],
  "byRoom":[{"roomNumber":"302","count":2}],
  "verification":{"status":"CONSISTENT","dbCount":12,"walCount":12,"recoveredCount":0,
                  "walOnly":[],"dbOnly":[],"checkedAt":"…"},
  "stats":{"todayVisitors":88,"todayPageViews":210,"totalVisitors":540,"totalRegistrations":320}
}
```
- `date` 파라미터 생략 시 오늘. `token` 필수 (`AccessTokenService.requireValid`).
- 이 응답 조립은 `LookupController`(registration 담당)가 `ReconciliationService` +
  `RegistrationService` + `StatsQueryService`(stats 담당, 5.6 인터페이스) 를 사용.

### 5.6 다른 모듈에 노출/의존하는 인터페이스

registration 은 **stats 모듈의 `StatsQueryService`** 를 주입받아 `statsSnapshot()` 을 쓴다(§7).
notification 모듈은 **registration 의 `RegistrationService`, `ReconciliationService`** 를 주입받는다.

---

## 6. notification 모듈 계약 — notification 담당

패키지 `com.skala.imlate.notification`

### 6.1 발송 채널 추상화

```java
package com.skala.imlate.notification.channel;

public record SendResult(boolean success, String providerMessageId, String errorMessage) {
    public static SendResult ok(String id);
    public static SendResult fail(String message);
}

public interface SmsSender {
    /** title 은 LMS 제목(90바이트 초과 시 자동 LMS). 실패해도 예외 대신 SendResult 반환 */
    SendResult send(String toPhone, String title, String message);
    String providerName();
}

public interface EmailSender {
    SendResult send(String toEmail, String subject, String textBody, String htmlBody);
    String providerName();
}
```

구현:
- `AligoSmsSender` — `@ConditionalOnProperty(prefix="imlate.sms", name="provider", havingValue="aligo")`
  - POST `application/x-www-form-urlencoded`: `key,user_id,sender,receiver,msg,title,msg_type,testmode_yn`
  - 90바이트(EUC-KR 기준 대략) 초과 시 `msg_type=LMS`, 아니면 `SMS`
  - 응답 JSON `result_code` 가 `"1"` 또는 `1` 이면 성공, 그 외 `message` 를 에러로
- `NoopSmsSender` — `havingValue="noop"`, `matchIfMissing=true`. 로그만 남기고 성공 반환(로컬/테스트 기본)
- `SesEmailSender` — `provider=ses`. `SesV2Client` 빈은 `SesClientConfig` 에서 생성
  (`region`, `DefaultCredentialsProvider`). 실패 시 `SendResult.fail`
- `NoopEmailSender` — `provider=noop`, `matchIfMissing=true`

전화번호 정규화: 숫자만 남기고 `010…` 형태로. 이메일/전화 비어 있으면 해당 채널 SKIP.

### 6.2 문구 렌더링 (R4, R9)

```java
package com.skala.imlate.notification.template;

public record NoticePayload(LocalDate date, List<Row> rows, String lookupUrl,
                            LocalTime returnTime, LocalTime curfewTime,
                            ReconciliationReport verification, StatsSnapshot stats) {
    public record Row(int no, String className, String studentName, String roomNumber) {}
}

@Component
public class CurfewNoticeRenderer {
    public String smsTitle(NoticePayload p);   // "[기숙사] 8/5 23:30 복귀 12명"
    public String smsBody(NoticePayload p);    // 문자용 텍스트(간결, 조회 URL 포함)
    public String emailSubject(NoticePayload p);
    public String emailText(NoticePayload p);  // 고정폭 표 형태
    public String emailHtml(NoticePayload p);  // 인라인 CSS, 모바일 가독성
}
```
문자 본문 예시(반드시 "보기 좋은" 정렬·요약 포함):
```
[기숙사 야간복귀 명단]
8월 5일(수) 23:30 복귀 12명

· 1반 (5명)
  홍길동 302 / 김철수 305 …
· 2반 (7명)
  …

※ 22:30 이후 문은 잠기며 23:30에 일괄 개방됩니다.
검증: DB 12 / WAL 12 (일치)
전체 명단: https://…/lookup?date=2026-08-05&token=…
```
이메일 텍스트는 `반 | 이름 | 호수` 고정폭 표 + 통계 + 안내 문구.
HTML은 표 + 요약 카드. 한글 깨짐 방지를 위해 charset UTF-8 명시.

### 6.3 발송 이력 엔티티

```java
package com.skala.imlate.notification.domain;

@Entity @Table(name="notification_dispatch",
  indexes=@Index(name="idx_notification_dispatch_date", columnList="dispatch_date"))
public class NotificationDispatch {
    Long id; LocalDate dispatchDate; String channel;      // SMS | EMAIL
    String recipientName; String recipient; String status; // SUCCESS | FAILED | SKIPPED
    int attempt; int targetCount; String providerMessageId;
    String errorMessage; LocalDateTime sentAt;
}
public interface NotificationDispatchRepository extends JpaRepository<NotificationDispatch, Long> {
    List<NotificationDispatch> findByDispatchDate(LocalDate date);
    boolean existsByDispatchDateAndChannelAndRecipientAndStatus(LocalDate d, String c, String r, String s);
    long countByDispatchDateAndStatus(LocalDate date, String status);
}
```

### 6.4 오케스트레이션 & 스케줄러

```java
@Service
public class CurfewNotificationService {
    /** 대사 → 명단 조회 → 0명이면 SKIP → 렌더 → 사감별 SMS/EMAIL 발송 → 이력 저장 */
    public DispatchSummary dispatch(LocalDate date, boolean force);
    /** 실패분만 재시도 */
    public DispatchSummary retryFailed(LocalDate date);
}
public record DispatchSummary(LocalDate date, boolean skipped, String skipReason,
                              int targetCount, int smsSuccess, int smsFailed,
                              int emailSuccess, int emailFailed, String lookupUrl) {}
```
- **0명이면 발송하지 않는다** (`skipped=true, skipReason="NO_REGISTRATION"`).
- 멀티 인스턴스 중복 발송 방지: Redis `SET imlate:lock:dispatch:{date} <uuid> NX EX {lockTtlSeconds}`.
  락 획득 실패 시 skip(`skipReason="LOCK_NOT_ACQUIRED"`). Redis 불가 시엔 DB 이력으로 중복 판정.
- 이미 성공 이력이 있으면 `force=false` 일 때 재발송 안 함(`skipReason="ALREADY_SENT"`).
- 채널별 재시도: 최대 `maxAttempts`, 지수 백오프(1s, 2s) — 스레드 슬립 허용(스케줄러 스레드).

```java
@Component
public class CurfewNotificationScheduler {
    @Scheduled(cron="${imlate.notification.dispatch-cron}", zone="${imlate.timezone}")
    public void dispatchAt2210();
    @Scheduled(cron="${imlate.notification.retry-cron}", zone="${imlate.timezone}")
    public void retryFailed();
}
```
`imlate.notification.enabled=false` 면 아무것도 하지 않는다.

### 6.5 관리 API

```
POST /api/v1/admin/notifications/dispatch?date=YYYY-MM-DD&force=true   (X-Admin-Key)
POST /api/v1/admin/notifications/retry?date=YYYY-MM-DD                 (X-Admin-Key)
GET  /api/v1/admin/notifications?date=YYYY-MM-DD                       (X-Admin-Key)
POST /api/v1/admin/notifications/preview?date=YYYY-MM-DD               (X-Admin-Key) → 렌더 결과만 반환(미발송)
```

---

## 7. stats 모듈 계약 — stats 담당

패키지 `com.skala.imlate.stats`

### 7.1 수집

- 프론트는 모든 API 요청에 `X-Visitor-Id`(localStorage UUID) 헤더를 붙인다.
- `StatsInterceptor` (`preHandle`) 가 `/api/**` 요청마다:
  - `INCR imlate:stats:pv:total`, `INCR imlate:stats:pv:{date}` (+ TTL 400일)
  - `PFADD imlate:stats:uv:total {visitorId}`, `PFADD imlate:stats:uv:{date} {visitorId}`
  - `SADD imlate:stats:days {date}`
  - 헤더 없으면 클라이언트 IP 해시를 방문자 식별자로 사용
  - **actuator·정적 리소스는 제외**, 예외는 절대 요청을 실패시키지 않는다(try/catch 로 삼킴).
- 등록 성공 시 `RegistrationService` 가 아니라 **stats 모듈의 이벤트 리스너**가 카운트한다:
  registration 은 `ApplicationEventPublisher` 로 `RegistrationCreatedEvent` 를 발행하고
  (`com.skala.imlate.registration.event.RegistrationCreatedEvent(LocalDate date, String className)`)
  stats 가 `@EventListener` 로 `INCR imlate:stats:reg:{date}` 수행.
  → **registration 담당은 이 이벤트 클래스와 발행만, stats 담당은 리스너를 구현한다.**

### 7.2 조회 / 스냅샷

```java
package com.skala.imlate.stats;

public record StatsSnapshot(long totalVisitors, long todayVisitors,
                            long totalPageViews, long todayPageViews,
                            long todayRegistrations, long totalRegistrations) {}

public record DailyStatView(LocalDate date, long uniqueVisitors, long pageViews, long registrations) {}

@Service
public class StatsQueryService {
    public StatsSnapshot snapshot();                       // Redis 우선, 실패 시 DB fallback, 예외 없이 0
    public List<DailyStatView> daily(LocalDate from, LocalDate to);
}
```

```java
@Entity @Table(name="daily_stat")
public class DailyStat { LocalDate statDate /*PK*/; long pageViews; long uniqueVisitors;
                         long registrations; LocalDateTime updatedAt; }
public interface DailyStatRepository extends JpaRepository<DailyStat, LocalDate> {
    List<DailyStat> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);
}
```
`StatsSnapshotScheduler` : `imlate.stats.snapshot-cron`(기본 `0 5 0 * * *`) 에 전날 Redis 값을 `daily_stat` 로 영속화.
추가로 22:10 발송 직후 상태를 반영하기 위해 `0 55 23 * * *` 에 당일분도 upsert.

### 7.3 API

```
GET /api/v1/stats/summary                                  → StatsSnapshot (공개, PII 없음)
GET /api/v1/stats/daily?from=&to=&token=                   → List<DailyStatView> (lookup 토큰 필요)
```

---

## 8. ratelimit 모듈 계약 — ratelimit 담당

패키지 `com.skala.imlate.ratelimit`

- **Redis Lua 토큰 버킷**(원자적, 1 RTT). 스크립트 파일:
  `src/main/resources/redis/rate_limit_token_bucket.lua`
  KEYS[1]=버킷키, ARGV=capacity, refillTokens, refillPeriodMs, nowMs, requested
  반환: `{allowed(0|1), remaining, retryAfterMs}` — 키 TTL 은 가득 차는 시간으로 설정.
- `RedisRateLimiter implements RateLimiter` / `LocalFallbackRateLimiter`(Caffeine 미사용, `ConcurrentHashMap` +
  고정 윈도우, Redis 장애 시 사용) — `fail-open=true` 면 Redis 오류 시 로컬 리미터로 강등, `false` 면 429.
- 적용 지점: `RateLimitInterceptor`(HandlerInterceptor) 를 `/api/**` 에 등록.
  스코프 결정: `POST /api/v1/registrations` → `register` 규칙, `/api/v1/lookup` → `lookup` 규칙, 그 외 `global`.
  키: `imlate:rl:{scope}:{clientIp}`.
- 응답 헤더: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`(epoch sec),
  차단 시 `Retry-After`(sec) + 429 + `ErrorResponse(code=RATE_LIMITED)`.
- 성능: Redis 호출 1회, 스크립트는 `DefaultRedisScript` 로 SHA 캐싱. 인터셉터에서 예외를 절대 전파하지 않는다.
- 기본값(200명 규모 기준): global `capacity=120, refill=120/60s`,
  register `capacity=8, refill=8/60s`, lookup `capacity=40, refill=40/60s`.
- 신뢰 프록시(ALB) 뒤에서는 `X-Forwarded-For` 첫 IP 사용(`ClientIpResolver`).

---

## 9. DB 스키마 (Flyway `V1__init.sql`) — foundation 담당

```sql
CREATE TABLE return_registration (
  id BIGINT NOT NULL AUTO_INCREMENT,
  registration_date DATE NOT NULL,
  class_name   VARCHAR(20) NOT NULL,
  student_name VARCHAR(20) NOT NULL,
  room_number  VARCHAR(20) NOT NULL,
  wal_id       CHAR(36)    NOT NULL,
  registered_at DATETIME(6) NOT NULL,
  created_at    DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_return_registration_person (registration_date, class_name, student_name, room_number),
  UNIQUE KEY uk_return_registration_wal_id (wal_id),
  KEY idx_return_registration_date (registration_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notification_dispatch ( … 6.3 필드 … ) …;
CREATE TABLE daily_stat ( stat_date DATE NOT NULL PRIMARY KEY, page_views BIGINT NOT NULL DEFAULT 0,
  unique_visitors BIGINT NOT NULL DEFAULT 0, registrations BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL ) …;
```

---

## 10. 설정 파일 (R10) — foundation 담당

- `application.yml` — 공통 + 기본값, **비밀값은 환경변수 플레이스홀더**
  (`${IMLATE_ALIGO_API_KEY:}` 형태). `spring.config.import: optional:file:./config/application-secret.yml`
- `application-local.yml` — 로컬(H2/도커 MySQL·Redis, sms/email `noop`)
- `application-prod.yml` — RDS/ElastiCache/SES/Aligo 실제 사용, 모두 환경변수 주입
- `application-secret.yml.example` — 운영자가 복사해서 쓰는 템플릿 (실제 파일은 `.gitignore`)
- 인프라 변경 = **설정 파일/환경변수만 수정**하면 되도록, 호스트·포트·키·리전을 전부 프로퍼티화한다.

---

## 11. 프론트엔드 계약 (R5, R6) — frontend 담당

- Vite + Vue 3 `<script setup lang="ts">` + vue-router.
- 라우트: `/` (등록), `/lookup` (사감 조회, `?date=&token=`), 그 외 → `/` 리다이렉트.
- API 클라이언트 `src/api/client.ts`: `fetch` 래퍼, base `import.meta.env.VITE_API_BASE ?? '/api/v1'`,
  모든 요청에 `X-Visitor-Id` 헤더 부착(localStorage `imlate.visitorId`, 없으면 `crypto.randomUUID()`).
  에러 응답의 `code`/`message` 를 사용자 문구로 변환.
- 등록 화면 요구:
  - 서버의 `/registrations/window` 로 **서버 시간 기준** 마감까지 남은 시간 표시, 마감 시 폼 비활성 + 안내.
  - 입력: 반 / 이름 / 호수 3개. `localStorage('imlate.lastInput')` 로 이전 값 자동 채움(R6),
    최근 입력 3건까지 `datalist` 제안.
  - 성공 시 결과 카드(반·이름·호수·복귀시각 23:30) + "이미 등록됨" 구분 표시.
  - 오류/중복/마감/429 각각 명확한 한국어 메시지.
- 조회 화면 요구: 명단 표(번호·반·이름·호수·등록시각), 반별 요약, 검증 결과 배지, 통계, 인쇄 버튼.
  토큰 없으면 안내 문구.
- **반응형(중요)**: 320px ~ 2560px. 가로 스크롤 금지, 텍스트 잘림 금지.
  - 모바일: 표 대신 카드 레이아웃(`@media (max-width: 600px)`), `100dvh` 사용, 터치 타겟 ≥ 44px.
  - `font-size: 16px` 이상(iOS 확대 방지), 시스템 한글 폰트 스택,
    `word-break: keep-all; overflow-wrap: anywhere;`
  - 다크모드 `prefers-color-scheme` 대응, `prefers-reduced-motion` 존중.
  - 색 대비 WCAG AA 이상.
- 빌드 산출물은 `frontend/dist` → Nginx 또는 Spring 정적 서빙.

## 12. E2E/반응형 검증 — tests(frontend) 담당

`frontend/tests/` Playwright:
- 뷰포트 매트릭스: 320×568, 360×800(Galaxy), 375×667(iPhone SE), 390×844, 430×932(iPhone Pro Max),
  768×1024(iPad 세로), 1024×768(iPad 가로), 1280×800(Mac), 1920×1080(Windows), 2560×1440.
- 각 뷰포트에서 검증: `document.scrollingElement.scrollWidth <= innerWidth + 1` (가로 넘침 없음),
  주요 요소 가시성, 버튼 높이 ≥ 44px(모바일), 텍스트 clipping(`scrollWidth <= clientWidth + 1`) 검사,
  폼 제출 플로우, localStorage 자동 채움 동작.
- API 는 `page.route` 로 목킹하여 백엔드 없이 실행 가능해야 한다.

## 13. 인프라 — infra 담당

`infra/terraform/`
- `providers.tf`(aws provider, required_version), `variables.tf`, `outputs.tf`, `main.tf`, `terraform.tfvars.example`
- 모듈: `modules/network`(VPC, public/private subnet 2AZ, IGW, NAT, route),
  `modules/security`(SG: alb/app/db/redis), `modules/rds`(MySQL 8, private, multi-az 옵션, 파라미터그룹 utf8mb4),
  `modules/elasticache`(Redis 7, private subnet group), `modules/ec2`(app 인스턴스 + user_data + IAM instance profile),
  `modules/iam`(SES 송신 정책), `modules/ses`(도메인/이메일 identity), (선택) `modules/alb`
- 비밀값은 **SSM Parameter Store(SecureString)** 로 관리하고 EC2 user_data 가 읽어 환경변수로 주입.
  `terraform.tfvars` 는 `.gitignore`.
- 태그 규칙: `Project=imlate`, `Env=${var.environment}`.
- `terraform fmt` 통과, `validate` 가능한 문법(변수 기본값 제공).

## 14. 코딩 규칙

- 한국어 주석·사용자 문구, 로그는 영어 키워드 + 한국어 설명 혼용 가능.
- 모든 public 클래스에 간단한 Javadoc.
- `System.out.println` 금지 → SLF4J.
- 생성자 주입만 사용(필드 `@Autowired` 금지).
- 컨트롤러는 얇게, 비즈니스는 서비스에.
- 외부 호출(SMS/SES/Redis)은 반드시 예외를 잡아 서비스 가용성을 해치지 않는다.
