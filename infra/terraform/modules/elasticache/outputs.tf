# elasticache 모듈 출력

output "replication_group_id" {
  description = "복제 그룹 ID"
  value       = aws_elasticache_replication_group.this.id
}

output "arn" {
  description = "복제 그룹 ARN"
  value       = aws_elasticache_replication_group.this.arn
}

output "primary_endpoint_address" {
  description = "쓰기용 기본 엔드포인트 호스트명(IMLATE_REDIS_HOST)"
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "reader_endpoint_address" {
  description = "읽기 전용 엔드포인트 호스트명(노드 2개 이상일 때 의미 있음)"
  value       = aws_elasticache_replication_group.this.reader_endpoint_address
}

output "port" {
  description = "Redis 포트(IMLATE_REDIS_PORT)"
  value       = var.port
}

output "auth_token" {
  description = "AUTH token(IMLATE_REDIS_PASSWORD). auth_enabled = false 면 null."
  value       = local.auth_token
  sensitive   = true
}

output "auth_enabled" {
  description = "AUTH token 사용 여부"
  value       = var.auth_enabled
}

output "transit_encryption_enabled" {
  description = "전송 구간 암호화 여부(애플리케이션 IMLATE_REDIS_SSL_ENABLED 와 동일해야 한다)"
  value       = var.transit_encryption_enabled
}

output "subnet_group_name" {
  description = "캐시 서브넷 그룹 이름"
  value       = aws_elasticache_subnet_group.this.name
}

output "parameter_group_name" {
  description = "캐시 파라미터 그룹 이름"
  value       = aws_elasticache_parameter_group.this.name
}
