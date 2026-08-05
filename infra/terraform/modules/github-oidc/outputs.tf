# =====================================================================
# github-oidc 모듈 — 출력
# =====================================================================

output "role_arn" {
  description = "GitHub Actions 가 맡을 역할 ARN. 리포지터리 Secret AWS_ROLE_ARN 에 넣는다."
  value       = aws_iam_role.github_actions.arn
}

output "role_name" {
  description = "역할 이름"
  value       = aws_iam_role.github_actions.name
}

output "oidc_provider_arn" {
  description = "사용 중인 GitHub OIDC 공급자 ARN(새로 만들었든 기존 것을 참조했든)"
  value       = local.oidc_provider_arn
}

output "oidc_provider_created" {
  description = "이 모듈이 OIDC 공급자를 직접 만들었는지 여부"
  value       = var.create_oidc_provider
}

output "allowed_subjects" {
  description = "AssumeRole 이 허용된 토큰 sub 목록. AssumeRole 거부를 디버깅할 때 실제 토큰 sub 와 비교한다."
  value       = local.allowed_subjects
}

output "artifact_object_arn" {
  description = "쓰기 권한이 부여된 S3 객체 ARN 패턴"
  value       = local.artifact_object_arn
}

output "ssm_target_instance_arns" {
  description = "SendCommand 가 허용된 인스턴스 ARN 패턴"
  value       = local.instance_arns
}
