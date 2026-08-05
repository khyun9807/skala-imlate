# rds 모듈 출력

output "identifier" {
  description = "RDS 인스턴스 식별자"
  value       = aws_db_instance.this.identifier
}

output "arn" {
  description = "RDS 인스턴스 ARN"
  value       = aws_db_instance.this.arn
}

output "address" {
  description = "RDS 호스트명(포트 제외)"
  value       = aws_db_instance.this.address
}

output "port" {
  description = "RDS 포트"
  value       = aws_db_instance.this.port
}

output "endpoint" {
  description = "RDS 엔드포인트(host:port)"
  value       = aws_db_instance.this.endpoint
}

output "db_name" {
  description = "데이터베이스 이름"
  value       = aws_db_instance.this.db_name
}

output "username" {
  description = "마스터 사용자 이름"
  value       = aws_db_instance.this.username
}

output "password" {
  description = "마스터 비밀번호(입력값 또는 자동 생성값)"
  value       = local.master_password
  sensitive   = true
}

output "jdbc_url" {
  description = "애플리케이션이 사용할 JDBC URL(IMLATE_DB_URL)"
  value       = "jdbc:mysql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${var.db_name}?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=${var.db_timezone}&characterEncoding=UTF-8&rewriteBatchedStatements=true"
}

output "subnet_group_name" {
  description = "DB 서브넷 그룹 이름"
  value       = aws_db_subnet_group.this.name
}

output "parameter_group_name" {
  description = "DB 파라미터 그룹 이름"
  value       = aws_db_parameter_group.this.name
}
