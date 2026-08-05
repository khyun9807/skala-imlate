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

variable "recipient_identities" {
  description = <<-EOT
    수신 전용으로 검증할 이메일 주소 목록.

    SES 샌드박스에서는 **수신자도 검증된 아이덴티티여야** 메일이 전달된다
    (미검증 시 MessageRejected: Email address is not verified).
    프로덕션 액세스를 받지 않고 운영할 때 사감 수신 주소를 여기에 넣는다.
    각 주소로 AWS 확인 메일이 발송되며, 주소 소유자가 링크를 눌러야 Verified 가 된다.
  EOT
  type        = list(string)
  default     = []
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
