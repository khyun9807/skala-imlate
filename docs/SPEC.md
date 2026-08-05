# imlate — 기숙사 야간 복귀 등록 시스템 · 구현 계약서 (SPEC)

> 이 문서는 **모든 모듈이 반드시 따라야 하는 단일 진실 공급원(single source of truth)** 이다.
> 클래스명·패키지·시그니처·API 스키마·Redis 키·DB 스키마는 여기에 적힌 그대로 구현한다.
> 여기서 벗어나면 다른 모듈과 컴파일이 깨진다.

---

## 0. 요구사항 요약 (request.md)

| # | 요구 | 구현 위치 |
|---|---|---|
| R1 | 기숙 이용 교육생이 **21:45까지** 웹에서 23:30 복귀 등록 | `registration` 모듈 + 프론트 `/` |
| R2 | 등록 항목: **반 / 이름 / 기숙사 호수** | `ReturnRegistration` |
| R3 | **21:50** 에 사감 2명에게 문자 + 이메일 발송, 0명이면 미발송 | `notification` 모듈 스케줄러 |
| R4 | 목록에 반·이름·호수 포함, 보기 좋은 텍스트 | `CurfewNoticeRenderer` |
| R5 | 미니멀 · 전 디바이스 반응형 · 검증까지 완료 | `frontend` + Playwright |
| R6 | 이전 입력값 기억 → 자동 채움 | 프론트 localStorage |
| R7 | Redis에 WAL 1회 → DB 1회 (누락 방지) | `registration.wal` |
| R8 | 21:45 마감 후 Redis ↔ DB 대사(검증) → 조회 페이지 노출 | `ReconciliationService` + 프론트 `/lookup` |
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
- 등록 창: `[open-time(기본 00:00), close-time(기본 21:45))`. 21:45:00 이후 등록 거부.
  **21:45 이후에는 그날 등록이 닫히고, 자정(00:00)에 다음 날 대상 등록이 열린다.**
- 사감 발송: `close-time + 5분` = 21:50 (cron 설정값 `0 50 21 * * *`).
- 실패 채널 재시도: 발송 +15분 / +30분 = 22:05, 22:20 (cron 설정값 `0 5,20 22 * * *`).
- 원래 통금 22:30, 연장 통금 23:30 (안내 문구용 값).
- 하루 타임라인: `00:00 등록 시작 → 21:45 마감 → 21:50 발송 → (22:05·22:20 재시도) → 22:30 문 잠김 → 23:30 일괄 개방`

> **변경 이력 —** 원 요구(`request.md`)는 **마감 22:00 / 발송 22:10** 이었으나,
> 운영자 요청으로 **마감 21:45 / 발송 21:50** 으로 조정했다(재시도도 22:25·22:40 → 22:05·22:20 으로 함께 앞당김).
> 통금 22:30, 일괄 복귀 23:30, 등록 시작 00:00 은 **바뀌지 않았다.**
> `request.md` 는 원본 요구사항 기록이므로 그대로 둔다.

**위 시각은 전부 설정값이다. 코드에 하드코딩하지 않는다** — 바꾸는 것은 아래 기본값뿐이다.

| 프로퍼티 | 기본값 | 비고 |
|---|---|---|
| `imlate.registration.open-time` | `00:00` | 환경변수 없음(yml 직접 수정) |
| `imlate.registration.close-time` | `21:45` | `IMLATE_REGISTRATION_CLOSE_TIME` |
| `imlate.notification.dispatch-cron` | `0 50 21 * * *` | `IMLATE_NOTIFICATION_DISPATCH_CRON` |
| `imlate.notification.retry-cron` | `0 5,20 22 * * *` | `IMLATE_NOTIFICATION_RETRY_CRON` |
| `imlate.registration.curfew-time` | `22:30` | 안내 문구용 — **변경 없음** |
| `imlate.registration.return-time` | `23:30` | 안내 문구용 — **변경 없음** |

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
        String dispatchCron,          // "0 50 21 * * *"  (마감 21:45 + 5분)
        String retryCron,             // "0 5,20 22 * * *" (발송 +15분 / +30분)
        int maxAttempts,              // 3
        long lockTtlSeconds,          // 300
        String contactName,           // "SKALA 운영진"      — 문자/메일 문의처 안내용
        String contactEmail,          // "khdev07@naver.com" — 문자/메일 문의처 안내용
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
                                  Rule registerPerson,   // 개인 식별자 버킷 (§8.2 3단)
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
{ "code":"REGISTRATION_CLOSED", "message":"등록 마감 시간(21:45)이 지났습니다.",
  "path":"/api/v1/registrations", "timestamp":"2026-08-05T21:48:11+09:00", "errors":[] }
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
    /**
     * trusted-proxies 가 비어 있으면 X-Forwarded-For / X-Real-IP 를 **무시**하고 remoteAddr 만 쓴다.
     * 목록이 있으면 remoteAddr 이 신뢰 프록시일 때만 XFF 를 보고, 체인 오른쪽에서 신뢰 프록시를
     * 걷어낸 첫 주소를 클라이언트 IP 로 삼는다. 판별 실패 시 "unknown". 자세한 규칙은 §8.7.
     */
    public static String resolve(HttpServletRequest request);
}
```
> 예전 규칙("XFF 첫 IP 를 무조건 사용")은 **헤더 위조로 rate limit 을 우회할 수 있어 폐기**했다(§8.7).

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
> WAL 기록에 도달하기도 전에 500 이 나서 Redis 에 아무 흔적도 남지 않고, 21:50 대사로도 복구할 수 없다.
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
요청 { "className":"1반", "studentName":"홍길동", "roomNumber":"302", "cancelPassword":"1234" }
201  { "id":12, "registrationDate":"2026-08-05", "className":"1반", "studentName":"홍길동",
       "roomNumber":"302", "registeredAt":"2026-08-05T21:03:11", "duplicate":false,
       "returnTime":"23:30" }
```
- 중복 등록이면 **200 OK + `"duplicate": true`** (기존 레코드 반환, 새로 만들지 않음)
- **취소했던 사람이 다시 등록하면 201** — 새 행을 만들지 않고 기존 행을 되살린다(§5.7).
- 마감 후 → 409 `REGISTRATION_CLOSED`
- 검증: 각 필드 `@NotBlank`, className ≤ 20자, studentName ≤ 20자, roomNumber ≤ 20자,
  **반·기숙사 호수는 숫자만**, **이름은 글자만**(한글·영문). 운영 요청으로 좁혔다(§5.8)
- `cancelPassword` 는 **숫자 4자리 필수**(`^[0-9]{4}$`). 해시해서 저장하고 평문은 DB·로그·WAL 어디에도 남기지 않는다.

`POST /api/v1/registrations/cancel`
```json
요청 { "className":"1반", "studentName":"홍길동", "roomNumber":"302", "password":"1234" }
200  { "date":"2026-08-05", "cancelledAt":"2026-08-05T20:15:00",
       "alreadyCancelled":false, "message":"취소되었습니다. 오늘 밤 명단에서 빠졌습니다." }
```
- 네 값이 **모두** 맞아야 취소된다. 응답에 반·이름·호수를 되돌려주지 않는다.
- 이미 취소된 등록에 같은 비밀번호로 다시 요청 → 200 + `alreadyCancelled=true` (멱등, 최초 취소 시각 보존)
- 등록 없음 / 비밀번호 불일치 / 비밀번호 없는 행 → **모두 400 `CANCEL_REJECTED` + 동일 문구**.
  구분해 주면 응답 차이로 "오늘 누가 등록했는지"가 새어 나간다.
- 시도 상한 초과 → 429 `CANCEL_LOCKED`
- 마감 후 → 409 `REGISTRATION_CLOSED` (명단은 이미 사감에게 나갔다)
- `DELETE` 가 아니라 `POST` 인 이유: 비밀번호를 본문에 실어야 하는데 `DELETE` 본문은 명세상
  의미가 정의되어 있지 않아 버려질 수 있고, 쿼리로 옮기면 접근 로그·브라우저 히스토리에 평문으로 남는다.

`GET /api/v1/registrations/window` → `RegistrationWindow` JSON (프론트가 서버시간 기준으로 카운트다운)

`GET /api/v1/registrations/summary` → `{ "date":"2026-08-05", "count":12, "open":true }` (PII 없음, 공개)

`GET /api/v1/lookup?date=2026-08-05&token=…` → **사감용 조회 페이지 데이터**
```json
{ "date":"2026-08-05", "generatedAt":"2026-08-05T21:50:00+09:00", "totalCount":12,
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

### 5.7 등록 취소 (운영 요청, V2)

> "등록된 것을 취소하는 것도 가능하게. 남이 다른 사람의 등록을 취소할 수 있으니 등록할 때
> 비밀번호 숫자 4자리도 받아서 보관하고, 취소할 때 반·이름·호수 + 그 비밀번호가 모두 맞았을 때만 취소되게."

**설계 기준** — 취소는 남의 등록을 지울 수 있는 유일한 경로다. 명단에서 빠진 교육생은 22:30 에 문이
잠기면 밖에서 밤을 샌다. 따라서 **"잘못 취소되는 것"이 "취소가 안 되는 것"보다 훨씬 나쁜 실패**이며,
모든 판단을 그 비대칭에 맞춘다.

| 결정 | 내용 | 근거 |
|---|---|---|
| 소프트 삭제 | `cancelled_at` 만 채우고 행은 남긴다 | 행을 지우면 WAL 기록이 남아 **21:50 대사가 취소분을 되살린다** |
| 대사에서 제외 | 취소한 사람을 DB·WAL **양쪽에서** 뺀다 | 한쪽만 빼면 되살아나거나 가짜 불일치가 뜬다 |
| 되살리기 | 취소 후 재등록은 INSERT 가 아니라 기존 행 복구 | 유니크 제약이 취소된 행에도 걸려 있어 새 행을 못 만든다 |
| 비밀번호 저장 | PBKDF2 + **서버 시크릿 pepper** | 4자리는 1만 가지뿐 — 해시만으로는 DB 유출 시 즉시 뚫린다 |
| pepper 유도 | `HMAC-SHA256(lookup.token-secret, "imlate:cancel-pin:pepper:v1")` | 새 시크릿을 만들면 SSM 파라미터가 늘어 `terraform apply` 가 필요해진다 |
| 시도 총량 제한 | 사람·날짜당 10회 (`CancelAttemptGuard`) | 속도 제한(분당 N회)만으론 하루 종일 두드리면 1만 가지가 다 뚫린다 |
| Redis 장애 시 | **취소를 거부**(fail-closed) | 등록 실패는 "문 밖에 갇힘"(치명), 취소 실패는 "명단에 남음"(무해) — 방향이 반대다 |
| 실패 응답 | 사유를 구분하지 않는 단일 문구 | 구분하면 응답 차이로 등록 여부가 노출된다 |
| 해시 반복 | 20,000회 (설정 가능) | t3.small 2 vCPU. 10만 회는 1건당 150~200ms 라 마감 직전 몰림에서 타임아웃 위험 |

**부작용(의도한 것)** — 남의 등록에 일부러 10번 틀리면 그 사람이 그날 취소하지 못한다.
그 경우 피해자는 *명단에 남을* 뿐이다. 상한을 두지 않으면 남의 등록을 지워 *실제로 밖에 갇히게* 만들 수 있다.
가벼운 쪽을 택했다.

### 5.8 입력 규칙 · 어제 인원 안내 (운영 요청)

**입력 규칙** — 반·기숙사 호수는 숫자만, 이름은 글자만(한글·영문).

| 필드 | 규칙 | 예시 |
|---|---|---|
| `className` | 숫자만 | `1`, `10` |
| `studentName` | 한글·영문. **띄어쓰기 불가** | `홍길동`, `AliceKim` |
| `roomNumber` | 숫자만 | `302`, `1204` |

- **DTO 정규식은 앞뒤 공백을 눈감아 준다**(`^\s*[0-9]{1,20}\s*$`). 이 계층은 1차 필터일 뿐이고
  최종 판정은 서비스가 정규화(trim + 연속 공백 축약)한 뒤에 한다.
  여기서 공백까지 막으면 `" 302 "` 가 정리될 기회 없이 400 이 되어, 기존 동작이 조용히 사라진다.
- **프론트는 반·호수만 입력 순간에 걸러낸다.** 이름 칸에는 필터를 걸지 않는다 —
  한글은 `ㅎ → 호 → 홍` 조합 중간 상태를 거치므로 필터가 있으면 한글을 아예 칠 수 없다.
- 등록과 취소가 **정확히 같은 규칙**을 써야 한다. 한쪽만 좁으면 "등록은 됐는데 취소가 안 되는" 상태에 갇힌다.
- 규칙 변경 전에 저장된 값(`"1반"`, `"302호"`)은 프론트가 숫자만 뽑아 자동 채움에 쓴다
  (`useLastInput`). 버리지 않고 고쳐 쓰는 이유는, 사용자가 건드리지도 않은 칸에서 오류를 보지 않게 하기 위해서다.

**반이 숫자가 되면서 함께 손봐야 했던 것**

| 곳 | 문제 | 조치 |
|---|---|---|
| 명단 정렬 | DB `ORDER BY` 는 VARCHAR 사전순 → `1, 10, 11, 2, 3` | `RegistrationService.findByDate` 에서 숫자 순으로 재정렬 |
| 조회 페이지 반별 칩 | `TreeMap` 기본 정렬(사전순) | `NumericTextOrder` 적용(호수와 동일) |
| 사감 문자·이메일 | `"· 1 (5명)"` — 무엇이 반인지 안 보임 | 표 밖에서는 `classLabel()` 로 "반" 부착 |
| 취소 확인 문구 | `"1 · 홍길동 · 302호"` | `"1반 · 홍길동 · 302호"` |

반이 9개까지는 사전순과 숫자순이 같아 아무 문제가 없다가, **10개가 되는 순간** 조용히 뒤집힌다.

**어제 인원 안내** — `GET /api/v1/registrations/yesterday` → `{ "date": "2026-08-05", "count": 12 }`

- 취소분을 제외한 **최종 인원**이다(`countByDate`). `daily_stat.registrations` 는 취소해도 줄지 않으므로 다르다.
- 등록 화면 맨 아래에 6개 문구 중 하나를 **접속할 때마다 새로 뽑아** 보여 준다(하루 고정 아님).
- **0명이면 아예 표시하지 않는다.** 값을 못 불러와도 마찬가지로 감춘다 — 곁들이는 정보라 없으면 없는 대로 둔다.
- 오늘 인원은 여전히 감춘다(`/summary`). 어제 합계는 이미 끝난 하루의 숫자라
  그것으로 오늘 누가 무엇을 하는지 알 수 없고, 이름·반·호수는 어느 쪽에도 담기지 않는다.

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
※ 이 번호는 수신 전용이라 답장을 받을 수 없습니다.
   문의는 SKALA 운영진 또는 khdev07@naver.com 으로 부탁드립니다.
전체 명단: https://…/lookup?date=2026-08-05&token=…
```
이메일 텍스트는 `반 | 이름 | 호수` 고정폭 표 + 안내 문구.
HTML은 표 + 요약 카드. 한글 깨짐 방지를 위해 charset UTF-8 명시.

**문자·메일 공통 필수 문구 (운영자 요청).** 두 채널 모두에 아래 두 가지가 반드시 들어간다.

1. **수신 전용 안내** — 발신번호는 수신 전용이라 **답장이 불가**하다는 문장.
2. **문의처 안내** — **SKALA 운영진** 또는 **khdev07@naver.com**.

문구에 박아 넣지 말고 `imlate.notification.contact-name` / `contact-email` 설정값을 읽어 렌더한다.
(대사·통계 문구는 사감에게 노출하지 않는다 — API.md §0 "노출 원칙" 참고)

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
추가로 21:50 발송 직후 상태를 반영하기 위해 `0 55 23 * * *` 에 당일분도 upsert.

### 7.3 API

```
GET /api/v1/stats/summary                                  → StatsSnapshot (공개, PII 없음)
GET /api/v1/stats/daily?from=&to=&token=                   → List<DailyStatView> (lookup 토큰 필요)
```

---

## 8. ratelimit 모듈 계약 — ratelimit 담당

패키지 `com.skala.imlate.ratelimit`

### 8.0 왜 IP 단독 버킷을 버렸는가 (배경 — 먼저 읽을 것)

교육생 약 200명은 **기숙사 공용 와이파이**로 등록한다. NAT 뒤라 **전원이 공인 IP 하나를 공유**한다.
그런데 초기 구현은 버킷을 `imlate:rl:{scope}:{clientIp}` 로만 만들고 등록 한도를 **IP당 8회/분**으로 두었다.

> 결과: 같은 와이파이에서 **9번째 학생부터 429**. 마감(21:45) 직전 몰리는 시간대에
> 정확히 최악의 타이밍으로 정상 사용자가 차단된다. **운영이 불가능한 결함이었다.**

부하 테스트가 이걸 잡지 못한 이유도 함께 기록해 둔다 — 요청마다 다른 `X-Forwarded-For` 를 붙여
200명을 **서로 다른 IP** 로 시뮬레이션했기 때문이다. **기본 시나리오가 실제 사용 환경과 정반대**였다.
(자세한 교훈: [docs/LOCAL-TESTING.md §2.5](LOCAL-TESTING.md))

**결정 — 둘 다 적용한다.**

1. **IP 한도를 대폭 상향**한다. IP 버킷은 "정상 사용자 구분"에서 손을 떼고
   **한 회선에서의 대량 폭주(DDoS) 차단** 전용으로 격하한다.
2. **개인 식별자 기준 버킷을 추가**한다. 같은 사람이 도배하는 것은 여전히 막는다.
   개인 키는 요청 본문에서 나오므로 **등록(register) 스코프에만** 적용된다.

한 줄 요약: **회선은 IP 가, 사람은 personKey 가 막는다. 옆자리 학생을 막는 것은 둘 다 아니다.**

### 8.1 토큰 버킷 엔진

- **Redis Lua 토큰 버킷**(원자적, 1 RTT). 스크립트 파일:
  `src/main/resources/redis/rate_limit_token_bucket.lua`
  KEYS[1]=버킷키, ARGV=capacity, refillTokens, refillPeriodMs, nowMs, requested
  반환: `{allowed(0|1), remaining, retryAfterMs}` — 키 TTL 은 가득 차는 시간으로 설정.
- `RedisRateLimiter implements RateLimiter` / `LocalFallbackRateLimiter`(Caffeine 미사용, `ConcurrentHashMap` +
  고정 윈도우, Redis 장애 시 사용) — `fail-open=true` 면 Redis 오류 시 로컬 리미터로 강등, `false` 면 429.
- 스크립트는 `DefaultRedisScript` 로 SHA 캐싱.
- **리미터 내부에서 발생한 어떤 예외도 요청을 실패시키지 않는다.** rate limit 은 부가 기능이며
  서비스 가용성을 해쳐서는 안 된다(§14). 이 원칙은 개인 버킷에도 그대로 적용된다.

### 8.2 버킷 2단 — 키와 적용 순서

| 단 | 스코프 | 버킷 키 | 적용 대상 | 막는 것 |
|---|---|---|---|---|
| 1 | `global` | `imlate:rl:global:{clientIp}` | 모든 `/api/**` | 한 회선의 대량 폭주 |
| 2 | `register` | `imlate:rl:register:{clientIp}` | `POST /api/v1/registrations` | 한 회선의 등록 폭주 |
| 2 | `lookup` | `imlate:rl:lookup:{clientIp}` | `/api/v1/lookup…` | 조회 링크 남용(PII 대량 수집) |
| 3 | `register-person` | `imlate:rl:register-person:{personHash}` | `POST /api/v1/registrations` | **같은 사람의 도배** |

적용 순서는 `1 → 2 → 3` 이며 **앞 단계에서 막히면 뒤 단계는 호출하지 않는다**(단락 평가).
`OPTIONS`(CORS preflight)와 `/actuator/**` 는 전부 대상에서 제외한다.

### 8.3 개인 식별자(personKey)와 해시

```
personKey  = normalize(className) + "|" + normalize(studentName) + "|" + normalize(roomNumber)
             normalize = 앞뒤 공백 제거 + 연속 공백 1칸 축약
                         → RegistrationService 의 정규화를 그대로 재사용한다(복붙 금지)
personHash = hex( SHA-256(personKey) ) 의 앞 16자 (= 64비트)
bucketKey  = "imlate:rl:register-person:" + personHash
```

- **정규화가 먼저다.** `" 1반 "` 과 `"1반"` 이 다른 버킷을 쓰면 공백만 바꿔 가며 우회할 수 있다.
  등록 멱등성 판정(§5.2 `WalEntry.personKey()`)과 **같은 정규화 규칙**을 쓴다.
  두 곳이 갈라지지 않도록 `RegistrationService` 의 정규화 메서드를 **재사용**한다.
  (등록 멱등 키와 달리 날짜는 넣지 않는다 — 버킷 TTL 이 분 단위라 의미가 없다.)
- **왜 해시해서 넣는가 — 개인정보 때문이다.**
  Redis 키는 `KEYS`/`SCAN`/`MONITOR` 결과, 슬로우로그, 모니터링 대시보드, RDB/AOF 덤프,
  운영자의 `redis-cli` 화면에 **그대로 노출**된다. 원문을 키에 박으면
  *"누가 몇 시에 복귀 등록을 시도했는지"가 버킷 이름만으로 드러난다*.
  버킷은 사람을 **구분**하기만 하면 되고 원문을 다시 읽을 일이 없으므로 되돌릴 수 있을 필요가 없다.
  부수 효과로 키 길이가 고정되어 메모리 사용량이 예측 가능해지고, 한글·`|`·공백이 섞인 키를
  운영 도구에서 다루는 번거로움도 사라진다.
- **16자(64비트)로 자르는 이유** — 200명 규모에서 충돌 확률이 무시할 수준이고,
  설령 충돌해도 결과는 "두 사람이 분당 한도를 나눠 쓴다" 정도라 **안전한 방향으로 실패**한다.
- **알려진 한계(의도적)** — 입력 공간이 작아(반·이름·호수 조합) 평문 SHA-256 은 사전 공격으로
  되돌릴 수 있다. 즉 이 해시는 **익명화가 아니라 "덤프를 열었을 때 눈에 안 띄게" 하는 수준**이다.
  버킷은 TTL 이 분 단위인 임시 키이고 Redis 자체가 사설 서브넷에 있으므로 여기까지로 둔다.
  더 강한 보장이 필요해지면 `imlate.lookup.token-secret` 을 키로 쓰는 HMAC-SHA256 으로 바꾼다
  (키 포맷만 바뀌고 로직은 그대로다).
- 개인 버킷 차단은 **WAL append(§5.2 3단계)보다 반드시 앞에서** 일어나야 한다.
  차단된 요청이 WAL 에 남으면 21:50 대사가 유령 인원을 DB 로 복구한다.
- 본문에서 개인 키를 못 만들면(본문 없음·JSON 파손·필드 누락) **개인 버킷 검사를 건너뛴다.**
  리미터가 정상 등록을 막는 것보다 낫고, 잘못된 본문은 어차피 컨트롤러 `@Valid` 에서 400 이 된다.

### 8.4 적용 지점 (본문을 읽어야 한다는 제약)

- 1·2단(IP)은 `RateLimitInterceptor`(HandlerInterceptor)를 `/api/**` 에 등록해 처리한다.
- 3단(개인)은 **요청 본문이 필요**하다. 그런데 인터셉터에서 `request.getInputStream()` 을 읽어 버리면
  컨트롤러가 본문을 다시 읽지 못한다. 그래서 **등록 요청에 한해** 본문을 캐싱하는 필터를 앞에 둔다.

```java
RegistrationBodyCachingFilter   // POST /api/v1/registrations 에만 적용. 본문을 바이트로 캐싱
CachedBodyHttpServletRequest    // 캐시된 본문을 몇 번이든 다시 읽게 해 주는 래퍼
PersonKeyResolver               // 캐시된 본문 → personHash (만들 수 없으면 null)
```

- **캐싱 대상을 등록 경로로 좁히는 것이 중요하다.** 모든 요청의 본문을 메모리에 들고 있으면
  그 자체가 공격 표면이 된다. 등록 본문은 수십 바이트라 부담이 없다.
- 3단은 **1·2단(IP)을 통과한 등록 요청에서만** 소비한다. 앞에서 막히면 개인 버킷은 건드리지 않는다.
- 어디서 막든 **429 응답 계약은 동일해야 한다**(아래 §8.5). 개인 버킷 차단만 헤더가 빠지면 안 된다.
  다만 사용자 문구는 스코프별로 다르게 준다 — 개인 버킷은
  `"같은 정보로 너무 자주 등록을 시도했습니다. 잠시 후 다시 시도해 주세요."` 처럼
  **왜 막혔는지 짐작할 수 있는 문구**여야 한다(회선 폭주와 도배는 사용자가 할 행동이 다르다).

### 8.5 응답 계약

- 모든 응답: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`(epoch sec)
- 차단 시: HTTP **429** + `Retry-After`(sec) + `ErrorResponse(code=RATE_LIMITED)`
- `X-RateLimit-Limit` 값은 **실제로 막은 규칙의 capacity** 를 싣는다(어느 단에서 막혔는지 구분 가능해야 한다).

### 8.6 기본값과 그 근거 (200명 규모)

| 규칙 | 기본값 | 이 숫자를 고른 근거 |
|---|---|---|
| `global` (IP) | `capacity=1200, refill=1200/60s` | 공용 와이파이 뒤 200명 × 1인당 분당 6회(등록 창 조회 1 + 요약 1 + 앱 전환 시 재조회 1~2 + 등록 1~2) ≈ 1200. 뒤집으면 **한 회선이 20 req/s 를 넘으면 사람이 아니라 스크립트로 본다**는 선언이다. |
| `register` (IP) | `capacity=300, refill=300/60s` | 200명 × 1.5회 = 300. 1.5회는 "오타 수정 후 재제출 / 새로고침 후 재시도" 여유다. 200~400 중 300 을 고른 이유: 400 은 회선 폭주 방어가 느슨해지고, 200 은 재시도 여유가 0 이라 마감 직전 동시 재시도에서 정상 사용자가 막힐 수 있다. 개인 도배는 아래 `register-person` 이 막으므로 이 값은 "사람 수 × 재시도"만 감당하면 된다. **반드시 교육생 규모보다 커야 한다.** |
| `register-person` (개인) | `capacity=5, refill=5/60s` | 정상 사용자는 1회(성공) 또는 2회(오타 수정)면 끝난다. 5회면 통신 오류 재시도까지 충분하고, **6번째부터 막히므로** 한 사람이 서버를 두드려 대는 것은 실질적으로 차단된다. |
| `lookup` (IP) | `capacity=20, refill=20/60s` | 사용자는 사감 2명뿐이고 조회 화면은 진입 시 1회만 호출한다(자동 폴링 없음). 20회/분이면 3초에 한 번 새로고침하는 셈이라 사람 손으로는 닿지 않는다. 반대로 이 화면은 **200명의 이름·호수(PII)** 를 담고 있어, 조회 토큰이 유출됐을 때 **대량 수집 속도를 늦추는 것**이 이 버킷의 진짜 목적이다. 그래서 40 → 20 으로 오히려 낮췄다. |
| `local-fallback-permits-per-minute` | `1200` | Redis 장애 시 쓰는 인메모리 폴백의 분당 상한. **`global` 과 같은 수준으로 맞춘다** — 이 값이 작으면 폴백으로 강등되는 순간 공용 와이파이 전체가 막힌다(예전 `120` 이 정확히 그랬다). |

> **IP 축은 올리고 조회 축은 내렸다**는 점에 주의. 방향이 반대인 이유는 지키려는 것이 다르기 때문이다.
> 등록은 *교육생 200명의 가용성*, 조회는 *그 200명의 개인정보*를 지킨다.

**지켜야 하는 부등식** (설정만 보고도 결함을 잡을 수 있다. 두 검증 스크립트가 이 세 줄을 단언한다):

```
register(IP) 한도        ≥ 교육생 규모            (300 ≥ 200)
global(IP)   한도        ≥ 교육생 규모 × 2        (1200 ≥ 400)
register-person 한도     <  register(IP) 한도      (5 < 300 — 개인 버킷이 IP 만큼 크면 도배를 방치하는 것)
```

설정 프로퍼티(§4.1 `RateLimitProperties`)에 `register-person` 규칙이 추가된다.
모든 한도는 환경변수로 덮을 수 있다(`IMLATE_RATE_LIMIT_*_CAPACITY` 등, §10).

```yaml
imlate:
  rate-limit:
    global:          { capacity: 1200, refill-tokens: 1200, refill-period-seconds: 60 }
    register:        { capacity: 300,  refill-tokens: 300,  refill-period-seconds: 60 }
    register-person: { capacity: 5,    refill-tokens: 5,    refill-period-seconds: 60 }
    lookup:          { capacity: 20,   refill-tokens: 20,   refill-period-seconds: 60 }
    trusted-proxies: []
    local-fallback-permits-per-minute: 1200
```

### 8.7 클라이언트 IP 판별 — `trusted-proxies` 가 비면 XFF 를 신뢰하지 않는다

`ClientIpResolver`(§4.3)는 `imlate.rate-limit.trusted-proxies` 에 따라 동작이 갈린다.

| `trusted-proxies` | 동작 |
|---|---|
| **비어 있음(기본·로컬)** | `X-Forwarded-For` / `X-Real-IP` 를 **완전히 무시**하고 `remoteAddr` 만 쓴다 |
| 목록이 있음(운영, ALB 사설 대역) | `remoteAddr` 이 그 목록에 속할 때만 XFF 를 신뢰하고, **체인 오른쪽에서부터 신뢰 프록시를 걷어낸 첫 주소**를 클라이언트 IP 로 삼는다 |

- **왼쪽 첫 IP 를 그대로 쓰면 안 된다.** 클라이언트가 헤더를 통째로 위조해 요청마다 다른 IP 를
  주장하면 버킷을 무한히 만들 수 있어 리미터가 사실상 무력해진다.
- 판별 실패 시 `"unknown"` 을 식별자로 쓴다 — 미확인 클라이언트끼리 버킷을 공유시켜 우회 통로를 만들지 않는다.
- 로컬은 빈 목록이 기본이므로 **위조 헤더로 리미터를 우회할 수 없다.**
  `scripts/integration-test.mjs` §3-3 이 "위조 IP 로 만들어진 버킷이 존재하지 않는다"를 단언한다.

### 8.8 성능 (R14 — "성능·품질에 지장이 없어야 한다")

- Redis 호출 횟수: **등록 외 모든 요청은 1회**(변화 없음). 등록 요청만 최대 3회
  (global + register + 개인). 등록은 하루 200~600건 규모라 이 증가는 무시할 수 있다.
- 앞 단에서 차단되면 뒤 단은 호출하지 않으므로, 공격 트래픽일수록 호출 수가 **줄어든다**.
- 선택 최적화: IP 키와 개인 키를 하나의 Lua 호출에 `KEYS[1], KEYS[2]` 로 함께 넘기면 등록도 2회로
  줄일 수 있다(단일 노드 전제). 필요해지기 전에는 하지 않는다.
- 개인 해시 계산은 요청당 SHA-256 1회(수 μs)와 등록 본문(수십 바이트) 캐싱뿐이다.
  본문 캐싱은 `POST /api/v1/registrations` 에만 적용하므로 다른 경로의 비용은 0 이다.

---

## 9. DB 스키마 (Flyway) — foundation 담당

마이그레이션은 **앱 기동 때 자동 적용**된다(`flyway.enabled: true`, `baseline-on-migrate: true`).
배포 스크립트는 스키마를 건드리지 않으므로, 컬럼 추가는 코드 배포만으로 반영된다.

> **주의** — `ddl-auto: validate` 이므로 엔티티와 스키마가 어긋나면 **앱이 아예 뜨지 않는다.**
> 마이그레이션과 엔티티 변경은 반드시 **같은 jar 에 함께** 실려야 한다.

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

`V2__add_cancel.sql` — 등록 취소(§5.7)

```sql
ALTER TABLE return_registration
  ADD COLUMN cancel_password_hash VARCHAR(200) NULL,   -- pbkdf2-sha256$반복$salt$hash
  ADD COLUMN cancelled_at         DATETIME(6)  NULL;   -- NULL 이면 유효한 등록
CREATE INDEX idx_return_registration_date_active
  ON return_registration (registration_date, cancelled_at);
```

- **두 컬럼 모두 NULL 허용이다.** 이 마이그레이션은 운영 중인 DB 위에서 실행되므로,
  `NOT NULL` 로 두면 기존 행 때문에 마이그레이션이 통째로 실패하고 앱이 뜨지 못한다.
  "비밀번호 필수"는 API 검증에서 강제하고 스키마는 관대하게 둔다.
- 비밀번호 해시가 없는 행(V2 이전 등록 · WAL 복구분)은 **취소할 수 없다.**
  명단에 남아 잠기지 않는 쪽이 명단에서 사라지는 것보다 안전하다.
- 명단·통계 조회는 전부 `cancelled_at IS NULL` 로 걸러야 한다.
  단, **대사(§5.4)는 취소분까지 읽어야 한다** — 취소된 행이 "DB 에 있다"로 보여야 WAL 로 되살리지 않는다.

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
