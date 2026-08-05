# rds 모듈 변수

variable "identifier" {
  description = "RDS 인스턴스 식별자 (예: imlate-prod-mysql)"
  type        = string
}

variable "name_prefix" {
  description = "부속 리소스(서브넷 그룹, 파라미터 그룹) 이름 접두어"
  type        = string
}

variable "subnet_ids" {
  description = "DB 서브넷 그룹에 넣을 프라이빗 서브넷 ID 목록(2개 이상, 서로 다른 AZ)"
  type        = list(string)
}

variable "security_group_ids" {
  description = "RDS 에 붙일 보안 그룹 ID 목록"
  type        = list(string)
}

variable "engine_version" {
  description = "MySQL 엔진 버전"
  type        = string
  default     = "8.0"
}

variable "parameter_group_family" {
  description = "파라미터 그룹 패밀리"
  type        = string
  default     = "mysql8.0"
}

variable "instance_class" {
  description = "RDS 인스턴스 클래스"
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "초기 스토리지(GiB)"
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "스토리지 오토스케일링 상한(GiB). 0 이면 비활성."
  type        = number
  default     = 100
}

variable "storage_type" {
  description = "스토리지 타입"
  type        = string
  default     = "gp3"
}

variable "kms_key_id" {
  description = "스토리지 암호화 KMS 키 ARN. null 이면 기본 aws/rds 키를 사용한다."
  type        = string
  default     = null
}

variable "db_name" {
  description = "생성할 데이터베이스 이름"
  type        = string
  default     = "imlate"
}

variable "username" {
  description = "마스터 사용자 이름"
  type        = string
  default     = "imlate_admin"
}

variable "password" {
  description = "마스터 비밀번호. null 이면 random_password 로 생성한다."
  type        = string
  default     = null
  sensitive   = true
}

variable "port" {
  description = "MySQL 포트"
  type        = number
  default     = 3306
}

variable "multi_az" {
  description = "Multi-AZ 배치 여부"
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "자동 백업 보존 일수"
  type        = number
  default     = 7
}

variable "backup_window" {
  description = "자동 백업 창(UTC). 기본값은 KST 02:00~03:00."
  type        = string
  default     = "17:00-18:00"
}

variable "maintenance_window" {
  description = "유지보수 창(UTC). 기본값은 KST 월요일 03:30~04:30."
  type        = string
  default     = "mon:18:30-mon:19:30"
}

variable "deletion_protection" {
  description = "삭제 방지"
  type        = bool
  default     = true
}

variable "skip_final_snapshot" {
  description = "삭제 시 최종 스냅샷 생략 여부"
  type        = bool
  default     = false
}

variable "apply_immediately" {
  description = "변경 즉시 적용 여부"
  type        = bool
  default     = false
}

variable "enabled_cloudwatch_logs_exports" {
  description = "CloudWatch Logs 로 내보낼 로그 종류"
  type        = list(string)
  default     = ["error", "slowquery"]
}

variable "performance_insights_enabled" {
  description = "Performance Insights 사용 여부(t4g.micro 등 소형 클래스는 미지원)"
  type        = bool
  default     = false
}

variable "ca_cert_identifier" {
  description = "RDS CA 인증서 식별자. null 이면 AWS 기본값."
  type        = string
  default     = null
}

variable "db_timezone" {
  description = "MySQL time_zone 파라미터 값"
  type        = string
  default     = "Asia/Seoul"
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
