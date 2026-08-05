# alb 모듈 출력

output "arn" {
  description = "ALB ARN (WAF 연결에 사용)"
  value       = aws_lb.this.arn
}

output "arn_suffix" {
  description = "ALB ARN suffix (CloudWatch 지표 차원)"
  value       = aws_lb.this.arn_suffix
}

output "dns_name" {
  description = "ALB DNS 이름"
  value       = aws_lb.this.dns_name
}

output "zone_id" {
  description = "ALB Route53 호스팅 존 ID (Alias 레코드용)"
  value       = aws_lb.this.zone_id
}

output "target_group_arn" {
  description = "애플리케이션 타겟 그룹 ARN"
  value       = aws_lb_target_group.app.arn
}

output "target_group_name" {
  description = "애플리케이션 타겟 그룹 이름"
  value       = aws_lb_target_group.app.name
}

output "target_port" {
  description = "타겟 그룹이 바라보는 인스턴스 포트"
  value       = var.target_port
}

output "http_listener_arn" {
  description = "HTTP(80) 리스너 ARN"
  value       = aws_lb_listener.http.arn
}

output "https_listener_arn" {
  description = "HTTPS(443) 리스너 ARN(인증서가 없으면 null)"
  value       = one(aws_lb_listener.https[*].arn)
}

output "https_enabled" {
  description = "HTTPS 리스너 생성 여부"
  value       = local.https_enabled
}

output "url" {
  description = "접속 URL"
  value       = local.https_enabled ? "https://${aws_lb.this.dns_name}" : "http://${aws_lb.this.dns_name}"
}
