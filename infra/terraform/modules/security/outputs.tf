# security 모듈 출력

output "alb_security_group_id" {
  description = "ALB 보안 그룹 ID"
  value       = aws_security_group.alb.id
}

output "app_security_group_id" {
  description = "애플리케이션 EC2 보안 그룹 ID"
  value       = aws_security_group.app.id
}

output "db_security_group_id" {
  description = "RDS 보안 그룹 ID"
  value       = aws_security_group.db.id
}

output "redis_security_group_id" {
  description = "ElastiCache 보안 그룹 ID"
  value       = aws_security_group.redis.id
}

output "security_group_ids" {
  description = "생성된 보안 그룹 ID 모음"
  value = {
    alb   = aws_security_group.alb.id
    app   = aws_security_group.app.id
    db    = aws_security_group.db.id
    redis = aws_security_group.redis.id
  }
}
