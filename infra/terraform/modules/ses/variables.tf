# ses 모듈 변수

variable "enabled" {
  description = "SES 아이덴티티를 생성할지 여부. identity 가 비어 있으면 자동으로 비활성."
  type        = bool
  default     = true
}

variable "identity" {
  description = "검증 대상. 도메인(example.com) 또는 이메일 주소(noreply@example.com)."
  type        = string
  default     = ""
}

variable "enable_configuration_set" {
  description = "configuration set 생성/연결 여부"
  type        = bool
  default     = false
}

variable "configuration_set_name" {
  description = "configuration set 이름"
  type        = string
  default     = "imlate-default"
}

variable "dkim_signing_key_length" {
  description = "Easy DKIM 서명 키 길이"
  type        = string
  default     = "RSA_2048_BIT"

  validation {
    condition     = contains(["RSA_1024_BIT", "RSA_2048_BIT"], var.dkim_signing_key_length)
    error_message = "dkim_signing_key_length 는 RSA_1024_BIT 또는 RSA_2048_BIT 여야 합니다."
  }
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
