# imlate 운영 가이드

이 문서는 **서비스를 매일 돌리는 사람**을 위한 것입니다.

> ### 사고가 났다면 이 문서가 아니라 [RUNBOOK.md](RUNBOOK.md) 입니다
> "사감님이 문자를 못 받았다", "등록이 안 된다", "인스턴스가 죽었다" 처럼
> **증상에서 출발하는 대응 절차**는 전부 [RUNBOOK.md](RUNBOOK.md) 에 있습니다.
> 이 문서는 **평상시**(체크리스트·설정 변경·통계·로그 위치)를 다룹니다.

구조는 [ARCHITECTURE.md](ARCHITECTURE.md), 엔드포인트 상세는 [API.md](API.md),
최초 구축·배포·모니터링 설정은 [DEPLOYMENT.md](DEPLOYMENT.md)를 보세요.

현재 운영 주소는 **<https://skala-imlate.link>** 입니다(EC2 1대 + nginx, ALB 없음).

아래 예시에서 자주 쓰는 변수 — **PowerShell**(운영자 PC 가 Windows):

```powershell
cd C:\Users\<사용자>\Desktop\skala-imlate\infra\terraform

$REGION = "ap-northeast-2"
$BASE   = "https://skala-imlate.link"
$KEY    = terraform output -raw admin_api_key
$H      = @{ "X-Admin-Key" = $KEY }
$IID    = terraform output -raw app_instance_id
$TODAY  = (Get-Date).ToString("yyyy-MM-dd")
```

같은 값을 **Git Bash** 에서 쓸 때:

```bash
export API="https://skala-imlate.link"           # 또는 http://localhost:8080
export ADMIN_KEY="$(terraform -chdir=infra/terraform output -raw admin_api_key)"
export INSTANCE_ID="$(terraform -chdir=infra/terraform output -raw app_instance_id)"
export REGION=ap-northeast-2

# Redis 를 직접 들여다볼 때 (EC2 안에서만 접근 가능)
export REDIS_HOST="$(terraform -chdir=infra/terraform output -raw redis_endpoint)"
export REDIS_AUTH="$(terraform -chdir=infra/terraform output -raw redis_auth_token)"
```

> **외부에서 부를 수 있는 헬스체크는 `/healthz` 입니다.**
> `/actuator/**` 는 nginx 가 사설 대역·localhost 만 허용하므로 밖에서 부르면 403 입니다(의도된 설정).
> 상세 헬스는 서버 안에서 `curl -s http://127.0.0.1:8080/actuator/health` 로 봅니다.

EC2 접속은 SSH 대신 SSM Session Manager를 권장합니다.

```powershell
aws ssm start-session --target $IID --region $REGION
```

세션 플러그인 없이 명령 한 줄만 실행하는 방법(`Run-OnApp` 헬퍼)은
[RUNBOOK.md §0.4](RUNBOOK.md#04-서버에-붙는-두-가지-방법) 에 있습니다.

---

## 1. 운영 체크리스트

### 1.1 일일 운영 체크리스트

하루 흐름은 이렇습니다(전부 KST, 전부 설정값 — §5.2 로 바꿀 수 있습니다).

```
00:00 등록 시작 → 21:45 등록 마감 → 21:50 사감 발송 → (22:05 / 22:20 실패분 재시도)
                → 22:30 출입문 잠김 → 23:30 일괄 개방
```

| 시각 | 확인 | 방법 | 실패하면 |
|---|---|---|---|
| 아침(아무 때나) | 서비스 살아 있는가 | `Invoke-RestMethod "$BASE/healthz"` | [RUNBOOK §3](RUNBOOK.md#3-인스턴스가-죽었다-알람을-받았을-때) |
| 21:35 | 등록 창이 열려 있는가 | `Invoke-RestMethod "$BASE/api/v1/registrations/window"` (`open:true`, `closesAt` 이 21:45 인지) | [RUNBOOK §2.1](RUNBOOK.md#21-마감-이후인가-409--대개-정상) |
| 21:46 | 마감되었는가 | `summary` 의 `"open": false` 확인 | 〃 |
| **21:52** | **발송 성공했는가 ★** | `(Invoke-RestMethod "$BASE/api/v1/admin/notifications?date=$TODAY" -Headers $H).items` | [RUNBOOK §1](RUNBOOK.md#1-2150인데-사감님이-문자를-못-받았다--최우선) |
| 21:52 | 대사 결과가 `CONSISTENT`/`RECOVERED` 인가 | `Invoke-RestMethod "$BASE/api/v1/admin/reconciliation?date=$TODAY" -Headers $H` | [RUNBOOK §4](RUNBOOK.md#4-명단이-이상하다-인원이-안-맞는다) |
| 22:25 | 재시도 후에도 FAILED가 남았는가 | 위 이력 API에서 `status: "FAILED"` 검색 | [RUNBOOK §1.8](RUNBOOK.md#18-수동-재발송-원인-해결-후) |
| **22:30 전** | **사감님이 명단을 받았는가 ★** | 문 잠기기 전에 확인 | [RUNBOOK §1.7](RUNBOOK.md#17-최후-수단--조회-페이지-링크를-직접-전달) |
| 다음날 | 통계 스냅샷이 저장되었는가 | `SELECT * FROM daily_stat ORDER BY stat_date DESC LIMIT 5;` | §6.3 |

> **21:52 확인은 사람이 합니다.** 자동 알림(§5.7)과 CloudWatch 알람을 붙였지만,
> **알람이 실제로 우는 것을 확인하기 전까지는 그것을 감지 수단으로 믿지 마세요**
> (구독 미확인·지표 미연결이면 침묵합니다 — [DEPLOYMENT.md §11.3](DEPLOYMENT.md#113-알람이-만들어졌는지--지금-상태가-어떤지)).
> 실제로 알리고 IP 미등록·SES 미검증으로 두 번 실패했고, 두 번 다 관리 API 를 직접 조회하고서야 알았습니다.
>
> 마감(21:45)과 통금(22:30) 사이 45분이 "명단을 받아 확인하는 시간"입니다.
> 발송이 실패해도 재시도 2회(22:05 / 22:20)가 통금 전에 끝나도록 배치되어 있습니다.

21:52 한 번에 훑기 (PowerShell):

```powershell
$TODAY = (Get-Date).ToString("yyyy-MM-dd")
Invoke-RestMethod "$BASE/healthz"
Invoke-RestMethod "$BASE/api/v1/registrations/summary"
(Invoke-RestMethod "$BASE/api/v1/admin/notifications?date=$TODAY" -Headers $H).items |
  Format-Table channel, recipientName, status, attempt, errorMessage
Invoke-RestMethod "$BASE/api/v1/admin/reconciliation?date=$TODAY" -Headers $H |
  Select-Object status, dbCount, walCount, recoveredCount
```

Git Bash:

```bash
TODAY=$(TZ=Asia/Seoul date +%F)
curl -fsS "$API/healthz"; echo
curl -s "$API/api/v1/registrations/summary"; echo
curl -s "$API/api/v1/admin/notifications?date=$TODAY" -H "X-Admin-Key: $ADMIN_KEY"; echo
```

### 1.2 운영 전환 체크리스트 (실제 사용자에게 열기 전 1회)

리허설이 아니라 **진짜 교육생·진짜 사감님**을 대상으로 돌리기 직전에 한 번 훑습니다.
각 항목은 "안 하면 무슨 일이 생기는가"를 함께 적었습니다.

#### (1) 수신자 — 잘못되면 명단이 엉뚱한 곳으로 갑니다

- [ ] **사감 연락처를 실제 값으로 교체**했는가 (`supervisor1/2_name·phone·email`, §5.3)
      → 테스트 번호가 남아 있으면 사감님은 아무것도 못 받습니다
- [ ] 전화번호가 하이픈 없는 형식(`01012345678`)인가 → 형식 오류는 `FAILED` 로 남습니다
- [ ] **SES 수신 주소 검증 완료**(`VerifiedForSendingStatus: true`) — 샌드박스 운영 중입니다

      ```powershell
      terraform output -json ses_recipient_identities
      aws sesv2 get-email-identity --email-identity "<주소>" --region $REGION --query "VerifiedForSendingStatus"
      ```

      → 미검증이면 메일이 `Email address is not verified` 로 전부 실패합니다(**실제 발생한 사고**)
- [ ] 현재 구성상 **사감 2명의 이메일이 모두 운영자 주소**입니다. 의도한 구성인지 확인
      (운영자가 매일 발송 결과를 메일로 확인하는 구조)
- [ ] **운영자 알림 수신처가 사감 수신처와 분리**되어 있는가 (§5.7)
      → 분리되지 않으면 "발송 실패했습니다" 같은 내부 알림이 **사감님께 갑니다**

#### (2) 문자 채널 — 잘못되면 문자만 조용히 실패합니다

- [ ] 알리고 **발신번호 사전등록**이 유효한가 (해지·만료 여부)
- [ ] 알리고 **발신 IP 화이트리스트 = 현재 EIP** 인가 (**실제 발생한 사고**)

      ```powershell
      terraform output -raw aligo_whitelist_ip
      ```

      → 다르면 `result_code=-101 인증오류` 로 문자만 전부 실패합니다([RUNBOOK §1.4](RUNBOOK.md#14-알리고--101-인증오류--발신-ip-화이트리스트))
- [ ] 알리고 **잔액**이 충분한가 (콘솔의 잔여건수 확인. 명단이 길면 LMS 로 나가 단가가 3배)
- [ ] `IMLATE_ALIGO_TEST_MODE` 파라미터가 **남아 있지 않은가**
      (`prod` 기본값은 실제 발송. 리허설용으로 넣었다면 반드시 삭제 — [DEPLOYMENT.md §1.5](DEPLOYMENT.md#15-aligo문자))

#### (3) 감시 — 잘못되면 실패를 아무도 모릅니다

- [ ] CloudWatch 알람이 실제로 만들어졌는가

      ```powershell
      aws cloudwatch describe-alarms --region $REGION `
        --query "MetricAlarms[].{이름:AlarmName,상태:StateValue}" --output table
      ```

- [ ] **SNS 구독 확인 메일을 클릭했는가** (`PendingConfirmation` 이면 알람이 와도 메일이 안 옵니다)

      ```powershell
      aws sns list-subscriptions --region $REGION --query "Subscriptions[].{주소:Endpoint,구독:SubscriptionArn}" --output table
      ```

      자세한 절차는 [DEPLOYMENT.md §11](DEPLOYMENT.md#11-모니터링--알람)
- [ ] 알림 메일이 **실제로 내 받은편지함에 도착**하는지 1회 테스트했는가(스팸함 포함)
- [ ] 발송 실패 시 운영자 알림이 오는 경로를 확인했는가 (§5.7)

#### (4) 데이터 보호

- [ ] **`db_deletion_protection = true` 로 되돌렸는가** ★
      → 구축 편의를 위해 `false` 로 두었다면 운영 전환 시 반드시 켭니다. 실수 한 번에 DB 가 사라집니다
- [ ] `db_skip_final_snapshot = false` 로 되돌렸는가 (삭제 시 최종 스냅샷 보존)
- [ ] RDS 자동 백업 보존 기간(기본 7일)이 의도한 값인가

#### (5) 리허설 — 위 항목이 다 끝난 뒤 마지막에

- [ ] 문구 육안 검수: `preview` 로 문자/메일 본문 확인(한글 깨짐·표 정렬·문의처 문구)

      ```powershell
      $p = Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/preview" -Headers $H
      $p.smsBody; $p.emailText
      ```

- [ ] **실제 발송 리허설**: 테스트 등록 1건 → `dispatch?force=true` → 문자·메일 수신 확인 → 테스트 데이터 정리
      (리허설 수신처를 잠깐 본인 번호로 바꿔 두면 사감님을 놀라게 하지 않습니다)
- [ ] 리허설 후 사감 연락처를 **실제 값으로 되돌렸는지** 재확인
- [ ] 21:50 스케줄이 KST 로 도는지 다음 날 로그로 확인

      ```powershell
      Run-OnApp "grep '사감 발송' /var/log/imlate/imlate.log | tail -5"
      Run-OnApp "timedatectl | head -5"
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
※ 이 번호는 수신 전용이라 답장을 받을 수 없습니다.
   문의는 SKALA 운영진 또는 khdev07@naver.com 으로 부탁드립니다.
전체 명단: https://skala-imlate.link/lookup?date=2026-08-05&token=...
```

> **수신 전용 안내와 문의처는 문자·메일 양쪽에 반드시 들어갑니다.**
> 사감님이 문자에 답장해도 아무도 읽지 않기 때문입니다. 문구는 `imlate.notification.contact-name` /
> `contact-email` 설정값으로 렌더되므로, 담당자가 바뀌면 §5.4 로 값만 바꾸면 됩니다.

> 명단이 길어 본문이 EUC-KR 기준 1,850바이트를 넘으면 **자동으로 요약 모드**로 바뀝니다.
> 이때는 이름/호수 대신 `· 1반 5명 / 2반 7명` 과 `(명단이 길어 이름/호수는 아래 링크에서 확인해 주세요)` 가 들어갑니다.
> 요약 모드에서도 위 두 안내(수신 전용 · 문의처)는 남습니다. 200명 규모에서는 요약 모드가 정상 동작입니다.

> 대사(검증) 결과와 방문/등록 통계는 **사감님께 보내는 문구에 넣지 않습니다**(API.md §0 노출 원칙).
> 운영자는 `/api/v1/admin/reconciliation` 과 `/api/v1/stats/**` 로 확인합니다.

### 2.2 이메일 (Amazon SES / 텍스트 파트)

제목: `[기숙사 야간복귀] 8월 5일(수) 23:30 복귀 12명 명단`

```
============================================================
 기숙사 야간복귀(23:30) 명단
============================================================

 대상일    : 2026년 8월 5일(수)
 총 인원   : 12명
 등록 마감 : 21:45 (마감된 명단입니다)
 복귀 시각 : 23:30 (출입문 일괄 개방)
 통금 시각 : 22:30 (이후 출입문 잠김)

[복귀 명단]
 번호  반   이름    호수
 ----  ---  ------  ----
    1  1반  홍길동  302
    2  1반  김철수  305

[반별 인원]
 1반 5명 / 2반 7명

[안내]
 - 등록은 21:45에 마감되었습니다. (이후 등록분은 없습니다)
 - 22:30 이후 기숙사 출입문은 잠깁니다.
 - 위 명단의 교육생은 23:30에 출입문이 일괄 개방될 때 함께 입관합니다.
 - 명단에 없는 교육생은 22:30 이전에 복귀해야 합니다.

[전체 명단 조회 페이지]
 https://skala-imlate.link/lookup?date=2026-08-05&token=...
 (링크에는 열람 토큰이 포함되어 있습니다. 외부에 공유하지 말아 주세요.)

[문의]
 - 이 메일과 함께 발송된 문자의 발신번호는 수신 전용이라 답장을 받을 수 없습니다.
 - 문의는 SKALA 운영진 또는 khdev07@naver.com 으로 부탁드립니다.

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

## 3. 발송 실패 시 대응 (요약)

> **원인 분기·실패 사유 해석·최후 수단까지 전체 절차는 [RUNBOOK §1](RUNBOOK.md#1-2150인데-사감님이-문자를-못-받았다--최우선) 에 있습니다.**
> 여기에는 **가장 많이 쓰는 명령 세 개**만 둡니다(중복을 줄여 두 문서가 어긋나지 않게 합니다).

### 3.1 상태 확인

```powershell
(Invoke-RestMethod "$BASE/api/v1/admin/notifications?date=$TODAY" -Headers $H).items |
  Format-Table channel, recipientName, status, attempt, errorMessage
```

`status` 가 `FAILED` 면 `errorMessage` 로 원인을 가릅니다 →
[RUNBOOK §1.2 원인 분기표](RUNBOOK.md#12-2단계--errormessage-로-원인을-가른다)

### 3.2 실패 채널만 재발송

성공한 채널은 건드리지 않습니다.

```powershell
Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/retry?date=$TODAY" -Headers $H
```

### 3.3 전체 강제 재발송

이미 성공 이력이 있어도 다시 보냅니다(사감님이 문자를 못 받았다고 하는 경우 등).
`enabled=false` 여도 강제로 나갑니다.

```powershell
Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/dispatch?date=$TODAY&force=true" -Headers $H
```

### 3.4 스케줄러가 아예 돌지 않은 것 같을 때

```powershell
Run-OnApp "grep -E '정기 사감 발송|사감 발송 완료|건너뜀' /var/log/imlate/imlate.log | tail -20"
```

로그별 의미(`DISABLED` / `LOCK_NOT_ACQUIRED` / `ALREADY_SENT` / `NO_REGISTRATION` …)와 조치는
[RUNBOOK §1.3](RUNBOOK.md#13-이력이-아예-없다-스케줄러-미실행) 표를 보세요.

---

## 4. 대사 불일치(MISMATCH) 대응 (요약)

> **전체 절차는 [RUNBOOK §4](RUNBOOK.md#4-명단이-이상하다-인원이-안-맞는다) 에 있습니다.**

```powershell
Invoke-RestMethod "$BASE/api/v1/admin/reconciliation?date=$TODAY" -Headers $H | ConvertTo-Json -Depth 5
```

| `status` | 의미 | 조치 |
|---|---|---|
| `CONSISTENT` | WAL 과 DB 가 일치 | **정상** |
| `MISMATCH` | 불일치가 있다 | [RUNBOOK §4.2](RUNBOOK.md#42-mismatch-읽는-법) |
| `WAL_UNAVAILABLE` | Redis 접속 불가로 대사 불가 | §8.2. **명단 자체는 DB 기준으로 정상** |
| `RECOVERED` | 복구 후 일치 — **발송(`dispatch`) 응답·로그에서만** 보입니다 | **정상** |

기억해 둘 세 가지:

- **대사 결과는 사감님께 보내는 문구·조회 페이지에 노출하지 않습니다.** 운영자만 이 경로로 봅니다.
- **`GET /api/v1/admin/reconciliation` 은 복구하지 않습니다**(부작용 없는 GET).
  복구는 발송 경로(`dispatch`)에서만 일어납니다 — "봤는데 안 고쳐졌다"는 대부분 이것 때문입니다.
- 그래서 **발송 전에 조회하면 아직 복구되지 않은 `walOnly` 때문에 `MISMATCH` 로 보일 수 있습니다.**
  21:50 발송 이후에도 남아 있으면 그때가 진짜 문제입니다.

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

### 5.2 등록 마감시간 변경 (예: 21:45 → 21:30)

코드 수정 없이 설정만 바꿉니다. 관련 값은 서로 맞물려 있으니 함께 확인하세요.

| 환경변수 | 프로퍼티 | 기본값 | 의미 |
|---|---|---|---|
| `IMLATE_REGISTRATION_CLOSE_TIME` | `imlate.registration.close-time` | **`21:45`** | 이 시각 **정각부터** 등록 거부 |
| `IMLATE_NOTIFICATION_DISPATCH_CRON` | `imlate.notification.dispatch-cron` | **`0 50 21 * * *`** | 발송 시각(= 21:50, 마감 + 5분) |
| `IMLATE_NOTIFICATION_RETRY_CRON` | `imlate.notification.retry-cron` | **`0 5,20 22 * * *`** | 실패 재시도(= 22:05 / 22:20) |
| `IMLATE_REGISTRATION_CURFEW_TIME` | `imlate.registration.curfew-time` | `22:30` | 문 잠김(안내 문구용) — **변경 없음** |
| `IMLATE_REGISTRATION_RETURN_TIME` | `imlate.registration.return-time` | `23:30` | 일괄 개방(안내 문구용) — **변경 없음** |
| — | `imlate.registration.open-time` | `00:00` | 등록 시작(환경변수 미노출, yml 직접 수정) — **변경 없음** |

> **이력:** 원래는 마감 22:00 / 발송 22:10 / 재시도 22:25·22:40 이었으나,
> 운영자 요청으로 **마감 21:45 / 발송 21:50 / 재시도 22:05·22:20** 으로 앞당겼습니다.
> 통금(22:30)과 일괄 개방(23:30), 등록 시작(00:00)은 그대로입니다.

**지켜야 할 순서:** `등록 시작(00:00) < 마감 < 발송 < 재시도 < 통금(22:30) < 일괄 개방(23:30)`
발송이 통금을 넘어가면 사감님이 문을 잠근 뒤에 명단을 받게 되므로 의미가 없습니다.

```bash
aws ssm put-parameter --name "/imlate/prod/IMLATE_REGISTRATION_CLOSE_TIME" \
  --value '21:30' --type SecureString --overwrite --region "$REGION"
aws ssm put-parameter --name "/imlate/prod/IMLATE_NOTIFICATION_DISPATCH_CRON" \
  --value '0 35 21 * * *' --type SecureString --overwrite --region "$REGION"
# 재시도(22:05 / 22:20)는 여전히 "발송 이후 · 통금 이전" 이므로 그대로 두어도 됩니다.
# 바꾼다면 cron 한 줄에 여러 시각을 넣을 때 시·분이 곱해진다는 점에 주의하세요.
#   '0 5,20 22 * * *'    → 22:05, 22:20        (의도한 값)
#   '0 50 21,22 * * *'   → 21:50, 22:50        (22:50 은 통금 이후 — 잘못된 예)
# 재기동 (5.1 의 send-command)
```

> 주의: cron은 6필드(`초 분 시 일 월 요일`)이며 `zone`은 `imlate.timezone`(Asia/Seoul)이 적용됩니다.
> 마감 시각을 바꾸면 프론트 카운트다운·안내 문구는 서버 `window` 응답(`closesAt`)을 따라 자동으로 맞춰지고,
> `REGISTRATION_CLOSED` 메시지도 서버가 실제 마감 시각으로 만들어 내려보냅니다.
> **프론트·문구·스크립트 어디에도 시각을 하드코딩하지 않는 것이 원칙입니다.**

바꾼 뒤에는 실제 반영을 눈으로 확인합니다.

```bash
curl -s "$API/api/v1/registrations/window" | python3 -m json.tool   # closesAt 확인
```

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

### 5.4 문자·메일 안내 문구 (수신 전용 안내 · 문의처)

사감님께 나가는 문자와 메일에는 **"발신번호는 수신 전용이라 답장이 불가"** 하다는 안내와
**문의처**가 항상 들어갑니다. 담당자·주소가 바뀌면 아래 두 값만 고치면 문구가 따라 바뀝니다.

| 환경변수 | 프로퍼티 | 기본값 | 의미 |
|---|---|---|---|
| `IMLATE_NOTIFICATION_CONTACT_NAME` | `imlate.notification.contact-name` | `SKALA 운영진` | 문의처 이름 |
| `IMLATE_NOTIFICATION_CONTACT_EMAIL` | `imlate.notification.contact-email` | `khdev07@naver.com` | 문의처 이메일 |

```bash
aws ssm put-parameter --name "/imlate/prod/IMLATE_NOTIFICATION_CONTACT_EMAIL" \
  --value 'khdev07@naver.com' --type SecureString --overwrite --region "$REGION"
# 재기동 (5.1 의 send-command) 후 실제 문구 확인
curl -s -X POST "$API/api/v1/admin/notifications/preview" -H "X-Admin-Key: $ADMIN_KEY" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['smsBody'])"
```

- 두 값이 비어 있어도 발송은 되지만 **문의처 줄이 빠지므로** 반드시 채워 두세요.
- 문자 본문이 길어져 요약 모드로 바뀌어도 이 안내는 유지됩니다(§2.1).
- 알리고 발신번호 자체를 수신 가능한 번호로 바꿀 계획이라면, 이 문구도 함께 손봐야 합니다.

### 5.5 발송 일시 중지

```bash
aws ssm put-parameter --name "/imlate/prod/IMLATE_NOTIFICATION_ENABLED" \
  --value 'false' --type SecureString --overwrite --region "$REGION"
# 재기동 후 스케줄러는 skipReason=DISABLED 로 아무것도 하지 않습니다.
# 그래도 관리 API 에 force=true 를 주면 강제 발송은 가능합니다.
```

### 5.6 rate limit 조정

`imlate.rate-limit.*`(`application.yml`)에서 스코프별 `capacity` / `refill-tokens` / `refill-period-seconds` 를 바꿉니다.
`enabled=false` 로 끄거나, Redis 장애 시 동작을 `fail-open`(기본 true, 통과) / `false`(429)로 선택할 수 있습니다.
AWS WAF 상한은 Terraform `waf_rate_limit_per_5min`, nginx는 `infra/nginx/imlate.conf` 의 `limit_req` 입니다.

마감 직전에 정상 사용자가 429로 막혔을 때 **무엇을 얼마나 올릴지**는
[RUNBOOK §2.2](RUNBOOK.md#22-429-인가--공용-와이파이-한도) 에 표로 정리되어 있습니다.

### 5.7 운영자 알림 / 감시 설정 (신규)

> **최종 근거는 `backend/src/main/resources/application.yml` 의 `imlate.notification.*` /
> `imlate.sms.aligo.*` 절과 `infra/terraform/variables.tf`** 입니다.
> 이 표와 다르면 그쪽이 맞습니다(운영 프로파일 기본값은 `application-prod.yml`).

#### 왜 생겼나

지금까지 **발송 실패가 조용했습니다.** `notification_dispatch` 테이블에 기록만 남고 아무 통보가 없어서,
알리고 IP 미등록·SES 미검증으로 두 번 실패했을 때 모두 관리 API 를 직접 조회하고서야 알았습니다.
또 CloudWatch 알람이 0개라, 21:50 직전에 인스턴스가 죽으면 발송이 통째로 실패해도 아무도 몰랐습니다.

#### 애플리케이션 설정 (SSM `/imlate/prod/*`)

| 프로퍼티 | 환경변수(SSM 파라미터 이름) | 기본값(prod) | 의미 |
|---|---|---|---|
| `imlate.notification.ops-alert.enabled` | `IMLATE_NOTIFICATION_OPS_ALERT_ENABLED` | `true` | 운영자 알림 사용 여부 |
| `imlate.notification.ops-alert.email` | `IMLATE_NOTIFICATION_OPS_ALERT_EMAIL` | 비우면 `contact-email` | **운영자** 알림 수신 메일 |
| `imlate.notification.ops-alert.phone` | `IMLATE_NOTIFICATION_OPS_ALERT_PHONE` | **빈 값** | 운영자 알림 수신 번호. **비우면 문자 알림을 하지 않습니다**(안전한 기본값) |
| `imlate.notification.ops-alert.notify-on-success` | `IMLATE_NOTIFICATION_OPS_ALERT_NOTIFY_ON_SUCCESS` | `false` | 전부 성공한 날에도 알릴지. `true` 로 두면 매일 와서 무시하게 됩니다 |
| `imlate.notification.heartbeat.enabled` | `IMLATE_NOTIFICATION_HEARTBEAT_ENABLED` | `true` | 발송 완료 하트비트(외부 감시용 신호) |
| `imlate.notification.heartbeat.namespace` | `IMLATE_NOTIFICATION_HEARTBEAT_NAMESPACE` | `Imlate` | CloudWatch 네임스페이스. **알람 쪽 값과 반드시 일치해야 합니다** |
| `imlate.notification.heartbeat.environment` | **`IMLATE_ENV`** | `prod` (운영 프로파일) | 지표 차원 `Environment`. 환경변수 이름이 다른 값들과 다릅니다 |
| `imlate.sms.aligo.low-balance-threshold` | **`IMLATE_ALIGO_LOW_BALANCE_THRESHOLD`** | `100` | 문자 잔여 건수 경고 임계값(건). `0` 이하면 감시하지 않음 |

> 임계값 100건의 근거: 사감 2명 × 하루 1회 = 2건/일이므로 **약 50일치 여유**입니다.
> 충전에 영업일이 걸려도 충분히 이르게 알 수 있는 값입니다.
> 다만 명단이 길면 SMS 가 아니라 **LMS 로 나가 단가가 3배**이므로, 인원이 늘면 임계값도 함께 올리세요.
> (현재 잔액은 넉넉합니다 — SMS 6,329 / LMS 2,109 수준.)

> **하트비트 지표가 실제로 CloudWatch 에 올라오는지 반드시 확인하세요.**
> 앱이 남기는 신호와 알람이 보는 지표(네임스페이스·이름·차원)가 어긋나면
> 알람은 영원히 `INSUFFICIENT_DATA` 로 남고 **인스턴스가 죽어도 아무 메일이 오지 않습니다.**
> 계약값은 `terraform output dispatch_metric_contract`, 설계는 `infra/terraform/modules/monitoring/README.md`.
> 확인: [DEPLOYMENT.md §11.3](DEPLOYMENT.md#113-알람이-만들어졌는지--지금-상태가-어떤지)

#### 반드시 지켜야 할 두 가지

1. **운영자 알림 수신처 ≠ 사감 수신처.**
   사감님께 가는 것은 "오늘 밤 복귀 명단"뿐입니다. "발송 실패", "잔액 부족" 같은 운영 신호가
   사감님께 가면 **명단 문자와 혼동해 진짜 명단을 놓칩니다.**
   그래서 `ops-alert.phone` 기본값은 빈 값이고, 사감 번호와 같은 값이 들어오면 발송하지 않습니다.
2. **알림 때문에 발송이 느려지거나 실패하면 안 됩니다.**
   운영자 알림·잔액 조회·하트비트는 전부 부작용 없는(실패해도 무시되는) 경로입니다.
   잔액 조회는 **하루 1회**만 하며(재시도에서는 재조회하지 않음), 실패하면 로그만 남깁니다.

> ⚠️ **운영자 알림 메일도 사감 메일과 같은 SES 로 나갑니다.**
> SES 샌드박스에서는 **`ops-alert.email` 주소도 검증되어 있어야** 알림이 도착합니다
> (미검증이면 "발송 실패" 알림 자체가 조용히 실패합니다).
> 현재는 사감 수신 주소와 같은 운영자 주소를 쓰므로 이미 검증된 상태입니다.
> 다른 주소로 바꾼다면 `ses_additional_verified_emails` 에 추가하고 확인 메일을 클릭하세요.
> **CloudWatch 알람 메일은 SNS 경로**라 SES 검증과 무관합니다(별도로 구독 확인 필요 — §1.2).

#### 인프라 설정 (Terraform)

| 변수 | 어디 | 의미 |
|---|---|---|
| `alert_email` | `infra/terraform/terraform.tfvars` | 알람 메일 수신 주소. **운영자 전용 — 사감 연락처를 넣지 말 것** |
| `enable_monitoring` | 〃 | 알람·SNS 생성 여부(기본 `true`). **`alert_email` 이 비면 어차피 아무것도 만들지 않습니다** |

`terraform apply` 후 **AWS 확인 메일의 링크를 눌러야** 구독이 살아납니다.
누르기 전에는 알람이 울려도 메일이 오지 않습니다. 절차와 확인 명령은
[DEPLOYMENT.md §11](DEPLOYMENT.md#11-모니터링--알람) 을 보세요.

```powershell
terraform output alert_subscription_notice   # 구독 확인 절차
terraform output -json monitoring_alarm_names
```

#### 값 반영

```powershell
aws ssm put-parameter --name /imlate/prod/IMLATE_NOTIFICATION_OPS_ALERT_EMAIL `
  --value "<운영자주소>" --type SecureString --overwrite --region $REGION
aws ssm put-parameter --name /imlate/prod/IMLATE_ALIGO_LOW_BALANCE_THRESHOLD `
  --value "300" --type SecureString --overwrite --region $REGION
# 재기동 (5.1 의 send-command)
```

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

## 8. 컴포넌트별 장애 영향 범위

> **증상에서 출발하는 대응 절차는 [RUNBOOK.md](RUNBOOK.md) 입니다.**
> 이 절은 "이 컴포넌트가 죽으면 무엇이 어떻게 되는가"라는 **설계상의 영향 범위**만 정리합니다.
> 사고 중에는 아래 표에서 해당 시나리오로 바로 이동하세요.

| 증상 | 어디로 |
|---|---|
| 21:50인데 사감님이 문자를 못 받았다 | [RUNBOOK §1](RUNBOOK.md#1-2150인데-사감님이-문자를-못-받았다--최우선) |
| 등록이 안 된다는 문의(409 / 429 / 500) | [RUNBOOK §2](RUNBOOK.md#2-등록이-안-된다는-문의) |
| 서비스 응답 없음 / 인스턴스가 죽었다 / 알람 수신 | [RUNBOOK §3](RUNBOOK.md#3-인스턴스가-죽었다-알람을-받았을-때) |
| 명단 인원이 안 맞는다 / `MISMATCH` | [RUNBOOK §4](RUNBOOK.md#4-명단이-이상하다-인원이-안-맞는다) |
| HTTPS 실패 / 인증서 만료 임박 | [RUNBOOK §5](RUNBOOK.md#5-인증서-만료-임박--https-실패) |
| 사감님이 "링크가 만료됐다"고 함 | §8.5 (아래) |

### 8.1 앱 프로세스가 죽으면

`systemd Restart=always` 가 프로세스를 다시 띄웁니다. 다만 **기동 자체가 실패하는 원인**
(SSM 파라미터 누락, 스키마 불일치, DB 접속 불가)이면 재시작 루프에 빠집니다 —
이때는 [RUNBOOK §3.2](RUNBOOK.md#32-앱-프로세스가-죽었다) 의 원인별 표를 보세요.

> **`Restart=always` 는 프로세스 재시작만 커버합니다.** 인스턴스(EC2 1대·단일 AZ)가 통째로 죽으면
> 아무것도 자동 복구되지 않습니다. 그래서 EC2 상태검사 알람과 발송 하트비트 알람을 붙였습니다(§5.7).

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

등록이 500으로 실패합니다. 다만 **WAL에는 남아 있으므로** DB 복구 후 21:50 발송 경로의 대사
(또는 수동 `dispatch?force=true`)에서 누락분이 자동 복구됩니다 — 절차는
[RUNBOOK §2.3](RUNBOOK.md#23-500-인가--db-장애-데이터는-잃지-않는다).

RDS 상태·커넥션 수·`FreeableMemory` 를 CloudWatch에서 확인하고, 필요하면 Hikari 풀 크기
(`IMLATE_DB_POOL_MAX`, 운영 기본 20)를 조정합니다.

### 8.4 nginx / 인증서

nginx 가 죽으면 정적 페이지와 `/api` 프록시가 모두 멈춥니다(앱은 살아 있어도 외부에서 못 씁니다).
인증서 발급/갱신은 `imlate-tls.timer` 가 **best-effort** 로 수행하며, 실패해도 80 포트 서비스는 계속됩니다.
→ [RUNBOOK §3.3](RUNBOOK.md#33-nginx-만-죽었다), [RUNBOOK §5](RUNBOOK.md#5-인증서-만료-임박--https-실패)

### 8.5 사감님이 "링크가 만료됐다"고 함

토큰 TTL(운영 기본 48시간)이 지난 경우입니다. 새 링크를 발급해 전달합니다.

```powershell
(Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/preview?date=$TODAY" -Headers $H).lookupUrl
```

TTL을 늘리려면 `IMLATE_LOOKUP_TOKEN_TTL_HOURS` 를 조정합니다.
`IMLATE_LOOKUP_TOKEN_SECRET` 을 바꾸면 **이미 발급된 모든 링크가 즉시 무효**가 됩니다(유출 시 대응책).

### 8.6 트래픽 급증 / 429 폭주

정상 사용자까지 막히는 경우의 **구체적인 조정 값**은
[RUNBOOK §2.2](RUNBOOK.md#22-429-인가--공용-와이파이-한도) 표를 보세요.

- 특정 IP의 공격이면 WAF에서 IP 차단 규칙을 추가하는 편이 저렴합니다(단, 현재 구성은 `enable_waf = false`).
- WAF 를 켠 상태에서 관리형 규칙 오탐이 의심되면 `waf_managed_rules_count_only = true` 로
  카운트 모드 전환 후 지표를 확인합니다.

### 8.7 사용자 화면 문구 → 원인 대조표

| 사용자 화면 문구 | 원인 | 확인 |
|---|---|---|
| "등록 마감 시간(21:45)이 지났습니다." | 마감 후(21:45 이후). 다음 날 대상 등록은 자정에 열린다 | `window` 응답의 `open`, `closesAt` |
| "요청이 너무 많습니다…" | rate limit | 같은 IP(공용 Wi-Fi/NAT)에서 다수 접속인지 확인 |
| "반은 숫자만 입력해 주세요" | `1반` 처럼 글자를 섞음 | 반은 숫자만. `1반` → `1` 로 입력 |
| "기숙사 호수는 숫자만 입력해 주세요" | `302호`·`B-101` 입력 | 호수는 숫자만. 문자가 섞인 호수 체계라면 운영진에 문의 |
| "이름에는 한글·영문만 사용할 수 있습니다" | 이름에 숫자·기호 | 한글 또는 영문만. `Alice Kim` 처럼 띄어쓰기는 가능 |
| "이미 등록되어 있습니다" | 정상(멱등) | 명단에 있으면 문제 없음 |
| 네트워크/타임아웃 | 프론트 10초 타임아웃 | 백엔드 헬스·응답시간 확인 |

---

## 9. 정기 점검 (월 1회 권장)

- [ ] SES 발송량/바운스율 확인(바운스 5% 초과 시 계정 제재 위험)
- [ ] **알리고 잔액** 및 발신번호 유효성 (임계값 감시는 §5.7, 확인 절차는 [RUNBOOK §1.5](RUNBOOK.md#15-알리고-잔액-소진))
- [ ] **알리고 허용 IP == 현재 EIP** (`terraform output -raw aligo_whitelist_ip`) — 실제로 겪은 사고
- [ ] **SES 수신 주소 검증 상태가 아직 `true`** 인가
- [ ] **CloudWatch 알람이 전부 `OK`/`INSUFFICIENT_DATA` 이고 `ALARM` 방치본이 없는가**

      ```powershell
      aws cloudwatch describe-alarms --region $REGION `
        --query "MetricAlarms[].{이름:AlarmName,상태:StateValue}" --output table
      ```

- [ ] **SNS 구독이 `PendingConfirmation` 상태로 남아 있지 않은가** ([DEPLOYMENT.md §11](DEPLOYMENT.md#11-모니터링--알람))
- [ ] **알림 메일이 실제로 도착하는지** 테스트 1회(스팸함 포함)
- [ ] **HTTPS 인증서 만료일**에 여유가 있는가 ([RUNBOOK §5.1](RUNBOOK.md#51-지금-인증서가-언제-만료되는가-내-pc-에서))
- [ ] 문자·메일 문구의 문의처(`imlate.notification.contact-name` / `contact-email`)가 아직 유효한 담당자인지 (§5.4)
- [ ] `daily_stat` 보존 정리가 도는지(로그: `보존 기간 초과 일별 통계 N건 삭제`)
- [ ] RDS 자동 백업 보존(기본 7일)·스토리지 여유, **`db_deletion_protection = true`** 유지 확인
- [ ] ElastiCache 메모리/축출(`Evictions`) 지표
- [ ] `IMLATE_LOOKUP_TOKEN_SECRET` / `IMLATE_ADMIN_API_KEY` 교체 여부 검토
- [ ] `terraform plan` 이 비어 있는지(수동 변경 드리프트 확인)
