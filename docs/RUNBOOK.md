# imlate 장애 대응 런북

**이 문서는 사고가 났을 때 여는 문서입니다.** 증상으로 찾아 들어가서, 명령을 복사해 붙여넣으세요.

- 평상시 운영(체크리스트·설정 변경·통계)은 [OPERATIONS.md](OPERATIONS.md)
- 최초 구축·배포·모니터링 설정은 [DEPLOYMENT.md](DEPLOYMENT.md)
- 엔드포인트 상세는 [API.md](API.md), 구조는 [ARCHITECTURE.md](ARCHITECTURE.md)

> ### 이 서비스에서 "실패"의 의미
> **발송 실패 = 교육생이 기숙사에 못 들어간다.** 22:30에 문이 잠기고 23:30에 열리는데,
> 사감님이 명단을 못 받으면 명단에 있는 교육생도 밖에 남습니다.
> 그래서 이 런북의 1순위는 **"어떻게든 21:50~22:30 사이에 사감님 손에 명단을 쥐여 주는 것"** 입니다.
> 원인 규명은 그다음입니다.

---

## 0. 시작하기 전에 (1분 세팅)

### 0.1 하루 타임라인

```
00:00 등록 시작 → 21:45 등록 마감 → 21:50 사감 발송
     → 22:05 / 22:20 실패분 자동 재시도 → 22:30 문 잠김 → 23:30 일괄 개방
```

**21:50에 실패해도 22:30까지 40분이 남아 있습니다.** 당황하지 말고 §1 순서대로 내려가세요.

### 0.2 셸 (Windows 기준)

| 코드블록 표기 | 어디서 실행 | 비고 |
|---|---|---|
| `powershell` | **내 PC 의 PowerShell** | 기본. 이 문서의 로컬 명령은 전부 PowerShell 문법입니다 |
| `bash` | **EC2 안** (SSM Session Manager 로 접속한 뒤) | 서버 로그·systemd·인증서 |

> PowerShell 에서 `curl` 은 `Invoke-WebRequest` 의 별칭이라 bash 의 `curl` 문법이 통하지 않습니다.
> 이 문서는 전부 `Invoke-RestMethod` 로 씁니다. bash(Git Bash)를 쓰고 싶으면
> [DEPLOYMENT.md §0.2](DEPLOYMENT.md#02-어느-셸에서-실행하나-windows) 의 변환표를 보세요.

### 0.3 공통 변수 — 사고 대응은 항상 이 블록부터

```powershell
cd C:\Users\<사용자>\Desktop\skala-imlate\infra\terraform

# Windows PowerShell 5.1 은 기본 TLS 설정이 낡아 HTTPS 호출이 실패할 수 있습니다.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$env:AWS_REGION = "ap-northeast-2"
$REGION = "ap-northeast-2"
$BASE   = "https://skala-imlate.link"

# 관리 API 키 — 이 값이 없으면 아무 조회도 못 합니다
$KEY = terraform output -raw admin_api_key
$H   = @{ "X-Admin-Key" = $KEY }

$IID   = terraform output -raw app_instance_id
$TODAY = (Get-Date).ToString("yyyy-MM-dd")
```

확인:

```powershell
$KEY.Length            # 0 이면 terraform 디렉터리가 아니거나 state 가 없는 것
$IID                   # i-0abc... 형태
Invoke-RestMethod "$BASE/healthz"    # {"status":"UP"} 이면 서버는 살아 있다
```

> **헬스체크는 `/healthz` 입니다.** `/actuator/**` 는 nginx 가 사설 대역·localhost 만 허용하므로
> 외부에서 부르면 403 입니다(의도된 설정). 상세 헬스를 보려면 서버 안에서
> `curl -s http://127.0.0.1:8080/actuator/health` 를 쓰세요.

### 0.4 서버에 붙는 두 가지 방법

**(A) 대화형 세션** — 로그를 눈으로 훑을 때. Session Manager 플러그인이 필요합니다.

```powershell
aws ssm start-session --target $IID --region $REGION
```

**(B) 명령 한 줄만 실행** — 플러그인이 없거나 결과만 필요할 때. 이 문서에서 자주 씁니다.

```powershell
function Run-OnApp([string]$cmd) {
  # 파라미터를 JSON 파일로 넘긴다(따옴표·파이프가 섞인 명령도 그대로 전달된다)
  $f = Join-Path $env:TEMP "imlate-ssm.json"
  [IO.File]::WriteAllText($f, (@{ commands = @($cmd) } | ConvertTo-Json -Compress))

  $id = aws ssm send-command --instance-ids $IID --region $REGION `
        --document-name AWS-RunShellScript --parameters "file://$f" `
        --query "Command.CommandId" --output text

  for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 2
    $st = aws ssm get-command-invocation --command-id $id --instance-id $IID --region $REGION `
          --query "Status" --output text 2>$null
    if ($st -notin @("Pending", "InProgress", "Delayed", $null, "")) { break }
  }
  aws ssm get-command-invocation --command-id $id --instance-id $IID --region $REGION `
        --query "[Status,StandardOutputContent,StandardErrorContent]" --output text
}
```

사용 예:

```powershell
Run-OnApp "systemctl is-active imlate nginx"
Run-OnApp "tail -50 /var/log/imlate/imlate.log"
```

> 이 함수를 못 쓰는 상황(다른 PC 등)이면 대화형 세션 (A) 로 붙어 같은 명령을 그냥 실행하세요.
> 이 문서의 `bash` 코드블록은 전부 **서버 안에서** 실행하는 명령입니다.

### 0.5 이미 겪은 사고 (실제 발생분)

| 언제 | 증상 | 진짜 원인 | 지금은 |
|---|---|---|---|
| 운영 초기 | 문자만 실패, 메일은 정상 | 알리고 **발신 IP 화이트리스트에 EIP 미등록** → `result_code=-101 인증오류` | §1.4 |
| 운영 초기 | 메일만 실패, 문자는 정상 | **SES 샌드박스 + 수신 주소 미검증** → `Email address is not verified` | §1.6 |
| 마감 직전 | 정상 교육생이 429로 등록 실패 | 공용 와이파이(NAT)라 서버에서 보면 IP 가 1개 → IP 당 한도에 전원이 걸림 | 버킷을 IP 축/사람 축으로 분리(수정 완료). 재발 시 §2.2 |

> **두 사고 모두 "조용히" 실패했습니다.** 관리 API 를 직접 조회하고 나서야 알았습니다.
> 그래서 CloudWatch 알람과 운영자 알림을 붙였습니다([DEPLOYMENT.md §11](DEPLOYMENT.md#11-모니터링--알람)).
>
> 다만 **알람이 실제로 우는지 확인하기 전까지는 그것을 믿지 마세요.**
> SNS 구독 확인 메일을 안 눌렀거나 하트비트 지표가 안 올라오면 알람은 침묵합니다
> ([DEPLOYMENT.md §11.2](DEPLOYMENT.md#112--sns-구독-확인-메일을-반드시-클릭해야-합니다),
> [§11.3](DEPLOYMENT.md#113-알람이-만들어졌는지--지금-상태가-어떤지)).
> 그때까지는 **21:52에 사람이 §1.1 을 직접 실행하는 것**이 확실한 감지 수단입니다.

---

## 1. 21:50인데 사감님이 문자를 못 받았다 ★ 최우선

> **먼저 시계를 보세요. 22:30까지 남은 시간이 곧 여유 시간입니다.**
> 원인을 못 찾겠으면 §1.7(최후 수단)로 바로 건너뛰어도 됩니다. 명단을 전달하는 것이 먼저입니다.

### 1.1 1단계 — 발송 이력을 본다 (항상 여기서 시작)

```powershell
Invoke-RestMethod "$BASE/api/v1/admin/notifications?date=$TODAY" -Headers $H |
  ConvertTo-Json -Depth 5
```

읽는 법:

| 응답 | 의미 | 다음 |
|---|---|---|
| `count: 0` (이력 자체가 없음) | 스케줄러가 안 돌았다 | **§1.3** |
| `status: "SUCCESS"` 인데 못 받았다 | 통신사 지연 / 메일 스팸함 / `recipient` 번호가 사감님 번호가 아님 | 응답의 `recipient` 값을 먼저 눈으로 확인 → 맞으면 **§1.8 (B) 강제 재발송**, 틀리면 [OPERATIONS.md §5.3](OPERATIONS.md#53-사감-연락처-추가--변경) 으로 번호 교체 후 재발송 |
| `status: "FAILED"` | 발송을 시도했고 실패했다 | **§1.2** 로 원인 분기 |
| `status: "SKIPPED"` | 연락처가 비어 있다 | 해당 사감의 `IMLATE_SUPERVISOR*_PHONE/EMAIL` 확인 ([OPERATIONS.md §5.3](OPERATIONS.md#53-사감-연락처-추가--변경)) |

실패 사유만 빠르게 뽑기:

```powershell
(Invoke-RestMethod "$BASE/api/v1/admin/notifications?date=$TODAY" -Headers $H).items |
  Select-Object channel, recipientName, status, attempt, errorMessage | Format-Table -Wrap
```

같은 요청을 **한 줄로**(다른 PC 에서 급하게):

```powershell
$K=(terraform output -raw admin_api_key); (Invoke-RestMethod "https://skala-imlate.link/api/v1/admin/notifications" -Headers @{"X-Admin-Key"=$K}).items | Format-Table channel,status,errorMessage
```

### 1.2 2단계 — `errorMessage` 로 원인을 가른다

| errorMessage 에 들어 있는 문구 | 원인 | 이동 |
|---|---|---|
| `result_code=-101`, `인증오류` | 알리고 발신 IP 화이트리스트 불일치 | **§1.4** |
| `잔액`, `충전`, `result_code=-102` 계열 | 알리고 잔액 소진 | **§1.5** |
| `발신번호` | 발신번호 사전등록 만료·해지 | 알리고 콘솔에서 재등록(당일 복구 불가 → §1.7) |
| `Email address is not verified` | SES 샌드박스 + 수신 주소 미검증 | **§1.6** |
| `SesV2Exception … credentials`, `AccessDenied` | EC2 IAM 권한 | 인스턴스 프로파일 확인. 당일은 문자 채널로 커버 |
| `ResourceAccessException`, `timeout`, `Connect` | 일시적 네트워크 | 즉시 **§1.8 재시도**. 두 번 실패하면 §1.4 (IP 차단이 타임아웃처럼 보이기도 함) |
| `수신 번호 형식이 올바르지 않습니다` | 번호 설정 오타 | SSM 값 수정 → 재기동 → §1.8 |
| `Aligo 설정(api-key / user-id / sender)이 비어 있어…` | SSM 파라미터 누락/공백 | SSM 확인 → 재기동 → §1.8 |

### 1.3 이력이 아예 없다 (스케줄러 미실행)

```powershell
Run-OnApp "systemctl is-active imlate; timedatectl | head -5"
Run-OnApp "grep -E '정기 사감 발송|사감 발송 완료|건너뜀' /var/log/imlate/imlate.log | tail -20"
```

| 로그 | 의미 | 조치 |
|---|---|---|
| 아무것도 없음 + `imlate` 가 `inactive` | 앱이 죽어 있었다 | **§3** 으로 이동 후, 살아나면 §1.8 강제 발송 |
| 아무것도 없음 + `imlate` 는 `active` | 시간대 오설정(UTC로 돌면 21:50 KST 를 놓친다) | `timedatectl` 이 `Asia/Seoul` 인지 확인 → §1.8 강제 발송 |
| `사감 발송이 비활성화되어…` | `imlate.notification.enabled=false` | §1.8 은 `force=true` 라 꺼져 있어도 나갑니다. 이후 설정 복구 |
| `건너뜀: … 사유=NO_REGISTRATION` | **등록자 0명 = 정상.** 보낼 명단이 없다 | 조치 불필요. 등록 페이지가 열려 있었는지만 확인(§2) |
| `건너뜀: … 사유=ALREADY_SENT` | 이미 성공 이력이 있다 | §1.1 이력을 다시 확인. 진짜 못 받았으면 §1.8 `force=true` |
| `건너뜀: … 사유=LOCK_NOT_ACQUIRED` | 락 경합(정상 동작) | 이력 확인 후 필요 시 §1.8 |

### 1.4 알리고 `-101 인증오류` — 발신 IP 화이트리스트

**가장 자주 재발할 사고입니다.** 알리고 계정에 발신 IP 화이트리스트를 걸어 두면,
**서버의 공인 IP 가 바뀌는 순간 문자만 통째로 실패**합니다(메일은 멀쩡해서 더 헷갈립니다).

지금 나가는 IP:

```powershell
terraform output -raw aligo_whitelist_ip
```

> 이 프로젝트는 ALB·NAT 를 걷어낸 구성이라 **인바운드·아웃바운드가 모두 앱 EC2 의 EIP 하나**입니다.
> 즉 위 값 = 교육생이 접속하는 IP = 알리고에 등록해야 하는 IP 입니다.

교차 확인(서버가 실제로 어떤 IP 로 나가는지):

```powershell
Run-OnApp "curl -s https://checkip.amazonaws.com"
```

두 값이 같은지, 그리고 **알리고 콘솔(<https://smartsms.aligo.in> → API 설정 → 접속 IP)** 에 그 값이 있는지 확인합니다.

| 결과 | 조치 |
|---|---|
| 콘솔의 IP 가 다르다 | 콘솔에서 현재 IP 로 교체 → 반영 즉시 **§1.8 재시도** |
| 콘솔에 IP 가 아예 없다 | 화이트리스트 기능을 껐다가 켠 이력이 있는지 확인 후 등록 → §1.8 |
| IP 는 맞는데 계속 -101 | API Key / User ID 불일치. SSM `IMLATE_ALIGO_API_KEY`, `IMLATE_ALIGO_USER_ID` 확인 |

EIP 가 바뀌는 경우는 사실상 `terraform destroy` 뿐입니다(인스턴스 `-replace` 로는 안 바뀝니다).
**IP 가 바뀌면 Route53 A 레코드는 terraform 이 자동 갱신하지만, 알리고 화이트리스트는 수동입니다.**

### 1.5 알리고 잔액 소진

`-101` 이 아니라 잔액 관련 메시지면 이쪽입니다. 문자는 건당 과금이라 **잔액이 0이 되는 순간 전부 실패**합니다.

잔액 확인(가장 확실한 것은 콘솔):

```
https://smartsms.aligo.in → 로그인 → 충전/잔여건수
```

CLI 로 보고 싶다면 알리고 잔여건수 API 를 씁니다(키가 노출되지 않도록 화면 공유 중에는 주의).

```powershell
$akey = aws ssm get-parameter --name /imlate/prod/IMLATE_ALIGO_API_KEY --with-decryption --region $REGION --query "Parameter.Value" --output text
$auid = aws ssm get-parameter --name /imlate/prod/IMLATE_ALIGO_USER_ID  --with-decryption --region $REGION --query "Parameter.Value" --output text
Invoke-RestMethod -Method Post "https://apis.aligo.in/remain/" -Body @{ key = $akey; user_id = $auid }
```

| 상황 | 조치 |
|---|---|
| 잔액이 남아 있다 | 잔액 문제가 아니다. §1.4 로 돌아가라 |
| 잔액 0 / 부족 | 콘솔에서 즉시 충전 → **§1.8 재시도**. 충전 반영은 보통 즉시 |
| 충전이 당장 불가 | **§1.7 최후 수단**으로 명단부터 전달. 메일 채널은 살아 있을 가능성이 높다 |

> 명단이 길면 SMS 가 아니라 **LMS 로 전환**되어 건당 단가가 올라갑니다(LMS ≈ SMS 3배).
> 200명 규모면 대부분 LMS 입니다. 하루 2건이라 소진 속도 자체는 느립니다.
> 잔액 임계치 감시 설정은 [OPERATIONS.md §5.7](OPERATIONS.md#57-운영자-알림--감시-설정-신규) 을 보세요.

### 1.6 SES `Email address is not verified`

SES 를 **샌드박스**로 운영 중입니다. 샌드박스에서는 **검증된 주소로만** 발송할 수 있습니다.
현재 사감 두 명의 수신 주소는 **둘 다 운영자 메일**로 되어 있습니다
(운영자가 매일 발송 결과를 메일로 확인하는 구조 — 실제 값은 `terraform.tfvars` 의 `supervisor*_email`).
이 주소는 terraform 이 SES 아이덴티티로 만들어 두지만 **소유자가 AWS 확인 메일의 링크를 눌러야** 검증됩니다.

검증 상태 확인:

```powershell
terraform output -json ses_recipient_identities        # 검증 대상 주소 목록
$ADDR = terraform output -raw ses_from_address          # 발신 주소
aws sesv2 get-email-identity --email-identity "<수신주소>" --region $REGION --query "VerifiedForSendingStatus"
aws sesv2 get-email-identity --email-identity $ADDR      --region $REGION --query "VerifiedForSendingStatus"
```

| 결과 | 조치 |
|---|---|
| `false` | 해당 메일함에서 **AWS 확인 메일 링크 클릭**(스팸함 확인). 링크가 만료됐으면 아래로 재발송 |
| 확인 메일이 없다 | `aws sesv2 create-email-identity --email-identity <주소> --region $REGION` 로 재발송 |
| `true` 인데도 실패 | 발신 주소(`ses_from_address`) 쪽 검증 상태와 IAM 권한 확인 |

검증이 끝나면 **§1.8 재시도**. 발신·수신 어느 쪽이든 검증이 안 끝났으면 그날은 문자 채널로 커버하고 §1.7 로 보완합니다.

### 1.7 최후 수단 — 조회 페이지 링크를 직접 전달

★ **원인 규명보다 이게 먼저인 상황이 있습니다.** 22:20이 넘었는데 아직 원인을 모르겠다면 지금 이걸 하세요.

```powershell
$p = Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/preview?date=$TODAY" -Headers $H
$p.targetCount          # 오늘 명단 인원
$p.lookupUrl            # 사감님께 보낼 링크 (토큰 포함)
$p.smsBody              # 문자로 나갈 본문 — 그대로 복사해 붙여넣어도 된다
```

전달 방법(빠른 순):

1. **`$p.smsBody` 를 복사해 개인 휴대폰에서 사감님께 직접 문자/카카오톡으로 전송**
2. `$p.lookupUrl` 만 보내도 됩니다(조회 페이지에 전체 명단이 있습니다)
3. 사감님께 전화로 인원수(`$p.targetCount`)만이라도 먼저 알리기

> - 링크에는 **열람 토큰**이 들어 있습니다. 외부 공개 채널(단톡방 등)에 뿌리지 마세요.
> - 토큰 TTL 은 기본 48시간입니다. "링크가 만료됐다"고 하면 위 명령으로 **새로 발급**해 다시 보내면 됩니다.
> - 사감님 연락처는 `terraform.tfvars` / SSM(`IMLATE_SUPERVISOR*_PHONE`)에 있습니다. 이 문서에는 적지 않습니다.

### 1.8 수동 재발송 (원인 해결 후)

**(A) 실패한 채널만 재발송** — 성공한 채널은 건드리지 않습니다. 기본은 이쪽입니다.

```powershell
Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/retry?date=$TODAY" -Headers $H
```

**(B) 전체 강제 재발송** — 이미 성공 이력이 있어도 다시 보냅니다. "SUCCESS 인데 못 받았다"일 때.

```powershell
Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/dispatch?date=$TODAY&force=true" -Headers $H
```

응답 읽는 법:

| 필드 | 확인 |
|---|---|
| `skipped: true`, `skipReason` | 발송하지 않았다. 사유는 §1.3 표 참고 |
| `smsSuccess` / `smsFailed` | 문자 결과 |
| `emailSuccess` / `emailFailed` | 메일 결과 |
| `targetCount` | 이 숫자가 예상 인원과 다르면 **§4** 로 |

보낸 뒤 반드시 이력으로 재확인:

```powershell
(Invoke-RestMethod "$BASE/api/v1/admin/notifications?date=$TODAY" -Headers $H).items |
  Format-Table channel, recipientName, status, attempt, errorMessage
```

> `force=true` 는 **발송 기능이 꺼져 있어도(`enabled=false`) 강제로 진행**합니다.
> 반대로 `retry` 는 `enabled=false` 면 `skipReason=DISABLED` 로 아무것도 하지 않습니다.

### 1.9 사후 정리 (다음 날 낮에)

- [ ] 근본 원인을 §0.5 표에 한 줄 추가
- [ ] 같은 원인이 재발하지 않도록 설정/알람을 고쳤는가
- [ ] 사감님께 상황 공유 (다음 날 명단은 정상이라는 안내)

---

## 2. "등록이 안 된다"는 문의

먼저 **화면에 뜬 문구나 상태 코드**를 물어보세요. 그게 곧 분기 조건입니다.

### 2.1 마감 이후인가 (409) — 대개 정상

> "등록 마감 시간(21:45)이 지났습니다."

```powershell
Invoke-RestMethod "$BASE/api/v1/registrations/window"
```

| 응답 | 판정 |
|---|---|
| `open: false` + 현재 시각이 21:45 이후 | **정상 동작.** 다음 날 대상 등록은 자정(00:00)에 열립니다 |
| `open: false` 인데 아직 21:45 전 | 서버 시간대 오설정 의심 → `Run-OnApp "timedatectl"` 로 `Asia/Seoul` 확인 |
| `closesAt` 이 21:45 가 아님 | 누군가 `IMLATE_REGISTRATION_CLOSE_TIME` 을 바꿨다 → [OPERATIONS.md §5.2](OPERATIONS.md#52-등록-마감시간-변경-예-2145--2130) |

> 마감 이후 등록을 **예외적으로 받아야 한다면** 마감 시각을 잠깐 늦추는 것이 아니라,
> 사감님께 §1.7 방식으로 개별 연락하는 편이 안전합니다. 마감을 미루면 21:50 발송과 겹칩니다.

### 2.2 429 인가 — 공용 와이파이 한도

> "요청이 너무 많습니다…"

기숙사 공용 와이파이(NAT)를 쓰면 **200명이 서버에서는 IP 1개**로 보입니다.
그래서 "IP 당 N회" 형태의 제한은 전부 **기숙사 전체가 하나의 버킷을 나눠 쓰는 구조**가 됩니다.

**429 를 돌려준 곳이 두 군데입니다. 먼저 어느 쪽인지부터 가르세요.**

| 층 | 제한 | 응답 특징 |
|---|---|---|
| **nginx** (앞단) | `limit_req 10r/s burst=20`, `limit_conn 40` — 키가 클라이언트 IP | nginx 기본 오류 **HTML** 페이지. `X-RateLimit-*` 헤더 **없음** |
| **애플리케이션** (뒤) | `imlate.rate-limit.*` (IP 축 + 사람 축) | **JSON** 본문에 `"code":"RATE_LIMITED"`, `X-RateLimit-*` 헤더 있음 |

```powershell
# 응답 본문과 헤더를 같이 본다 — 어느 층이 막았는지 여기서 갈린다
try { Invoke-WebRequest "$BASE/api/v1/registrations/summary" -UseBasicParsing }
catch { $_.Exception.Response.StatusCode; $_.ErrorDetails.Message }
```

```powershell
Run-OnApp "grep 'rate limit 차단 발생' /var/log/imlate/imlate.log | tail -20"   # 앱이 막았다
Run-OnApp "grep -c 'limiting requests' /var/log/nginx/error.log"                # nginx 가 막았다
Run-OnApp "grep -c 'limiting connections' /var/log/nginx/error.log"
```

#### (a) 애플리케이션이 막은 경우 — SSM 값만 올리면 됩니다

| 파라미터(SSM) | 현재 | 급할 때 | 의미 |
|---|---|---|---|
| `IMLATE_RATE_LIMIT_REGISTER_CAPACITY` / `_REFILL` | 300 | **600** | IP 당 분당 등록 요청. 200명 × 재시도 여유 |
| `IMLATE_RATE_LIMIT_GLOBAL_CAPACITY` / `_REFILL` | 1200 | **2400** | IP 당 분당 전체 `/api` 요청 |
| `IMLATE_RATE_LIMIT_REGISTER_PERSON_CAPACITY` | 10 | **건드리지 말 것** | 같은 사람의 도배 방지. 올리면 방어가 사라진다 |
| `IMLATE_RATE_LIMIT_LOCAL_FALLBACK_PER_MINUTE` | 1200 | global 과 같은 값으로 | Redis 장애 시 폴백 상한. 작으면 장애 중 전원이 막힌다 |

```powershell
aws ssm put-parameter --name /imlate/prod/IMLATE_RATE_LIMIT_REGISTER_CAPACITY --value "600" --type SecureString --overwrite --region $REGION
aws ssm put-parameter --name /imlate/prod/IMLATE_RATE_LIMIT_REGISTER_REFILL   --value "600" --type SecureString --overwrite --region $REGION
Run-OnApp "systemctl restart imlate-env.service && systemctl restart imlate.service"
```

> **원칙:** `register-person(10) < register(300) < global(1200)`. 이 순서가 깨지면
> 개인 도배를 막지 못하거나(person 이 크면), 정상 사용자가 막힙니다(register 가 작으면).

#### (b) nginx 가 막은 경우 — 설정 파일을 고쳐야 합니다

nginx 의 `limit_req_zone` 키도 **클라이언트 IP**(`$binary_remote_addr`)입니다.
공용 와이파이 뒤에서는 **기숙사 전체가 초당 10요청(버스트 20)을 나눠 쓰는 셈**이므로,
21:44 처럼 동시 접속이 몰리는 순간 앱에 닿기도 전에 429 가 나갈 수 있습니다.

임시 완화(서버에서 직접, 다음 배포 때 되돌아갑니다):

```bash
sudo sed -i 's/rate=10r\/s/rate=50r\/s/' /etc/nginx/conf.d/imlate.conf
sudo nginx -t && sudo systemctl reload nginx
```

> ⚠️ **`deploy.sh` / GitHub Actions 배포가 nginx 설정을 덮어씁니다.**
> 임시 완화는 다음 배포 때 원복되므로, **항구적 조치는 저장소의
> `infra/nginx/imlate.conf`(`limit_req_zone … rate=`, `limit_conn`)를 고쳐 배포**하는 것입니다.
> 값을 올릴 때는 "이 회선 하나가 곧 200명"이라는 전제를 기준으로 잡으세요.

### 2.3 500 인가 — DB 장애 (데이터는 잃지 않는다)

> 화면에 "잠시 후 다시 시도해 주세요" 류의 오류

```powershell
Invoke-RestMethod "$BASE/healthz"     # DB 가 죽으면 여기서도 실패한다
Run-OnApp "tail -50 /var/log/imlate/imlate-error.log"
```

**중요: 등록은 DB 기록 전에 Redis WAL 에 먼저 남습니다.**
DB 가 죽어 500 이 나도 WAL 에 남아 있고, **21:50 발송 경로의 WAL↔DB 대사가 누락분을 DB 로 복구**합니다.
즉 DB 를 제때 살리면 명단은 그대로 복원됩니다.

1. RDS 상태 확인 (콘솔 또는 CLI)

   ```powershell
   aws rds describe-db-instances --region $REGION --query "DBInstances[].{ID:DBInstanceIdentifier,상태:DBInstanceStatus}" --output table
   ```

2. 앱만 문제면 재기동

   ```powershell
   Run-OnApp "systemctl restart imlate.service"
   ```

3. **DB 복구 후 반드시 대사 + 발송을 다시 실행**(21:50 이 이미 지났다면)

   ```powershell
   Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/dispatch?date=$TODAY&force=true" -Headers $H
   ```

4. 복구 결과 확인

   ```powershell
   Invoke-RestMethod "$BASE/api/v1/admin/reconciliation?date=$TODAY" -Headers $H
   # status 가 RECOVERED 이고 recoveredCount 가 0보다 크면 누락분이 복구된 것
   ```

> WAL TTL 은 기본 7일입니다. **당일 안에만 복구하면 데이터는 안전합니다.**

### 2.4 그 밖의 문구

| 화면 문구 | 원인 | 조치 |
|---|---|---|
| "…한글·영문·숫자와 공백, 괄호, 하이픈만…" | 특수문자·이모지 입력 | 정상 검증. 이름/호수를 다시 입력하도록 안내 |
| "이미 등록되어 있습니다" | 멱등 처리(정상) | 조회 페이지에 이름이 있으면 문제 없음 |
| 화면이 아예 안 뜬다 | 프론트/nginx/인증서 | **§3**, **§5** |
| 네트워크 오류·타임아웃 | 프론트 10초 타임아웃 | `Invoke-RestMethod "$BASE/healthz"` 로 서버 상태 확인 |

---

## 2-1. "취소가 안 된다"는 문의

취소 화면(`/cancel`)은 **반·이름·호수 + 등록할 때 정한 비밀번호 4자리**가 모두 맞아야 동작합니다.

> **먼저 알아 둘 것** — 취소가 안 되는 것은 *안전한 실패*입니다. 그 사람은 **명단에 그대로 남아 있어**
> 23:30 일괄 개방 때 정상적으로 들어옵니다. 굳이 안 들어가면 그만입니다.
> 반대로 잘못 취소되면 명단에서 사라져 22:30 에 문 밖에 갇힙니다. **급하게 손대지 마세요.**

| 화면 문구 | 원인 | 조치 |
|---|---|---|
| "등록 정보 또는 비밀번호가 일치하지 않습니다" | 넷 중 하나 이상이 다름 | 아래 2-1.1 |
| "취소 시도가 10회를 넘어 오늘은 더 시도할 수 없습니다" | 시도 상한 도달 | 아래 2-1.2 |
| "등록 마감 시간(21:45)이 지났습니다" | 마감 후 취소 시도 | 정상. 명단은 이미 발송됨 — 사감님께 구두로 전달 |

### 2-1.1 비밀번호가 안 맞는다

시스템은 "그런 등록이 없음"과 "비밀번호가 틀림"을 **일부러 구분해 주지 않습니다**(등록 여부가
밖으로 새면 안 되기 때문). 그래서 먼저 등록이 실제로 있는지부터 확인합니다.

```powershell
# 조회 페이지(사감 링크)에서 그 이름이 보이는지 확인
```

- **명단에 없다** → 애초에 등록이 안 된 것입니다. 취소할 것도 없으니 그대로 두면 됩니다.
- **명단에 있다** → 반·이름·호수를 **등록할 때와 똑같이** 입력했는지 확인합니다.
  띄어쓰기는 자동 보정되지만 `1반`/`1 반`은 같아도 `1반`/`일반`은 다릅니다.
- 비밀번호를 정말 잊었다면 **취소할 방법이 없습니다.** 이건 설계상 의도된 것입니다
  (되찾는 경로를 만들면 그 경로가 곧 남의 등록을 지우는 구멍이 됩니다).
  → 그냥 두시면 됩니다. 명단에 남아 있어도 안 들어가면 그만이고, 사감님께 한마디 전하면 끝납니다.

### 2-1.2 시도 상한(10회)에 걸렸다

4자리는 경우의 수가 1만 개뿐이라 마음껏 두드리게 두면 남의 등록이 뚫립니다. 그래서 **사람·날짜당
10회**로 총량을 막습니다. 자정이 지나면 자동으로 풀립니다.

정말 본인이고 급하다면 그 사람의 카운터만 지울 수 있습니다(**대상이 본인임을 확인한 뒤에만**):

```bash
redis-cli --scan --pattern "imlate:cancel:fail:$(date +%F):*"
```

키에는 이름이 해시로만 들어 있어 **어느 키가 누구인지 알 수 없습니다.** 특정할 수 없으므로,
정말 필요하면 그날 것을 전부 지우는 수밖에 없습니다(= 모두의 시도 횟수가 초기화됩니다).
그럴 만한 상황은 거의 없습니다 — 위에 적었듯 취소가 안 되는 것은 안전한 실패입니다.

### 2-1.3 "취소했는데 명단에 아직 있다"

취소는 행을 지우지 않고 `cancelled_at` 만 채웁니다. 명단 조회는 그 값이 비어 있는 행만 보여 주므로
**정상 동작이라면 즉시 사라집니다.** 그래도 보인다면 취소가 실제로는 실패한 것입니다.

```sql
SELECT student_name, room_number, cancelled_at
FROM return_registration
WHERE registration_date = CURDATE() AND student_name = '이름';
```

- `cancelled_at` 이 차 있는데 명단에 보인다 → 조회 화면 캐시입니다. 새로고침하세요.
- `cancelled_at` 이 비어 있다 → 취소가 안 된 것입니다. 2-1.1 로 돌아갑니다.

---

## 3. 인스턴스가 죽었다 (알람을 받았을 때)

**앱·nginx 가 EC2 1대(단일 AZ)에 다 올라가 있습니다.** 이 인스턴스가 죽으면 등록도 발송도 전부 멈춥니다.
`systemd Restart=always` 는 **프로세스** 재시작만 커버합니다 — 인스턴스 자체가 죽으면 아무것도 못 합니다.

알람 메일을 받았다면 먼저 **어떤 알람인지** 확인합니다(이름이 곧 원인입니다).

```powershell
aws cloudwatch describe-alarms --alarm-name-prefix "imlate-prod-" --region $REGION `
  --query "MetricAlarms[?StateValue=='ALARM'].{이름:AlarmName,사유:StateReason}" --output table
```

| 알람 이름(접미어) | 뜻 | 이동 |
|---|---|---|
| `-dispatch-heartbeat-missing` | **21:50 발송이 아예 일어나지 않았다** | 아래 §3.1 → 살아나면 **§1.8 강제 발송** |
| `-dispatch-failures` | 발송은 했는데 실패 건수가 있다 | **§1.1** |
| `-ec2-status-check-failed` | 인스턴스/하드웨어 이상 | §3.1 |
| `-ec2-disk-used-high` | 디스크가 참(로그 누적) | §3.6 |
| `-rds-*` | DB 스토리지·CPU | §2.3 |
| `-redis-memory-high-*` | ElastiCache 메모리 | [OPERATIONS.md §8.2](OPERATIONS.md#82-rediselasticache-장애) |

> **21:45~22:30 사이에 온 알람은 "오늘 명단이 안 나갔을 수 있다"는 뜻입니다.**
> 서버 복구와 병행해 **§1.1 부터 확인**하세요.

### 3.1 어디까지 죽었는지 3줄로 판정

```powershell
aws ec2 describe-instance-status --instance-ids $IID --region $REGION --include-all-instances `
  --query "InstanceStatuses[].{상태:InstanceState.Name,시스템:SystemStatus.Status,인스턴스:InstanceStatus.Status}" --output table
Invoke-RestMethod "$BASE/healthz"
Run-OnApp "systemctl is-active imlate nginx"
```

| 판정 | 증상 | 이동 |
|---|---|---|
| 앱 프로세스만 죽음 | `running` + `/healthz` 실패 + `imlate` 가 `inactive`/`activating` | §3.2 |
| nginx 만 죽음 | 앱은 `active` 인데 외부에서 접속 불가 | §3.3 |
| 인스턴스 자체가 죽음 | `stopped`/`terminated`, 또는 SSM 명령이 아예 안 나감 | §3.4 |
| 상태검사 실패 | `SystemStatus: impaired` | AWS 하드웨어 문제 → §3.4 (재시작) |

### 3.2 앱 프로세스가 죽었다

```powershell
Run-OnApp "journalctl -u imlate.service -n 80 --no-pager"
Run-OnApp "tail -80 /var/log/imlate/imlate-error.log"
Run-OnApp "systemctl restart imlate-env.service; systemctl restart imlate.service; sleep 15; systemctl is-active imlate"
Invoke-RestMethod "$BASE/healthz"
```

자주 나오는 기동 실패 원인:

| 로그 | 원인 | 조치 |
|---|---|---|
| `Failed to bind properties` / `Could not resolve placeholder` | SSM 파라미터 누락 | `aws ssm get-parameters-by-path --path /imlate/prod --recursive` 로 존재 확인 후 채우고 재기동 |
| `Schema-validation` / Flyway 오류 | 스키마 불일치 | 직전 jar 로 롤백([DEPLOYMENT.md §8.1](DEPLOYMENT.md#81-애플리케이션-롤백-가장-빠름--직전-jar-복구)) |
| `Cannot create PoolableConnectionFactory` | RDS 접속 불가 | §2.3 |
| `OutOfMemoryError` | 힙 부족 | 재기동으로 급한 불을 끄고, `jvm_opts` 조정 검토 |

**살아나면 발송 시각이 지났는지 확인하고, 지났으면 §1.8 강제 발송을 반드시 실행하세요.**

### 3.3 nginx 만 죽었다

```powershell
Run-OnApp "nginx -t"
Run-OnApp "systemctl restart nginx; systemctl is-active nginx"
Run-OnApp "tail -30 /var/log/nginx/error.log"
```

`nginx -t` 가 실패하면 설정 파일 문제입니다. 배포 스크립트는 `nginx -t` 통과 시에만 reload 하므로
보통은 인증서 파일이 사라진 경우입니다 → **§5**.

### 3.4 인스턴스 자체가 죽었다

```powershell
aws ec2 start-instances --instance-ids $IID --region $REGION
aws ec2 describe-instances --instance-ids $IID --region $REGION --query "Reservations[].Instances[].State.Name" --output text
```

- **EIP 는 인스턴스와 분리되어 있어 재시작해도 주소가 유지됩니다.** 알리고 화이트리스트를 다시 만질 필요는 없습니다.
- 부팅 후 `imlate-env.service` → `imlate.service` 순으로 자동 기동됩니다. 2~3분 기다렸다가 `/healthz` 확인.
- 그래도 안 되면 콘솔에서 **인스턴스 스크린샷 / 시스템 로그**를 확인하고,
  최악의 경우 `terraform apply -replace=module.ec2.aws_instance.app` 로 교체한 뒤 재배포합니다.
  (교체 후에는 **반드시 §1.8 로 그날 발송을 수동 실행**하세요.)

### 3.5 21:45~22:30 사이에 죽었다면

우선순위가 다릅니다. **서버 복구보다 명단 전달이 먼저입니다.**

1. 살아날 기미가 없으면 **§1.7 최후 수단**을 먼저 시도 — 단, 앱이 죽었으면 preview API 도 안 됩니다.
2. 이때는 **RDS 를 직접 조회**해 명단을 뽑습니다(EC2 가 죽었으면 다른 경로가 필요하므로,
   RDS 가 프라이빗이면 사실상 인스턴스 복구가 유일한 길입니다 — §3.4 를 최우선으로).

   ```sql
   SELECT class_name, student_name, room_number
   FROM return_registration
   WHERE registration_date = CURDATE()
   ORDER BY class_name, student_name;
   ```

3. 22:20이 지나도 복구가 안 되면 사감님께 **전화로 상황을 알리고**, 문을 잠그기 전 판단을 요청하세요.

### 3.6 디스크가 찼다 (`-ec2-disk-used-high`)

**디스크가 100% 가 되면 앱이 로그를 못 쓰고, 재기동도 실패합니다.** 여유가 있을 때 미리 치우세요.

```powershell
Run-OnApp "df -h /; du -sh /var/log/* 2>/dev/null | sort -h | tail -10"
```

| 원인 | 조치 |
|---|---|
| `/var/log/imlate/*` 누적 | logrotate 는 일 단위·14세대입니다. 급하면 오래된 회전본 삭제: `sudo find /var/log/imlate -name '*.gz' -mtime +7 -delete` |
| `/var/log/nginx/*` 누적 | `sudo logrotate -f /etc/logrotate.d/nginx` |
| `/opt/imlate/*.jar.bak` 등 배포 잔여물 | 직전 버전(`imlate.jar.bak`)은 롤백용이므로 **지우지 마세요**. 그 외 임시 파일만 정리 |
| 근본적으로 부족 | `root_volume_size` 를 늘려 `terraform apply` (EBS 확장 후 파일시스템 확장 필요) |

---

## 4. 명단이 이상하다 (인원이 안 맞는다)

"등록했는데 명단에 없다", "인원수가 다르다" — 대사(WAL↔DB 검증) 결과부터 봅니다.

### 4.1 대사 결과 확인

```powershell
Invoke-RestMethod "$BASE/api/v1/admin/reconciliation?date=$TODAY" -Headers $H | ConvertTo-Json -Depth 5
```

| `status` | 의미 | 조치 |
|---|---|---|
| `CONSISTENT` | WAL 과 DB 가 완전히 일치 | **정상.** 인원이 다르다면 애초에 등록이 안 된 것(§2) |
| `MISMATCH` | 불일치가 있다 | §4.2 |
| `WAL_UNAVAILABLE` | Redis 접속 불가로 대사 자체를 못 했다 | §4.3. **명단 자체는 DB 기준으로 정상** |
| `RECOVERED` | 누락분을 DB 로 복구해 일치시켰다 | **정상.** `recoveredCount` 만큼 살려낸 것 |

핵심 필드: `dbCount`(DB 인원), `walCount`(WAL 인원), `recoveredCount`(이번에 복구한 건수),
`walOnly`(WAL 에만 있음), `dbOnly`(DB 에만 있음). 표시 형식은 `"1반/홍길동/302"`.

> **이 GET 은 복구하지 않습니다(조회 전용).** 그래서 `RECOVERED` 는 이 응답에 **나오지 않고**,
> 발송(`dispatch`) 응답·로그에서만 볼 수 있습니다.
> 반대로 **복구하면 사라졌을 `walOnly` 가 남아 `MISMATCH` 로 보일 수 있습니다** —
> 발송 전(21:50 이전)에 조회하면 정상적으로도 이렇게 나옵니다. §4.2 로 판정하세요.

### 4.2 `MISMATCH` 읽는 법

```powershell
$r = Invoke-RestMethod "$BASE/api/v1/admin/reconciliation?date=$TODAY" -Headers $H
"DB={0} WAL={1} 복구={2}" -f $r.dbCount, $r.walCount, $r.recoveredCount
"WAL에만: " + ($r.walOnly -join ", ")
"DB에만 : " + ($r.dbOnly  -join ", ")
```

| 어디에 남았나 | 원인 | 조치 |
|---|---|---|
| **`dbOnly` 에만 값이 있다** | 등록 당시 Redis 장애로 WAL 미기록, 또는 WAL TTL(7일) 만료 | **데이터 손실 아님.** DB 가 정답이므로 그대로 사용. 과거 날짜 조회라면 정상 |
| **`walOnly` 에 값이 있다** — 아직 발송 전(21:50 이전) | 복구를 아직 안 돌렸을 뿐 | **정상 범위.** 발송 때 복구됩니다. 급하면 아래 복구 재실행 |
| **`walOnly` 에 값이 있다** — 발송 이후에도 | 복구 INSERT 가 실패했다 = DB 장애가 계속되고 있다 | **진짜 문제.** §2.3 으로 DB 를 살린 뒤 아래 복구 재실행 |
| 양쪽에 다 있다 | 이름/호수 표기가 미묘하게 다른 중복 등록일 수 있다 | 두 목록을 눈으로 대조 |

> **`walOnly` 에 이름이 있다면 그 교육생은 아직 명단(DB)에 없습니다.**
> 복구가 끝나기 전에 발송이 나가면 그 사람이 명단에서 빠집니다 — 아래 복구 재실행 후
> 이력을 다시 확인하세요.

복구 재실행(대사 + 발송을 한 번에):

```powershell
Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/dispatch?date=$TODAY&force=true" -Headers $H
```

> `GET /api/v1/admin/reconciliation` 은 **조회만 하고 복구하지 않습니다**(부작용 없는 GET).
> 복구는 발송 경로(`dispatch`)에서만 일어납니다. "봤는데 안 고쳐졌다"면 이것 때문입니다.

### 4.3 원본을 직접 확인 (서버 안에서)

```bash
# Redis WAL
redis-cli -h "$IMLATE_REDIS_HOST" --tls -a "$IMLATE_REDIS_PASSWORD" HLEN   imlate:wal:2026-08-05
redis-cli -h "$IMLATE_REDIS_HOST" --tls -a "$IMLATE_REDIS_PASSWORD" HGETALL imlate:wal:2026-08-05
redis-cli -h "$IMLATE_REDIS_HOST" --tls -a "$IMLATE_REDIS_PASSWORD" TTL    imlate:wal:2026-08-05
```

```sql
-- MySQL 명단
SELECT id, class_name, student_name, room_number, wal_id, registered_at
FROM return_registration
WHERE registration_date = '2026-08-05'
ORDER BY class_name, student_name;
```

> 환경변수는 `/etc/imlate/imlate.env` 에 있습니다(`source /etc/imlate/imlate.env` 후 사용).
> **대사 결과는 사감님께 나가는 문구·조회 페이지에 노출하지 않습니다.** 운영자만 이 경로로 봅니다.

### 4.4 "등록했는데 명단에 없다"

```powershell
# 조회 페이지 링크(토큰 포함)를 뽑아 직접 확인
(Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/preview?date=$TODAY" -Headers $H).lookupUrl
```

1. 조회 페이지에 있는가 → 있으면 **사감님이 받은 명단에도 있습니다**(같은 데이터)
2. 없는데 본인은 "등록했다"고 한다 → 등록 시각을 물어보세요. **21:45 이후면 등록이 안 된 것**입니다
3. 21:45 이전이라는데 없다 → §4.1 대사 결과 확인 → `walOnly` 에 이름이 있으면 §4.2 복구 재실행

---

## 5. 인증서 만료 임박 / HTTPS 실패

`https://skala-imlate.link` 의 인증서는 **EC2 안의 `imlate-tls.timer`** 가 Let's Encrypt 에서
발급·갱신합니다(부팅 3분 뒤 시작, 이후 1시간 간격, 만료 30일 전부터 실제 갱신).

### 5.1 지금 인증서가 언제 만료되는가 (내 PC 에서)

```powershell
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$req = [Net.HttpWebRequest]::Create("https://skala-imlate.link/healthz")
$req.GetResponse().Close()
$req.ServicePoint.Certificate.GetExpirationDateString()   # 인증서 만료일
```

서버 안에서:

```bash
sudo openssl x509 -enddate -noout -in /etc/letsencrypt/live/skala-imlate.link/fullchain.pem
sudo /opt/certbot/bin/certbot certificates
```

### 5.2 타이머가 도는지 확인

```powershell
Run-OnApp "systemctl list-timers imlate-tls.timer --all --no-pager"
Run-OnApp "journalctl -u imlate-tls.service -n 60 --no-pager"
```

| 로그 | 의미 | 조치 |
|---|---|---|
| `TLS 비활성 (enable_tls=false …)` | 설정상 TLS 를 끈 상태 | `terraform.tfvars` 의 `enable_tls` / `domain_name` 확인 |
| `경고: 인증서 발급 실패 — 다음 타이머(1시간 뒤)에…` | 발급 실패 | §5.3 |
| `경고: 갱신 실패 — 기존 인증서를 그대로 사용합니다` | 갱신 실패(아직 유효기간은 남음) | §5.3. **만료 전까지 여유가 있으니 침착하게** |
| `인증서 발급 완료` | 정상 | 조치 불필요 |
| 타이머가 목록에 없다 | 유닛 미활성 | `Run-OnApp "systemctl enable --now imlate-tls.timer"` |

> **이 스크립트는 무슨 일이 있어도 `exit 0` 입니다.** 발급이 실패해도 서비스는 80 포트로 계속 동작합니다.
> 즉 "HTTPS 는 안 되는데 사이트는 뜬다"가 정상적인 실패 모드입니다.

### 5.3 발급/갱신이 실패할 때 확인 순서

1. **DNS A 레코드가 이 서버의 EIP 를 가리키는가**

   ```powershell
   Resolve-DnsName skala-imlate.link -Type A | Select-Object Name, IPAddress
   terraform output -raw app_public_ip
   ```

   두 값이 다르면 A 레코드를 고칩니다(`create_dns_record = true` 면 `terraform apply` 로 정렬됩니다).

2. **80 포트가 외부에 열려 있는가** — HTTP-01 챌린지는 80 으로 들어옵니다

   ```powershell
   Invoke-WebRequest "http://skala-imlate.link/.well-known/acme-challenge/test" -UseBasicParsing -MaximumRedirection 0
   # 404 가 나오면 정상(경로까지는 도달). 타임아웃이면 보안 그룹 80 인바운드 확인
   ```

3. **수동으로 즉시 재시도**

   ```powershell
   Run-OnApp "systemctl start imlate-tls.service; journalctl -u imlate-tls.service -n 40 --no-pager"
   ```

4. Let's Encrypt 실패 제한(시간당 5회)에 걸렸다면 **1시간 기다리는 것이 정답**입니다.
   반복 시도는 제한을 더 길게 만듭니다.

### 5.4 만료 알림 메일

`tls_contact_email`(현재 운영자 주소)로 Let's Encrypt 가 만료 20일/7일/1일 전에 메일을 보냅니다.
**이 메일을 받았다는 것은 자동 갱신이 실패하고 있다는 뜻**입니다 — §5.2 부터 확인하세요.

---

## 6. 부록 — 자주 쓰는 명령 모음

### 6.1 상태 한 번에 훑기 (21:52 점검용)

```powershell
$TODAY = (Get-Date).ToString("yyyy-MM-dd")
Invoke-RestMethod "$BASE/healthz"
Invoke-RestMethod "$BASE/api/v1/registrations/summary"
(Invoke-RestMethod "$BASE/api/v1/admin/notifications?date=$TODAY" -Headers $H).items |
  Format-Table channel, recipientName, status, attempt, errorMessage
Invoke-RestMethod "$BASE/api/v1/admin/reconciliation?date=$TODAY" -Headers $H |
  Select-Object status, dbCount, walCount, recoveredCount
```

### 6.2 로그 grep

```powershell
Run-OnApp "grep '사감 발송' /var/log/imlate/imlate.log | tail -20"
Run-OnApp "grep -E 'Reconciliation|WAL' /var/log/imlate/imlate.log | tail -20"
Run-OnApp "grep 'rate limit 차단 발생' /var/log/imlate/imlate.log | tail -20"
Run-OnApp "tail -100 /var/log/imlate/imlate-error.log"
```

| 위치 | 내용 |
|---|---|
| `/var/log/imlate/imlate.log` | 애플리케이션 표준 출력(logrotate 일 단위·14세대) |
| `/var/log/imlate/imlate-error.log` | 표준 에러 |
| `journalctl -u imlate.service` | 기동/종료/재시작 |
| `journalctl -u imlate-env.service` | SSM 환경변수 로딩 결과 |
| `journalctl -u imlate-tls.service` | 인증서 발급/갱신 |
| `/var/log/nginx/error.log` | nginx (429·인증서) |
| `/var/log/imlate-bootstrap.log` | EC2 최초 부트스트랩 |

> 전화번호·이메일은 로그에서 마스킹됩니다(`010****5678`). 스택 트레이스는 응답 본문에 나가지 않습니다.

### 6.3 설정 값 바꾸고 반영하기

```powershell
aws ssm put-parameter --name /imlate/prod/<이름> --value "<값>" --type SecureString --overwrite --region $REGION
Run-OnApp "systemctl restart imlate-env.service && systemctl restart imlate.service"
Invoke-RestMethod "$BASE/healthz"
```

파라미터 이름의 **마지막 세그먼트가 그대로 환경변수 이름**입니다.
현재 값 목록:

```powershell
aws ssm get-parameters-by-path --path /imlate/prod --recursive --region $REGION --query "Parameters[].Name" --output table
```

### 6.4 판단이 서지 않을 때의 우선순위

1. **명단 전달** (§1.7) — 22:30 전에 사감님 손에 명단이 있는가
2. **서비스 복구** (§3) — 다음 날 등록이 가능한가
3. **원인 규명** — 위 둘이 끝난 뒤에 해도 늦지 않다
