# imlate 배포 가이드 (AWS)

Terraform으로 인프라를 만들고, `infra/scripts/deploy.sh` 로 애플리케이션을 올리는 전체 절차입니다.
모듈별 상세는 [infra/terraform/README.md](../infra/terraform/README.md), 운영은 [OPERATIONS.md](OPERATIONS.md)를 보세요.

```
terraform.tfvars ──apply──> AWS 리소스 + SSM SecureString
                                   │
deploy.sh ──build──> imlate.jar + frontend/dist
          ──전송──> EC2 (/opt/imlate, /var/www/imlate)
          ──재시작─> imlate-env.service → imlate.service
```

---

## 0. 값이 흘러가는 길 / 실행 환경

### 0.1 당신이 손으로 값을 적는 파일은 하나뿐입니다

```
① 외부에서 받아오는 값 (알리고 키, 사감 연락처, SES 발신주소 …)
        ↓  손으로 적는다  ← 사람이 하는 일은 여기가 전부
   infra/terraform/terraform.tfvars      (.gitignore 대상)
        ↓  terraform apply
② SSM Parameter Store  /imlate/{env}/IMLATE_*   (SecureString)
        ↓  EC2 기동/재시작 시 자동 로드 (imlate-env.service)
③ /etc/imlate/imlate.env                        (root:imlate 0640)
        ↓  systemd EnvironmentFile
④ Spring Boot — application-prod.yml 의 ${IMLATE_*} 가 채워짐
```

②③④는 전부 자동입니다. **서버에 접속해 비밀번호를 타이핑할 일이 없습니다.**

값은 세 종류입니다.

| 종류 | 예 | 누가 채우나 |
|---|---|---|
| 외부에서 받아오는 값 | 알리고 API 키, 사감 연락처, SES 발신 주소 | **사람** — `terraform.tfvars` |
| 자동 생성 비밀값 | `db_password`, `redis_auth_token`, `lookup_token_secret`, `admin_api_key` | **terraform** — 비워 두면 `random_password` |
| AWS가 알려주는 값 | RDS 엔드포인트, Redis 엔드포인트, ALB DNS | **terraform** — 생성 후 자동 주입 |

### 0.2 어느 셸에서 실행하나 (Windows)

이 문서의 명령은 기본적으로 **bash 문법**입니다. PowerShell에 그대로 붙여넣으면 깨집니다.

| 명령 | 실행할 곳 | 비고 |
|---|---|---|
| `terraform …` | PowerShell ✅ / Git Bash ✅ | 어디서든 |
| `aws …` | PowerShell ✅ / Git Bash ✅ | 어디서든 |
| `infra/scripts/deploy.sh` | **Git Bash 또는 WSL 전용** | bash 스크립트 |
| `"$(terraform output -raw …)"` 형태 | **Git Bash 전용** | `$( )` 는 bash 문법 |

bash → PowerShell 변환 규칙:

| bash | PowerShell |
|---|---|
| `VAR="$(명령)"` | `$VAR = 명령` |
| `curl -fsS "$BASE/actuator/health"` | `Invoke-RestMethod "$BASE/actuator/health"` |
| `export AWS_REGION=ap-northeast-2` | `$env:AWS_REGION = "ap-northeast-2"` |
| `curl -H "X-Admin-Key: $KEY" …` | `Invoke-RestMethod … -Headers @{ "X-Admin-Key" = $KEY }` |

### 0.3 도구 설치 (Windows / PowerShell)

```powershell
winget install --id Hashicorp.Terraform --source winget
```

```powershell
winget install --id Amazon.AWSCLI --source winget
```

설치 후 **PowerShell 창을 새로 열어야** PATH 가 반영됩니다.

```powershell
terraform version; aws --version
```

Git Bash 는 Git for Windows 에 포함되어 있습니다(`C:\Program Files\Git\bin\bash.exe`).
Git Bash 에서 Windows 경로는 `/c/Users/...` 형태로 씁니다.

### 0.4 AWS 자격증명

콘솔에서 발급:

```
IAM → 사용자 → 사용자 생성 → 이름 imlate-deployer
   → 권한: AdministratorAccess (최소 권한으로 좁히려면 §1.2 목록 참고)
   → 생성된 사용자 → [보안 자격 증명] 탭 → [액세스 키 만들기] → 사용 사례: CLI
```

> Secret access key 는 **생성 직후 화면에서만 1회 표시**됩니다.

등록:

```powershell
aws configure
```

| 프롬프트 | 입력 |
|---|---|
| `AWS Access Key ID` | 발급받은 액세스 키 ID |
| `AWS Secret Access Key` | 발급받은 시크릿 |
| `Default region name` | `ap-northeast-2` |
| `Default output format` | `json` |

```powershell
aws sts get-caller-identity
```

---

## 1. 사전 준비

### 1.1 로컬 도구

| 도구 | 버전 | 확인 |
|---|---|---|
| Terraform | ≥ 1.6 | `terraform version` |
| AWS CLI | v2 | `aws sts get-caller-identity` |
| JDK | 21+ | `java -version` |
| Node.js | ≥ 20.19 | `node -v` |
| bash / curl / tar | — | `deploy.sh` 가 사용 |
| Git for Windows | — | Git Bash 제공(`C:\Program Files\Git\bin\bash.exe`) |

설치 방법과 셸 선택은 [§0.2 ~ §0.4](#02-어느-셸에서-실행하나-windows)를 보세요.
`deploy.sh` 는 bash 스크립트이므로 Windows 에서는 **Git Bash 또는 WSL**에서 실행합니다.

### 1.2 AWS 계정

- 리소스 생성 권한(VPC, EC2, RDS, ElastiCache, ALB, WAF, IAM, SSM, SES)이 있는 자격증명
- 기본 리전: `ap-northeast-2`(서울)
- (권장) Terraform 원격 상태용 S3 버킷 + DynamoDB 잠금 테이블
  → `infra/terraform/providers.tf` 의 `backend "s3"` 주석을 해제하고 값 입력

### 1.3 도메인 / 인증서 (선택이지만 권장)

- Route 53 호스팅 영역 또는 외부 DNS
- ACM 인증서(**리전은 ALB와 동일한 `ap-northeast-2`**) → `acm_certificate_arn` 에 입력
- 인증서를 넣으면 443 리스너가 생기고 80은 443으로 리다이렉트됩니다.
- 도메인이 없으면 `app_base_url` 을 비워 두세요. ALB DNS 이름이 조회 링크의 base URL로 쓰입니다.

### 1.4 Amazon SES

> ⚠️ **아이덴티티를 콘솔에서 직접 만들지 마세요.**
> `modules/ses` 의 `aws_sesv2_email_identity` 가 `ses_identity` 값으로 아이덴티티를 생성합니다.
> 콘솔에서 먼저 만들어 두면 `terraform apply` 가 `AlreadyExistsException` 으로 실패합니다.
> (이미 만들었다면 콘솔에서 삭제하거나, `enable_ses = false` 로 두고 수동 관리하거나,
> `terraform import module.ses.aws_sesv2_email_identity.this[0] <identity>` 로 가져오세요.)
>
> 콘솔에서 **반드시 사람이 해야 하는 것은 두 가지**뿐입니다 — ① 프로덕션 액세스 신청,
> ② (도메인이면) DNS 레코드 등록 / (이메일이면) 검증 메일 링크 클릭.

#### 1.4.1 아이덴티티 종류 선택

| 상황 | 선택 | `ses_identity` 값 | DNS 작업 |
|---|---|---|---|
| 도메인이 없다 | **이메일 주소** | `"no-reply@gmail.com"` 처럼 실제 수신 가능한 주소 | 불필요(검증 메일 클릭) |
| 소유 도메인이 있고 DNS 를 수정할 수 있다 | **도메인**(권장) | `"imlate.example.com"` | Easy DKIM CNAME 3건 |

도메인 아이덴티티가 권장되는 이유는 발신 주소를 자유롭게 정할 수 있고 배달률·평판 관리가 낫기 때문입니다.
다만 **DNS 를 수정할 수 없는 도메인을 적으면 영원히 `Verified` 가 되지 않습니다.**

#### 1.4.2 콘솔 "전송 도메인 추가" 화면을 쓰는 경우 (수동 관리 시)

| 입력칸 | 값 |
|---|---|
| **전송 도메인** | 소유·DNS 수정이 가능한 도메인. 하위 도메인도 가능(`mail.example.com`) |
| **MAIL FROM 도메인**(선택) | **비워 두기.** 지정하면 해당 하위 도메인에 MX + SPF TXT 레코드를 추가로 등록해야 합니다 |
| **MX 실패 시 동작** | **`기본 MAIL FROM 도메인 사용`** 유지. `메시지 거부` 는 DNS 가 어긋나면 발송이 아예 중단됩니다 |
| DKIM | `Easy DKIM` + `RSA_2048` + 서명 활성화 |

#### 1.4.3 이 프로젝트는 샌드박스로도 운영 가능합니다

SES 샌드박스 제한은 다음과 같습니다.

- **검증된** 이메일 주소·도메인으로만 발송
- 24시간당 200통, 초당 1통

이 시스템은 **하루 1회, 사감 N명(기본 2명)에게만** 메일을 보냅니다.
따라서 **사감들의 수신 주소를 각각 Identity 로 검증해 두면 샌드박스 상태 그대로 정상 운영됩니다.**
도메인도 프로덕션 액세스도 필수가 아닙니다.

검증해야 할 주소:

| 무엇 | 개수 | 대응 설정 | 누가 만드나 |
|---|---|---|---|
| 발신 주소 | 1 | `ses_identity`(또는 `ses_from_address`) | terraform (`module.ses.this`) |
| 사감 수신 주소 | 사감 수만큼 | `supervisor1_email`, `supervisor2_email` … | **terraform** (`module.ses.recipients`) |
| **운영자 알림 수신 주소** | 1 | `ses_additional_verified_emails` 에 추가 | **terraform** |

> ⚠️ **운영자 알림 메일도 같은 SES 로 나갑니다.**
> 즉 샌드박스에서는 **운영자 알림 주소(`imlate.notification.ops-alert.email`)도 검증되어 있어야**
> 알림이 도착합니다. 검증하지 않으면 "발송이 실패했다"는 알림 자체가 조용히 실패합니다.
> 사감 수신 주소와 같은 주소를 쓰고 있다면 이미 검증되어 있으므로 추가 작업이 없습니다.
> (CloudWatch 알람 메일은 SNS 경로라 SES 검증과 무관합니다 — §11.)

**수신 주소도 terraform 이 자동으로 아이덴티티로 등록합니다.**
`ses_verify_supervisor_emails = true`(기본값)이면 `supervisor*_email` 이 SES 아이덴티티로 생성됩니다.
발신 아이덴티티와 같은 주소는 중복 생성하지 않으며, 중복 주소는 제거됩니다.

| 변수 | 기본값 | 용도 |
|---|---|---|
| `ses_verify_supervisor_emails` | `true` | 사감 수신 주소를 SES 아이덴티티로 등록 |
| `ses_additional_verified_emails` | `[]` | 운영자 알림 등 추가 수신 주소 |

`apply` 후 **각 주소 소유자가 AWS 확인 메일의 링크를 눌러야** `Verified` 가 됩니다.

```powershell
terraform output -json ses_recipient_identities
```

```powershell
aws sesv2 get-email-identity --email-identity "someone@example.com" --region ap-northeast-2 --query VerifiedForSendingStatus
```

> **콘솔에서 수동으로 만들지 마세요.** terraform 이 생성하므로 먼저 만들면
> `apply` 가 `AlreadyExistsException` 으로 실패합니다(§1.4 상단 경고와 동일).
>
> 트레이드오프: 사감이 교체되면 `terraform.tfvars` 의 이메일을 바꾸고 다시 `apply` → 새 주소 검증이 필요합니다.
> 그게 번거로우면 아래 프로덕션 액세스를 신청하세요.

#### 1.4.4 프로덕션 액세스(샌드박스 해제) 신청

사감 외에 다른 사람에게도 메일을 보낼 계획이라면 신청합니다. §1.4.3 구성만으로 충분하면 건너뛰어도 됩니다.

**콘솔의 "프로덕션 액세스 요청" 버튼이 `도메인 확인 필요` 로 비활성인 경우**

`Get set up` 페이지의 버튼은 **검증된 도메인 아이덴티티가 있을 때만** 활성화됩니다.
하지만 도메인 검증은 AWS 문서상 **필수 조건이 아니라 승인을 빠르게 하는 모범 사례**이며,
`PutAccountDetails` API 명세에도 도메인 관련 필수 항목은 없습니다.
따라서 **도메인이 없으면 CLI 로 신청**하면 됩니다.

```powershell
aws sesv2 put-account-details --production-access-enabled --mail-type TRANSACTIONAL --website-url "https://<소속기관_또는_저장소_URL>" --additional-contact-email-addresses "you@example.com" --contact-language EN --region ap-northeast-2
```

| 파라미터 | 값 | 비고 |
|---|---|---|
| `--mail-type` | `TRANSACTIONAL` | 사용자 행동에 따라 자동 발송되는 알림. 광고성 아님 |
| `--website-url` | **필수**(1~1000자) | 웹사이트가 없으면 소속 기관 홈페이지나 저장소 URL |
| `--additional-contact-email-addresses` | 심사 결과 받을 주소 | 최대 4개 |
| `--contact-language` | `EN` \| `JA` | 한국어 옵션 없음 |

신청서에 참고할 내용: 발송 목적(기숙사 야간 복귀 명단 자동 통지), 수신자(사감 2명, 내부 직원),
일 발송량(≈ 2건/일), 바운스·컴플레인 처리(발송 이력 테이블 기록, 실패 시 재시도 후 운영자 확인).

> ⚠️ **제출 후에는 심사가 끝날 때까지 내용을 수정할 수 없습니다.** 초기 응답은 보통 24시간 내.
> 이미 심사 중인 요청이 있으면 `ConflictException(409)` 이 납니다.

상태 확인:

```powershell
aws sesv2 get-account --region ap-northeast-2 --query "ProductionAccessEnabled"
```

### 1.5 Aligo(문자)

1. <https://smartsms.aligo.in> 가입
2. **발신번호 사전등록**(통신사 규정상 필수) — 등록 완료까지 시간이 걸립니다
3. API Key / User ID 발급 (`API 설정` 메뉴)
4. 충전(잔액이 없으면 `result_code` 실패로 떨어집니다)
5. **API 연동 허용 IP(화이트리스트)에 서버 공인 IP 등록** — `terraform output -raw aligo_whitelist_ip`

   ```powershell
   terraform output -raw aligo_whitelist_ip
   ```

   > ⚠️ **실제로 겪은 사고입니다.** 등록하지 않으면 알리고가 `result_code=-101 인증오류` 를 돌려주고
   > **문자만 전부 실패**합니다(메일은 정상이라 더 늦게 발견합니다).
   > 현재 구성은 ALB·NAT 없이 EC2 EIP 하나로 나가므로, 그 값이 곧 등록할 IP 입니다.
   > EIP 는 인스턴스를 교체해도 유지되지만 `terraform destroy` 후 재구축하면 바뀝니다 —
   > 그때는 **알리고 화이트리스트를 반드시 다시 등록**해야 합니다(Route53 A 레코드는 자동 갱신).
6. **`prod` 프로파일은 기본이 실제 발송입니다** — `application-prod.yml` 의
   `test-mode: ${IMLATE_ALIGO_TEST_MODE:false}`. 리허설 중 실제 발송을 막고 싶으면
   SSM 에 파라미터를 **직접 추가**해야 합니다(terraform 이 만들어 주지 않습니다).

   ```powershell
   aws ssm put-parameter --name /imlate/prod/IMLATE_ALIGO_TEST_MODE --value "true" --type SecureString --overwrite --region ap-northeast-2
   ```

   반영하려면 서비스 재시작이 필요하고(§7 명령 참고), 리허설이 끝나면 삭제합니다.

   ```powershell
   aws ssm delete-parameter --name /imlate/prod/IMLATE_ALIGO_TEST_MODE --region ap-northeast-2
   ```

---

## 2. Terraform 적용

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars
```

PowerShell:

```powershell
cd infra\terraform; Copy-Item terraform.tfvars.example terraform.tfvars; notepad terraform.tfvars
```

### 2.1 반드시 채워야 하는 값 (기본값 없음)

| 변수 | 어디서 얻나 | 예시 |
|---|---|---|
| `aligo_api_key` | 알리고 → `문자 API` → API Key 발급 | `"abcd1234…"` |
| `aligo_user_id` | 알리고 로그인 아이디 | `"myschool"` |
| `aligo_sender` | 알리고 → `발신번호 사전등록` 의 **승인된** 번호 | `"0212345678"` |
| `ses_identity` | §1.4 에서 정한 도메인 또는 이메일 주소 | `"no-reply@example.com"` |
| `supervisor1_name/_phone/_email` | 사감 선생님께 직접 확인 | `"김사감"`, `"01012345678"` |
| `supervisor2_name/_phone/_email` | 〃 | |
| `alert_email` | **운영자 본인** 메일. 알람이 여기로 옵니다 (§11) | `"ops@example.com"` |

> `alert_email` 을 비워 두면 **CloudWatch 알람과 SNS 가 아예 만들어지지 않습니다**(의도된 동작).
> 운영에 들어간다면 반드시 채우세요.

> ⚠️ **`alert_email` 에 사감 연락처를 넣지 마세요.**
> 사감님께 가는 것은 "오늘 밤 복귀 명단"뿐이고, 이 주소로 가는 것은 "시스템이 고장났다"는
> 운영자용 신호입니다. 섞이면 사감님이 새벽에 CloudWatch 알람 메일을 받게 되고,
> 진짜 명단 문자와 혼동하게 됩니다.

`ses_identity` 와 `ses_from_address` 가 **둘 다 비면 plan 단계에서 막힙니다**(§2.3 가드레일).

**비워 두어도 되는 값** — 비우면 `random_password` 로 자동 생성되어 SSM SecureString 에 저장됩니다:
`db_password`, `redis_auth_token`, `lookup_token_secret`, `admin_api_key`.
생성된 값은 `terraform output -raw <이름>` 으로 확인합니다.

선택 항목:

| 변수 | 언제 채우나 |
|---|---|
| `acm_certificate_arn` | HTTPS 를 쓸 때. ACM 에서 **ALB 와 같은 `ap-northeast-2`** 리전에 발급 |
| `app_base_url` | 커스텀 도메인이 있을 때. 비우면 ALB DNS 이름이 조회 링크 base URL 이 됨 |
| `artifact_bucket_name` | `deploy.sh --mode ssm` 을 쓸 때. 버킷은 미리 만들어 둘 것 |
| `key_pair_name` | SSH 로 접속할 때(권장하지 않음 — SSM Session Manager 사용) |

> `terraform.tfvars` 에는 실제 API 키가 들어갑니다. `.gitignore` 대상이지만
> **파일 자체를 메신저·메일로 공유하지 마세요.**

### 2.2 실행

한 단계씩 결과를 확인하며 진행합니다(PowerShell·Git Bash 동일).

```bash
terraform init
```

```bash
terraform validate
```

```bash
terraform plan -out=tfplan
```

`plan` 은 "무엇을 만들지"에 대한 견적서입니다. **과금되는 리소스가 생성되므로 반드시 읽어 보세요.**

```bash
terraform apply tfplan
```

> **`apply` 가 끝났다고 알림이 켜진 것이 아닙니다.**
> SNS 이메일 구독은 **수신자가 확인 메일의 링크를 눌러야** 활성화됩니다 — 반드시 §11 을 이어서 하세요.

`--mode ssm` 배포용 S3 버킷이 필요하면 먼저 만들고 `artifact_bucket_name` 에 넣은 뒤 다시 `apply` 합니다.

```bash
aws s3 mb s3://imlate-artifacts --region ap-northeast-2
```

RDS/ElastiCache 생성 때문에 **첫 apply는 15~25분** 정도 걸립니다.

### 2.3 plan 단계 가드레일

잘못된 조합은 `terraform_data.guardrails` 의 precondition이 미리 잡아 줍니다.

| 조건 | 메시지 |
|---|---|
| `redis_auth_enabled=true` 인데 `redis_transit_encryption_enabled=false` | ElastiCache 제약 위반 |
| SES 발신 주소 미지정 | `ses_identity` 또는 `ses_from_address` 필요 |
| `enable_alb=false` 인데 `public_app=false` | 외부에서 접근 불가 |
| `public_app=false` 인데 `enable_nat_gateway=false` | SSM/SES 접근 불가 |

### 2.4 주요 출력값

```bash
terraform output                     # 전체
terraform output -raw service_url    # 서비스 URL(조회 링크 base)
terraform output -raw aligo_whitelist_ip   # ★ 알리고에 등록할 발신 IP
terraform output -raw app_instance_id
terraform output -raw rds_jdbc_url
terraform output -raw redis_endpoint
terraform output -json ses_dkim_dns_records
terraform output next_steps

# 모니터링 (§11)
terraform output alert_subscription_notice     # ★ SNS 구독 확인 절차
terraform output -json monitoring_alarm_names
terraform output -raw alert_sns_topic_arn
terraform output dispatch_metric_contract      # 앱↔알람 지표 계약
terraform output heartbeat_alarm_window

# 민감값
terraform output -raw db_password
terraform output -raw admin_api_key
terraform output -raw lookup_token_secret
terraform output -raw redis_auth_token
```

> 모니터링 출력이 `null` 이면 `alert_email` 이 비어 있거나 `enable_monitoring = false` 인 것입니다(§11.1).

---

## 3. SSM 파라미터 확인 / 채우기

Terraform이 `/imlate/{environment}/*` 에 SecureString으로 아래 값을 만듭니다.
**파라미터 이름의 마지막 세그먼트가 그대로 환경변수 이름**이 됩니다.

```
IMLATE_DB_URL              IMLATE_DB_USERNAME          IMLATE_DB_PASSWORD
IMLATE_REDIS_HOST          IMLATE_REDIS_PORT           IMLATE_REDIS_SSL_ENABLED
IMLATE_REDIS_PASSWORD*     IMLATE_LOOKUP_BASE_URL      IMLATE_LOOKUP_TOKEN_SECRET
IMLATE_ADMIN_API_KEY       IMLATE_WEB_ALLOWED_ORIGIN_1
IMLATE_ALIGO_API_KEY       IMLATE_ALIGO_USER_ID        IMLATE_ALIGO_SENDER
IMLATE_SES_REGION          IMLATE_SES_FROM             IMLATE_SES_FROM_NAME
IMLATE_SES_CONFIGURATION_SET*
IMLATE_SUPERVISOR1_NAME    IMLATE_SUPERVISOR1_PHONE    IMLATE_SUPERVISOR1_EMAIL
IMLATE_SUPERVISOR2_NAME    IMLATE_SUPERVISOR2_PHONE    IMLATE_SUPERVISOR2_EMAIL
```

`*` 는 조건부 생성(`redis_auth_enabled`, `enable_ses_configuration_set`).

확인:

```bash
aws ssm get-parameters-by-path --path /imlate/prod --recursive \
  --region ap-northeast-2 --query 'Parameters[].Name' --output table
```

추가/수정(예: 발송 cron, 마감 시각처럼 Terraform이 만들지 않는 값):

```bash
aws ssm put-parameter --name /imlate/prod/IMLATE_REGISTRATION_CLOSE_TIME \
  --value '21:30' --type SecureString --overwrite --region ap-northeast-2
```

EC2의 `imlate-load-env.sh` 는 `/imlate/{env}` 아래 **모든** 파라미터를 읽어 환경변수 파일을 만들기 때문에,
`application.yml` 이 참조하는 어떤 `IMLATE_*` 값이든 이 경로에 넣으면 반영됩니다.

---

## 4. 애플리케이션 배포

### 4.1 deploy.sh

> ⚠️ **`deploy.sh` 는 bash 스크립트입니다. Windows 에서는 Git Bash 또는 WSL 에서 실행하세요.**
> PowerShell 에서는 실행되지 않습니다. Git Bash 에서 리포지터리로 이동할 때는
> `cd /c/Users/<사용자>/Desktop/skala-imlate` 형태의 경로를 씁니다.
> 인스턴스 ID 는 PowerShell 에서 `terraform output -raw app_instance_id` 로 확인해 붙여넣으면 됩니다.

```bash
# (A) SSM RunCommand 경유 — 프라이빗 서브넷 배치 시 권장. S3 아티팩트 버킷 필요
infra/scripts/deploy.sh --mode ssm \
  --instance-id "$(terraform -chdir=infra/terraform output -raw app_instance_id)" \
  --bucket imlate-artifacts \
  --region ap-northeast-2 \
  --health-url "$(terraform -chdir=infra/terraform output -raw service_url)/actuator/health"

# (B) SSH 경유 — public_app=true 또는 배스천/터널 사용 시
infra/scripts/deploy.sh --mode ssh --host 3.35.1.2 --key ~/.ssh/imlate.pem
```

주요 옵션:

| 옵션 | 설명 |
|---|---|
| `--backend-only` / `--frontend-only` | 한쪽만 배포 |
| `--no-build` | 빌드 생략, 기존 산출물 사용 |
| `--no-config` | nginx/systemd 설정 파일 갱신 생략 |
| `--s3-prefix <접두어>` | S3 키 접두어(기본 `deploy`) |
| `--health-url <URL>` | 배포 후 최대 180초 헬스체크 대기 |

> `--mode ssm` 을 쓰려면 아티팩트용 S3 버킷을 미리 만들고 `artifact_bucket_name` 변수에 넣어야 합니다
> (그래야 EC2 역할에 해당 버킷 읽기 권한이 붙습니다).

### 4.2 스크립트가 하는 일

1. `backend/gradlew --no-daemon clean bootJar` → `backend/build/libs/*.jar`(`-plain.jar` 제외)
2. `frontend` 에서 `npm ci`(lock 있으면) → `npm run build` → `dist` 를 tar.gz로 압축
3. 아티팩트 + `infra/systemd/imlate.service` + `infra/nginx/imlate.conf` 전송
4. 원격에서:
   - systemd 유닛 갱신 → `daemon-reload`
   - nginx 설정 배치 → `nginx -t` 통과 시에만 reload (실패하면 이전 설정 유지)
   - `dist` → `/var/www/imlate` 동기화(rsync `--delete`)
   - 기존 jar을 `/opt/imlate/imlate.jar.bak` 으로 백업 후 교체
   - `systemctl restart imlate-env.service` → `systemctl restart imlate.service`
   - 30초 동안 `is-active` 확인, 실패 시 최근 로그 50줄 출력 후 실패 종료
5. `--health-url` 이 있으면 성공할 때까지 5초 간격 재시도(최대 180초)

### 4.3 서버 파일 배치

| 경로 | 내용 |
|---|---|
| `/opt/imlate/imlate.jar` | 실행 jar (소유자 `imlate:imlate`) |
| `/opt/imlate/imlate.jar.bak` | 직전 버전(롤백용) |
| `/var/www/imlate/` | 프론트 빌드 산출물(nginx 문서 루트) |
| `/etc/imlate/imlate.env` | SSM에서 생성된 환경변수(root:imlate 0640) |
| `/etc/systemd/system/imlate.service` | 애플리케이션 유닛 |
| `/etc/systemd/system/imlate-env.service` | SSM 환경변수 로더(oneshot) |
| `/etc/nginx/conf.d/imlate.conf` | 정적 서빙 + `/api` 프록시 |
| `/var/log/imlate/` | 애플리케이션 로그 (logrotate 14세대) |

### 4.4 nginx / systemd 요약

`infra/nginx/imlate.conf`

- `listen 80 default_server`, root `/var/www/imlate`, SPA 폴백 `try_files $uri $uri/ /index.html`
  → `/lookup` 직접 접속이 동작합니다
- `/api/` → `http://127.0.0.1:8080`, `X-Forwarded-For` 전달
- `limit_req 10r/s burst=20`, `limit_conn 40`, `client_max_body_size 1m`
- `/actuator/health` 는 사설 대역·localhost만 허용 (하위 경로 `/actuator/health/alb` 도 동일 규칙 적용)
- `/assets/` 30일 immutable 캐시, `index.html` 은 no-cache

> **ALB 헬스체크 경로는 `/actuator/health/alb` 입니다** (`alb_health_check_path` 기본값).
> Redis 를 제외한 `db` + `ping` 만 검사합니다. 전체 `/actuator/health` 를 쓰면 ElastiCache 장애 때
> 정상 동작하는 인스턴스까지 서비스에서 빠집니다 — 이 앱은 Redis 없이도 등록을 계속 처리합니다.
> 운영자가 눈으로 확인할 때는 상세 정보가 있는 `/actuator/health` 를 그대로 쓰면 됩니다.

`infra/systemd/imlate.service`

- `Requires/After=imlate-env.service` → 환경변수 파일이 준비된 뒤에만 기동
- `ExecStart=/usr/bin/java -jar /opt/imlate/imlate.jar --spring.profiles.active=prod`
- `SuccessExitStatus=143`(graceful shutdown), `Restart=always`, `RestartSec=10`
- `NoNewPrivileges`, `PrivateTmp`, `ProtectHome`, `ProtectSystem=full`

---

## 5. 배포 후 확인

```bash
BASE="$(terraform -chdir=infra/terraform output -raw service_url)"

# 1) 헬스체크
curl -fsS "$BASE/actuator/health"

# 2) 공개 API
curl -s "$BASE/api/v1/registrations/window"
curl -s "$BASE/api/v1/registrations/summary"
curl -s "$BASE/api/v1/stats/summary"

# 3) rate limit 헤더가 붙는지
curl -sD - -o /dev/null "$BASE/api/v1/registrations/summary" | grep -i x-ratelimit

# 4) 프론트 SPA 라우트 폴백
curl -sI "$BASE/lookup" | head -1        # 200 이어야 함

# 5) 발송 경로 리허설(실제 발송 없음)
curl -s -X POST "$BASE/api/v1/admin/notifications/preview" \
  -H "X-Admin-Key: $(terraform -chdir=infra/terraform output -raw admin_api_key)"
```

같은 확인을 **PowerShell** 로 할 때:

```powershell
$BASE = terraform -chdir=infra/terraform output -raw service_url
```

```powershell
Invoke-RestMethod "$BASE/actuator/health"
```

```powershell
Invoke-RestMethod "$BASE/api/v1/registrations/window"
```

```powershell
(Invoke-WebRequest "$BASE/api/v1/registrations/summary" -UseBasicParsing).Headers.GetEnumerator() | Where-Object Key -like "X-RateLimit*"
```

```powershell
$KEY = terraform -chdir=infra/terraform output -raw admin_api_key; Invoke-RestMethod -Method Post "$BASE/api/v1/admin/notifications/preview" -Headers @{ "X-Admin-Key" = $KEY }
```

서버에서:

```bash
aws ssm start-session --target "$INSTANCE_ID" --region ap-northeast-2
systemctl status imlate imlate-env nginx
sudo tail -50 /var/log/imlate/imlate.log
sudo grep -c "Flyway" /var/log/imlate/imlate.log     # 마이그레이션 적용 로그
```

첫 기동 시 Flyway가 `V1__init.sql` 을 적용합니다. `ddl-auto: validate` 이므로
스키마가 어긋나면 **기동 자체가 실패**합니다(의도된 안전장치).

---

## 6. SES 도메인 인증 (DKIM / SPF / DMARC)

### 6.1 DKIM (Easy DKIM, 도메인 아이덴티티일 때)

```bash
terraform -chdir=infra/terraform output -json ses_dkim_dns_records
```

출력된 CNAME 3건을 DNS에 등록합니다.

| 타입 | 이름 | 값 |
|---|---|---|
| CNAME | `<token1>._domainkey.example.com` | `<token1>.dkim.amazonses.com` |
| CNAME | `<token2>._domainkey.example.com` | `<token2>.dkim.amazonses.com` |
| CNAME | `<token3>._domainkey.example.com` | `<token3>.dkim.amazonses.com` |

전파 후 콘솔의 아이덴티티 상태가 `Verified` / DKIM `Successful` 이 되면 완료입니다.

### 6.2 SPF

발신 도메인의 TXT 레코드에 SES를 포함시킵니다(이미 SPF가 있으면 병합, 레코드는 1개만 유지).

```
example.com.  TXT  "v=spf1 include:amazonses.com ~all"
```

### 6.3 DMARC (권장)

```
_dmarc.example.com.  TXT  "v=DMARC1; p=none; rua=mailto:dmarc@example.com; fo=1"
```

운영이 안정되면 `p=none` → `p=quarantine` 으로 강화합니다.

### 6.4 확인

```bash
dig +short TXT example.com
dig +short CNAME <token1>._domainkey.example.com
aws ses get-identity-verification-attributes --identities example.com --region ap-northeast-2
```

발신자 표시 이름(`IMLATE_SES_FROM_NAME`)이 한글이어도 RFC 2047로 인코딩되어 깨지지 않습니다.

---

## 7. 도메인 연결

```
Route 53 → 호스팅 영역 → 레코드 생성
  이름:   imlate.example.com
  타입:   A
  별칭:   예 → Application Load Balancer → ap-northeast-2 → <alb_dns_name>
```

연결 후 `app_base_url` 을 그 도메인으로 지정하고 다시 `apply` 하면
`IMLATE_LOOKUP_BASE_URL` / `IMLATE_WEB_ALLOWED_ORIGIN_1` 이 함께 갱신됩니다.

```bash
# terraform.tfvars
app_base_url = "https://imlate.example.com"

terraform apply
aws ssm send-command --instance-ids "$INSTANCE_ID" --region ap-northeast-2 \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["systemctl restart imlate-env.service","systemctl restart imlate.service"]'
```

---

## 8. 롤백

### 8.1 애플리케이션 롤백 (가장 빠름 — 직전 jar 복구)

```bash
aws ssm start-session --target "$INSTANCE_ID" --region ap-northeast-2

sudo cp /opt/imlate/imlate.jar /opt/imlate/imlate.jar.failed
sudo cp /opt/imlate/imlate.jar.bak /opt/imlate/imlate.jar
sudo chown imlate:imlate /opt/imlate/imlate.jar
sudo systemctl restart imlate.service
curl -fsS http://127.0.0.1:8080/actuator/health
```

### 8.2 특정 커밋으로 재배포

```bash
git checkout <GOOD_COMMIT>
infra/scripts/deploy.sh --mode ssm --instance-id "$INSTANCE_ID" --bucket imlate-artifacts
```

### 8.3 프론트만 롤백

```bash
git checkout <GOOD_COMMIT> -- frontend
infra/scripts/deploy.sh --mode ssm --instance-id "$INSTANCE_ID" --bucket imlate-artifacts --frontend-only
```

### 8.4 설정 롤백

SSM 파라미터 값을 되돌리고 재기동합니다(파라미터 이력은 `aws ssm get-parameter-history` 로 확인).

```bash
aws ssm get-parameter-history --name /imlate/prod/IMLATE_ALIGO_API_KEY \
  --with-decryption --region ap-northeast-2 --query 'Parameters[-2].Value' --output text
```

### 8.5 DB 롤백

- 현재 마이그레이션은 `V1__init.sql` 하나뿐이라 애플리케이션 롤백만으로 충분합니다.
- 이후 스키마를 바꿀 때는 **하위 호환 마이그레이션**(컬럼 추가 → 코드 배포 → 정리)을 원칙으로 하세요.
  Flyway는 down 마이그레이션을 제공하지 않습니다.
- 데이터 사고 시에는 RDS 스냅샷/PITR로 새 인스턴스를 복원한 뒤 `IMLATE_DB_URL` 을 갈아끼웁니다.

### 8.6 인프라 롤백

```bash
terraform plan -out=tfplan   # 되돌릴 변경 확인
terraform apply tfplan
```

주의: `db_deletion_protection = true` 가 기본이며, `aws_instance.app` 은 `ignore_changes = [ami]` 라
AMI가 바뀌어도 인스턴스가 자동 교체되지 않습니다. 의도적 교체는
`terraform apply -replace=module.ec2.aws_instance.app` 입니다.

---

## 9. 월 비용 개략 (ap-northeast-2, 온디맨드 기준)

**200명 규모 · 단일 인스턴스 · 24시간 가동** 가정입니다.
실제 청구는 사용량/할인/환율에 따라 달라지므로 <https://calculator.aws> 로 재확인하세요.

| 항목 | 사양 | 월 예상(USD) |
|---|---|---|
| EC2 | `t3.small` 1대 (730h) | ≈ 19 |
| EBS | gp3 30GiB | ≈ 3 |
| RDS | `db.t4g.micro`, Single-AZ | ≈ 15 |
| RDS 스토리지 | gp3 20GiB + 백업 7일 | ≈ 3 |
| ElastiCache | `cache.t4g.micro` 1노드 | ≈ 13 |
| ALB | 시간당 요금 + 소량 LCU | ≈ 18 |
| NAT Gateway | 1개(`single_nat_gateway=true`) + 소량 전송 | ≈ 40 |
| WAFv2 | Web ACL 1 + 규칙 2 + 소량 요청 | ≈ 8 |
| SES | 월 수십 통 | < 1 |
| SSM Parameter Store | Standard 티어 | 0 |
| Route 53 | 호스팅 영역 1개 | ≈ 0.5 |
| **합계** | | **≈ 120** |

### 비용을 줄이는 선택지

| 방법 | 절감 | 트레이드오프 |
|---|---|---|
| `public_app = true` + `enable_nat_gateway = false` | **≈ 40** | 앱이 퍼블릭 서브넷에 노출(SG로 제한 필요) |
| `enable_alb = false` (+ `public_app = true`) | ≈ 18 | HTTPS 종단·헬스체크를 직접 구성해야 함 |
| `enable_waf = false` | ≈ 8 | 1단 방어 없음(nginx + 앱 리미터만 남음) |
| 야간만 가동(개발 환경) | 인스턴스 시간 비례 | 21:45~22:30(마감·발송·재시도) 시간대는 반드시 켜 둘 것 |
| Savings Plans / RI 1년 | EC2·RDS 약 30~40% | 약정 필요 |
| Aligo 문자 | 건당 과금(별도) | SMS ≈ 9원, LMS ≈ 27원 수준. 하루 2~4건이면 월 수백 원 |

리소스를 완전히 정리하려면:

```bash
cd infra/terraform
# 삭제 방지가 켜져 있으므로 먼저 해제한 뒤 destroy
terraform apply -var 'db_deletion_protection=false' -var 'db_skip_final_snapshot=true'
terraform destroy
```

---

## 10. 배포 체크리스트

- [ ] terraform / AWS CLI 설치, `aws sts get-caller-identity` 성공 (§0.3, §0.4)
- [ ] SES: **발신 주소 + 사감 수신 주소 전부 `Verified`** (샌드박스면 이것만으로 운영 가능 — §1.4.3)
- [ ] (선택) 프로덕션 액세스 신청 — 콘솔 버튼이 막혀 있으면 CLI 로 (§1.4.4)
- [ ] SES 아이덴티티를 **콘솔에서 미리 만들지 않았는지** 확인 (terraform 이 생성 — §1.4)
- [ ] SES DKIM CNAME 3건 + SPF + DMARC 등록, 상태 `Verified` (도메인 아이덴티티일 때)
- [ ] 알리고 발신번호 사전등록 완료 / 잔액 충전
- [ ] **알리고 발신 IP 화이트리스트 = `terraform output -raw aligo_whitelist_ip`** ★
      (등록하지 않으면 문자만 `result_code=-101` 로 전부 실패 — 실제로 겪은 사고)
- [ ] `terraform.tfvars` 작성(필수 4종 + 사감 연락처 + **`alert_email`**), 커밋되지 않았는지 확인
- [ ] `terraform fmt -check && terraform validate && terraform plan` 통과
- [ ] `terraform apply` 성공, `next_steps` 출력 확인
- [ ] SSM 파라미터 전량 존재 확인
- [ ] `deploy.sh`(또는 GitHub Actions 배포) 성공 + 헬스체크 통과
- [ ] `/`, `/lookup` 직접 접속 200 (SPA 폴백)
- [ ] `X-RateLimit-*` 헤더 확인
- [ ] `preview` 로 문자/메일 문구 육안 검수(한글 깨짐·표 정렬·문의처 문구)
- [ ] 실제 발송 리허설: 테스트 등록 1건 → `dispatch?force=true` → **내 번호로** 수신 확인 → 테스트 데이터 정리
- [ ] 리허설용으로 `IMLATE_ALIGO_TEST_MODE=true` 를 넣었다면 **삭제**했는지 확인
      (`prod` 기본값은 `false` = 실제 발송이므로, 안 넣었으면 할 일 없음 — §1.5)
- [ ] 사감 연락처를 실제 값으로 교체 후 `apply` + 서비스 재시작
- [ ] **CloudWatch 알람 생성 확인 + SNS 구독 확인 메일 클릭 + 테스트 발송** (§11) ★
- [ ] **`db_deletion_protection = true` 로 되돌렸는지** (§11.5) ★
- [ ] **21:50 발송 스케줄이 KST 로 도는지** 다음 날 로그로 확인
      (`grep '사감 발송' /var/log/imlate/imlate.log`, `timedatectl`)
- [ ] 운영 전환 전체 점검은 [OPERATIONS.md §1.2 운영 전환 체크리스트](OPERATIONS.md#12-운영-전환-체크리스트-실제-사용자에게-열기-전-1회)

---

## 11. 모니터링 / 알람

> **왜 필요한가 —** 이 서비스는 EC2 1대(단일 AZ)에 앱·nginx 가 다 올라가 있습니다.
> **21:50 직전에 인스턴스가 죽으면 발송이 통째로 실패하는데, 알람이 없으면 아무도 모릅니다.**
> `systemd Restart=always` 는 프로세스 재시작만 커버합니다.
> 실제로 알리고 IP 미등록·SES 미검증으로 두 번 조용히 실패했고, 둘 다 관리 API 를 직접 조회하고서야 알았습니다.

### 11.1 무엇이 만들어지나

`infra/terraform/modules/monitoring` 이 SNS 주제 1개와 CloudWatch 알람 몇 개를 만들고,
**모든 알람을 그 SNS 주제 하나로 보냅니다.** 알람 이름은 `<project>-<environment>-` 접두어를 씁니다(예: `imlate-prod-`).

| 알람(접미어) | 무엇을 잡나 | 알람을 받으면 |
|---|---|---|
| `-dispatch-heartbeat-missing` | **21:50 발송이 아예 일어나지 않음**(인스턴스 다운 등) | [RUNBOOK §1.3](RUNBOOK.md#13-이력이-아예-없다-스케줄러-미실행) → 살아나면 강제 발송 |
| `-dispatch-failures` | 발송은 했지만 **실패 건수가 있음** | [RUNBOOK §1.1](RUNBOOK.md#11-1단계--발송-이력을-본다-항상-여기서-시작) |
| `-ec2-status-check-failed` | EC2 상태검사 실패(인스턴스/하드웨어) | [RUNBOOK §3](RUNBOOK.md#3-인스턴스가-죽었다-알람을-받았을-때) |
| `-ec2-disk-used-high` | 루트 디스크 사용률 초과(로그가 차면 앱이 기록·기동에 실패) | [RUNBOOK §3.6](RUNBOOK.md#36-디스크가-찼다--ec2-disk-used-high) |
| `-rds-free-storage-low`, `-rds-cpu-high` | RDS 여유 스토리지·CPU | [RUNBOOK §2.3](RUNBOOK.md#23-500-인가--db-장애-데이터는-잃지-않는다) |
| `-redis-memory-high-<노드>` | ElastiCache 메모리 | [OPERATIONS.md §8.2](OPERATIONS.md#82-rediselasticache-장애) |

관련 변수(전부 `terraform.tfvars`):

| 변수 | 기본값 | 의미 |
|---|---|---|
| `alert_email` | (비어 있음) | 알람 수신 주소. **비우면 모니터링을 아예 만들지 않습니다** |
| `enable_monitoring` | `true` | 모니터링 생성 여부 |
| `enable_dispatch_heartbeat_alarm` | `true` | 발송 하트비트 알람 생성 여부 |
| `alarm_rds_free_storage_gib` / `alarm_rds_cpu_percent` / `alarm_redis_memory_percent` / `alarm_disk_used_percent` | 각 기본값 | 임계값 조정용 |

세부 설계와 한계는 `infra/terraform/modules/monitoring/README.md` 에 있습니다.

하트비트 알람은 **"최근 24시간 안에 발송 완료 신호가 한 번이라도 있었는가"** 를 봅니다.
정상 운영일에는 21:50 신호 덕분에 하루 종일 `OK` 이고, 신호가 없는 날에만 22:00 전후에 `ALARM` 으로 전이합니다.
정확한 평가 창은 출력으로 확인합니다.

```powershell
terraform output heartbeat_alarm_window
terraform output dispatch_metric_contract   # 앱이 지켜야 하는 네임스페이스·지표 이름·차원
```

> ⚠️ **하트비트 알람은 "앱이 올리는 지표"에 의존합니다 — 이 연결이 끊어지면 알람이 침묵합니다.**
> 알람이 보는 네임스페이스·지표 이름·`Environment` 차원이 앱 설정
> (`imlate.notification.heartbeat.*` — [OPERATIONS.md §5.7](OPERATIONS.md#57-운영자-알림--감시-설정-신규))과
> 어긋나면 알람은 영원히 `INSUFFICIENT_DATA` 로 남고, **인스턴스가 죽어도 메일이 오지 않습니다.**
> 배포 다음 날 §11.3 으로 이 알람이 `OK` 로 바뀌었는지 **반드시 눈으로 확인하세요.**
> (`INSUFFICIENT_DATA` 가 하루 넘게 유지되면 지표가 실제로 올라오지 않는 것입니다 — §11.3 하단 참고.)

### 11.2 ★ SNS 구독 확인 메일을 반드시 클릭해야 합니다

**`terraform apply` 만으로는 알림이 오지 않습니다.**
AWS 가 `alert_email` 주소로 `AWS Notification - Subscription Confirmation` 메일을 보내고,
**수신자가 그 메일의 `Confirm subscription` 링크를 눌러야** 구독이 살아납니다.
누르기 전에는 알람이 `ALARM` 으로 전이해도 **메일이 오지 않습니다.**

```powershell
terraform output alert_subscription_notice
```

구독 상태 확인 — `PendingConfirmation` 이면 아직 안 누른 것입니다.

```powershell
$TOPIC = terraform output -raw alert_sns_topic_arn
aws sns list-subscriptions-by-topic --topic-arn $TOPIC --region ap-northeast-2 `
  --query "Subscriptions[].{주소:Endpoint,구독:SubscriptionArn}" --output table
```

확인 메일이 안 보이면 **스팸함**을 보고, 그래도 없으면 구독을 다시 만듭니다.

```powershell
aws sns subscribe --topic-arn $TOPIC --protocol email --notification-endpoint "<운영자주소>" --region ap-northeast-2
```

### 11.3 알람이 만들어졌는지 / 지금 상태가 어떤지

```powershell
aws cloudwatch describe-alarms --region ap-northeast-2 `
  --query "MetricAlarms[].{이름:AlarmName,상태:StateValue,갱신:StateUpdatedTimestamp}" --output table
```

특정 알람만:

```powershell
aws cloudwatch describe-alarms --alarm-name-prefix "imlate-prod-" --region ap-northeast-2 `
  --query "MetricAlarms[].{이름:AlarmName,상태:StateValue}" --output table
```

| 상태 | 의미 |
|---|---|
| `OK` | 정상 |
| `ALARM` | 임계 초과. 메일이 갔어야 합니다(§11.2 확인) |
| `INSUFFICIENT_DATA` | 아직 지표가 안 올라옴. **배포 직후에는 정상**이지만, 하루가 지나도 그대로면 아래를 확인하세요 |

`INSUFFICIENT_DATA` 가 하루 넘게 유지될 때 — 지표가 실제로 존재하는지 봅니다.

```powershell
terraform output dispatch_metric_contract    # 알람이 기대하는 네임스페이스/이름/차원
aws cloudwatch list-metrics --namespace "Imlate" --region ap-northeast-2 --output table
```

| 결과 | 의미 | 조치 |
|---|---|---|
| 목록이 비어 있다 | **앱이 올리는 신호가 CloudWatch 지표로 이어지지 않고 있다** | 앱의 하트비트 설정(`imlate.notification.heartbeat.*`)과 위 계약값이 같은지 확인. 앱이 로그로만 남기는 구현이라면 로그 지표 필터가 필요합니다 |
| 지표는 있는데 차원이 다르다 | `Environment` 값 불일치 | 앱 쪽 `IMLATE_ENV`(운영 = `prod`)와 알람 차원을 맞춥니다 |

> 이 상태에서는 **21:50 발송이 통째로 실패해도 알람이 울리지 않습니다.**
> 알람이 붙기 전과 똑같은 상황이므로, 해결 전까지는
> [OPERATIONS.md §1.1](OPERATIONS.md#11-일일-운영-체크리스트) 의 21:52 수동 확인을 계속하세요.

### 11.4 알림이 실제로 도착하는지 테스트 (배포 때 1회 필수)

**"알람은 만들었는데 메일은 안 오더라"가 최악의 시나리오입니다.** 반드시 한 번 확인하세요.

**(A) SNS 로 직접 발송** — 구독이 살아 있는지만 봅니다.

```powershell
aws sns publish --topic-arn $TOPIC --subject "imlate alert test" --message "구독 확인 테스트" --region ap-northeast-2
```

**(B) 알람 상태를 억지로 `ALARM` 으로 전이** — 알람 → SNS → 메일 경로 전체를 봅니다.

```powershell
aws cloudwatch set-alarm-state --alarm-name "imlate-prod-dispatch-failures" `
  --state-value ALARM --state-reason "수동 테스트" --region ap-northeast-2
```

메일 도착을 확인한 뒤 원래대로 돌립니다(다음 평가 주기에 자동으로도 돌아옵니다).

```powershell
aws cloudwatch set-alarm-state --alarm-name "imlate-prod-dispatch-failures" `
  --state-value OK --state-reason "테스트 종료" --region ap-northeast-2
```

- [ ] 받은편지함(스팸함 포함)에 실제로 도착했는가
- [ ] 발신자를 스팸으로 학습시키지 않았는가 — 알람 메일이 스팸으로 가면 없는 것과 같습니다

### 11.5 ★ 운영 전환 시 `db_deletion_protection` 을 다시 켜세요

구축·재구축을 반복하는 동안 편의를 위해 삭제 방지를 꺼 두는 경우가 많습니다.
**현재 `terraform.tfvars` 는 `db_deletion_protection = false`, `db_skip_final_snapshot = true` 로 되어 있을 수 있습니다.**
이 상태로 운영에 들어가면 **`terraform destroy` 한 번, 콘솔 클릭 한 번에 등록 데이터가 스냅샷도 없이 사라집니다.**

```hcl
# infra/terraform/terraform.tfvars — 운영 전환 시
db_deletion_protection = true    # 실수로 지워지지 않게
db_skip_final_snapshot = false   # 지워야 할 때도 최종 스냅샷은 남기게
```

```powershell
terraform plan -out=tfplan
terraform apply tfplan

# 실제로 켜졌는지 AWS 쪽 값으로 확인
aws rds describe-db-instances --region ap-northeast-2 `
  --query "DBInstances[].{ID:DBInstanceIdentifier,삭제방지:DeletionProtection,백업일수:BackupRetentionPeriod}" --output table
```

> 삭제 방지를 켜면 **`terraform destroy` 가 실패합니다. 그게 정상이고 의도한 동작입니다.**
> 진짜로 지울 때는 §9 마지막의 절차대로 먼저 방지를 풀고 destroy 합니다.
>
> 같이 확인할 것: `db_backup_retention_days`(기본 7일)와 RDS 자동 백업이 실제로 켜져 있는지.

### 11.6 알람이 왔을 때

메일 제목의 알람 이름을 §11.1 표에서 찾아 해당 RUNBOOK 절로 이동하세요.
특히 **21:45~22:30 사이에 온 알람은 "발송이 안 됐을 수 있다"는 뜻**이므로,
원인 규명보다 [RUNBOOK §1](RUNBOOK.md#1-2150인데-사감님이-문자를-못-받았다--최우선) 을 먼저 실행합니다.
