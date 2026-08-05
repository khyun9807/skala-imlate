# =====================================================================
# ssm 모듈 — 애플리케이션 시크릿 (SSM Parameter Store, SecureString)
#
#   경로 규칙: /imlate/{env}/{환경변수명}
#     예) /imlate/prod/IMLATE_DB_URL
#
#   파라미터 이름의 마지막 세그먼트를 **그대로 환경변수 이름으로 사용**한다.
#   EC2 user_data 의 imlate-load-env.sh 가
#     aws ssm get-parameters-by-path --path /imlate/prod --recursive --with-decryption
#   결과를 읽어 /etc/imlate/imlate.env 를 만든다.
#   → 값을 바꾸고 싶으면 SSM 파라미터만 고친 뒤 서비스를 재시작하면 된다(R10).
#
#   ※ for_each 에는 절대 민감값에서 파생된 표현식을 쓰지 않는다.
#     (Terraform 은 sensitive 값을 for_each 인자로 허용하지 않음)
#     → 이름 목록은 non-sensitive 리터럴 맵(parameter_descriptions)에서만 뽑는다.
# =====================================================================

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

locals {
  path_prefix = trimsuffix(var.path_prefix, "/")

  # ---- 이름 → 설명 (전부 리터럴, 민감하지 않음) ----
  parameter_descriptions = {
    IMLATE_DB_URL               = "MySQL JDBC URL (spring.datasource.url)"
    IMLATE_DB_USERNAME          = "MySQL 사용자"
    IMLATE_DB_PASSWORD          = "MySQL 비밀번호"
    IMLATE_REDIS_HOST           = "ElastiCache Redis 호스트"
    IMLATE_REDIS_PORT           = "ElastiCache Redis 포트"
    IMLATE_REDIS_SSL_ENABLED    = "Redis TLS 사용 여부"
    IMLATE_LOOKUP_BASE_URL      = "사감 조회 페이지 base URL"
    IMLATE_LOOKUP_TOKEN_SECRET  = "조회 링크 HMAC 서명 시크릿"
    IMLATE_ADMIN_API_KEY        = "관리 API 키 (X-Admin-Key)"
    IMLATE_WEB_ALLOWED_ORIGIN_1 = "CORS 허용 오리진"
    IMLATE_ALIGO_API_KEY        = "알리고 문자 API 키"
    IMLATE_ALIGO_USER_ID        = "알리고 사용자 ID"
    IMLATE_ALIGO_SENDER         = "알리고 발신번호"
    IMLATE_SES_REGION           = "SES 리전"
    IMLATE_SES_FROM             = "SES 발신 주소"
    IMLATE_SES_FROM_NAME        = "SES 발신자 표시 이름"
    IMLATE_SUPERVISOR1_NAME     = "사감 1 이름"
    IMLATE_SUPERVISOR1_PHONE    = "사감 1 휴대폰"
    IMLATE_SUPERVISOR1_EMAIL    = "사감 1 이메일"
    IMLATE_SUPERVISOR2_NAME     = "사감 2 이름"
    IMLATE_SUPERVISOR2_PHONE    = "사감 2 휴대폰"
    IMLATE_SUPERVISOR2_EMAIL    = "사감 2 이메일"
  }

  # ---- 이름 → 값 (민감값 포함, for_each 에는 절대 쓰지 않는다) ----
  parameter_values = {
    IMLATE_DB_URL               = var.db_url
    IMLATE_DB_USERNAME          = var.db_username
    IMLATE_DB_PASSWORD          = var.db_password
    IMLATE_REDIS_HOST           = var.redis_host
    IMLATE_REDIS_PORT           = var.redis_port
    IMLATE_REDIS_SSL_ENABLED    = var.redis_ssl_enabled
    IMLATE_LOOKUP_BASE_URL      = var.lookup_base_url
    IMLATE_LOOKUP_TOKEN_SECRET  = var.lookup_token_secret
    IMLATE_ADMIN_API_KEY        = var.admin_api_key
    IMLATE_WEB_ALLOWED_ORIGIN_1 = var.web_allowed_origin
    IMLATE_ALIGO_API_KEY        = var.aligo_api_key
    IMLATE_ALIGO_USER_ID        = var.aligo_user_id
    IMLATE_ALIGO_SENDER         = var.aligo_sender
    IMLATE_SES_REGION           = var.ses_region
    IMLATE_SES_FROM             = var.ses_from
    IMLATE_SES_FROM_NAME        = var.ses_from_name
    IMLATE_SUPERVISOR1_NAME     = var.supervisor1_name
    IMLATE_SUPERVISOR1_PHONE    = var.supervisor1_phone
    IMLATE_SUPERVISOR1_EMAIL    = var.supervisor1_email
    IMLATE_SUPERVISOR2_NAME     = var.supervisor2_name
    IMLATE_SUPERVISOR2_PHONE    = var.supervisor2_phone
    IMLATE_SUPERVISOR2_EMAIL    = var.supervisor2_email
  }
}

# ---------------------------------------------------------------------
# 항상 생성되는 파라미터
# ---------------------------------------------------------------------
resource "aws_ssm_parameter" "app" {
  for_each = local.parameter_descriptions

  name        = "${local.path_prefix}/${each.key}"
  description = each.value
  type        = "SecureString"
  value       = local.parameter_values[each.key]
  key_id      = var.kms_key_id
  tier        = var.tier

  tags = merge(var.tags, {
    Name = each.key
  })
}

# ---------------------------------------------------------------------
# 선택 파라미터 — Redis AUTH token
#   auth 를 쓰지 않으면 파라미터를 만들지 않는다. 이때 애플리케이션은
#   user_data 가 심어 둔 기본값(IMLATE_REDIS_PASSWORD="")을 사용한다.
# ---------------------------------------------------------------------
resource "aws_ssm_parameter" "redis_password" {
  count = var.create_redis_password_parameter ? 1 : 0

  name        = "${local.path_prefix}/IMLATE_REDIS_PASSWORD"
  description = "ElastiCache Redis AUTH token"
  type        = "SecureString"
  value       = var.redis_password
  key_id      = var.kms_key_id
  tier        = var.tier

  tags = merge(var.tags, {
    Name = "IMLATE_REDIS_PASSWORD"
  })
}

# ---------------------------------------------------------------------
# 선택 파라미터 — SES configuration set
# ---------------------------------------------------------------------
resource "aws_ssm_parameter" "ses_configuration_set" {
  count = var.create_ses_configuration_set_parameter ? 1 : 0

  name        = "${local.path_prefix}/IMLATE_SES_CONFIGURATION_SET"
  description = "SES configuration set 이름"
  type        = "SecureString"
  value       = var.ses_configuration_set
  key_id      = var.kms_key_id
  tier        = var.tier

  tags = merge(var.tags, {
    Name = "IMLATE_SES_CONFIGURATION_SET"
  })
}
