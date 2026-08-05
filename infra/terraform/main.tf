# =====================================================================
# imlate — 루트 모듈
#
#   구성도 (기본값 기준 — ALB / NAT / WAF 없음)
#
#     인터넷
#       │  Route53 A 레코드: skala-imlate.com → EIP (고정)
#       ▼
#     EC2 app (public subnet, Amazon Linux 2023 + corretto 21)
#       │  nginx 80/443 (Let's Encrypt TLS)
#       │    ├ / , /lookup     → /var/www/imlate 정적 서빙(SPA 폴백)
#       │    └ /api, /actuator → 127.0.0.1:8080 Spring
#       │
#       ├─ RDS MySQL 8       (private subnet, 암호화, utf8mb4/KST)
#       ├─ ElastiCache Redis (private subnet, TLS + AUTH) — WAL / rate limit / stats
#       ├─ SSM Parameter Store /imlate/{env}/*  — 모든 시크릿
#       └─ SES v2            — 사감 메일 발송
#
#     아웃바운드도 같은 EIP 로 나간다 → 알리고 화이트리스트에 등록할 IP 가 이것 하나다.
#
#   ALB / NAT Gateway / WAF 는 200명 규모에 오버스펙이라 기본으로 끈다.
#   변수(enable_alb / enable_nat_gateway / enable_waf)는 남겨 두었으므로
#   필요해지면 true 로 바꾸기만 하면 된다(modules/alb, modules/waf 도 그대로 있다).
#
#   시크릿은 tfvars → SSM SecureString → EC2 user_data → /etc/imlate/imlate.env
#   경로로만 흐른다. user_data 자체에는 민감값을 넣지 않는다(평문 노출).
# =====================================================================

locals {
  name_prefix     = "${var.project}-${var.environment}"
  ssm_path_prefix = "/${var.project}/${var.environment}"

  common_tags = merge({
    Project   = var.project
    Env       = var.environment
    ManagedBy = "terraform"
  }, var.additional_tags)

  # ---- ALB ----
  alb_dns_name = one(module.alb[*].dns_name)
  alb_url      = one(module.alb[*].url)

  # ---- 도메인 / DNS / TLS ----
  domain_name      = trimspace(var.domain_name)
  domain_specified = length(local.domain_name) > 0

  # route53_zone_name 이 비면 도메인의 상위 2개 라벨을 영역 이름으로 추정한다.
  #   skala-imlate.com          → skala-imlate.com
  #   imlate.skala-imlate.com   → skala-imlate.com
  domain_labels = split(".", local.domain_name)
  inferred_zone_name = local.domain_specified ? join(".", slice(
    local.domain_labels,
    max(length(local.domain_labels) - 2, 0),
    length(local.domain_labels),
  )) : ""
  route53_zone_name = length(trimspace(var.route53_zone_name)) > 0 ? trimspace(var.route53_zone_name) : local.inferred_zone_name

  # A 레코드 + 인증서 SAN 대상 도메인 목록(첫 번째가 primary)
  dns_names = local.domain_specified ? distinct(concat(
    [local.domain_name],
    [for alias in var.domain_aliases : trimspace(alias) if trimspace(alias) != ""],
  )) : []

  # A 레코드는 EIP 를 가리키므로 퍼블릭 배치일 때만 만든다.
  dns_requested = var.create_dns_record && local.domain_specified
  dns_enabled   = local.dns_requested && local.eip_enabled

  # TLS 는 도메인이 있어야만 의미가 있다(Let's Encrypt 는 IP 에 발급하지 않는다).
  tls_enabled = var.enable_tls && local.domain_specified

  # ---- 고정 공인 IP ----
  eip_enabled = var.public_app && var.associate_elastic_ip

  # ---- 접속 URL ----
  #   우선순위: app_base_url → 도메인 → ALB → EIP → 로컬
  #   ※ 여기서 module.ec2 출력을 참조하면 안 된다.
  #     module.ssm 이 이 값을 쓰고 module.ec2 는 module.ssm 에 depends_on 이므로 순환이 생긴다.
  #     그래서 EIP 도 ec2 모듈이 아니라 루트에서 따로 만든다(아래 aws_eip.app).
  resolved_base_url = length(trimspace(var.app_base_url)) > 0 ? trimspace(var.app_base_url) : (
    local.domain_specified ? "${local.tls_enabled ? "https" : "http"}://${local.domain_name}" : (
      var.enable_alb ? local.alb_url : (
        local.eip_enabled ? "http://${aws_eip.app[0].public_ip}" : "http://127.0.0.1:${var.app_port}"
      )
    )
  )

  resolved_allowed_origin = length(trimspace(var.allowed_origin)) > 0 ? var.allowed_origin : local.resolved_base_url

  # ---- SES 발신 주소 ----
  ses_managed           = var.enable_ses && length(trimspace(var.ses_identity)) > 0
  ses_identity_is_email = can(regex("@", var.ses_identity))

  resolved_ses_from = length(trimspace(var.ses_from_address)) > 0 ? var.ses_from_address : (
    local.ses_identity_is_email ? var.ses_identity : (
      length(trimspace(var.ses_identity)) > 0 ? "no-reply@${var.ses_identity}" : ""
    )
  )

  ses_configuration_set_name = "${local.name_prefix}-ses"

  # ---- SES 샌드박스 수신자 ----
  # 샌드박스에서는 수신자도 검증된 아이덴티티여야 메일이 전달된다.
  # 프로덕션 액세스 없이 운영하려면 사감 수신 주소를 아이덴티티로 등록해야 한다.
  # 발신 아이덴티티와 중복되는 주소는 제외한다(이미 검증되어 있으므로).
  ses_recipient_candidates = concat(
    var.ses_verify_supervisor_emails ? [var.supervisor1_email, var.supervisor2_email] : [],
    var.ses_additional_verified_emails,
  )
  ses_recipient_identities = sort(distinct([
    for email in local.ses_recipient_candidates : lower(trimspace(email))
    if trimspace(email) != "" && lower(trimspace(email)) != lower(trimspace(var.ses_identity))
  ]))

  # IAM 정책 리소스 목록. 리스트 "길이"가 plan 단계에서 확정되도록 변수만으로 분기한다
  # (compact() 로 null 을 걸러내면 길이가 unknown 이 되어 정책 문서가 통째로 apply 로 미뤄진다).
  ses_identity_arns   = local.ses_managed ? [module.ses.identity_arn] : []
  ses_config_set_arns = local.ses_managed && var.enable_ses_configuration_set ? [module.ses.configuration_set_arn] : []
  ses_resource_arns   = concat(local.ses_identity_arns, local.ses_config_set_arns)

  # ---- 자동 생성 시크릿 ----
  lookup_token_secret = coalesce(var.lookup_token_secret, random_password.lookup_token_secret.result)
  admin_api_key       = coalesce(var.admin_api_key, random_password.admin_api_key.result)

  # ---- EC2 배치 위치 ----
  app_subnet_id = var.public_app ? module.network.public_subnet_ids[0] : module.network.private_subnet_ids[0]

  # ---- SSM 에 값이 없을 때 쓰는 비민감 기본 환경변수 ----
  default_env = {
    SPRING_PROFILES_ACTIVE          = var.spring_profile
    TZ                              = var.timezone
    IMLATE_TIMEZONE                 = var.timezone
    IMLATE_SERVER_PORT              = tostring(var.app_port)
    IMLATE_FORWARD_HEADERS_STRATEGY = "framework"
    IMLATE_SMS_PROVIDER             = "aligo"
    IMLATE_EMAIL_PROVIDER           = "ses"
    IMLATE_NOTIFICATION_ENABLED     = "true"
    IMLATE_RATE_LIMIT_ENABLED       = "true"
    IMLATE_STATS_ENABLED            = "true"
    # 아래 두 값은 SSM 파라미터가 없을 수도 있으므로 빈 문자열 기본값이 반드시 필요하다.
    IMLATE_REDIS_PASSWORD        = ""
    IMLATE_SES_CONFIGURATION_SET = ""
  }
}

# ---------------------------------------------------------------------
# 설정 가드레일 (plan 단계에서 잘못된 조합을 걸러낸다)
# ---------------------------------------------------------------------
resource "terraform_data" "guardrails" {
  input = local.name_prefix

  lifecycle {
    precondition {
      condition     = !var.redis_auth_enabled || var.redis_transit_encryption_enabled
      error_message = "redis_auth_enabled = true 이면 redis_transit_encryption_enabled 도 true 여야 합니다(ElastiCache 제약)."
    }

    precondition {
      condition     = length(trimspace(local.resolved_ses_from)) > 0
      error_message = "ses_identity 또는 ses_from_address 중 하나는 반드시 지정해야 합니다(메일 발신 주소)."
    }

    precondition {
      condition     = var.enable_alb || var.public_app
      error_message = "enable_alb = false 로 두려면 public_app = true 여야 외부에서 접근할 수 있습니다."
    }

    precondition {
      # 프라이빗 서브넷에 앱을 두면 SSM/SES/패키지 저장소 접근에 NAT 가 필요하다.
      condition     = var.public_app || var.enable_nat_gateway
      error_message = "public_app = false(프라이빗 배치)이면 enable_nat_gateway = true 여야 SSM/SES 에 접근할 수 있습니다."
    }

    precondition {
      # A 레코드는 EIP 를 가리킨다. ALB 를 쓴다면 A(alias) 레코드를 따로 만들어야 한다.
      condition     = !local.dns_requested || local.eip_enabled
      error_message = "domain_name 을 지정해 A 레코드를 만들려면 public_app = true 이고 associate_elastic_ip = true 여야 합니다(레코드가 EIP 를 가리킵니다). ALB 를 쓴다면 create_dns_record = false 로 두고 alb_dns_name / alb_zone_id 로 alias 레코드를 직접 만드세요."
    }

    precondition {
      # 인증서를 받으려면 80 포트가 인터넷에 열려 있어야 한다(HTTP-01 챌린지).
      condition     = !local.tls_enabled || var.enable_alb || length(var.direct_ingress_cidrs) > 0
      error_message = "enable_tls = true 로 Let's Encrypt 인증서를 받으려면 direct_ingress_cidrs 가 비어 있으면 안 됩니다(HTTP-01 챌린지가 80 포트로 들어옵니다)."
    }
  }
}

# ---------------------------------------------------------------------
# 고정 공인 IP (피드백 3번의 핵심)
#
#   EIP 를 **일부러 ec2 모듈 밖에** 둔다. 이유가 두 가지다.
#
#   1) 주소 보존 — aws_eip 에 instance 를 직접 물리면 EIP 리소스가 인스턴스에
#      의존하게 된다. 여기서는 할당(aws_eip)과 연결(aws_eip_association)을 분리했으므로
#      `terraform apply -replace=module.ec2.aws_instance.app` 으로 인스턴스를 갈아엎어도
#      EIP 는 그대로 남고 연결만 다시 맺힌다. 즉 DNS 와 알리고 화이트리스트를 건드릴 일이 없다.
#
#   2) 순환 참조 회피 — module.ec2 는 module.ssm 에 depends_on 이고, module.ssm 은
#      resolved_base_url(=EIP 주소)을 필요로 한다. EIP 가 ec2 모듈 안에 있으면 사이클이 된다.
#
#   ※ prevent_destroy 는 걸 수 없다(변수로 끌 수 없어 destroy 자체가 막힌다).
#     대신 이 주소가 바뀌면 반드시 함께 갱신해야 하는 것을 적어 둔다.
#       - Route53 A 레코드 (여기서 자동 갱신되지만 TTL 만큼 전파 지연)
#       - 알리고 발신 IP 화이트리스트 (수동)
#     terraform destroy 는 EIP 를 실제로 반납한다. 주소를 지키려면 destroy 하지 말 것.
# ---------------------------------------------------------------------
resource "aws_eip" "app" {
  count = local.eip_enabled ? 1 : 0

  domain = "vpc"

  tags = {
    Name = "${local.name_prefix}-app-eip"
  }

  lifecycle {
    # 재생성이 필요할 때 새 주소를 먼저 확보한 뒤 옛 주소를 반납한다.
    create_before_destroy = true
  }
}

resource "aws_eip_association" "app" {
  count = local.eip_enabled ? 1 : 0

  allocation_id = aws_eip.app[0].id
  instance_id   = module.ec2.instance_id
}

# ---------------------------------------------------------------------
# 자동 생성 시크릿 (변수로 주지 않은 경우에만 사용된다)
# ---------------------------------------------------------------------
resource "random_password" "lookup_token_secret" {
  length  = 64
  special = false
}

resource "random_password" "admin_api_key" {
  length  = 48
  special = false
}

# ---------------------------------------------------------------------
# 네트워크
# ---------------------------------------------------------------------
module "network" {
  source = "./modules/network"

  name_prefix        = local.name_prefix
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
  enable_nat_gateway = var.enable_nat_gateway
  single_nat_gateway = var.single_nat_gateway
}

# ---------------------------------------------------------------------
# 보안 그룹
# ---------------------------------------------------------------------
module "security" {
  source = "./modules/security"

  name_prefix  = local.name_prefix
  vpc_id       = module.network.vpc_id
  app_port     = var.app_port
  web_port     = var.web_port
  web_tls_port = var.web_tls_port

  # ALB 경유(A) 와 직접 노출(B) 중 정확히 하나만 켠다.
  # 둘 다 켜면 ALB 를 우회해 EC2 로 직접 붙을 수 있어 WAF/접근로그가 무력화된다.
  enable_alb_ingress   = var.enable_alb
  alb_ingress_cidrs    = var.alb_ingress_cidrs
  enable_https_ingress = true

  enable_direct_ingress = !var.enable_alb
  direct_ingress_cidrs  = var.direct_ingress_cidrs

  ssh_allowed_cidrs = var.ssh_allowed_cidrs
}

# ---------------------------------------------------------------------
# Route53 — 고정 URL (피드백 3번)
#
#   도메인은 Route53 콘솔에서 직접 구매한다. 구매하면 퍼블릭 호스팅 영역이
#   자동으로 만들어지므로 여기서는 **조회만** 하고 A 레코드만 얹는다.
#   (영역을 Terraform 이 새로 만들면 NS 가 달라져 도메인이 통째로 죽는다.)
#
#   domain_name 이 비어 있으면 data / resource 모두 count = 0 이라 아무것도 조회하지 않는다.
# ---------------------------------------------------------------------
data "aws_route53_zone" "this" {
  count = local.dns_enabled ? 1 : 0

  name         = "${local.route53_zone_name}."
  private_zone = false
}

resource "aws_route53_record" "app" {
  for_each = local.dns_enabled ? toset(local.dns_names) : toset([])

  zone_id = data.aws_route53_zone.this[0].zone_id
  name    = each.value
  type    = "A"
  ttl     = var.dns_record_ttl
  records = [aws_eip.app[0].public_ip]
}

# ---------------------------------------------------------------------
# SES (메일 발송 아이덴티티)
# ---------------------------------------------------------------------
module "ses" {
  source = "./modules/ses"

  enabled                  = var.enable_ses
  identity                 = var.ses_identity
  recipient_identities     = local.ses_recipient_identities
  enable_configuration_set = var.enable_ses_configuration_set
  configuration_set_name   = local.ses_configuration_set_name
}

# ---------------------------------------------------------------------
# IAM (EC2 역할 / 인스턴스 프로파일)
# ---------------------------------------------------------------------
module "iam" {
  source = "./modules/iam"

  name_prefix     = local.name_prefix
  ssm_path_prefix = local.ssm_path_prefix

  ses_resource_arns = local.ses_resource_arns

  # deploy.sh --mode ssm 이 S3 를 경유하므로 읽기 권한을 준다.
  artifact_bucket_names = var.artifact_bucket_name != "" ? [var.artifact_bucket_name] : []
}

# ---------------------------------------------------------------------
# RDS (MySQL 8)
# ---------------------------------------------------------------------
module "rds" {
  source = "./modules/rds"

  identifier         = "${local.name_prefix}-mysql"
  name_prefix        = local.name_prefix
  subnet_ids         = module.network.private_subnet_ids
  security_group_ids = [module.security.db_security_group_id]

  engine_version        = var.db_engine_version
  instance_class        = var.db_instance_class
  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  multi_az              = var.db_multi_az
  backup_retention_days = var.db_backup_retention_days
  deletion_protection   = var.db_deletion_protection
  skip_final_snapshot   = var.db_skip_final_snapshot
  apply_immediately     = var.db_apply_immediately
  db_timezone           = var.timezone
}

# ---------------------------------------------------------------------
# ElastiCache (Redis 7)
# ---------------------------------------------------------------------
module "elasticache" {
  source = "./modules/elasticache"

  replication_group_id = "${local.name_prefix}-redis"
  name_prefix          = local.name_prefix
  subnet_ids           = module.network.private_subnet_ids
  security_group_ids   = [module.security.redis_security_group_id]

  engine_version     = var.redis_engine_version
  node_type          = var.redis_node_type
  num_cache_clusters = var.redis_num_cache_clusters
  multi_az_enabled   = var.redis_multi_az_enabled

  at_rest_encryption_enabled = var.redis_at_rest_encryption_enabled
  transit_encryption_enabled = var.redis_transit_encryption_enabled
  auth_enabled               = var.redis_auth_enabled
  auth_token                 = var.redis_auth_token
}

# ---------------------------------------------------------------------
# ALB
# ---------------------------------------------------------------------
module "alb" {
  source = "./modules/alb"
  count  = var.enable_alb ? 1 : 0

  name_prefix        = local.name_prefix
  vpc_id             = module.network.vpc_id
  subnet_ids         = module.network.public_subnet_ids
  security_group_ids = [module.security.alb_security_group_id]

  internal               = var.alb_internal
  target_port            = var.alb_target_port
  health_check_path      = var.alb_health_check_path
  certificate_arn        = var.acm_certificate_arn
  redirect_http_to_https = var.alb_redirect_http_to_https
  deletion_protection    = var.alb_deletion_protection
}

# ---------------------------------------------------------------------
# WAF (ALB 앞단 1단 방어 — 애플리케이션 Redis 리미터와 2단 구성)
# ---------------------------------------------------------------------
module "waf" {
  source = "./modules/waf"
  count  = var.enable_alb && var.enable_waf ? 1 : 0

  name_prefix   = local.name_prefix
  metric_prefix = replace(local.name_prefix, "-", "")
  alb_arn       = module.alb[0].arn

  rate_limit_per_5min      = var.waf_rate_limit_per_5min
  managed_rules_count_only = var.waf_managed_rules_count_only
}

# ---------------------------------------------------------------------
# SSM Parameter Store (애플리케이션 시크릿 — R10)
# ---------------------------------------------------------------------
module "ssm" {
  source = "./modules/ssm"

  path_prefix = local.ssm_path_prefix

  db_url      = module.rds.jdbc_url
  db_username = module.rds.username
  db_password = module.rds.password

  redis_host        = module.elasticache.primary_endpoint_address
  redis_port        = tostring(module.elasticache.port)
  redis_ssl_enabled = tostring(var.redis_transit_encryption_enabled)

  create_redis_password_parameter = var.redis_auth_enabled
  redis_password                  = module.elasticache.auth_token

  lookup_base_url     = local.resolved_base_url
  lookup_token_secret = local.lookup_token_secret
  admin_api_key       = local.admin_api_key
  web_allowed_origin  = local.resolved_allowed_origin

  aligo_api_key = var.aligo_api_key
  aligo_user_id = var.aligo_user_id
  aligo_sender  = var.aligo_sender

  ses_region    = var.aws_region
  ses_from      = local.resolved_ses_from
  ses_from_name = var.ses_from_name

  create_ses_configuration_set_parameter = var.enable_ses_configuration_set
  ses_configuration_set                  = local.ses_configuration_set_name

  supervisor1_name  = var.supervisor1_name
  supervisor1_phone = var.supervisor1_phone
  supervisor1_email = var.supervisor1_email
  supervisor2_name  = var.supervisor2_name
  supervisor2_phone = var.supervisor2_phone
  supervisor2_email = var.supervisor2_email
}

# ---------------------------------------------------------------------
# 애플리케이션 EC2
#   SSM 파라미터가 먼저 있어야 부팅 시 환경변수를 만들 수 있다.
# ---------------------------------------------------------------------
module "ec2" {
  source = "./modules/ec2"

  name_prefix        = local.name_prefix
  aws_region         = var.aws_region
  subnet_id          = local.app_subnet_id
  security_group_ids = [module.security.app_security_group_id]

  iam_instance_profile_name = module.iam.instance_profile_name

  instance_type    = var.instance_type
  architecture     = var.instance_architecture
  ami_id           = var.ami_id
  key_name         = var.key_pair_name
  root_volume_size = var.root_volume_size

  # EIP 는 루트의 aws_eip.app / aws_eip_association.app 이 담당한다(위 주석 참고).
  associate_public_ip = var.public_app

  ssm_path_prefix = local.ssm_path_prefix
  spring_profile  = var.spring_profile
  app_port        = var.app_port
  timezone        = var.timezone
  jvm_opts        = var.jvm_opts

  install_nginx            = var.install_nginx
  install_cloudwatch_agent = var.install_cloudwatch_agent

  # ---- TLS (Let's Encrypt) ----
  enable_tls        = local.tls_enabled
  tls_domains       = local.dns_names
  tls_contact_email = var.tls_contact_email

  # ---- 부팅 시 심을 설정 파일 (저장소 파일이 원본) ----
  #   deploy.sh 가 배포하는 파일과 완전히 같은 내용을 user_data 에도 넣는다.
  #   덕분에 "부팅 직후"와 "배포 후" nginx 구성이 절대 어긋나지 않는다.
  nginx_conf       = file("${path.root}/../nginx/imlate.conf")
  nginx_app_conf   = file("${path.root}/../nginx/imlate-app.conf")
  tls_script       = file("${path.root}/../scripts/imlate-tls.sh")
  tls_sync_script  = file("${path.root}/../scripts/imlate-tls-sync.sh")
  tls_service_unit = file("${path.root}/../systemd/imlate-tls.service")
  tls_timer_unit   = file("${path.root}/../systemd/imlate-tls.timer")

  default_env = local.default_env

  depends_on = [
    module.ssm,
    module.network,
  ]
}

# ---------------------------------------------------------------------
# ALB ↔ EC2 연결
# ---------------------------------------------------------------------
resource "aws_lb_target_group_attachment" "app" {
  count = var.enable_alb ? 1 : 0

  target_group_arn = module.alb[0].target_group_arn
  target_id        = module.ec2.instance_id
  port             = var.alb_target_port
}

# ---------------------------------------------------------------------
# GitHub Actions 배포 역할 (OIDC)
#
#   GitHub Actions 가 장기 액세스 키 없이 AWS 에 접근하도록 OIDC 신뢰 관계를 만든다.
#   sub 조건을 리포지터리·브랜치로 제한하므로 다른 리포에서는 이 역할을 맡을 수 없다.
#
#   enable_github_oidc = false 로 두면 아무것도 만들지 않는다(CI/CD 를 안 쓰는 환경).
#   OIDC 역할만 먼저 만들고 싶으면:
#     terraform apply -target=module.github_oidc
# ---------------------------------------------------------------------
module "github_oidc" {
  source = "./modules/github-oidc"
  count  = var.enable_github_oidc ? 1 : 0

  name_prefix          = local.name_prefix
  create_oidc_provider = var.github_oidc_create_provider
  github_owner         = var.github_owner
  github_repo          = var.github_repo
  allowed_branches     = var.github_allowed_branches

  # 배포 아티팩트 버킷과 배포 대상 인스턴스로 권한을 좁힌다.
  artifact_bucket_name = var.artifact_bucket_name
  target_instance_ids  = var.artifact_bucket_name != "" ? [module.ec2.instance_id] : []
  target_instance_tags = {
    Project = var.project
    Env     = var.environment
  }
}
