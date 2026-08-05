# imlate API 명세

실제 구현(`backend/src/main/java/com/skala/imlate/**/web/`)을 기준으로 작성했습니다.

- **Base path**: `/api/v1`
- **Content-Type**: `application/json;charset=UTF-8`
- **시간대**: 모든 시각은 Asia/Seoul 기준
  - `LocalDate` → `"2026-08-05"`
  - `LocalDateTime` → `"2026-08-05T21:03:11"`
  - `OffsetDateTime` → `"2026-08-05T21:50:00+09:00"`
  - 안내용 시각(`returnTime`, `curfewTime`) → `"23:30"` (초 없음)
- 로컬 기준 호스트: `http://localhost:8080` (프론트 dev 서버 `http://localhost:5173` 이 `/api`를 프록시)

---

## 0. 엔드포인트 한눈에 보기

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/v1/registrations` | 없음 | 23:30 복귀 등록 |
| GET | `/api/v1/registrations/window` | 없음 | 등록 창 상태(서버 시간·마감까지 남은 초) |
| GET | `/api/v1/registrations/summary` | 없음 | 대상일·등록 가능 여부(인원 수 없음) |
| GET | `/api/v1/lookup` | 조회 토큰 | 사감용 명단·집계 |
| GET | `/api/v1/stats/summary` | `X-Admin-Key` | 방문/등록 통계 요약 |
| GET | `/api/v1/stats/daily` | `X-Admin-Key` | 일자별 통계 |
| GET | `/api/v1/admin/reconciliation` | `X-Admin-Key` | WAL ↔ DB 대사 결과 조회(복구 없음) |
| POST | `/api/v1/admin/notifications/dispatch` | `X-Admin-Key` | 수동 발송 |
| POST | `/api/v1/admin/notifications/retry` | `X-Admin-Key` | 실패 채널만 재발송 |
| GET | `/api/v1/admin/notifications` | `X-Admin-Key` | 발송 이력 조회 |
| POST | `/api/v1/admin/notifications/preview` | `X-Admin-Key` | 발송 없이 렌더 결과만 확인 |
| GET | `/actuator/health` | 없음(운영은 내부만) | 사람이 보는 전체 헬스체크(DB·Redis·디스크 포함) |
| GET | `/actuator/health/alb` | 없음(운영은 내부만) | **로드밸런서 전용** 헬스체크 — `db` + `ping` 만 본다 |

> **노출 원칙 — "기록은 그대로, 노출만 줄인다"**
> 대사(WAL ↔ DB)와 방문/등록 통계는 예전과 똑같이 **계속 수행·기록**한다. 다만 교육생·사감이 보는
> 응답(`/registrations/summary`, `/lookup`)과 문자·이메일 문구에는 **넣지 않는다.**
> 운영자는 `/api/v1/admin/reconciliation` 과 `/api/v1/stats/**` (둘 다 `X-Admin-Key`) 로 확인한다.

> **왜 ALB 용 헬스 그룹을 따로 두는가**
> 이 서비스는 Redis 가 죽어도 등록을 계속 처리하도록 설계되어 있다(WAL 미기록 경고만 남기고 DB 에는 저장).
> 그런데 ALB 가 `/actuator/health` 를 보면 ElastiCache 장애 때 모든 인스턴스가 `unhealthy` 로 빠져
> **정상 동작 중인 서비스가 통째로 내려간다.** 그래서 로드밸런서 판정에는 등록에 반드시 필요한
> `db` 와 `ping` 만 포함한 `alb` 그룹을 쓴다. 설정 위치는 `application.yml` 의
> `management.endpoint.health.group.alb`, 터라폼 변수는 `alb_health_check_path`.

---

## 1. 공통 규약

### 1.1 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Content-Type: application/json` | POST 시 | |
| `X-Visitor-Id` | 선택 | 방문 통계용 식별자(프론트가 localStorage `imlate.visitorId` 값을 자동 부착). 영숫자·`-`·`_` 8~64자만 유효하며, 없으면 서버가 `sha256(IP\|날짜)` 앞 16자를 사용 |
| `X-Admin-Key` | 관리 API | 관리 API 인증 키(`imlate.admin.api-key`) |

### 1.2 에러 응답 형식

모든 오류는 `GlobalExceptionHandler`가 아래 형태로 통일합니다.

```json
{
  "code": "REGISTRATION_CLOSED",
  "message": "등록 마감 시간(21:45)이 지났습니다.",
  "path": "/api/v1/registrations",
  "timestamp": "2026-08-05T21:48:11+09:00",
  "errors": []
}
```

검증 실패 시 `errors` 에 필드 단위 메시지가 채워집니다.

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값을 다시 확인해 주세요.",
  "path": "/api/v1/registrations",
  "timestamp": "2026-08-05T21:03:11+09:00",
  "errors": [
    { "field": "studentName", "message": "이름에는 반·호수는 숫자만, 이름은 한글·영문만 사용할 수 있습니다." }
  ]
}
```

### 1.3 에러 코드 표

| code | HTTP | 발생 상황 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 입력 검증 실패, JSON 파싱 실패, 필수 파라미터 누락, 파라미터 타입 오류, 미래 날짜 조회, 통계 기간 오류(최대 366일) |
| `REGISTRATION_NOT_OPEN` | 409 | 등록 시작 시각(기본 00:00) 이전 |
| `REGISTRATION_CLOSED` | 409 | 등록 마감 시각(기본 21:45) 이후 |
| `UNAUTHORIZED` | 401 | `X-Admin-Key` 누락·불일치, 관리자 키 미설정 |
| `FORBIDDEN` | 403 | 조회 토큰 누락·서명 불일치·만료 |
| `NOT_FOUND` | 404 | 존재하지 않는 경로/리소스 |
| `RATE_LIMITED` | 429 | 토큰 버킷 초과 |
| `EXTERNAL_API_ERROR` | 502 | 외부 API 오류(현재 경로에서 직접 반환하지는 않음 — 발송 실패는 이력에 기록) |
| `INTERNAL_ERROR` | 500 | 그 밖의 서버 오류 |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 HTTP 메서드 (enum이 아닌 문자열 코드) |

### 1.4 Rate limit 헤더

`/api/**` 응답에는 항상 아래 헤더가 붙습니다(`/actuator/**`, `/error`, `OPTIONS` 제외).

| 헤더 | 의미 |
|---|---|
| `X-RateLimit-Limit` | 해당 스코프 버킷 용량 |
| `X-RateLimit-Remaining` | 남은 토큰 수(정수 내림, 0 이상) |
| `X-RateLimit-Reset` | 버킷이 가득 차는 시각(epoch **초**, 올림) |
| `Retry-After` | 429일 때만. 재시도까지 대기할 **초**(최소 1) |

기본 정책(`imlate.rate-limit.*`):

| 스코프 | 버킷 축 | 대상 | capacity | refill |
|---|---|---|---|---|
| `global` | IP | 모든 `/api/**` | 1200 | 1200 / 60초 |
| `register` | IP | `POST /api/v1/registrations` | 300 | 300 / 60초 |
| `register-person` | 사람(반\|이름\|호수 해시) | `POST /api/v1/registrations` | 10 | 10 / 60초 |
| `lookup` | IP | `/api/v1/lookup…` | 120 | 120 / 60초 |

`register`/`lookup` 요청은 **GLOBAL 버킷을 먼저 소비한 뒤** 전용 버킷을 소비합니다.

> **IP 축 한도가 큰 이유** — 교육생 200명이 기숙사 공용 와이파이(NAT) 뒤에서 **공인 IP 하나**를
> 공유합니다. IP 한도를 사람 수 기준으로 잡으면 마감 직전에 정상 교육생이 429 로 막힙니다.
> 그래서 IP 버킷은 "한 회선의 대량 폭주(DDoS) 차단" 전용이고, 개인 도배는
> `register-person`(사람 축) 버킷이 막습니다. 배경은 [SPEC.md §8.0](SPEC.md) 참고.

429 본문:

```json
{
  "code": "RATE_LIMITED",
  "message": "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
  "path": "/api/v1/registrations",
  "timestamp": "2026-08-05T21:10:02+09:00",
  "errors": []
}
```

---

## 2. 등록 API

### 2.1 `POST /api/v1/registrations` — 복귀 등록

**요청**

```json
{ "className": "1반", "studentName": "홍길동", "roomNumber": "302" }
```

| 필드 | 타입 | 제약 |
|---|---|---|
| `className` | string | 필수, **숫자만** (`1`, `10`) |
| `studentName` | string | 필수, 1~20자, **한글·영문만**. 글자 사이 공백 한 칸 허용 |
| `roomNumber` | string | 필수, **숫자만** (`302`, `1204`) |
| `cancelPassword` | string | 필수, **숫자 4자리**. 취소할 때 본인 확인에 쓰입니다 |

> 서버는 `@Valid` 통과 후 다시 **정규화(앞뒤 공백 제거 + 연속 공백 1칸)** 하고 같은 규칙으로 재검증합니다.
> `@Valid` 단계의 정규식은 **앞뒤 공백을 눈감아 줍니다** — 정규화 기회를 남겨 두기 위해서입니다.
> `"  302  "` 는 통과해 `"302"` 로 저장되고, `"1반"`·`"B-101"`·`"홍길동2"`·`"<script>"` 는 거부됩니다.
> `"남궁 민수"`, `"Alice Kim"` 처럼 띄어 쓰는 이름은 통과합니다.

**201 Created — 신규 등록**

```json
{
  "id": 12,
  "registrationDate": "2026-08-05",
  "className": "1반",
  "studentName": "홍길동",
  "roomNumber": "302",
  "registeredAt": "2026-08-05T21:03:11",
  "duplicate": false,
  "returnTime": "23:30"
}
```

**200 OK — 이미 등록됨(멱등)**

같은 사람이 다시 등록하면 새 레코드를 만들지 않고 기존 레코드를 그대로 돌려줍니다.

```json
{
  "id": 12,
  "registrationDate": "2026-08-05",
  "className": "1반",
  "studentName": "홍길동",
  "roomNumber": "302",
  "registeredAt": "2026-08-05T21:03:11",
  "duplicate": true,
  "returnTime": "23:30"
}
```

**오류**

| 상태 | code | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 형식 오류 |
| 409 | `REGISTRATION_CLOSED` | 21:45 정각 이후 |
| 409 | `REGISTRATION_NOT_OPEN` | 등록 시작 전 |
| 429 | `RATE_LIMITED` | 같은 사람이 분당 10회 초과(사람 축) 또는 한 회선에서 분당 300회 초과(IP 축) |

```bash
curl -i -X POST "http://localhost:8080/api/v1/registrations" \
  -H "Content-Type: application/json" \
  -H "X-Visitor-Id: 3f2b9c10-7a4e-4d1f-9b22-0c5a8e6d1a44" \
  -d '{"className":"1반","studentName":"홍길동","roomNumber":"302"}'
```

---

### 2.2 `GET /api/v1/registrations/window` — 등록 창 상태

프론트가 **서버 시간 기준**으로 마감 카운트다운을 표시하는 데 씁니다.

```json
{
  "date": "2026-08-05",
  "open": true,
  "serverTime": "2026-08-05T21:03:11+09:00",
  "opensAt": "2026-08-05T00:00:00+09:00",
  "closesAt": "2026-08-05T21:45:00+09:00",
  "returnTime": "23:30",
  "curfewTime": "22:30",
  "secondsUntilClose": 2509
}
```

`secondsUntilClose`는 마감이 지났으면 `0` 입니다.

| 필드 | 값의 출처 | 기본값 |
|---|---|---|
| `opensAt` | `imlate.registration.open-time` | `00:00` |
| `closesAt` | `imlate.registration.close-time` | **`21:45`** |
| `curfewTime` | `imlate.registration.curfew-time` | `22:30` (문 잠김 — 변경 없음) |
| `returnTime` | `imlate.registration.return-time` | `23:30` (일괄 개방 — 변경 없음) |

> **프론트는 이 응답의 값만 쓰고 시각을 하드코딩하지 않습니다.** 마감 시각을 설정으로 바꾸면
> 카운트다운·안내 문구가 자동으로 따라갑니다.
> 마감(`closesAt`) 이후에는 그날 등록이 닫히고, 자정에 `date` 가 다음 날로 넘어가면서 다시 열립니다.

```bash
curl -s "http://localhost:8080/api/v1/registrations/window"
```

---

### 2.3 `GET /api/v1/registrations/summary` — 등록 현황 요약(공개)

개인정보를 포함하지 않습니다. **등록 인원 수(`count`)는 내려주지 않습니다** — 사감만 알면 되는 정보라
등록 화면에는 "지금 등록할 수 있는지"만 필요합니다. 집계 자체는 서버에서 계속 유지됩니다.

```json
{ "date": "2026-08-05", "open": true }
```

```bash
curl -s "http://localhost:8080/api/v1/registrations/summary"
```

---

## 3. 조회 API (사감용)

### 3.1 `GET /api/v1/lookup` — 명단·집계

| 쿼리 | 필수 | 설명 |
|---|---|---|
| `date` | 선택 | `yyyy-MM-dd`. 생략하면 오늘. **미래 날짜는 400** |
| `token` | 필수 | HMAC-SHA256 조회 토큰. 누락·불일치·만료 모두 **403 `FORBIDDEN`** |

토큰은 21:50 발송 문자/메일의 링크에 포함되어 있고, 관리 API `preview`의 `lookupUrl`로도 얻을 수 있습니다.
기본 유효기간은 `imlate.lookup.token-ttl-hours`(운영 기본 48시간, 로컬 168시간)입니다.

**200 OK**

```json
{
  "date": "2026-08-05",
  "generatedAt": "2026-08-05T21:50:00+09:00",
  "totalCount": 12,
  "returnTime": "23:30",
  "curfewTime": "22:30",
  "items": [
    { "no": 1, "className": "1반", "studentName": "홍길동", "roomNumber": "302",
      "registeredAt": "2026-08-05T21:03:11" },
    { "no": 2, "className": "1반", "studentName": "김철수", "roomNumber": "305",
      "registeredAt": "2026-08-05T20:41:55" }
  ],
  "byClass": [
    { "className": "1반", "count": 5 },
    { "className": "2반", "count": 7 }
  ],
  "byRoom": [
    { "roomNumber": "302", "count": 2 },
    { "roomNumber": "1002", "count": 1 }
  ]
}
```

| 필드 | 설명 |
|---|---|
| `items[].no` | 반 → 이름 정렬 순서로 1부터 부여 |
| `byClass` | 반 이름 오름차순 |
| `byRoom` | 호수 정렬 — 숫자면 숫자 크기 순(`302` < `1002`), 아니면 문자열 순 |

> **`verification` / `stats` 필드는 응답에서 제거되었습니다.**
> 서버는 조회할 때마다 예전과 동일하게 대사(복구 없는 `inspect`)를 수행하지만, 결과는 응답 대신
> 애플리케이션 로그로만 남깁니다.
> ```
> Lookup reconciliation date=2026-08-05 status=CONSISTENT db=12 wal=12
> ```
> 대사 결과는 `GET /api/v1/admin/reconciliation`, 통계는 `GET /api/v1/stats/**` 로 확인하세요.
> 대사가 실패해도 명단 조회는 계속 성공합니다(경고 로그만 남김).

```bash
TOKEN='REPLACE_WITH_TOKEN'
curl -s "http://localhost:8080/api/v1/lookup?date=2026-08-05&token=$TOKEN"
```

**403 예시**

```json
{
  "code": "FORBIDDEN",
  "message": "조회 링크가 유효하지 않거나 만료되었습니다. 사감님께 재발급을 요청해 주세요.",
  "path": "/api/v1/lookup",
  "timestamp": "2026-08-06T09:00:00+09:00",
  "errors": []
}
```

---

## 4. 통계 API (`X-Admin-Key` 필수)

통계는 사용자 화면에 노출하지 않습니다. **집계는 예전과 동일하게 계속 수행**하되(방문·페이지뷰·등록),
조회는 두 엔드포인트 모두 **운영자 전용**입니다. 키가 없거나 틀리면 **401 `UNAUTHORIZED`** 입니다.

### 4.1 `GET /api/v1/stats/summary` — 요약(운영자 전용)

```json
{
  "totalVisitors": 540,
  "todayVisitors": 88,
  "totalPageViews": 2100,
  "todayPageViews": 210,
  "todayRegistrations": 12,
  "totalRegistrations": 320
}
```

- `*Visitors`는 HyperLogLog 추정치(오차 약 0.81%)입니다.
- Redis 장애 시 `daily_stat` 스냅샷으로 폴백하고, 그마저 실패하면 **예외 없이 전부 0**을 반환합니다.
- 이 엔드포인트(`/api/v1/stats/**`)는 방문 통계 집계 대상에서 제외됩니다(통계가 통계를 부풀리지 않도록).

```bash
curl -s "http://localhost:8080/api/v1/stats/summary" -H "X-Admin-Key: $ADMIN_KEY"
```

### 4.2 `GET /api/v1/stats/daily` — 일자별 통계(운영자 전용)

| 쿼리 | 필수 | 설명 |
|---|---|---|
| `from` | 선택 | 생략 시 `to` 기준 최근 30일 |
| `to` | 선택 | 생략 시 오늘 |

> 예전에는 사감 조회 토큰(`token`)으로 열렸지만, 통계를 운영자 전용으로 통일하면서 `X-Admin-Key` 로
> 바뀌었습니다. `token` 파라미터는 더 이상 사용하지 않습니다.

기간 내 모든 날짜가 오름차순으로 빠짐없이 내려옵니다(데이터 없는 날은 0). 최대 366일.

```json
[
  { "date": "2026-08-03", "uniqueVisitors": 61, "pageViews": 150, "registrations": 9 },
  { "date": "2026-08-04", "uniqueVisitors": 74, "pageViews": 180, "registrations": 11 },
  { "date": "2026-08-05", "uniqueVisitors": 88, "pageViews": 210, "registrations": 12 }
]
```

| 상태 | code | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `from > to`, 기간 366일 초과, 날짜 형식 오류 |
| 401 | `UNAUTHORIZED` | `X-Admin-Key` 누락·불일치 |

```bash
curl -s "http://localhost:8080/api/v1/stats/daily?from=2026-08-01&to=2026-08-05" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

---

## 5. 관리 API (`X-Admin-Key` 필수)

키는 `imlate.admin.api-key` (환경변수 `IMLATE_ADMIN_API_KEY`)입니다.
로컬 프로파일 기본값은 `local-dev-admin-key`, 운영에서는 SSM `/imlate/{env}/IMLATE_ADMIN_API_KEY`
(`terraform output -raw admin_api_key`)입니다.

모든 관리 API의 `date` 파라미터는 선택이며, 생략하면 **오늘(KST)** 입니다.

### 5.0 `GET /api/v1/admin/reconciliation` — WAL ↔ DB 대사 결과

조회 페이지에서 검증 결과를 감췄기 때문에, **운영자가 대사 결과를 확인하는 정식 경로**입니다.
GET 이므로 **복구는 하지 않고 비교만** 합니다(`ReconciliationService.inspect`). 누락분 복구는
21:50 발송 경로에서 계속 수행됩니다.

| 쿼리 | 기본값 | 설명 |
|---|---|---|
| `date` | 오늘 | 대사 대상일 |

```json
{
  "date": "2026-08-05",
  "status": "CONSISTENT",
  "dbCount": 12,
  "walCount": 12,
  "recoveredCount": 0,
  "walOnly": [],
  "dbOnly": [],
  "checkedAt": "2026-08-05T21:50:00+09:00"
}
```

| 필드 | 설명 |
|---|---|
| `status` | `CONSISTENT` / `RECOVERED` / `MISMATCH` / `WAL_UNAVAILABLE` |
| `walOnly` / `dbOnly` | `"1반/홍길동/302"` 형식. 복구 없이 비교만 하므로 값이 남을 수 있음 |
| `recoveredCount` | 이 경로에서는 항상 `0`(복구하지 않음) |

```bash
curl -s "http://localhost:8080/api/v1/admin/reconciliation?date=2026-08-05" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

### 5.1 `POST /api/v1/admin/notifications/dispatch` — 수동 발송

| 쿼리 | 기본값 | 설명 |
|---|---|---|
| `date` | 오늘 | 발송 대상일 |
| `force` | `false` | `true`면 이미 성공 이력이 있어도 재발송하고, `imlate.notification.enabled=false` 여도 강제 진행 |

**200 OK — 발송함**

```json
{
  "date": "2026-08-05",
  "skipped": false,
  "skipReason": null,
  "targetCount": 12,
  "smsSuccess": 2,
  "smsFailed": 0,
  "emailSuccess": 2,
  "emailFailed": 0,
  "lookupUrl": "https://imlate.example.com/lookup?date=2026-08-05&token=…"
}
```

**200 OK — 건너뜀**

```json
{
  "date": "2026-08-05",
  "skipped": true,
  "skipReason": "NO_REGISTRATION",
  "targetCount": 0,
  "smsSuccess": 0, "smsFailed": 0,
  "emailSuccess": 0, "emailFailed": 0,
  "lookupUrl": null
}
```

| `skipReason` | 의미 |
|---|---|
| `NO_REGISTRATION` | 등록 인원 0명 → 발송하지 않음(요구사항) |
| `ALREADY_SENT` | 이미 SUCCESS 이력 존재(`force=false`) |
| `LOCK_NOT_ACQUIRED` | 다른 인스턴스가 발송 중 |
| `DISABLED` | `imlate.notification.enabled=false` |
| `NO_SUPERVISOR` | 수신 사감이 설정되지 않음 |
| `NO_FAILURE` | (retry) 재시도할 실패 이력 없음 |

```bash
curl -s -X POST "http://localhost:8080/api/v1/admin/notifications/dispatch?date=2026-08-05&force=true" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

### 5.2 `POST /api/v1/admin/notifications/retry` — 실패 채널만 재발송

성공한 채널은 건드리지 않고, `FAILED` 이력만 남은 채널/수신처를 다시 시도합니다.
응답 형식은 `dispatch`와 같습니다.

```bash
curl -s -X POST "http://localhost:8080/api/v1/admin/notifications/retry?date=2026-08-05" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

### 5.3 `GET /api/v1/admin/notifications` — 발송 이력

```json
{
  "date": "2026-08-05",
  "count": 4,
  "items": [
    {
      "id": 101,
      "channel": "SMS",
      "recipientName": "홍사감",
      "recipient": "01012345678",
      "status": "SUCCESS",
      "attempt": 1,
      "targetCount": 12,
      "providerMessageId": "123456789",
      "errorMessage": null,
      "sentAt": "2026-08-05T21:50:03"
    },
    {
      "id": 102,
      "channel": "EMAIL",
      "recipientName": "김사감",
      "recipient": "supervisor2@example.com",
      "status": "FAILED",
      "attempt": 3,
      "targetCount": 12,
      "providerMessageId": null,
      "errorMessage": "SES 발송 실패: MessageRejected - Email address is not verified.",
      "sentAt": "2026-08-05T21:50:09"
    }
  ]
}
```

`channel` = `SMS` | `EMAIL`, `status` = `SUCCESS` | `FAILED` | `SKIPPED`.
관리자 전용 API이므로 수신처를 마스킹하지 않습니다.

```bash
curl -s "http://localhost:8080/api/v1/admin/notifications?date=2026-08-05" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

### 5.4 `POST /api/v1/admin/notifications/preview` — 발송 없이 렌더만

실제 발송을 하지 않고, 대사도 **복구 없이 조회만** 합니다. 조회 페이지 링크를 얻는 용도로도 유용합니다.

```json
{
  "date": "2026-08-05",
  "targetCount": 12,
  "lookupUrl": "http://localhost:5173/lookup?date=2026-08-05&token=…",
  "smsTitle": "[기숙사] 8/5 23:30 복귀 12명",
  "smsBody": "[기숙사 야간복귀 명단]\n8월 5일(수) 23:30 복귀 12명\n\n· 1반 (5명)\n  홍길동 302 / …",
  "emailSubject": "[기숙사 야간복귀] 8월 5일(수) 23:30 복귀 12명 명단",
  "emailText": "============================================================\n 기숙사 야간복귀(23:30) 명단\n…",
  "emailHtml": "<!DOCTYPE html>\n<html lang=\"ko\">…"
}
```

```bash
curl -s -X POST "http://localhost:8080/api/v1/admin/notifications/preview?date=2026-08-05" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

---

## 6. 헬스체크 / 운영 엔드포인트

| 경로 | 로컬(`local`) | 운영(`prod`) |
|---|---|---|
| `/actuator/health` | 상세 포함(`show-details: always`) | 상태만(`show-details: never`), nginx가 사설 대역/localhost 에서만 허용 |
| `/actuator/info`, `/actuator/metrics` | 노출 | **미노출** |
| `/actuator/env`, `/actuator/configprops` | 노출 | 미노출 |

```bash
curl -fsS "http://localhost:8080/actuator/health"
# {"status":"UP", ...}
```

액추에이터는 rate limit·통계 인터셉터 모두에서 제외됩니다.

---

## 7. 프론트엔드에서의 사용

`frontend/src/api/client.ts` 가 아래를 담당합니다.

- base: `import.meta.env.VITE_API_BASE ?? '/api/v1'`
- 모든 요청에 `X-Visitor-Id` 자동 부착
- 타임아웃 10초(`AbortController`) → `TIMEOUT` 코드
- 네트워크 단절 → `NETWORK_ERROR` 코드
- 서버 `ErrorResponse`를 `ApiError(code, status, message, fieldErrors, retryAfterSeconds)`로 정규화
- `errors[].field` 는 해당 입력칸 아래에 표시

| 클라이언트 전용 코드 | 의미 |
|---|---|
| `NETWORK_ERROR` | fetch 실패(오프라인 등), `status = 0` |
| `TIMEOUT` | 10초 초과, `status = 0` |
| `INVALID_RESPONSE` | 200인데 본문이 JSON이 아님 |
