# waf 모듈 변수

variable "name_prefix" {
  description = "리소스 이름 접두어"
  type        = string
}

variable "metric_prefix" {
  description = "CloudWatch 지표 이름 접두어(영숫자만 사용)"
  type        = string
  default     = "imlate"
}

variable "alb_arn" {
  description = "Web ACL 을 연결할 ALB ARN"
  type        = string
}

variable "rate_limit_per_5min" {
  description = "동일 IP 가 5분 동안 보낼 수 있는 최대 요청 수(초과 시 차단). AWS 최소값 100."
  type        = number
  default     = 2000
}

variable "enable_common_rule_set" {
  description = "AWSManagedRulesCommonRuleSet 적용 여부"
  type        = bool
  default     = true
}

variable "enable_known_bad_inputs_rule_set" {
  description = "AWSManagedRulesKnownBadInputsRuleSet 적용 여부"
  type        = bool
  default     = true
}

variable "managed_rules_count_only" {
  description = "관리형 규칙을 차단이 아닌 카운트 모드로 둘지 여부(오탐 튜닝용)"
  type        = bool
  default     = false
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
