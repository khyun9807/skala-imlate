# waf 모듈 출력

output "web_acl_arn" {
  description = "Web ACL ARN"
  value       = aws_wafv2_web_acl.this.arn
}

output "web_acl_id" {
  description = "Web ACL ID"
  value       = aws_wafv2_web_acl.this.id
}

output "web_acl_name" {
  description = "Web ACL 이름"
  value       = aws_wafv2_web_acl.this.name
}

output "rate_limit_per_5min" {
  description = "적용된 IP 당 5분 요청 상한"
  value       = var.rate_limit_per_5min
}
