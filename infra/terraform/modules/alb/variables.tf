# alb 모듈 변수

variable "name_prefix" {
  description = "리소스 이름 접두어. ALB 이름은 32자 제한이 있으니 짧게 유지한다."
  type        = string
}

variable "vpc_id" {
  description = "타겟 그룹을 만들 VPC ID"
  type        = string
}

variable "subnet_ids" {
  description = "ALB 를 배치할 서브넷 ID 목록(퍼블릭 2AZ)"
  type        = list(string)
}

variable "security_group_ids" {
  description = "ALB 보안 그룹 ID 목록"
  type        = list(string)
}

variable "internal" {
  description = "내부 전용 ALB 여부"
  type        = bool
  default     = false
}

variable "target_port" {
  description = "타겟 그룹 포트. 8080 = Spring 직결, 80 = nginx 경유."
  type        = number
  default     = 8080
}

variable "health_check_path" {
  description = "헬스체크 경로"
  type        = string
  default     = "/actuator/health"
}

variable "health_check_matcher" {
  description = "정상으로 간주할 HTTP 상태 코드"
  type        = string
  default     = "200"
}

variable "health_check_interval" {
  description = "헬스체크 주기(초)"
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "헬스체크 타임아웃(초)"
  type        = number
  default     = 5
}

variable "health_check_healthy_threshold" {
  description = "정상 판정 연속 횟수"
  type        = number
  default     = 2
}

variable "health_check_unhealthy_threshold" {
  description = "비정상 판정 연속 횟수"
  type        = number
  default     = 3
}

variable "deregistration_delay" {
  description = "타겟 등록 해제 대기 시간(초). 배포 시 무중단 전환에 영향."
  type        = number
  default     = 30
}

variable "idle_timeout" {
  description = "ALB idle timeout(초)"
  type        = number
  default     = 60
}

variable "certificate_arn" {
  description = "ACM 인증서 ARN. 비우면 HTTPS 리스너를 만들지 않는다."
  type        = string
  default     = ""
}

variable "ssl_policy" {
  description = "HTTPS 리스너 SSL 정책"
  type        = string
  default     = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}

variable "redirect_http_to_https" {
  description = "인증서가 있을 때 80 → 443 리다이렉트 여부"
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "ALB 삭제 방지"
  type        = bool
  default     = false
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
