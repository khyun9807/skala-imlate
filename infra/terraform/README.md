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
                  Route53 A 레코드 (고정 URL)
                  skala-imlate.com → EIP
                             │
                 ┌───────────▼──────────────────────────┐
                 │  EC2 app  (public subnet)            │
                 │  Amazon Linux 2023 + corretto 21     │
                 │  EIP 고정 · 보안그룹 80/443 만 개방    │
                 │                                      │
                 │  nginx :80  → ACME 챌린지 + 443 리다이렉트
                 │  nginx :443 → Let's Encrypt TLS 종단  │
                 │      ├ / , /lookup     → /var/www/imlate (SPA 폴백)
                 │      └ /api, /actuator → 127.0.0.1:8080
                 │                                      │
                 │  systemd: imlate-env → imlate        │
                 │           imlate-tls.timer (인증서)   │
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

  아웃바운드(알리고 API, SES, SSM, dnf)도 같은 EIP 로 나간다.
```

**ALB / NAT Gateway / WAF 는 기본으로 끈다.** 200명 규모에 오버스펙이기 때문이다.

| 걷어낸 것 | 왜 | 대체 |
|---|---|---|
| ALB (`enable_alb = false`) | 인스턴스 1대에 월 $18 고정 + LCU. 얻는 게 헬스체크 정도 | EC2 nginx 가 직접 80/443 수신 |
| NAT Gateway (`enable_nat_gateway = false`) | 1개당 월 $35~40 + 데이터 전송료 | 앱을 퍼블릭 서브넷에 두고 IGW 로 직접 아웃바운드 |
| WAF (`enable_waf = false`) | ALB 전용이라 어차피 못 붙음. Web ACL $5 + 규칙 $1 + 요청 과금 | nginx `limit_req` + 애플리케이션 Redis 리미터 |
| ACM | ALB 가 없으면 붙일 곳이 없음 | Let's Encrypt (nginx 종단, 자동 갱신) |

변수는 전부 남겨 두었고 `modules/alb`, `modules/waf` 도 삭제하지 않았다.
다시 켜려면 `enable_alb = true`, `public_app = false`, `enable_nat_gateway = true`,
`create_dns_record = false`(대신 `alb_dns_name`/`alb_zone_id` 로 alias 레코드) 조합을 쓴다.

### 모듈 구성

| 모듈 | 역할 |
|---|---|
| `modules/network` | VPC(10.20.0.0/16), 퍼블릭/프라이빗 서브넷 2AZ, IGW, NAT(기본 off), 라우팅 |
| `modules/security` | 보안 그룹 4종 (alb / app / db / redis) + ALB 경유·직접 노출 인바운드 분기 |
| `modules/rds` | MySQL 8, 프라이빗 서브넷 그룹, 암호화, utf8mb4 + `Asia/Seoul` 파라미터 그룹 |
| `modules/elasticache` | Redis 7 복제 그룹, 프라이빗 서브넷 그룹, TLS + AUTH token |
| `modules/iam` | EC2 역할/인스턴스 프로파일 (SES 발송, SSM 읽기, KMS 복호화, SSM Core, CW Agent) |
| `modules/ses` | SESv2 이메일 아이덴티티(도메인이면 Easy DKIM), 선택적 configuration set |
| `modules/ssm` | 애플리케이션 시크릿 SecureString 파라미터 `/imlate/{env}/*` |
| `modules/alb` | ALB + 타겟 그룹 + 80/443 리스너 — **기본 비활성** |
| `modules/waf` | WAFv2 Web ACL (rate-based rule + AWS 관리형 규칙) — **기본 비활성** |
| `modules/ec2` | 앱 인스턴스 + `templates/user_data.sh.tftpl` 부트스트랩 |

루트 모듈이 직접 만드는 리소스: `aws_eip.app`, `aws_eip_association.app`,
`aws_route53_record.app`, `terraform_data.guardrails`, `random_password.*`.

---

## 2. 고정 URL (피드백 3번)

고객이 보는 주소가 재배포에도 바뀌지 않아야 한다. 두 축으로 보장한다.

### 2-1. 도메인 → Route53 A 레코드

도메인은 **Route53 콘솔에서 직접 구매**한다. 구매하면 퍼블릭 호스팅 영역이 자동으로
생성되므로, Terraform 은 `data "aws_route53_zone"` 으로 **조회만** 하고 A 레코드만 얹는다.
(영역을 Terraform 이 새로 만들면 NS 가 달라져 도메인이 통째로 죽는다.)

```hcl
domain_name    = "skala-imlate.com"
domain_aliases = ["www.skala-imlate.com"]   # 선택
```

`domain_name` 이 비어 있으면 `data` 와 `resource` 모두 `count = 0` 이라 아무것도 조회·생성하지 않는다.

### 2-2. EIP — 인스턴스를 갈아엎어도 유지된다

`aws_eip` 를 EC2 모듈이 아니라 **루트 모듈**에 두고 `aws_eip_association` 으로만 연결한다.

- EIP 리소스가 인스턴스에 의존하지 않으므로
  `terraform apply -replace=module.ec2.aws_instance.app` 로 인스턴스를 교체해도
  **주소는 그대로**이고 연결만 다시 맺힌다.
- `resolved_base_url` 이 `module.ssm` 에서 쓰이는데 `module.ec2` 는 `module.ssm` 에
  `depends_on` 이므로, EIP 가 ec2 모듈 안에 있으면 순환 참조가 된다. 이것도 함께 해결된다.

> ⚠️ **이 EIP 가 바뀌면 두 곳을 반드시 갱신해야 한다.**
> 1. **Route53 A 레코드** — `create_dns_record = true` 면 `terraform apply` 로 자동 갱신되지만
>    TTL(기본 300초)만큼 전파 지연이 있다.
> 2. **알리고 발신 IP 화이트리스트** — **수동**. 갱신 전까지 문자 발송이 전부 실패한다.
>
> `prevent_destroy` 는 걸지 않았다(변수로 끌 수 없어 `destroy` 자체가 막히기 때문).
> 대신 `terraform destroy` 는 주소를 실제로 반납한다는 점을 기억할 것.
> 인스턴스 교체 정도로는 절대 바뀌지 않는다.

확인:

```bash
terraform output -raw service_url          # https://skala-imlate.com
terraform output -raw app_public_ip        # EIP
terraform output -raw aligo_whitelist_ip   # ★ 알리고에 등록할 IP (= EIP)
```

---

## 3. TLS (Let's Encrypt)

ALB 가 없으므로 ACM 대신 EC2 의 nginx 가 직접 TLS 를 종단한다.

```hcl
enable_tls        = true            # domain_name 이 있을 때만 동작
tls_contact_email = "ops@example.com"
```

### 동작 방식

| 단계 | 주체 |
|---|---|
| certbot 설치 | `python3 -m venv /opt/certbot` + `pip install certbot`. AL2023 기본 저장소에는 certbot 패키지가 없다 |
| 발급 | `certbot certonly --webroot -w /var/www/acme` (HTTP-01). nginx 플러그인을 쓰지 않으므로 certbot 이 우리 설정을 건드리지 않는다 |
| 갱신 | `certbot renew --deploy-hook /usr/local/bin/imlate-tls-sync.sh` → nginx reload |
| 실행 | `imlate-tls.timer` — 부팅 3분 뒤 시작, 이후 1시간 간격 |

**발급 실패는 배포 실패도 서비스 중단도 아니다.** `imlate-tls.sh` 는 어떤 경우에도 `exit 0` 이고,
인증서가 없으면 nginx 는 80 포트로 정상 서비스한다. 최초 발급은 DNS 전파·EIP 연결 타이밍 때문에
첫 시도에 실패하는 게 정상이며, 늦어도 한 시간 안에 자동으로 켜진다.

### nginx 설정이 쪼개져 있는 이유

인증서 파일이 없으면 `ssl_certificate` 를 참조하는 순간 nginx 가 아예 기동하지 못한다.
그래서 "인증서 유무"에 따라 바뀌는 부분만 조각 파일로 분리하고 와일드카드로 include 한다
(일치하는 파일이 없어도 오류가 아니다).

```
/etc/nginx/conf.d/imlate.conf        http 설정 + :80 서버 (infra/nginx/imlate.conf)
/etc/nginx/imlate/app-locations.conf 서비스 location 모음 (infra/nginx/imlate-app.conf)
/etc/nginx/imlate/http-mode-*.conf   ← imlate-tls-sync.sh 가 둘 중 하나만 생성
    · http-mode-plain.conf     인증서 없음 → :80 이 그대로 서비스
    · http-mode-redirect.conf  인증서 있음 → :80 은 443 으로 301 (ACME 경로는 예외)
/etc/nginx/imlate/tls-server.conf    ← 인증서가 있을 때만 생성되는 :443 서버
```

`imlate-tls-sync.sh` 는 TLS 구성을 얹은 뒤 `nginx -t` 가 실패하면 **즉시 평문 구성으로 되돌린다.**
어떤 경우에도 nginx 가 죽지 않게 하기 위해서다.

상태 확인:

```bash
sudo systemctl list-timers imlate-tls.timer
sudo journalctl -u imlate-tls.service -n 50
sudo /opt/certbot/bin/certbot certificates
```

---

## 4. 두 겹의 Rate Limiting (R14)

WAF 를 걷어냈으므로 이제 두 겹이다.

| 계층 | 위치 | 단위 | 목적 |
|---|---|---|---|
| 1단 | **nginx** (`infra/nginx/imlate-app.conf`) | `/api` 10r/s, burst 20, 동시연결 40 | 인스턴스를 지키는 최후 방어선 |
| 2단 | **애플리케이션** (`ratelimit` 모듈, Redis 토큰 버킷) | 등록 8회/분, 조회 40회/분, 전역 120회/분 | 엔드포인트별 정밀 제어 |
| (선택) | **AWS WAF** (`modules/waf`) | IP당 5분 2,000요청 | `enable_alb` + `enable_waf` 를 켜야 사용 가능 |

nginx 만으로는 "한 IP 가 등록 API 만 분당 100회" 같은 패턴을 막지 못하고,
애플리케이션 리미터만으로는 대량 트래픽이 EC2/Redis 까지 도달한다. 그래서 두 층을 함께 둔다.

### 공개 엔드포인트

- `/healthz` — 공개. `/actuator/health/alb`(db + ping, `show-details: never`)를 프록시한다.
  배포 스크립트가 밖에서 두드리는 유일한 지점이다.
- `/actuator/` — 사설 대역(127.0.0.1, RFC1918)만 허용. ALB 가 없어진 지금은 사실상
  인스턴스 자신과 VPC 내부만 통과한다. 외부에서 오면 403.
  운영자는 SSM Session Manager 로 붙어 `curl 127.0.0.1:8080/actuator/...` 로 본다.

---

## 5. 사용법

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

## 6. 시크릿 흐름 (R10 — 설정 파일만 바꾸면 되도록)

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

## 7. apply 이후 체크리스트

1. **SES 검증**
   - 도메인 아이덴티티: `terraform output -json ses_dkim_dns_records` 의 CNAME 3건을 DNS 에 등록.
     추가로 SPF(`v=spf1 include:amazonses.com ~all`)와 DMARC 레코드 권장.
   - 이메일 아이덴티티: AWS 가 보낸 검증 메일의 링크 클릭.
   - **SES 샌드박스**: 해제 전에는 검증된 주소로만 발송된다. 사감 2명의 이메일을 개별 검증하거나
     프로덕션 액세스를 신청한다.
2. **알리고**: 발신번호 사전등록 + **`terraform output -raw aligo_whitelist_ip` 의 IP 를
   화이트리스트에 등록**. 이제 이 값은 앱 EC2 의 EIP 다(인바운드 주소와 동일).
3. **DNS 확인**: `dig +short skala-imlate.com` 이 `app_public_ip` 와 같은지 본다.
   Route53 에서 갓 구매한 도메인은 등록기관 반영에 몇 분~수십 분 걸릴 수 있다.
4. **배포**: `infra/scripts/deploy.sh` (아래 8절).
5. **헬스체크**: `curl -fsSL "$(terraform output -raw service_url)/healthz"`.
6. **HTTPS 확인**: DNS 전파 후 최대 1시간 안에 자동으로 켜진다.
   빨리 보고 싶으면 인스턴스에서 `sudo systemctl start imlate-tls.service` 로 즉시 시도한다.
   확인: `curl -I https://skala-imlate.com` / `sudo journalctl -u imlate-tls.service -n 50`

---

## 8. 배포

```bash
# SSH 경유 — 퍼블릭 배치가 기본이므로 이쪽이 1순위다.
# --health-url 을 생략하면 http://<host>/healthz 를 쓴다(리다이렉트 추적).
infra/scripts/deploy.sh --mode ssh \
  --host "$(terraform -chdir=infra/terraform output -raw app_public_ip)" \
  --key ~/.ssh/imlate.pem

# 도메인이 붙은 뒤에는 도메인으로 확인하는 게 낫다.
infra/scripts/deploy.sh --mode ssh --host skala-imlate.com \
  --key ~/.ssh/imlate.pem --health-url https://skala-imlate.com/healthz

# SSM 경유(SSH 를 열지 않을 때 / 프라이빗 배치) — S3 아티팩트 버킷 필요
infra/scripts/deploy.sh --mode ssm \
  --instance-id "$(terraform -chdir=infra/terraform output -raw app_instance_id)" \
  --bucket imlate-artifacts --region ap-northeast-2 \
  --health-url https://skala-imlate.com/healthz
```

스크립트는 백엔드 `bootJar` → 인스턴스로 전송 → `systemctl restart imlate`,
프론트 `npm run build` → nginx 문서 루트 동기화 순서로 진행하고 마지막에 헬스체크를 기다린다.

설정 파일(`--no-config` 로 끌 수 있음)도 함께 동기화한다.

| 저장소 파일 | 배치 위치 |
|---|---|
| `infra/nginx/imlate.conf` | `/etc/nginx/conf.d/imlate.conf` |
| `infra/nginx/imlate-app.conf` | `/etc/nginx/imlate/app-locations.conf` |
| `infra/systemd/imlate.service` | `/etc/systemd/system/imlate.service` |
| `infra/systemd/imlate-tls.{service,timer}` | `/etc/systemd/system/` |
| `infra/scripts/imlate-tls.sh`, `imlate-tls-sync.sh` | `/usr/local/bin/` |

같은 파일들을 `user_data` 도 `file()` 로 읽어 부팅 시 심는다.
**저장소가 유일한 원본**이라 부팅 직후 상태와 배포 후 상태가 어긋나지 않는다.

---

## 9. 운영 메모 / 주의점

- **AMI 고정**: `aws_instance.app` 은 `lifecycle.ignore_changes = [ami]` 로 두었다.
  AWS 가 새 AL2023 AMI 를 내도 인스턴스가 저절로 교체되지 않는다.
  의도적으로 갈아엎을 때만 `terraform apply -replace=module.ec2.aws_instance.app`.
- **EIP 는 인스턴스 교체에도 유지된다**(2-2절). 다만 `terraform destroy` 는 주소를 반납한다.
  주소가 바뀌면 **알리고 화이트리스트를 수동으로 다시 등록**해야 문자 발송이 살아난다.
- **user_data 변경**: `user_data_replace_on_change = false` 이므로 템플릿을 고쳐도
  기존 인스턴스는 재생성되지 않는다. 반영하려면 위 `-replace` 를 쓴다.
  다만 nginx/TLS 파일은 `deploy.sh` 가 그대로 덮어쓰므로 대부분 재생성이 필요 없다.
- **user_data 크기**: EC2 제한은 16 KiB 다. nginx/TLS 부트스트랩까지 넣으면서
  `base64gzip()` 으로 압축해 넣는다(cloud-init 이 자동 해제). 압축 전 약 32 KB, 압축 후 약 11 KB.
  내용을 크게 늘릴 때는 이 여유를 확인할 것.
- **RDS 파라미터 그룹**: 문자셋 계열은 정적 파라미터라 `pending-reboot` 이다.
  최초 생성 시에는 문제없지만, 나중에 바꾸면 RDS 재부팅이 필요하다.
- **NAT 비용**: 기본값 `enable_nat_gateway = false` 라 NAT 는 만들어지지 않는다.
  프라이빗 배치로 되돌리면 NAT 1개당 월 약 $35~40 + 데이터 전송료가 든다.
- **삭제 방지**: `db_deletion_protection = true` 가 기본이다. `destroy` 하려면
  먼저 `false` 로 `apply` 한 뒤 `db_skip_final_snapshot = true` 로 다시 `apply` 한다.
- **nginx default server**: `conf.d/imlate.conf` 가 `listen 80 default_server` 를 잡는다.
  AL2023 기본 `nginx.conf` 의 `server _` 블록과 `conflicting server name` 경고가 뜰 수 있으나
  동작에는 영향이 없다(우리 블록이 default 가 된다).
- **app_port 를 바꾸면**: `infra/nginx/imlate-app.conf` 가 `127.0.0.1:8080` 을 고정으로
  프록시하므로 nginx 설정도 함께 고쳐야 한다.
- **인증서 발급이 안 될 때**: ① DNS A 레코드가 EIP 를 가리키는지, ② 보안 그룹 80 포트가
  열려 있는지, ③ `curl http://<도메인>/.well-known/acme-challenge/test` 가 404(=경로 살아있음)를
  주는지 순서로 본다. Let's Encrypt 실패 제한은 시간당 5회이므로 수동 재시도는 아껴서 한다.
- **Redis 축출 정책**: `maxmemory-policy = volatile-lru`.
  통계 누계 키(`imlate:stats:*:total`)에는 TTL 이 없으므로 축출되지 않는다.
- **SSH**: `ssh_allowed_cidrs` 기본값은 빈 목록이라 22번 포트가 열리지 않는다.
  접속은 SSM Session Manager(`aws ssm start-session --target <INSTANCE_ID>`)를 권장한다.

---

## 10. 파일 구조

```
infra/
├─ terraform/
│  ├─ providers.tf              provider / required_version / default_tags
│  ├─ variables.tf              루트 변수
│  ├─ main.tf                   모듈 배선 + 가드레일 precondition + EIP/Route53
│  ├─ outputs.tf                운영에 필요한 출력값
│  ├─ terraform.tfvars.example  값 템플릿(플레이스홀더만)
│  ├─ README.md                 이 문서
│  └─ modules/
│     ├─ network/  security/  rds/  elasticache/
│     ├─ iam/      ses/       ssm/  alb/  waf/     (alb, waf 는 기본 비활성)
│     └─ ec2/{main,variables,outputs}.tf + templates/user_data.sh.tftpl
├─ nginx/
│  ├─ imlate.conf               http 설정 + :80 서버(ACME 챌린지 + 모드 include)
│  └─ imlate-app.conf           서비스 location 모음(:80/:443 이 공유)
├─ systemd/
│  ├─ imlate.service            애플리케이션 서비스 유닛
│  ├─ imlate-tls.service        인증서 발급/갱신 oneshot
│  └─ imlate-tls.timer          부팅 3분 뒤 + 1시간 간격
└─ scripts/
   ├─ deploy.sh                 백엔드/프론트 빌드 및 배포
   ├─ imlate-tls.sh             certbot 설치 + 발급/갱신 (항상 exit 0)
   └─ imlate-tls-sync.sh        인증서 상태 → nginx 조각 생성 + reload
```
