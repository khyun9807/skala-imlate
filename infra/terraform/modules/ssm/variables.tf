# ssm 모듈 변수
#
# 값은 전부 문자열로 저장한다(환경변수로 그대로 주입되기 때문).
# 숫자/불린은 호출부에서 tostring() 으로 변환해 전달한다.

variable "path_prefix" {
  description = "파라미터 경로 접두어 (예: /imlate/prod). 끝의 슬래시는 자동 제거된다."
  type        = string
}

variable "kms_key_id" {
  description = "SecureString 암호화에 사용할 KMS 키 ID/ARN. null 이면 계정 기본 키(aws/ssm)."
  type        = string
  default     = null
}

variable "tier" {
  description = "파라미터 티어 (Standard / Advanced / Intelligent-Tiering)"
  type        = string
  default     = "Standard"
}

# ---------------- 데이터 계층 ----------------
variable "db_url" {
  description = "MySQL JDBC URL"
  type        = string
  sensitive   = true
}

variable "db_username" {
  description = "MySQL 사용자"
  type        = string
  sensitive   = true
}

variable "db_password" {
  description = "MySQL 비밀번호"
  type        = string
  sensitive   = true
}

variable "redis_host" {
  description = "Redis 호스트"
  type        = string
  sensitive   = true
}

variable "redis_port" {
  description = "Redis 포트(문자열)"
  type        = string
}

variable "redis_ssl_enabled" {
  description = "Redis TLS 사용 여부(\"true\" / \"false\")"
  type        = string
}

variable "create_redis_password_parameter" {
  description = "Redis AUTH token 파라미터를 만들지 여부(민감값이 아닌 불린이어야 한다)"
  type        = bool
  default     = false
}

variable "redis_password" {
  description = "Redis AUTH token. create_redis_password_parameter = true 일 때만 사용된다."
  type        = string
  default     = null
  sensitive   = true
}

# ---------------- 애플리케이션 ----------------
variable "lookup_base_url" {
  description = "사감 조회 페이지 base URL"
  type        = string
}

variable "lookup_token_secret" {
  description = "조회 링크 HMAC 서명 시크릿"
  type        = string
  sensitive   = true
}

variable "admin_api_key" {
  description = "관리 API 키(X-Admin-Key)"
  type        = string
  sensitive   = true
}

variable "web_allowed_origin" {
  description = "CORS 허용 오리진"
  type        = string
}

# ---------------- 문자(알리고) ----------------
variable "aligo_api_key" {
  description = "알리고 API 키"
  type        = string
  sensitive   = true
}

variable "aligo_user_id" {
  description = "알리고 사용자 ID"
  type        = string
  sensitive   = true
}

variable "aligo_sender" {
  description = "알리고 발신번호"
  type        = string
  sensitive   = true
}

# ---------------- 메일(SES) ----------------
variable "ses_region" {
  description = "SES 리전"
  type        = string
}

variable "ses_from" {
  description = "SES 발신 주소"
  type        = string
}

variable "ses_from_name" {
  description = "SES 발신자 표시 이름"
  type        = string
  default     = "기숙사 야간복귀 시스템"
}

variable "create_ses_configuration_set_parameter" {
  description = "SES configuration set 파라미터를 만들지 여부"
  type        = bool
  default     = false
}

variable "ses_configuration_set" {
  description = "SES configuration set 이름"
  type        = string
  default     = null
}

# ---------------- 사감 연락처 ----------------
variable "supervisor1_name" {
  description = "사감 1 이름"
  type        = string
}

variable "supervisor1_phone" {
  description = "사감 1 휴대폰"
  type        = string
  sensitive   = true
}

variable "supervisor1_email" {
  description = "사감 1 이메일"
  type        = string
  sensitive   = true
}

variable "supervisor2_name" {
  description = "사감 2 이름"
  type        = string
}

variable "supervisor2_phone" {
  description = "사감 2 휴대폰"
  type        = string
  sensitive   = true
}

variable "supervisor2_email" {
  description = "사감 2 이메일"
  type        = string
  sensitive   = true
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
