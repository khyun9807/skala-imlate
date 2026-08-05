# =====================================================================
# imlate — 루트 모듈
#
#   구성도
#
#     인터넷
#       │  (WAF: IP 당 5분 2000요청 차단)
#       ▼
#     ALB (public subnet × 2AZ, 80/443)
#       │  target group :8080 (또는 :80 nginx)
#       ▼
#     EC2 app (private subnet, Amazon Linux 2023 + corretto 21)
#       ├─ RDS MySQL 8       (private subnet, 암호화, utf8mb4/KST)
#       ├─ ElastiCache Redis (private subnet, TLS + AUTH) — WAL / rate limit / stats
#       ├─ SSM Parameter Store /imlate/{env}/*  — 모든 시크릿
#       └─ SES v2            — 사감 메일 발송
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

  # ---- ALB / 접속 URL ----
  alb_dns_name = one(module.alb[*].dns_name)
  alb_url      = one(module.alb[*].url)

  # 사용자가 도메인을 지정했으면 그것을, 아니면 ALB DNS 이름을 base URL 로 쓴다.
  resolved_base_url = length(trimspace(var.app_base_url)) > 0 ? var.app_base_url : (
    var.enable_alb ? local.alb_url : "http://127.0.0.1:${var.app_port}"
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
  }
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

  name_prefix          = local.name_prefix
  vpc_id               = module.network.vpc_id
  app_port             = var.app_port
  web_port             = var.web_port
  alb_ingress_cidrs    = var.alb_ingress_cidrs
  enable_https_ingress = true
  ssh_allowed_cidrs    = var.ssh_allowed_cidrs
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

  associate_public_ip  = var.public_app
  associate_elastic_ip = var.public_app && var.associate_elastic_ip

  ssm_path_prefix = local.ssm_path_prefix
  spring_profile  = var.spring_profile
  app_port        = var.app_port
  timezone        = var.timezone
  jvm_opts        = var.jvm_opts

  install_nginx            = var.install_nginx
  install_cloudwatch_agent = var.install_cloudwatch_agent

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
