# iam 모듈 변수

variable "name_prefix" {
  description = "리소스 이름 접두어 (예: imlate-prod)"
  type        = string
}

variable "ssm_path_prefix" {
  description = "읽기를 허용할 SSM Parameter Store 경로 접두어 (예: /imlate/prod)"
  type        = string
}

variable "ses_resource_arns" {
  description = "발송을 허용할 SES 리소스 ARN 목록(아이덴티티, configuration set). 비우면 * 로 둔다."
  type        = list(string)
  default     = []
}

variable "kms_key_arns" {
  description = "복호화를 허용할 추가 KMS 키 ARN 목록. aws/ssm 기본 키는 자동으로 포함된다."
  type        = list(string)
  default     = []
}

variable "artifact_bucket_names" {
  description = "배포 아티팩트(jar/dist)를 내려받을 S3 버킷 이름 목록. 비우면 S3 권한을 부여하지 않는다."
  type        = list(string)
  default     = []
}

variable "extra_managed_policy_arns" {
  description = "추가로 붙일 AWS 관리형 정책 ARN 목록"
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
