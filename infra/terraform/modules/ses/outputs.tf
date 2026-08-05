# ses 모듈 출력

output "enabled" {
  description = "SES 아이덴티티 생성 여부"
  value       = local.enabled
}

output "identity" {
  description = "SES 아이덴티티 값(도메인 또는 이메일)"
  value       = local.enabled ? var.identity : ""
}

output "identity_arn" {
  description = "SES 아이덴티티 ARN(IAM 정책 리소스로 사용)"
  value       = one(aws_sesv2_email_identity.this[*].arn)
}

output "recipient_identities" {
  description = "수신 전용으로 생성한 아이덴티티 목록(샌드박스 운영용)"
  value       = aws_sesv2_email_identity.recipients[*].email_identity
  sensitive   = true
}

output "is_domain_identity" {
  description = "도메인 아이덴티티 여부"
  value       = local.is_domain
}

output "configuration_set_name" {
  description = "연결된 configuration set 이름(없으면 null)"
  value       = one(aws_sesv2_configuration_set.this[*].configuration_set_name)
}

output "configuration_set_arn" {
  description = "configuration set ARN(없으면 null)"
  value       = one(aws_sesv2_configuration_set.this[*].arn)
}

output "dkim_tokens" {
  description = "Easy DKIM 토큰 목록(도메인 아이덴티티일 때만 값이 있음)"
  value       = local.dkim_tokens
}

output "dkim_dns_records" {
  description = "DNS 에 등록해야 할 DKIM CNAME 레코드 3건. 등록 후 수 분~수 시간 내 검증이 완료된다."
  value = [
    for token in local.dkim_tokens : {
      name  = "${token}._domainkey.${var.identity}"
      type  = "CNAME"
      value = "${token}.dkim.amazonses.com"
    }
  ]
}

output "verification_guide" {
  description = "검증 절차 안내 문구"
  value = local.is_domain ? join("", [
    "도메인 아이덴티티입니다. outputs.dkim_dns_records 의 CNAME 3건을 DNS 에 등록하세요. ",
    "추가로 SPF(TXT: v=spf1 include:amazonses.com ~all) 와 DMARC 레코드 등록을 권장합니다."
    ]) : (local.enabled ? join("", [
      "이메일 주소 아이덴티티입니다. AWS 가 보낸 검증 메일의 링크를 클릭해야 발송이 가능합니다."
  ]) : "SES 아이덴티티를 생성하지 않았습니다(ses_identity 가 비어 있음).")
}
