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

variable "web_tls_port" {
  description = "nginx HTTPS 포트(Let's Encrypt 인증서로 직접 종단할 때 사용)"
  type        = number
  default     = 443
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

variable "enable_alb_ingress" {
  description = <<-EOT
    ALB 경유 인바운드 규칙(ALB SG 80/443, app SG ← ALB SG)을 만들지 여부.
    false 면 ALB 관련 규칙을 만들지 않는다. 보안 그룹 자체는 남으므로
    나중에 enable_alb 를 다시 켜면 규칙만 되살아난다.
  EOT
  type        = bool
  default     = true
}

variable "enable_direct_ingress" {
  description = <<-EOT
    인터넷 → EC2 직접 인바운드(80/443) 규칙을 만들지 여부.

    ALB 를 쓰지 않는 구성(enable_alb = false)에서는 이 규칙이 없으면
    **외부에서 서비스에 접근할 방법이 전혀 없다**. 반대로 ALB 를 쓸 때 이 규칙을 켜면
    ALB 를 우회해 EC2 로 직접 붙을 수 있게 되므로(WAF/로그 우회) 켜지 말아야 한다.
    → 루트 모듈에서 `!var.enable_alb` 로 배선한다.
  EOT
  type        = bool
  default     = false
}

variable "direct_ingress_cidrs" {
  description = <<-EOT
    인터넷 → EC2 직접 인바운드를 허용할 CIDR 목록(enable_direct_ingress = true 일 때만 사용).

    트레이드오프: 기본값 0.0.0.0/0 은 누구나 접속 가능하다는 뜻이다.
    이 서비스는 교육생 누구나 휴대폰으로 접속해야 하므로 전체 개방이 맞다.
    사내망에서만 쓸 서비스라면 여기를 사무실/VPN 대역으로 좁힌다.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
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
