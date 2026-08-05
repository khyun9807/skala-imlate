# imlate CI/CD 가이드 (GitHub Actions)

`main` 에 머지되면 자동으로 검증하고 EC2 에 배포하는 파이프라인입니다.
배포 대상 인프라는 [DEPLOYMENT.md](DEPLOYMENT.md), 운영은 [OPERATIONS.md](OPERATIONS.md)를 보세요.

핵심 원칙 세 가지입니다.

| 원칙 | 이유 |
|---|---|
| **배포 로직은 워크플로에 두지 않는다** | `infra/scripts/deploy.sh` 하나만 유지한다. 손으로 배포할 때와 CD 가 하는 일이 완전히 같아야 장애 때 재현할 수 있다. |
| **장기 액세스 키를 저장하지 않는다** | OIDC 로 잡마다 1시간짜리 임시 자격증명을 받는다. 저장되는 비밀은 역할 ARN 하나뿐이다. |
| **앱은 컨테이너로 배포하지 않는다** | 산출물이 jar 하나이고 서버가 한 대다. systemd 로 충분하다. ([근거](#8-왜-앱-배포에-docker-를-쓰지-않는가)) |

---

## 1. 전체 흐름

```
개발자
  │  git push / PR
  ▼
GitHub  ─────────────────────────────────────────────────────────────┐
  │                                                                  │
  ├─ [PR → main] ci.yml                                              │
  │     ├── backend    JDK21 · gradlew build · 테스트 리포트 업로드   │
  │     ├── frontend   node20 · typecheck · build · Playwright E2E   │
  │     └── terraform  fmt -check · init(-backend=false) · validate  │
  │        ※ 세 잡 모두 AWS 자격증명 없이 돈다                        │
  │                                                                  │
  └─ [push → main / 수동 실행] deploy.yml                             │
        │                                                            │
        │ ① OIDC 토큰 요청 (permissions: id-token: write)             │
        ▼                                                            │
     AWS STS ── sub 조건 검증 ──> 임시 자격증명(1시간)                 │
        │        repo:khyun9807/skala-imlate:environment:production   │
        ▼                                                            │
     infra/scripts/deploy.sh --mode ssm                              │
        │                                                            │
        │ ② gradlew bootJar        →  backend/build/libs/*.jar        │
        │ ③ npm ci && npm run build →  frontend/dist → tar.gz         │
        │ ④ aws s3 cp              →  s3://<버킷>/deploy/<타임스탬프>/ │
        │ ⑤ ssm send-command       →  AWS-RunShellScript              │
        ▼                                                            │
     EC2 (프라이빗, SSH 포트 없음)                                     │
        ├── S3 에서 아티팩트 내려받기                                  │
        ├── /var/www/imlate 동기화        (프론트)                     │
        ├── /opt/imlate/imlate.jar 교체   (백엔드, 이전 jar 은 .bak)   │
        ├── systemctl restart imlate-env → imlate                     │
        └── is-active 확인 (실패 시 journalctl 출력 후 비정상 종료)     │
        │                                                            │
        │ ⑥ curl <HEALTH_URL> 재시도(최대 180초)                       │
        ▼                                                            │
     성공/실패 → 워크플로 종료 코드에 그대로 반영 ─────────────────────┘
```

SSH 를 쓰지 않으므로 **EC2 에 22번 포트를 열 필요가 없고, 러너 IP 를 보안그룹에 넣을 필요도 없습니다.**
전송은 S3, 실행은 SSM 이 담당합니다.

---

## 2. 워크플로 두 개

### 2.1 `.github/workflows/ci.yml`

| 잡 | 하는 일 | 타임아웃 |
|---|---|---|
| `backend` | `actions/setup-java@v4`(temurin 21, gradle 캐시) → `./gradlew --no-daemon build` → 테스트 리포트를 `backend-test-report` 아티팩트로 업로드 | 30분 |
| `frontend` | `actions/setup-node@v4`(node 20, npm 캐시) → `npm ci` → `npm run typecheck` → `npm run build` → `npx playwright install --with-deps chromium` → `npm run test:e2e` → `playwright-report` 업로드 | 30분 |
| `terraform` | `hashicorp/setup-terraform@v3` → `fmt -check -recursive` → `init -backend=false` → `validate` → `modules/*` 단독 validate | 20분 |

- 같은 브랜치에 새 커밋이 오면 이전 실행은 취소됩니다(`concurrency.cancel-in-progress: true`).
- `permissions: contents: read` 로 토큰 권한을 최소화했습니다. CI 는 아무것도 쓰지 않습니다.
- E2E 는 백엔드·DB 없이 돕니다. `frontend/tests/helpers/mockApi.ts` 가 `/api/v1/**` 를 전부 목킹하고,
  `playwright.config.ts` 의 `webServer` 가 vite dev 서버를 띄웁니다.
- `terraform` 잡은 **자격증명을 요구하지 않습니다.** `-backend=false` 로 init 하므로 원격 상태도,
  AWS API 도 건드리지 않습니다. 루트에서 호출되지 않는 모듈(`github-oidc` 등)도
  `modules/*` 루프에서 단독으로 validate 합니다.

### 2.2 `.github/workflows/deploy.yml`

- 트리거: `main` 푸시 + `workflow_dispatch`(수동)
- 수동 실행 입력값

  | 입력 | 기본값 | 의미 |
  |---|---|---|
  | `scope` | `all` | `backend` → `--backend-only`, `frontend` → `--frontend-only` |
  | `skip_config` | `false` | `true` 면 `--no-config` (nginx/systemd 파일 갱신 생략) |

- `concurrency: deploy-production`, `cancel-in-progress: false` — 배포는 겹치지 않게 순서대로 기다립니다.
  (진행 중인 배포를 중간에 죽이면 jar 만 바뀌고 재시작이 안 된 상태로 남을 수 있습니다.)
- `environment: production` — 승인 게이트를 붙일 수 있습니다([6.3](#63-배포-승인-게이트-선택)).
- 실패 처리: `deploy.sh` 는 SSM 실패·헬스체크 실패에 0이 아닌 코드로 종료하므로 잡도 그대로 실패합니다.

---

## 3. 최초 설정 절차

한 번만 하면 됩니다. 순서를 지켜 주세요.

### 3.1 아티팩트용 S3 버킷 준비

`deploy.sh --mode ssm` 은 S3 를 경유합니다. 버킷이 없으면 먼저 만듭니다.

```bash
aws s3 mb s3://imlate-artifacts --region ap-northeast-2
```

이미 `terraform.tfvars` 의 `artifact_bucket_name` 에 넣어 두었다면 그 값을 그대로 씁니다.
(EC2 쪽 읽기 권한은 기존 `modules/iam` 이 이미 부여합니다.)

### 3.2 OIDC 모듈을 루트에 배선

`infra/terraform/main.tf` 맨 아래에 붙입니다.

```hcl
# ---------------------------------------------------------------------
# GitHub Actions OIDC — CI/CD 배포 역할
#   장기 액세스 키 대신 잡마다 발급되는 임시 자격증명을 쓴다.
# ---------------------------------------------------------------------
module "github_oidc" {
  source = "./modules/github-oidc"
  count  = var.enable_github_oidc ? 1 : 0

  # 공통 태그는 provider default_tags 가 붙이므로 tags 는 넘기지 않는다(중복 diff 방지).
  name_prefix = local.name_prefix

  github_owner = var.github_owner
  github_repo  = var.github_repo

  # deploy.yml 이 environment: production 을 쓰므로 environment sub 가 반드시 필요하다.
  allowed_branches     = ["main"]
  allowed_environments = ["production"]

  # OIDC 공급자는 계정당 1개다. 이미 만들어져 있으면 false 로 두고 기존 것을 참조한다.
  create_oidc_provider = var.create_github_oidc_provider

  artifact_bucket_name = var.artifact_bucket_name
  artifact_key_prefix  = "deploy" # deploy.sh 의 --s3-prefix 기본값과 같아야 한다

  # SendCommand 대상을 이 인스턴스 하나로 제한한다.
  target_instance_ids = [module.ec2.instance_id]
}
```

`infra/terraform/variables.tf` 에 추가합니다.

```hcl
# ---------------------------------------------------------------------
# GitHub Actions OIDC
# ---------------------------------------------------------------------
variable "enable_github_oidc" {
  description = "GitHub Actions 배포용 OIDC 역할을 만들지 여부. artifact_bucket_name 이 필요하다."
  type        = bool
  default     = false
}

variable "create_github_oidc_provider" {
  description = "OIDC 공급자를 직접 생성할지 여부. 계정에 이미 있으면 false (EntityAlreadyExists 회피)."
  type        = bool
  default     = true
}

variable "github_owner" {
  description = "GitHub 소유자(사용자/조직)"
  type        = string
  default     = "khyun9807"
}

variable "github_repo" {
  description = "GitHub 리포지터리 이름"
  type        = string
  default     = "skala-imlate"
}
```

`infra/terraform/outputs.tf` 에 추가합니다.

```hcl
# ---------------- CI/CD ----------------
output "github_actions_role_arn" {
  description = "GitHub 리포지터리 Secret AWS_ROLE_ARN 에 넣을 역할 ARN"
  value       = one(module.github_oidc[*].role_arn)
}

output "github_actions_allowed_subjects" {
  description = "AssumeRole 이 허용된 OIDC 토큰 sub 목록(거부 디버깅용)"
  value       = one(module.github_oidc[*].allowed_subjects)
}
```

`terraform.tfvars` 에서 켭니다.

```hcl
enable_github_oidc   = true
artifact_bucket_name = "imlate-artifacts"
```

### 3.3 apply 하고 값 확인

```bash
cd infra/terraform
terraform init      # 새 모듈이 추가되었으므로 한 번 필요합니다
terraform apply

terraform output -raw github_actions_role_arn
# arn:aws:iam::123456789012:role/imlate-prod-github-actions-deploy

terraform output -raw app_instance_id
# i-0abc...
```

### 3.4 GitHub 리포지터리에 값 등록

**클릭 경로**: 리포지터리 → **Settings** → 좌측 **Secrets and variables** → **Actions**

- **Variables** 탭 → `New repository variable` (비민감 값. 로그에 그대로 찍혀도 되는 것들)
- **Secrets** 탭 → `New repository secret` (민감 값. 등록 후 다시 볼 수 없고 로그에서 마스킹됨)

| 이름 | 위치 | 필수 | 값 예시 | 어디서 얻나 |
|---|---|:---:|---|---|
| `AWS_ROLE_ARN` | **Secret** | ✅ | `arn:aws:iam::123456789012:role/imlate-prod-github-actions-deploy` | `terraform output -raw github_actions_role_arn` |
| `AWS_REGION` | Variable | ✅ | `ap-northeast-2` | `terraform.tfvars` 의 `aws_region` |
| `APP_INSTANCE_ID` | Variable | ✅ | `i-0abc1234def567890` | `terraform output -raw app_instance_id` |
| `ARTIFACT_BUCKET` | Variable | ✅ | `imlate-artifacts` | `terraform.tfvars` 의 `artifact_bucket_name` |
| `ARTIFACT_PREFIX` | Variable | — | `deploy` | 비우면 `deploy.sh` 기본값(`deploy`) 사용 |
| `HEALTH_URL` | Variable | 권장 | `https://skala-imlate.com/healthz` | 아래 주의사항 참고 |
| `APP_BASE_URL` | Variable | — | `https://skala-imlate.com` | Actions 화면의 environment 링크 표시용 |

> **역할 ARN 은 사실 민감 정보가 아닙니다.** 그것만으로는 아무나 AssumeRole 할 수 없고,
> 신뢰 정책의 `sub` 조건을 통과해야 하기 때문입니다. 그래도 계정 ID 노출을 줄이려고 Secret 에 둡니다.

> **`HEALTH_URL` 주의**: nginx 는 `/actuator/` 전체를 사설 대역(127.0.0.1, 10/8, 172.16/12, 192.168/16)
> 으로만 허용합니다. GitHub 러너는 공인 IP 라서 `https://도메인/actuator/health` 를 넣으면
> **403 때문에 헬스체크가 반드시 실패합니다.**
>
> 외부에서 두드릴 수 있는 지점은 `/healthz` 입니다(`infra/nginx/` 설정이 `/actuator/health/alb`
> 그룹으로 프록시하며, 응답 본문은 `{"status":"UP"}` 뿐이라 내부 정보가 새지 않습니다).
>
> | 쓰임 | URL |
> |---|---|
> | 백엔드까지 확인 (권장) | `https://skala-imlate.com/healthz` |
> | 백엔드 API 응답까지 확인 | `https://skala-imlate.com/api/v1/registrations/window` |
> | 프론트 정적 파일만 확인 | `https://skala-imlate.com/` |
>
> 백엔드 프로세스 자체는 원격 설치 스크립트가 `systemctl is-active` 로 이미 확인하고,
> 실패하면 `journalctl` 을 출력한 뒤 비정상 종료합니다. 즉 `HEALTH_URL` 은
> "밖에서도 실제로 보이는가"를 확인하는 마지막 관문입니다.

### 3.5 확인

`Actions` 탭 → `Deploy` → `Run workflow` 로 수동 실행해 봅니다.
로그에 `[deploy] SSM 배포 성공` 과 `[deploy] 헬스체크 성공` 이 나오면 끝입니다.

---

## 4. IAM 권한 범위

`infra/terraform/modules/github-oidc` 가 만드는 역할이 가진 권한 전부입니다.

| 액션 | 대상 | 왜 필요한가 |
|---|---|---|
| `s3:PutObject` `s3:GetObject` `s3:AbortMultipartUpload` `s3:ListMultipartUploadParts` | `arn:aws:s3:::<버킷>/deploy/*` | 아티팩트 업로드 |
| `s3:ListBucket` `s3:GetBucketLocation` | `arn:aws:s3:::<버킷>` | `aws s3 cp --recursive` 가 대상 접두어를 확인 |
| `ssm:SendCommand` | `arn:aws:ssm:<리전>::document/AWS-RunShellScript` | 실행 가능한 문서를 이거 하나로 제한 |
| `ssm:SendCommand` | `arn:aws:ec2:<리전>:<계정>:instance/<대상 ID>` | 대상 인스턴스 제한 |
| `ssm:ListCommandInvocations` `ssm:GetCommandInvocation` | `*` | 실행 결과 폴링. 이 두 API 는 IAM 이 리소스 수준 제한을 지원하지 않는다(읽기 전용이고 CommandId 를 알아야 의미가 있다). |

**주지 않는 권한**: `ec2:*`(인스턴스 생성/종료/보안그룹 변경), `iam:*`, RDS·ElastiCache 접근,
terraform 상태 버킷 접근, SSM Parameter Store 읽기. CD 는 배포만 하면 되므로 인프라를 바꿀 수 없습니다.

`SendCommand` 는 **문서와 인스턴스 두 리소스를 모두** 평가합니다. 문서를 `AWS-RunShellScript` 로
못 박아 두면 임의의 AWS 관리 자동화 문서를 실행할 수 없습니다.

인스턴스를 자주 재생성해 ID 고정이 곤란하면 `target_instance_ids` 를 비우고
`target_instance_tags = { Project = "imlate", Env = "prod" }` 를 쓰면 `ssm:resourceTag` 조건으로
대상이 좁혀집니다. 둘 다 비우면 apply 가 precondition 에서 막힙니다.

---

## 5. 신뢰 정책(`sub`)이 어떻게 만들어지나

GitHub 이 발급하는 OIDC 토큰의 `sub` 클레임은 **워크플로가 어떻게 실행됐는지에 따라 형태가 달라집니다.**

| 상황 | 토큰의 `sub` |
|---|---|
| 잡에 `environment:` 가 **있을 때** | `repo:khyun9807/skala-imlate:environment:production` |
| 잡에 `environment:` 가 없고 브랜치 푸시 | `repo:khyun9807/skala-imlate:ref:refs/heads/main` |
| 태그 푸시 | `repo:khyun9807/skala-imlate:ref:refs/tags/v1.0.0` |
| Pull request | `repo:khyun9807/skala-imlate:pull_request` |

**`environment:` 가 선언되면 ref 형태의 sub 는 나오지 않습니다.** 이걸 모르면
"브랜치를 main 으로 제한했는데 왜 거부되지?" 로 한참 헤매게 됩니다.
`deploy.yml` 이 `environment: production` 을 쓰므로 모듈 기본값에
`allowed_environments = ["production"]` 이 들어 있고, 브랜치 sub 도 함께 허용해 둡니다.

허용된 sub 전체 목록은 언제든 확인할 수 있습니다.

```bash
terraform -chdir=infra/terraform output github_actions_allowed_subjects
```

---

## 6. 운영 팁

### 6.1 CI 와 CD 의 관계

`main` 푸시에는 `ci.yml` 과 `deploy.yml` 이 **동시에** 시작합니다. CD 는 CI 통과를 기다리지 않습니다.
`main` 에 직접 푸시하지 않고 **PR 로만 머지**하면 PR 단계에서 이미 CI 가 돌기 때문에 실무상 문제가 없습니다.
`Settings → Branches` 에서 `main` 에 브랜치 보호 규칙을 걸고 CI 잡 3개를 required status check 로
지정하는 것을 권합니다.

굳이 순서를 강제하고 싶다면 `deploy.yml` 의 트리거를 아래로 바꿉니다.

```yaml
on:
  workflow_run:
    workflows: [CI]
    types: [completed]
    branches: [main]
  workflow_dispatch:
```

이때 `jobs.deploy.if: ${{ github.event.workflow_run.conclusion == 'success' }}` 를 함께 넣어야 합니다.
다만 `workflow_run` 은 항상 기본 브랜치의 워크플로 정의로 실행되고 커밋 SHA 를 직접 체크아웃해야 해서
설정이 번거로워집니다. 브랜치 보호 규칙 쪽이 단순합니다.

### 6.2 롤백

`deploy.sh` 는 교체 전 jar 을 `/opt/imlate/imlate.jar.bak` 로 남깁니다. 급할 때는 SSM 세션에서 되돌립니다.

```bash
aws ssm start-session --target "$INSTANCE_ID"
sudo cp /opt/imlate/imlate.jar.bak /opt/imlate/imlate.jar
sudo systemctl restart imlate
```

정석은 **직전 정상 커밋에서 `Deploy` 워크플로를 수동 실행**하는 것입니다.
`Actions → Deploy → Run workflow` 에서 브랜치/태그를 고를 수 있습니다.

### 6.3 배포 승인 게이트 (선택)

배포 전에 사람이 승인하게 하려면:

1. **Settings → Environments → New environment** → 이름 `production`
2. **Required reviewers** 체크 → 승인자 지정 (본인도 가능)
3. 필요하면 **Deployment branches and tags** 를 `Selected branches` → `main` 으로 제한

이렇게 하면 `deploy.yml` 의 `deploy` 잡이 승인 대기 상태로 멈추고, 승인해야 진행합니다.
환경 이름이 `production` 그대로여야 IAM 신뢰 정책의 sub 조건과 맞습니다.

### 6.4 배포 전용 시크릿을 환경에 두기

`AWS_ROLE_ARN` 을 리포지터리 Secret 대신 **environment secret**(`production` 환경)으로 두면
환경 보호 규칙을 통과한 잡만 값을 읽을 수 있습니다. 워크플로의 `${{ secrets.AWS_ROLE_ARN }}` 표현은
그대로 두면 됩니다. 협업자가 늘어나면 이쪽을 권합니다.

---

## 7. 트러블슈팅

### 7.1 `EntityAlreadyExists: Provider with url ... already exists`

계정에 GitHub OIDC 공급자가 이미 있습니다. IAM OIDC 공급자는 **URL 당 계정에 하나뿐**입니다.

```bash
aws iam list-open-id-connect-providers
aws iam get-open-id-connect-provider --open-id-connect-provider-arn <위에서 나온 ARN>
```

`token.actions.githubusercontent.com` 가 이미 있으면 `create_github_oidc_provider = false` 로 두고
다시 apply 합니다. 모듈이 `data "aws_iam_openid_connect_provider"` 로 기존 것을 참조합니다.

이미 만든 것을 terraform 상태로 가져오고 싶다면:

```bash
terraform import 'module.github_oidc[0].aws_iam_openid_connect_provider.github[0]' <ARN>
```

### 7.2 `Not authorized to perform sts:AssumeRoleWithWebIdentity`

신뢰 정책의 `sub` 조건과 실제 토큰의 `sub` 가 다릅니다. 흔한 원인 순서대로:

| 증상 | 원인 | 해결 |
|---|---|---|
| `environment: production` 을 쓰는데 거부 | 신뢰 정책에 `...:environment:production` sub 가 없음 | `allowed_environments = ["production"]` 확인 |
| 다른 브랜치에서 수동 실행했더니 거부 | `allowed_branches` 에 그 브랜치가 없음 | 브랜치 추가하거나 `main` 에서 실행 |
| 리포지터리 이름/오너 오타 | `repo:owner/repo` 불일치 | `github_owner` / `github_repo` 확인 |
| 포크 PR 에서 거부 | 의도된 동작 | 포크에는 배포 권한을 주지 않는다 |

실제 토큰의 sub 를 눈으로 확인하려면 `deploy.yml` 에 임시로 넣어 봅니다(끝나면 지울 것).

```yaml
      # ACTIONS_ID_TOKEN_REQUEST_* 는 permissions.id-token: write 가 있으면 자동으로 주입된다.
      - name: OIDC 클레임 확인 (디버그 전용)
        run: |
          TOKEN="$(curl -sS \
            -H "Authorization: bearer ${ACTIONS_ID_TOKEN_REQUEST_TOKEN}" \
            "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=sts.amazonaws.com" | jq -r '.value')"
          jq -R 'split(".") | .[1] | @base64d | fromjson | {sub, aud, repository}' <<< "$TOKEN"
```

토큰 자체는 절대 출력하지 마세요(유효한 자격증명입니다). 위 명령은 페이로드의 클레임 세 개만 찍습니다.

허용된 값과 비교합니다.

```bash
terraform -chdir=infra/terraform output github_actions_allowed_subjects
```

### 7.3 `Credentials could not be loaded` / `Unable to get OIDC token`

워크플로에 `permissions: id-token: write` 가 없습니다. `deploy.yml` 최상단의 `permissions` 블록을
확인하세요. 잡 단위로 `permissions` 를 다시 선언하면 상위 설정을 덮어쓰므로 주의합니다.

### 7.4 `InvalidInstanceId` / SSM 대상 인스턴스를 못 찾음

`ssm send-command` 가 인스턴스를 찾지 못할 때 확인 순서입니다.

```bash
# 1) SSM 이 인스턴스를 관리 중인가 (Online 이어야 한다)
aws ssm describe-instance-information \
  --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
  --query 'InstanceInformationList[0].[InstanceId,PingStatus,AgentVersion]' --output table
```

| 확인 | 아니라면 |
|---|---|
| 인스턴스가 `running` 인가 | 켜고 다시 시도 |
| `PingStatus = Online` 인가 | ssm-agent 가 죽었거나 부팅 직후. 1~2분 기다리거나 인스턴스 재시작 |
| 인스턴스 프로파일에 `AmazonSSMManagedInstanceCore` 가 붙어 있나 | `modules/iam` 이 붙입니다. `terraform apply` 재실행 |
| 프라이빗 서브넷인데 SSM 엔드포인트가 있나 | `ssm` `ssmmessages` `ec2messages` VPC 엔드포인트 필요 |
| `APP_INSTANCE_ID` 변수가 최신인가 | 인스턴스를 재생성하면 ID 가 바뀝니다. **Variables 와 `target_instance_ids` 를 둘 다** 갱신 |

마지막 항목이 가장 흔합니다. `terraform apply` 로 EC2 가 교체됐다면
`terraform output -raw app_instance_id` 를 다시 읽어 GitHub Variables 를 고치고,
OIDC 역할도 다시 apply 해야 정책의 인스턴스 ARN 이 갱신됩니다.

### 7.5 `AccessDenied` — S3 업로드 실패

`ARTIFACT_PREFIX` 변수와 모듈의 `artifact_key_prefix` 가 어긋났을 가능성이 큽니다.
정책은 `s3://<버킷>/<접두어>/*` 에만 쓰기를 허용합니다. 둘을 같은 값으로 맞추거나
`artifact_key_prefix = ""` 로 버킷 전체를 허용하세요.

### 7.6 헬스체크만 실패하고 서비스는 살아 있음

거의 항상 `HEALTH_URL` 문제입니다. [3.4](#34-github-리포지터리에-값-등록)의 주의사항을 보세요.
`/actuator/*` 는 공인 IP 에서 403 이므로 `/healthz` 를 써야 합니다.
TLS 인증서 발급 전이라면 `https://` 대신 `http://` 로 두어야 통과합니다.
실제 서비스 상태는 이렇게 확인합니다.

```bash
aws ssm start-session --target "$INSTANCE_ID"
curl -fsS http://127.0.0.1:8080/actuator/health
sudo journalctl -u imlate -n 100 --no-pager
```

### 7.7 CI 의 `terraform fmt -check` 실패

포맷 문제입니다. 로컬에서 고치고 다시 푸시하면 됩니다.

```bash
"C:/Users/kkh98/Terraform/terraform.exe" -chdir=infra/terraform fmt -recursive
```

CI 는 `terraform_version: '1.15.8'` 로 고정되어 있습니다. 로컬 버전이 다르면 포맷 결과가 갈릴 수 있으니
`.github/workflows/ci.yml` 의 값을 로컬과 맞춰 주세요.

### 7.8 Playwright 잡이 러너에서만 실패

`npx playwright install --with-deps chromium` 이 러너의 시스템 라이브러리까지 설치하므로
보통은 로컬과 같게 동작합니다. 그래도 다르면 `playwright-report` 아티팩트를 내려받아
스크린샷·trace 를 보세요. `Actions` 실행 화면 하단 **Artifacts** 에 있습니다.

---

## 8. 왜 앱 배포에 Docker 를 쓰지 않는가

이 서비스의 배포 단위는 **EC2 한 대에 올라가는 실행 가능한 jar 파일 하나**입니다. 컨테이너가 해결하는
문제 — 언어 런타임 격리, 여러 서비스의 의존성 충돌, 수평 확장과 무중단 롤링 배포, 이기종 서버 간
환경 재현 — 이 여기엔 하나도 없습니다. 런타임은 Gradle 툴체인이 Java 21 로 고정하고, 서버는 한 대이며,
환경은 SSM Parameter Store → `/etc/imlate/imlate.env` 로 이미 일원화되어 있고, 프로세스 수명주기는
systemd 가 `Restart=always` 와 `journalctl` 로 관리합니다. 컨테이너화하면 여기에 ECR 리포지터리와
이미지 스토리지 비용, 매 배포마다 수백 MB 이미지를 빌드·푸시·풀 하는 시간(현재는 20~30MB jar 하나를
S3 로 올립니다), 그리고 Dockerfile·태그 전략·이미지 정리 정책이라는 새 운영 부담이 더해집니다.
얻는 것 없이 파이프라인만 느려지고 복잡해지는 교환입니다. 나중에 서버가 여러 대가 되거나 무중단 배포가
필요해지면 그때 도입해도 늦지 않습니다 — jar 을 만드는 빌드 단계는 그대로 재사용됩니다.

**단, 로컬 개발용 `docker-compose.yml`(MySQL 8 / Redis 7)은 계속 씁니다.** 이건 정반대의 경우입니다.
개발자가 자기 PC 에 MySQL 과 Redis 를 직접 설치·설정하지 않게 해 주는 것이 컨테이너가 가장 잘하는 일이고,
운영 서버에는 아무런 영향이 없기 때문입니다. 정리하면 **로컬 의존성은 컨테이너로, 운영 배포는 jar + systemd 로** 갑니다.

---

## 9. 관련 문서

| 문서 | 내용 |
|---|---|
| [DEPLOYMENT.md](DEPLOYMENT.md) | Terraform 변수, 최초 인프라 구성, `deploy.sh` 옵션 전체 |
| [OPERATIONS.md](OPERATIONS.md) | 장애 대응, 로그 확인, 정기 점검 |
| [LOCAL-TESTING.md](LOCAL-TESTING.md) | 로컬 docker-compose 로 전 구간 검증 |
| [SPEC.md](SPEC.md) | 기능 계약서 |
| [../infra/terraform/README.md](../infra/terraform/README.md) | 모듈별 상세 |
