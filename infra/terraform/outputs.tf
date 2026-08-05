# =====================================================================
# imlate — 루트 모듈 출력
#
#   terraform output                            (전체)
#   terraform output -raw service_url           고객이 접속하는 주소
#   terraform output -raw aligo_whitelist_ip    알리고에 등록할 발신 IP  ★
#   terraform output -raw db_password           (sensitive 값은 -raw 필요)
#   terraform output -json ses_dkim_dns_records
# =====================================================================

# ---------------- 한눈에 보는 3가지 ----------------
output "service_url" {
  description = <<-EOT
    고객이 접속하는 서비스 URL(등록 페이지).
    조회 페이지는 여기에 /lookup?date=&token= 을 붙인다.
    domain_name 을 지정했다면 재배포해도 이 주소는 바뀌지 않는다.
  EOT
  value       = local.resolved_base_url
}

output "app_public_ip" {
  description = <<-EOT
    앱 서버의 공인 IP. associate_elastic_ip = true 면 고정 EIP 다.
    Route53 A 레코드가 이 주소를 가리킨다.
  EOT
  value       = local.eip_enabled ? aws_eip.app[0].public_ip : module.ec2.public_ip
}

output "aligo_whitelist_ip" {
  description = <<-EOT
    ★ 알리고 발신 IP 화이트리스트에 등록할 주소.

    ALB / NAT 를 걷어낸 뒤로는 인바운드와 아웃바운드가 모두 앱 EC2 의 EIP 하나로 통일됐다.
    즉 여기 나오는 IP = 고객이 접속하는 IP = 알리고에 등록할 IP 다.
    (프라이빗 배치 + NAT 구성으로 되돌리면 NAT Gateway 의 IP 목록이 나온다.)

    이 IP 가 바뀌면 알리고 화이트리스트를 반드시 다시 등록해야 문자 발송이 살아난다.
  EOT
  value = local.eip_enabled ? aws_eip.app[0].public_ip : (
    length(module.network.nat_public_ips) > 0 ? join(",", module.network.nat_public_ips) : module.ec2.public_ip
  )
}

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
  description = "NAT Gateway 고정 공인 IP 목록. 기본 구성(enable_nat_gateway = false)에서는 빈 목록이다 — 발신 IP 는 aligo_whitelist_ip 를 보라."
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

output "app_instance_public_ip" {
  description = "EC2 인스턴스에 자동 할당된 퍼블릭 IP(EIP 를 붙이면 EIP 주소로 대체된다)"
  value       = module.ec2.public_ip
}

output "app_elastic_ip" {
  description = <<-EOT
    앱에 붙은 고정 EIP(할당하지 않았으면 null).

    ★ 이 주소가 바뀌면 두 곳을 반드시 갱신해야 한다.
       1) Route53 A 레코드  — create_dns_record = true 면 terraform apply 로 자동 갱신
       2) 알리고 발신 IP 화이트리스트 — 수동. 갱신 전까지 문자 발송이 실패한다.
    인스턴스를 -replace 로 교체하는 정도로는 바뀌지 않는다(EIP 와 인스턴스를 분리해 두었다).
    terraform destroy 는 주소를 실제로 반납하므로 재구축 시 새 IP 를 받는다.
  EOT
  value       = one(aws_eip.app[*].public_ip)
}

# ---------------- 도메인 / DNS / TLS ----------------
output "domain_name" {
  description = "서비스 도메인(지정하지 않았으면 빈 문자열)"
  value       = local.domain_name
}

output "dns_record_names" {
  description = "Terraform 이 만든 Route53 A 레코드 이름 목록(도메인 미지정 시 빈 목록)"
  value       = sort([for record in aws_route53_record.app : record.name])
}

output "route53_zone_name" {
  description = "A 레코드를 얹은 기존 호스팅 영역 이름(Terraform 이 생성하지 않고 조회만 한다)"
  value       = local.dns_enabled ? local.route53_zone_name : ""
}

output "tls_enabled" {
  description = <<-EOT
    Let's Encrypt HTTPS 사용 여부(설정 기준).
    실제 발급은 EC2 안의 imlate-tls.timer 가 best-effort 로 수행한다.
    상태 확인: sudo journalctl -u imlate-tls.service -n 50
  EOT
  value       = local.tls_enabled
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

# ---------------- 모니터링 / 알람 ----------------
output "alert_sns_topic_arn" {
  description = <<-EOT
    모든 CloudWatch 알람이 게시되는 SNS 주제 ARN(모니터링 비활성이면 null).
    수동 테스트: aws sns publish --topic-arn <ARN> --subject test --message test
  EOT
  value       = one(module.monitoring[*].sns_topic_arn)
}

output "alert_subscription_notice" {
  description = <<-EOT
    ★ 이메일 구독은 apply 만으로 활성화되지 않는다.
    AWS 가 alert_email 로 보낸 확인 메일의 "Confirm subscription" 링크를 눌러야
    PendingConfirmation → Confirmed 로 바뀌고, 그 전에는 알람이 울려도 메일이 오지 않는다.
  EOT
  value       = one(module.monitoring[*].alert_subscription_notice)
}

output "monitoring_alarm_names" {
  description = "생성된 CloudWatch 알람 이름 목록(모니터링 비활성이면 null)"
  value       = one(module.monitoring[*].alarm_names)
}

output "monitoring_alarm_arns" {
  description = "생성된 CloudWatch 알람 ARN 목록"
  value       = one(module.monitoring[*].alarm_arns)
}

output "dispatch_metric_contract" {
  description = <<-EOT
    애플리케이션이 지켜야 하는 CloudWatch 커스텀 지표 계약(네임스페이스/이름/차원).
    앱의 PutMetricData 호출과 이 값이 어긋나면 하트비트 알람이 영원히 데이터를 못 찾는다.
  EOT
  value       = one(module.monitoring[*].dispatch_metric_contract)
}

output "heartbeat_alarm_window" {
  description = "발송 하트비트 알람의 평가 설계 요약(알람 메일을 받았을 때 해석하는 근거)"
  value       = one(module.monitoring[*].heartbeat_alarm_window)
}

# ---------------- 운영 안내 ----------------
output "next_steps" {
  description = "apply 직후 해야 할 일 요약"
  value = join("\n", [
    "1. SES 검증: ${module.ses.verification_guide}",
    "2. SES 수신자 검증: 등록된 수신 주소로 AWS 확인 메일이 발송되었습니다. 각 주소 소유자가 링크를 눌러야 합니다.",
    "   확인: aws sesv2 get-email-identity --email-identity <주소> --region ${var.aws_region} --query VerifiedForSendingStatus",
    "   (샌드박스로 운영하면 이 검증이 필수입니다. 프로덕션 액세스를 받으면 수신자 검증 없이 발송 가능합니다.)",
    "3. 알리고: 발신번호 사전등록 확인 + 발신 IP 화이트리스트 등록 → terraform output -raw aligo_whitelist_ip",
    "   (ALB/NAT 를 걷어냈으므로 인바운드·아웃바운드가 모두 앱 EIP 하나입니다.)",
    "4. 배포: infra/scripts/deploy.sh --mode ssh --host ${local.eip_enabled ? "$(terraform output -raw app_public_ip)" : "<EC2 주소>"} --key <pem 경로>",
    "   또는 --mode ssm --instance-id ${module.ec2.instance_id} --region ${var.aws_region} --bucket <아티팩트버킷>",
    "5. 헬스체크: curl -fsSL ${local.resolved_base_url}/healthz",
    local.tls_enabled ? "6. HTTPS: EC2 안의 imlate-tls.timer 가 부팅 3분 뒤부터 1시간 간격으로 인증서를 시도합니다. DNS 전파 후 자동으로 켜집니다. 확인: sudo journalctl -u imlate-tls.service -n 50" : "6. HTTPS 미사용: domain_name 을 지정하면 Let's Encrypt 인증서가 자동 발급됩니다.",
    "7. 시크릿 변경 시: SSM 파라미터 수정 → sudo systemctl restart imlate-env imlate",
    local.monitoring_enabled ? "8. ★ 알람 구독 확인: ${local.alert_email} 로 온 AWS 확인 메일의 링크를 눌러야 알림이 살아납니다. 누르기 전에는 알람이 울려도 메일이 오지 않습니다. terraform output alert_subscription_notice" : "8. 알람 미설정: alert_email 을 채우면 CloudWatch 알람 + SNS 통보가 만들어집니다(enable_monitoring).",
  ])
}

# ---------------- GitHub Actions (CI/CD) ----------------
output "github_actions_role_arn" {
  description = "GitHub Actions 가 맡을 역할 ARN. 리포지터리 Secret 'AWS_ROLE_ARN' 에 넣는다."
  value       = one(module.github_oidc[*].role_arn)
}

output "github_actions_allowed_subjects" {
  description = "AssumeRole 이 허용된 토큰 sub 목록. 배포가 거부되면 실제 토큰 sub 와 대조한다."
  value       = one(module.github_oidc[*].allowed_subjects)
}
