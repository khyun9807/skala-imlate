# security 모듈 변수

variable "name_prefix" {
  description = "리소스 이름 접두어 (예: imlate-prod)"
  type        = string
}

variable "vpc_id" {
  description = "보안 그룹을 생성할 VPC ID"
  type        = string
}

variable "app_port" {
  description = "Spring Boot 애플리케이션 포트"
  type        = number
  default     = 8080
}

variable "web_port" {
  description = "nginx 포트(프론트 정적 서빙)"
  type        = number
  default     = 80
}

variable "db_port" {
  description = "MySQL 포트"
  type        = number
  default     = 3306
}

variable "redis_port" {
  description = "Redis 포트"
  type        = number
  default     = 6379
}

variable "alb_ingress_cidrs" {
  description = "ALB 인바운드를 허용할 CIDR 목록"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "enable_https_ingress" {
  description = "ALB 443 인바운드 규칙 생성 여부"
  type        = bool
  default     = true
}

variable "ssh_allowed_cidrs" {
  description = "앱 EC2 22번 포트를 열어줄 CIDR 목록. 비우면 SSH 규칙을 만들지 않는다."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
