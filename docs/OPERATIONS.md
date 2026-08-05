# imlate 운영 가이드 / 런북

이 문서는 **서비스를 매일 돌리는 사람**을 위한 것입니다.
구조는 [ARCHITECTURE.md](ARCHITECTURE.md), 엔드포인트 상세는 [API.md](API.md), 최초 구축은 [DEPLOYMENT.md](DEPLOYMENT.md)를 보세요.

아래 예시에서 자주 쓰는 변수:

```bash
export API="https://imlate.example.com"          # 또는 http://localhost:8080
export ADMIN_KEY="$(terraform -chdir=infra/terraform output -raw admin_api_key)"
export INSTANCE_ID="$(terraform -chdir=infra/terraform output -raw app_instance_id)"
export REGION=ap-northeast-2

# Redis 를 직접 들여다볼 때 (EC2 안에서만 접근 가능 — 프라이빗 서브넷)
export REDIS_HOST="$(terraform -chdir=infra/terraform output -raw redis_endpoint)"
export REDIS_AUTH="$(terraform -chdir=infra/terraform output -raw redis_auth_token)"
```

EC2 접속은 SSH 대신 SSM Session Manager를 권장합니다.

```bash
aws ssm start-session --target "$INSTANCE_ID" --region "$REGION"
```

---

## 1. 일일 운영 체크리스트

| 시각 | 확인 | 방법 |
|---|---|---|
| 아침(아무 때나) | 서비스 살아 있는가 | `curl -fsS "$API/actuator/health"` |
| 21:50 | 등록 창이 열려 있는가, 인원 수가 상식적인가 | `curl -s "$API/api/v1/registrations/window"` / `.../summary` |
| 22:01 | 마감되었는가 | `summary` 의 `"open": false` 확인 |
| **22:12** | **발송 성공했는가** | `curl -s "$API/api/v1/admin/notifications?date=$(date +%F)" -H "X-Admin-Key: $ADMIN_KEY"` |
| 22:12 | 검증 결과가 `CONSISTENT`/`RECOVERED` 인가 | 조회 페이지 배지 또는 발송 메일의 `[검증 결과]` 섹션 |
| 22:45 | 재시도 후에도 FAILED가 남았는가 | 위 이력 API에서 `status: "FAILED"` 검색 |
| 다음날 | 통계 스냅샷이 저장되었는가 | `SELECT * FROM daily_stat ORDER BY stat_date DESC LIMIT 5;` |

한 줄 점검 스크립트:

```bash
TODAY=$(TZ=Asia/Seoul date +%F)
curl -fsS "$API/actuator/health"; echo
curl -s "$API/api/v1/registrations/summary"; echo
curl -s "$API/api/v1/admin/notifications?date=$TODAY" -H "X-Admin-Key: $ADMIN_KEY"; echo
```

---

## 2. 사감님이 받는 내용 샘플

### 2.1 문자 (Aligo / 90바이트 초과 시 자동 LMS)

제목:

```
[기숙사] 8/5 23:30 복귀 12명
```

본문:

```
[기숙사 야간복귀 명단]
8월 5일(수) 23:30 복귀 12명

· 1반 (5명)
  홍길동 302 / 김철수 305 / 이영희 311 / 박민수 312 / 최지우 315
· 2반 (7명)
  강도윤 401 / 남궁민수 402 / ...

※ 22:30 이후 문은 잠기며 23:30에 일괄 개방됩니다.
검증: DB 12 / WAL 12 (일치)
통계: 오늘 방문 88명 / 오늘 등록 12건 / 누적 등록 320건
전체 명단: https://imlate.example.com/lookup?date=2026-08-05&token=...
```

> 명단이 길어 본문이 EUC-KR 기준 1,850바이트를 넘으면 **자동으로 요약 모드**로 바뀝니다.
> 이때는 이름/호수 대신 `· 1반 5명 / 2반 7명` 과 `(명단이 길어 이름/호수는 아래 링크에서 확인해 주세요)` 가 들어갑니다.
> 200명 규모에서는 요약 모드가 정상 동작입니다.

### 2.2 이메일 (Amazon SES / 텍스트 파트)

제목: `[기숙사 야간복귀] 8월 5일(수) 23:30 복귀 12명 명단`

```
============================================================
 기숙사 야간복귀(23:30) 명단
============================================================

 대상일    : 2026년 8월 5일(수)
 총 인원   : 12명
 복귀 시각 : 23:30 (출입문 일괄 개방)
 통금 시각 : 22:30 (이후 출입문 잠김)

[복귀 명단]
 번호  반   이름    호수
 ----  ---  ------  ----
    1  1반  홍길동  302
    2  1반  김철수  305

[반별 인원]
 1반 5명 / 2반 7명

[검증 결과 - Redis WAL <-> DB 대사]
 상태      : 일치 (CONSISTENT)
 DB 등록   : 12건
 WAL 기록  : 12건
 복구 건수 : 0건
 확인 시각 : 2026-08-05 22:10:00

[통계]
 오늘 방문자   : 88명
 오늘 페이지뷰 : 210회
 오늘 등록     : 12건
 누적 방문자   : 540명
 누적 페이지뷰 : 2100회
 누적 등록     : 320건

[안내]
 - 22:30 이후 기숙사 출입문은 잠깁니다.
 - 위 명단의 교육생은 23:30에 출입문이 일괄 개방될 때 함께 입관합니다.
 - 명단에 없는 교육생은 22:30 이전에 복귀해야 합니다.

[전체 명단 조회 페이지]
 https://imlate.example.com/lookup?date=2026-08-05&token=...
 (링크에는 열람 토큰이 포함되어 있습니다. 외부에 공유하지 말아 주세요.)

============================================================
 본 메일은 기숙사 야간복귀 등록 시스템에서 자동 발송되었습니다.
============================================================
```

HTML 파트에는 같은 내용이 카드/표 형태로 들어갑니다(한글 열 정렬은 전각 폭 2를 반영해 계산).

실제로 나갈 내용을 미리 보고 싶다면:

```bash
curl -s -X POST "$API/api/v1/admin/notifications/preview?date=2026-08-05" \
  -H "X-Admin-Key: $ADMIN_KEY" | python3 -m json.tool
```

---

## 3. 발송 실패 시 대응

### 3.1 상태 확인

```bash
TODAY=$(TZ=Asia/Seoul date +%F)
curl -s "$API/api/v1/admin/notifications?date=$TODAY" -H "X-Admin-Key: $ADMIN_KEY" | python3 -m json.tool
```

`items[].status` 가 `FAILED` 면 `errorMessage` 를 먼저 읽습니다.

| errorMessage 패턴 | 원인 | 조치 |
|---|---|---|
| `Aligo 발송 실패(result_code=…)` | 잔액 부족, 발신번호 미등록, 수신 거부 | 알리고 콘솔 확인 → 해결 후 `retry` |
| `Aligo 호출 실패: ResourceAccessException` | 네트워크·타임아웃 | 잠시 후 `retry`. NAT 공인 IP 화이트리스트 확인 |
| `수신 번호 형식이 올바르지 않습니다` | 사감 전화번호 설정 오류 | §5.3 절차로 번호 수정 후 `retry` |
| `Aligo 설정(api-key / user-id / sender)이 비어 있어…` | SSM 파라미터 누락 | §5.1 절차로 값 채우고 재기동 |
| `SES 발송 실패: MessageRejected - Email address is not verified` | SES 샌드박스 / 미검증 주소 | 수신 주소 검증 또는 프로덕션 액세스 신청 |
| `SES 발송 실패: SesV2Exception … credentials` | IAM 권한/자격증명 | 인스턴스 프로파일 확인 |
| `status: SKIPPED` | 사감 전화/이메일 미설정 | §5.3 으로 연락처 등록 |

### 3.2 실패 채널만 재발송

성공한 채널은 건드리지 않습니다.

```bash
curl -s -X POST "$API/api/v1/admin/notifications/retry?date=$TODAY" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

### 3.3 전체 강제 재발송

이미 성공 이력이 있어도 다시 보냅니다(사감님이 문자를 못 받았다고 하는 경우 등).

```bash
curl -s -X POST "$API/api/v1/admin/notifications/dispatch?date=$TODAY&force=true" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

### 3.4 스케줄러가 아예 돌지 않은 것 같을 때

```bash
# 로그에서 22:10 실행 흔적 확인
sudo grep -E "정기 사감 발송|사감 발송 완료|건너뜀" /var/log/imlate/imlate.log | tail -20
```

| 로그 | 의미 | 조치 |
|---|---|---|
| `사감 발송이 비활성화되어…` | `imlate.notification.enabled=false` | §5.1 로 `IMLATE_NOTIFICATION_ENABLED=true` |
| `다른 인스턴스가 이미 발송 중이라 건너뜁니다` | 락 경합(정상). 다른 인스턴스가 보냈는지 이력 확인 | 이력 확인 후 필요 시 `force=true` |
| `이미 성공한 발송 이력이 있어…` | 중복 방지 정상 동작 | `force=true` 로만 재발송 |
| 아무 로그도 없음 | 프로세스 미기동 또는 시간대 설정 오류 | `systemctl status imlate`, `timedatectl` 로 KST 확인 |

수동으로 즉시 실행:

```bash
curl -s -X POST "$API/api/v1/admin/notifications/dispatch?force=true" -H "X-Admin-Key: $ADMIN_KEY"
```

---

## 4. 대사 불일치(MISMATCH) 대응

조회 페이지 배지나 메일의 `[검증 결과]`가 `불일치(MISMATCH)`면 아래 순서로 봅니다.

### 4.1 어느 쪽에만 있는지 확인

```bash
curl -s "$API/api/v1/lookup?date=2026-08-05&token=$TOKEN" | python3 -c \
  "import json,sys; v=json.load(sys.stdin)['verification']; print(v['status'], v['dbCount'], v['walCount']); print('WAL만:', v['walOnly']); print('DB만:', v['dbOnly'])"
```

| 상황 | 원인 | 조치 |
|---|---|---|
| `dbOnly` 에만 값이 있음 | 등록 당시 Redis 장애로 WAL 미기록, 또는 WAL TTL(기본 7일) 만료 | **데이터 손실 아님.** DB가 정답이므로 그대로 사용. 과거 날짜 조회라면 정상 |
| `walOnly` 에 값이 남음 | 복구 INSERT가 실패했다는 뜻(DB 장애 지속 등) | DB 상태 확인 → `dispatch?force=true` 로 대사+발송 재실행 |
| `WAL_UNAVAILABLE` | Redis 접속 불가 | §7 Redis 장애 절차. 명단 자체는 DB 기준으로 정상 |

### 4.2 복구를 다시 돌리는 방법

복구가 포함된 대사는 발송 경로에서만 실행됩니다(`reconcile`). 조회(`inspect`)는 복구하지 않습니다.

```bash
# force=true 로 대사(복구 포함) + 발송을 다시 수행
curl -s -X POST "$API/api/v1/admin/notifications/dispatch?date=2026-08-05&force=true" \
  -H "X-Admin-Key: $ADMIN_KEY"
```

### 4.3 원본 데이터 직접 확인

```bash
# Redis WAL (해당 일자 전체)
redis-cli -h "$REDIS_HOST" --tls -a "$REDIS_AUTH" HGETALL imlate:wal:2026-08-05
redis-cli -h "$REDIS_HOST" --tls -a "$REDIS_AUTH" HLEN   imlate:wal:2026-08-05
redis-cli -h "$REDIS_HOST" --tls -a "$REDIS_AUTH" TTL    imlate:wal:2026-08-05
```

```sql
-- MySQL 명단
SELECT id, class_name, student_name, room_number, wal_id, registered_at
FROM return_registration
WHERE registration_date = '2026-08-05'
ORDER BY class_name, student_name;
```

---

## 5. 설정 변경

### 5.1 운영(SSM Parameter Store) 값 바꾸기

**애플리케이션 재배포 없이** 값만 바꾸고 재기동하면 됩니다.

```bash
aws ssm put-parameter --name "/imlate/prod/IMLATE_ALIGO_API_KEY" \
  --value '<NEW_KEY>' --type SecureString --overwrite --region "$REGION"

aws ssm send-command --instance-ids "$INSTANCE_ID" --region "$REGION" \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["systemctl restart imlate-env.service","systemctl restart imlate.service"]'
```

`imlate-env.service` 가 `/imlate/{env}/*` 를 다시 읽어 `/etc/imlate/imlate.env` 를 새로 씁니다.
파라미터 이름의 **마지막 세그먼트가 그대로 환경변수 이름**입니다.

로컬/온프레미스라면 `backend/config/application-secret.yml` 을 고치고 애플리케이션을 재시작합니다.

### 5.2 등록 마감시간 변경 (예: 22:00 → 21:30)

코드 수정 없이 설정만 바꿉니다. 관련 값은 서로 맞물려 있으니 함께 확인하세요.

| 환경변수 | 프로퍼티 | 기본값 | 의미 |
|---|---|---|---|
| `IMLATE_REGISTRATION_CLOSE_TIME` | `imlate.registration.close-time` | `22:00` | 이 시각 **정각부터** 등록 거부 |
| `IMLATE_NOTIFICATION_DISPATCH_CRON` | `imlate.notification.dispatch-cron` | `0 10 22 * * *` | 발송 시각(마감 + 10분 권장) |
| `IMLATE_NOTIFICATION_RETRY_CRON` | `imlate.notification.retry-cron` | `0 25,40 22 * * *` | 실패 재시도 |
| `IMLATE_REGISTRATION_CURFEW_TIME` | `imlate.registration.curfew-time` | `22:30` | 문 잠김(안내 문구용) |
| `IMLATE_REGISTRATION_RETURN_TIME` | `imlate.registration.return-time` | `23:30` | 일괄 개방(안내 문구용) |
| — | `imlate.registration.open-time` | `00:00` | 등록 시작(환경변수 미노출, yml 직접 수정) |

```bash
aws ssm put-parameter --name "/imlate/prod/IMLATE_REGISTRATION_CLOSE_TIME" \
  --value '21:30' --type SecureString --overwrite --region "$REGION"
aws ssm put-parameter --name "/imlate/prod/IMLATE_NOTIFICATION_DISPATCH_CRON" \
  --value '0 40 21 * * *' --type SecureString --overwrite --region "$REGION"
# 재기동 (5.1 의 send-command)
```

> 주의: cron은 6필드(`초 분 시 일 월 요일`)이며 `zone`은 `imlate.timezone`(Asia/Seoul)이 적용됩니다.
> 마감 시각을 바꾸면 프론트 카운트다운은 서버 `window` 응답을 따라 자동으로 맞춰집니다.
> 단, 프론트 오류 문구(`REGISTRATION_CLOSED` 폴백 메시지)에 "22:00"이 하드코딩되어 있으므로
> 서버 메시지가 없을 때만 어긋납니다 — 서버는 항상 실제 마감 시각으로 메시지를 만듭니다.

### 5.3 사감 연락처 추가 / 변경

현재 설정은 **사감 2명**을 전제로 `SUPERVISOR1_*`, `SUPERVISOR2_*` 를 사용합니다.

```bash
for k in NAME PHONE EMAIL; do
  aws ssm put-parameter --name "/imlate/prod/IMLATE_SUPERVISOR1_$k" \
    --value '<VALUE>' --type SecureString --overwrite --region "$REGION"
done
# 재기동 (5.1 의 send-command)
```

- 전화번호는 하이픈 없이 `01012345678` 형태로 넣습니다(서버가 숫자만 남겨 정규화합니다).
- 전화 또는 이메일 중 하나가 비어 있으면 해당 채널은 `SKIPPED` 이력으로 남고 나머지 채널만 발송됩니다.
- **3명 이상으로 늘리려면** `application-*.yml` 의 `imlate.notification.supervisors` 리스트에 항목을 추가하고,
  Terraform `modules/ssm` 에 대응 파라미터를 추가해야 합니다(설정 구조상 리스트는 yml에서 확장).
- 이메일 수신자를 새로 추가할 때는 **SES 검증 상태**를 먼저 확인하세요(샌드박스면 미검증 주소로 못 보냅니다).

### 5.4 발송 일시 중지

```bash
aws ssm put-parameter --name "/imlate/prod/IMLATE_NOTIFICATION_ENABLED" \
  --value 'false' --type SecureString --overwrite --region "$REGION"
# 재기동 후 스케줄러는 skipReason=DISABLED 로 아무것도 하지 않습니다.
# 그래도 관리 API 에 force=true 를 주면 강제 발송은 가능합니다.
```

### 5.5 rate limit 조정

`imlate.rate-limit.*`(`application.yml`)에서 스코프별 `capacity` / `refill-tokens` / `refill-period-seconds` 를 바꿉니다.
`enabled=false` 로 끄거나, Redis 장애 시 동작을 `fail-open`(기본 true, 통과) / `false`(429)로 선택할 수 있습니다.
AWS WAF 상한은 Terraform `waf_rate_limit_per_5min`, nginx는 `infra/nginx/imlate.conf` 의 `limit_req` 입니다.

---

## 6. 통계 확인

### 6.1 API

```bash
# 공개 요약
curl -s "$API/api/v1/stats/summary" | python3 -m json.tool

# 일자별 (조회 토큰 필요)
curl -s "$API/api/v1/stats/daily?from=2026-08-01&to=2026-08-05&token=$TOKEN" | python3 -m json.tool
```

### 6.2 Redis 원본

로컬은 `redis-cli` 그대로, 운영은 EC2 안에서 `redis-cli -h "$REDIS_HOST" --tls -a "$REDIS_AUTH"` 를 붙여 실행합니다.

```bash
redis-cli GET    imlate:stats:pv:total
redis-cli GET    imlate:stats:pv:2026-08-05
redis-cli PFCOUNT imlate:stats:uv:total
redis-cli PFCOUNT imlate:stats:uv:2026-08-05
redis-cli GET    imlate:stats:reg:2026-08-05
redis-cli GET    imlate:stats:reg:total
redis-cli SMEMBERS imlate:stats:days
```

### 6.3 DB 스냅샷

```sql
SELECT stat_date, page_views, unique_visitors, registrations, updated_at
FROM daily_stat ORDER BY stat_date DESC LIMIT 14;
```

스냅샷은 23:55(당일 선반영)와 00:05(전일 확정 + 보존 기간 초과분 삭제)에 갱신됩니다.
**모든 값이 0이면 저장하지 않습니다** — Redis 장애로 0이 나왔을 때 기존 스냅샷을 덮어쓰지 않기 위해서입니다.
보존 기간은 `imlate.stats.retention-days`(기본 400일)입니다.

---

## 7. 로그 확인 위치

| 위치 | 내용 |
|---|---|
| `/var/log/imlate/imlate.log` | 애플리케이션 표준 출력 (logrotate: 일 단위, 14세대, 압축) |
| `/var/log/imlate/imlate-error.log` | 표준 에러 |
| `journalctl -u imlate.service` | systemd 기동/종료/재시작 |
| `journalctl -u imlate-env.service` | SSM 환경변수 로딩 결과 |
| `/var/log/imlate-bootstrap.log` | EC2 최초 부트스트랩(user_data) |
| `/var/log/nginx/access.log`, `error.log` | nginx |
| CloudWatch Logs `/imlate/app`, `/imlate/app-error` | CloudWatch Agent 설치 시 |

자주 쓰는 grep:

```bash
sudo tail -f /var/log/imlate/imlate.log

# 등록 흐름
sudo grep "Registration created"        /var/log/imlate/imlate.log | tail -20
sudo grep "Duplicate registration"      /var/log/imlate/imlate.log | tail -20
sudo grep "WAL append failed"           /var/log/imlate/imlate.log

# 대사 / 발송
sudo grep -E "Reconciliation (mismatch|CONSISTENT|RECOVERED|WAL_UNAVAILABLE)" /var/log/imlate/imlate.log
sudo grep "사감 발송"                    /var/log/imlate/imlate.log | tail -20

# rate limit (WARN 요약은 1분에 1회만 남습니다)
sudo grep "rate limit 차단 발생"          /var/log/imlate/imlate.log
```

로그 레벨은 운영 `INFO`, 로컬 `com.skala.imlate=DEBUG` 입니다.
전화번호/이메일은 로그에서 마스킹되며(`010****5678`), 스택 트레이스는 응답 본문에 나가지 않습니다.

---

## 8. 장애 대응 런북

### 8.1 서비스 응답 없음 (5xx / 접속 불가)

```bash
aws ssm start-session --target "$INSTANCE_ID" --region "$REGION"
systemctl status imlate.service
sudo tail -100 /var/log/imlate/imlate-error.log
sudo systemctl restart imlate.service
curl -fsS http://127.0.0.1:8080/actuator/health
```

ALB 헬스체크가 계속 실패하면 대상 그룹 상태와 보안 그룹(앱 8080 인바운드)을 확인합니다.

### 8.2 Redis(ElastiCache) 장애

**서비스는 계속 동작합니다.** 확인 순서:

1. `AWS 콘솔 → ElastiCache` 상태 / CloudWatch `CurrConnections`, `Evictions`
2. 애플리케이션 로그: `Redis WAL unavailable`, `Redis rate limit 백엔드 오류`, `Redis 통계 조회 실패`
3. 영향 범위
   - 등록: 정상(WAL만 미기록 → 이후 `dbOnly` 로 표시됨)
   - rate limit: `fail-open=true` 면 인메모리 리미터로 강등
   - 통계: `daily_stat` 폴백 또는 0
   - 발송 락: 락 없이 진행하되 DB 이력으로 중복 방지
4. 복구 후 특별한 수동 조치는 필요 없습니다. 그날 대사는 `WAL_UNAVAILABLE` 또는 `MISMATCH(dbOnly)` 로 남습니다.

### 8.3 MySQL(RDS) 장애

등록이 500으로 실패합니다. 다만 **WAL에는 남아 있으므로** DB 복구 후 22:10 대사(또는 수동 `dispatch?force=true`)에서
누락분이 자동 복구됩니다.

```bash
# 복구 후 확인
curl -s -X POST "$API/api/v1/admin/notifications/dispatch?date=$TODAY&force=true" -H "X-Admin-Key: $ADMIN_KEY"
```

RDS 상태·커넥션 수·`FreeableMemory` 를 CloudWatch에서 확인하고, 필요하면 Hikari 풀 크기
(`IMLATE_DB_POOL_MAX`, 운영 기본 20)를 조정합니다.

### 8.4 22:10에 문자/메일이 안 왔다

```
1) 이력 확인:  GET /api/v1/admin/notifications?date=오늘
   - 이력이 없다  → 스케줄러 미실행/비활성 (§3.4)
   - skipped NO_REGISTRATION → 등록자 0명(정상)
   - FAILED    → §3 표로 원인 파악 후 retry
2) 그래도 안 되면 force 재발송 (§3.3)
3) 최후 수단: 조회 페이지 URL 을 preview 로 뽑아 사감님께 직접 전달
   curl -s -X POST "$API/api/v1/admin/notifications/preview" -H "X-Admin-Key: $ADMIN_KEY"
```

### 8.5 사감님이 "링크가 만료됐다"고 함

토큰 TTL(운영 기본 48시간)이 지난 경우입니다. 새 링크를 발급해 전달합니다.

```bash
curl -s -X POST "$API/api/v1/admin/notifications/preview?date=2026-08-05" \
  -H "X-Admin-Key: $ADMIN_KEY" | python3 -c "import json,sys; print(json.load(sys.stdin)['lookupUrl'])"
```

TTL을 늘리려면 `IMLATE_LOOKUP_TOKEN_TTL_HOURS` 를 조정합니다.
`IMLATE_LOOKUP_TOKEN_SECRET` 을 바꾸면 **이미 발급된 모든 링크가 즉시 무효**가 됩니다(유출 시 대응책).

### 8.6 트래픽 급증 / 429 폭주

```bash
sudo grep "rate limit 차단 발생" /var/log/imlate/imlate.log | tail
```

- 정상 사용자까지 막힌다면 `imlate.rate-limit.global.capacity` 를 올립니다(등록 1회에 요청 2~3개 소모).
- 특정 IP의 공격이면 WAF에서 IP 차단 규칙을 추가하는 편이 저렴합니다(ALB 도달 전 차단).
- WAF 관리형 규칙 오탐이 의심되면 `waf_managed_rules_count_only = true` 로 카운트 모드 전환 후 지표를 확인합니다.

### 8.7 등록이 안 된다는 문의

| 사용자 화면 문구 | 원인 | 확인 |
|---|---|---|
| "등록 마감 시간(22:00)이 지났습니다." | 마감 후 | `window` 응답의 `open`, `closesAt` |
| "요청이 너무 많습니다…" | rate limit | 같은 IP(공용 Wi-Fi/NAT)에서 다수 접속인지 확인 |
| "…한글·영문·숫자와 공백, 괄호, 하이픈만…" | 특수문자 입력 | 정규식 `^[가-힣A-Za-z0-9 ()\-]{1,20}$` |
| "이미 등록되어 있습니다" | 정상(멱등) | 명단에 있으면 문제 없음 |
| 네트워크/타임아웃 | 프론트 10초 타임아웃 | 백엔드 헬스·응답시간 확인 |

---

## 9. 정기 점검 (월 1회 권장)

- [ ] SES 발송량/바운스율 확인(바운스 5% 초과 시 계정 제재 위험)
- [ ] 알리고 잔액 및 발신번호 유효성
- [ ] `daily_stat` 보존 정리가 도는지(로그: `보존 기간 초과 일별 통계 N건 삭제`)
- [ ] RDS 자동 백업 보존(기본 7일)·스토리지 여유
- [ ] ElastiCache 메모리/축출(`Evictions`) 지표
- [ ] `IMLATE_LOOKUP_TOKEN_SECRET` / `IMLATE_ADMIN_API_KEY` 교체 여부 검토
- [ ] `terraform plan` 이 비어 있는지(수동 변경 드리프트 확인)
