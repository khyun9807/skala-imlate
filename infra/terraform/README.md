# imlate 인프라 (Terraform / AWS)

기숙사 야간 복귀 등록 시스템(imlate)의 AWS 인프라를 Terraform 으로 관리한다.
SPEC 문서의 **§13 인프라** 요구사항을 구현한다.

- Terraform `>= 1.6`, AWS Provider `~> 5.0`
- 기본 리전: `ap-northeast-2` (서울)
- 모든 리소스에 `Project=imlate`, `Env=<environment>`, `ManagedBy=terraform` 태그가 붙는다
  (provider `default_tags`).

---

## 1. 아키텍처

```
                     인터넷
                        │
                 ┌──────▼───────┐
                 │  AWS WAFv2   │  IP당 5분 2,000요청 초과 차단
                 │  (REGIONAL)  │  + AWSManagedRulesCommonRuleSet
                 └──────┬───────┘
                        │
                 ┌──────▼───────┐
                 │     ALB      │  public subnet × 2AZ, :80 (+ :443 with ACM)
                 └──────┬───────┘
                        │  target group :8080 (또는 :80 nginx)
                 ┌──────▼───────────────────────────────┐
                 │  EC2 app  (private subnet)           │
                 │  Amazon Linux 2023 + corretto 21     │
                 │  systemd: imlate-env → imlate        │
                 │  nginx: dist 정적 서빙 + /api 프록시  │
                 └───┬──────────────┬───────────────┬───┘
                     │              │               │
            ┌────────▼───┐   ┌──────▼──────┐  ┌─────▼──────────┐
            │ RDS MySQL8 │   │ ElastiCache │  │ SSM Parameter  │
            │  private   │   │  Redis 7    │  │ Store (Secure) │
            │  utf8mb4   │   │  TLS + AUTH │  │ /imlate/{env}/ │
            └────────────┘   └─────────────┘  └────────────────┘
                                                      │
                                              ┌───────▼────────┐
                                              │  SES v2 (메일)  │
                                              └────────────────┘
```

### 모듈 구성

| 모듈 | 역할 |
|---|---|
| `modules/network` | VPC(10.20.0.0/16), 퍼블릭/프라이빗 서브넷 2AZ, IGW, NAT(단일 옵션), 라우팅 |
| `modules/security` | 보안 그룹 4종 (alb / app / db / redis) |
| `modules/rds` | MySQL 8, 프라이빗 서브넷 그룹, 암호화, utf8mb4 + `Asia/Seoul` 파라미터 그룹 |
| `modules/elasticache` | Redis 7 복제 그룹, 프라이빗 서브넷 그룹, TLS + AUTH token |
| `modules/iam` | EC2 역할/인스턴스 프로파일 (SES 발송, SSM 읽기, KMS 복호화, SSM Core, CW Agent) |
| `modules/ses` | SESv2 이메일 아이덴티티(도메인이면 Easy DKIM), 선택적 configuration set |
| `modules/ssm` | 애플리케이션 시크릿 SecureString 파라미터 `/imlate/{env}/*` |
| `modules/alb` | ALB + 타겟 그룹 + 80/443 리스너 |
| `modules/waf` | WAFv2 Web ACL (rate-based rule + AWS 관리형 규칙), ALB 연결 |
| `modules/ec2` | 앱 인스턴스 + `templates/user_data.sh.tftpl` 부트스트랩 |

---

## 2. 두 겹의 Rate Limiting (R14)

| 계층 | 위치 | 단위 | 목적 |
|---|---|---|---|
| 1단 | **AWS WAF** (`modules/waf`) | IP당 5분 2,000요청 | L7 DDoS·스크래핑을 ALB 도달 전에 차단 |
| 2단 | **애플리케이션** (`ratelimit` 모듈, Redis 토큰 버킷) | 등록 8회/분, 조회 40회/분, 전역 120회/분 | 엔드포인트별 정밀 제어 |
| (보조) | **nginx** (`infra/nginx/imlate.conf`) | `/api` 10r/s, burst 20 | 인스턴스 단 최후 방어 |

WAF 만으로는 "한 IP 가 등록 API 만 분당 100회" 같은 패턴을 막지 못하고,
애플리케이션 리미터만으로는 대량 트래픽이 EC2/Redis 까지 도달한다. 그래서 두 층을 함께 둔다.

WAF 관리형 규칙은 한글 본문/긴 UA 때문에 오탐이 날 수 있다. 도입 초기에는
`waf_managed_rules_count_only = true` 로 카운트만 하며 CloudWatch 지표를 확인한 뒤 차단으로 바꾼다.

---

## 3. 사용법

```bash
cd infra/terraform

cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars      # 알리고 키 / 사감 연락처 / SES 아이덴티티 입력

terraform init
terraform fmt -check
terraform validate
terraform plan  -out=tfplan
terraform apply tfplan
```

`terraform.tfvars` 는 루트 `.gitignore` 에 의해 커밋되지 않는다(`*.tfvars`, 예외 `*.tfvars.example`).

### 필수 입력값 (default 없음)

| 변수 | 설명 |
|---|---|
| `aligo_api_key`, `aligo_user_id`, `aligo_sender` | 알리고 문자 API |
| `supervisor1_phone`, `supervisor1_email` | 사감 1 연락처 |
| `supervisor2_phone`, `supervisor2_email` | 사감 2 연락처 |
| `ses_identity` 또는 `ses_from_address` | 메일 발신 주소(둘 다 비면 plan 단계에서 막힌다) |

### 자동 생성되는 값

`db_password`, `redis_auth_token`, `lookup_token_secret`, `admin_api_key` 는
값을 주지 않으면 `random_password` 로 생성되어 SSM SecureString 에 저장된다.
확인하려면:

```bash
terraform output -raw db_password
terraform output -raw admin_api_key
terraform output -raw lookup_token_secret
```

---

## 4. 시크릿 흐름 (R10 — 설정 파일만 바꾸면 되도록)

```
terraform.tfvars
      │  terraform apply
      ▼
SSM Parameter Store  /imlate/{env}/IMLATE_XXX   (SecureString)
      │  EC2 부팅 시 imlate-load-env.sh
      │  aws ssm get-parameters-by-path --recursive --with-decryption
      ▼
/etc/imlate/imlate.env   (root:imlate 0640)
      │  systemd EnvironmentFile
      ▼
application-prod.yml 의 ${IMLATE_XXX} 플레이스홀더
```

**파라미터 이름의 마지막 세그먼트가 그대로 환경변수 이름이 된다.**
예) `/imlate/prod/IMLATE_DB_URL` → `IMLATE_DB_URL`

관리되는 파라미터(SecureString):

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
`*` 는 조건부 생성(각각 `redis_auth_enabled`, `enable_ses_configuration_set`).

값만 바꾸고 재배포 없이 반영하려면:

```bash
aws ssm put-parameter --name /imlate/prod/IMLATE_ALIGO_API_KEY \
  --value '<NEW_KEY>' --type SecureString --overwrite --region ap-northeast-2

aws ssm send-command --instance-ids <INSTANCE_ID> \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["systemctl restart imlate-env.service","systemctl restart imlate"]'
```

---

## 5. apply 이후 체크리스트

1. **SES 검증**
   - 도메인 아이덴티티: `terraform output -json ses_dkim_dns_records` 의 CNAME 3건을 DNS 에 등록.
     추가로 SPF(`v=spf1 include:amazonses.com ~all`)와 DMARC 레코드 권장.
   - 이메일 아이덴티티: AWS 가 보낸 검증 메일의 링크 클릭.
   - **SES 샌드박스**: 해제 전에는 검증된 주소로만 발송된다. 사감 2명의 이메일을 개별 검증하거나
     프로덕션 액세스를 신청한다.
2. **알리고**: 발신번호 사전등록, 필요하면 `terraform output nat_public_ips` 의 IP 를 화이트리스트에 등록.
3. **배포**: `infra/scripts/deploy.sh` (아래 6절).
4. **헬스체크**: `curl -fsS "$(terraform output -raw service_url)/actuator/health"`.
5. **도메인**: `alb_dns_name` / `alb_zone_id` 로 Route53 Alias 레코드를 만들고,
   `app_base_url` 을 그 도메인으로 다시 `apply` 하면 조회 링크 URL 도 함께 바뀐다.

---

## 6. 배포

```bash
# SSM 경유(프라이빗 서브넷 권장) — S3 아티팩트 버킷 필요
infra/scripts/deploy.sh --mode ssm \
  --instance-id "$(terraform -chdir=infra/terraform output -raw app_instance_id)" \
  --bucket imlate-artifacts --region ap-northeast-2

# SSH 경유(퍼블릭 배치 / 배스천 사용 시)
infra/scripts/deploy.sh --mode ssh --host 1.2.3.4 --key ~/.ssh/imlate.pem
```

스크립트는 백엔드 `bootJar` → 인스턴스로 전송 → `systemctl restart imlate`,
프론트 `npm run build` → nginx 문서 루트 동기화 순서로 진행하고 마지막에 헬스체크를 기다린다.

---

## 7. 운영 메모 / 주의점

- **AMI 고정**: `aws_instance.app` 은 `lifecycle.ignore_changes = [ami]` 로 두었다.
  AWS 가 새 AL2023 AMI 를 내도 인스턴스가 저절로 교체되지 않는다.
  의도적으로 갈아엎을 때만 `terraform apply -replace=module.ec2.aws_instance.app`.
- **user_data 변경**: `user_data_replace_on_change = false` 이므로 템플릿을 고쳐도
  기존 인스턴스는 재생성되지 않는다. 반영하려면 위 `-replace` 를 쓴다.
- **RDS 파라미터 그룹**: 문자셋 계열은 정적 파라미터라 `pending-reboot` 이다.
  최초 생성 시에는 문제없지만, 나중에 바꾸면 RDS 재부팅이 필요하다.
- **NAT 비용**: `single_nat_gateway = true` 기준 NAT 1개(월 약 $35~40 + 데이터 전송).
  개발 환경을 완전히 내리려면 `terraform destroy` 로 정리한다.
- **삭제 방지**: `db_deletion_protection = true` 가 기본이다. `destroy` 하려면
  먼저 `false` 로 `apply` 한 뒤 `db_skip_final_snapshot = true` 로 다시 `apply` 한다.
- **nginx default server**: `conf.d/imlate.conf` 가 `listen 80 default_server` 를 잡는다.
  AL2023 기본 `nginx.conf` 의 `server _` 블록과 `conflicting server name` 경고가 뜰 수 있으나
  동작에는 영향이 없다(우리 블록이 default 가 된다).
- **Redis 축출 정책**: `maxmemory-policy = volatile-lru`.
  통계 누계 키(`imlate:stats:*:total`)에는 TTL 이 없으므로 축출되지 않는다.
- **SSH**: `ssh_allowed_cidrs` 기본값은 빈 목록이라 22번 포트가 열리지 않는다.
  접속은 SSM Session Manager(`aws ssm start-session --target <INSTANCE_ID>`)를 권장한다.

---

## 8. 파일 구조

```
infra/
├─ terraform/
│  ├─ providers.tf              provider / required_version / default_tags
│  ├─ variables.tf              루트 변수
│  ├─ main.tf                   모듈 배선 + 가드레일 precondition
│  ├─ outputs.tf                운영에 필요한 출력값
│  ├─ terraform.tfvars.example  값 템플릿(플레이스홀더만)
│  ├─ README.md                 이 문서
│  └─ modules/
│     ├─ network/  security/  rds/  elasticache/
│     ├─ iam/      ses/       ssm/  alb/  waf/
│     └─ ec2/{main,variables,outputs}.tf + templates/user_data.sh.tftpl
├─ nginx/imlate.conf            프론트 정적 서빙 + /api 리버스 프록시
├─ systemd/imlate.service       애플리케이션 서비스 유닛
└─ scripts/deploy.sh            백엔드/프론트 빌드 및 배포
```
