# =====================================================================
# imlate — 루트 모듈 출력
#
#   terraform output              (전체)
#   terraform output -raw alb_dns_name
#   terraform output -raw db_password        (sensitive 값은 -raw 필요)
#   terraform output -json ses_dkim_dns_records
# =====================================================================

# ---------------- 네트워크 ----------------
output "vpc_id" {
  description = "VPC ID"
  value       = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "퍼블릭 서브넷 ID 목록"
  value       = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  description = "프라이빗 서브넷 ID 목록"
  value       = module.network.private_subnet_ids
}

output "nat_public_ips" {
  description = "NAT Gateway 고정 공인 IP(알리고 등 외부 API 화이트리스트 등록용)"
  value       = module.network.nat_public_ips
}

output "security_group_ids" {
  description = "보안 그룹 ID 모음 (alb / app / db / redis)"
  value       = module.security.security_group_ids
}

# ---------------- ALB / WAF ----------------
output "alb_dns_name" {
  description = "ALB DNS 이름. Route53 CNAME/Alias 를 여기로 붙인다."
  value       = local.alb_dns_name
}

output "alb_arn" {
  description = "ALB ARN"
  value       = one(module.alb[*].arn)
}

output "alb_zone_id" {
  description = "ALB 호스팅 존 ID(Route53 Alias 레코드용)"
  value       = one(module.alb[*].zone_id)
}

output "alb_target_group_arn" {
  description = "애플리케이션 타겟 그룹 ARN"
  value       = one(module.alb[*].target_group_arn)
}

output "service_url" {
  description = "서비스 접속 URL(등록 페이지). 조회 페이지는 여기에 /lookup?date=&token= 을 붙인다."
  value       = local.resolved_base_url
}

output "waf_web_acl_arn" {
  description = "WAF Web ACL ARN(비활성이면 null)"
  value       = one(module.waf[*].web_acl_arn)
}

output "waf_rate_limit_per_5min" {
  description = "WAF 가 적용한 IP 당 5분 요청 상한(애플리케이션 Redis 리미터와 2단 방어)"
  value       = one(module.waf[*].rate_limit_per_5min)
}

# ---------------- 애플리케이션 서버 ----------------
output "app_instance_id" {
  description = "애플리케이션 EC2 인스턴스 ID (deploy.sh --instance-id, SSM Session Manager 접속에 사용)"
  value       = module.ec2.instance_id
}

output "app_private_ip" {
  description = "애플리케이션 EC2 프라이빗 IP"
  value       = module.ec2.private_ip
}

output "app_public_ip" {
  description = "애플리케이션 EC2 퍼블릭 IP(프라이빗 배치면 빈 문자열)"
  value       = module.ec2.public_ip
}

output "app_elastic_ip" {
  description = "애플리케이션 EC2 고정 EIP(할당하지 않았으면 null)"
  value       = module.ec2.elastic_ip
}

output "app_ami_id" {
  description = "사용된 Amazon Linux 2023 AMI ID"
  value       = module.ec2.ami_id
}

output "app_instance_profile_name" {
  description = "EC2 인스턴스 프로파일 이름"
  value       = module.iam.instance_profile_name
}

output "app_role_arn" {
  description = "EC2 IAM 역할 ARN"
  value       = module.iam.role_arn
}

# ---------------- 데이터 계층 ----------------
output "rds_endpoint" {
  description = "RDS 엔드포인트(host:port)"
  value       = module.rds.endpoint
}

output "rds_address" {
  description = "RDS 호스트명"
  value       = module.rds.address
}

output "rds_port" {
  description = "RDS 포트"
  value       = module.rds.port
}

output "rds_jdbc_url" {
  description = "애플리케이션이 사용하는 JDBC URL(IMLATE_DB_URL 과 동일)"
  value       = module.rds.jdbc_url
}

output "db_username" {
  description = "RDS 마스터 사용자"
  value       = module.rds.username
}

output "db_password" {
  description = "RDS 마스터 비밀번호(자동 생성된 경우 확인용). terraform output -raw db_password"
  value       = module.rds.password
  sensitive   = true
}

output "redis_endpoint" {
  description = "ElastiCache Redis 기본 엔드포인트 호스트명"
  value       = module.elasticache.primary_endpoint_address
}

output "redis_port" {
  description = "ElastiCache Redis 포트"
  value       = module.elasticache.port
}

output "redis_tls_enabled" {
  description = "Redis TLS 사용 여부(애플리케이션 IMLATE_REDIS_SSL_ENABLED 와 동일)"
  value       = module.elasticache.transit_encryption_enabled
}

output "redis_auth_token" {
  description = "Redis AUTH token(자동 생성된 경우 확인용)"
  value       = module.elasticache.auth_token
  sensitive   = true
}

# ---------------- 시크릿 / SSM ----------------
output "ssm_path_prefix" {
  description = "애플리케이션 시크릿이 저장된 SSM 경로 접두어"
  value       = module.ssm.path_prefix
}

output "ssm_parameter_names" {
  description = "생성된 SSM 파라미터 전체 이름 목록"
  value       = module.ssm.parameter_names
}

output "lookup_token_secret" {
  description = "조회 링크 HMAC 서명 시크릿(자동 생성된 경우 확인용)"
  value       = local.lookup_token_secret
  sensitive   = true
}

output "admin_api_key" {
  description = "관리 API 키(X-Admin-Key). 발송 수동 트리거 등에 사용."
  value       = local.admin_api_key
  sensitive   = true
}

# ---------------- SES ----------------
output "ses_identity" {
  description = "SES 아이덴티티(도메인 또는 이메일)"
  value       = module.ses.identity
}

output "ses_from_address" {
  description = "애플리케이션이 사용하는 발신 주소"
  value       = local.resolved_ses_from
}

output "ses_recipient_identities" {
  description = <<-EOT
    수신 전용으로 등록한 SES 아이덴티티 목록(샌드박스 운영용).
    각 주소로 AWS 확인 메일이 발송되며, **소유자가 링크를 눌러야** 메일 수신이 가능하다.
    상태 확인: aws sesv2 get-email-identity --email-identity <주소> --region <리전>
  EOT
  value       = module.ses.recipient_identities
  sensitive   = true
}

output "ses_dkim_dns_records" {
  description = "DNS 에 등록해야 할 DKIM CNAME 레코드(도메인 아이덴티티일 때). terraform output -json ses_dkim_dns_records"
  value       = module.ses.dkim_dns_records
}

output "ses_configuration_set_name" {
  description = "SES configuration set 이름(비활성이면 null)"
  value       = module.ses.configuration_set_name
}

output "ses_verification_guide" {
  description = "SES 검증 절차 안내"
  value       = module.ses.verification_guide
}

# ---------------- 운영 안내 ----------------
output "next_steps" {
  description = "apply 직후 해야 할 일 요약"
  value = join("\n", [
    "1. SES 검증: ${module.ses.verification_guide}",
    "2. SES 수신자 검증: 등록된 수신 주소로 AWS 확인 메일이 발송되었습니다. 각 주소 소유자가 링크를 눌러야 합니다.",
    "   확인: aws sesv2 get-email-identity --email-identity <주소> --region ${var.aws_region} --query VerifiedForSendingStatus",
    "   (샌드박스로 운영하면 이 검증이 필수입니다. 프로덕션 액세스를 받으면 수신자 검증 없이 발송 가능합니다.)",
    "3. 알리고: 발신번호 사전등록 확인 + NAT 공인 IP 를 화이트리스트에 등록 → terraform output -json nat_public_ips",
    "4. 배포: infra/scripts/deploy.sh --mode ssm --instance-id ${module.ec2.instance_id} --region ${var.aws_region} --bucket <아티팩트버킷>",
    "   또는 --mode ssh --host <EC2 주소> --key <pem 경로>",
    "5. 헬스체크: curl -fsS ${local.resolved_base_url}/actuator/health",
    "6. 시크릿 변경 시: SSM 파라미터 수정 → sudo systemctl restart imlate-env imlate",
  ])
}
