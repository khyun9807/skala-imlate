# iam 모듈 출력

output "role_name" {
  description = "애플리케이션 EC2 IAM 역할 이름"
  value       = aws_iam_role.app.name
}

output "role_arn" {
  description = "애플리케이션 EC2 IAM 역할 ARN"
  value       = aws_iam_role.app.arn
}

output "instance_profile_name" {
  description = "EC2 인스턴스 프로파일 이름(aws_instance.iam_instance_profile 에 사용)"
  value       = aws_iam_instance_profile.app.name
}

output "instance_profile_arn" {
  description = "EC2 인스턴스 프로파일 ARN"
  value       = aws_iam_instance_profile.app.arn
}

output "ssm_parameter_arns" {
  description = "정책에서 허용한 SSM 파라미터 ARN 패턴"
  value       = local.ssm_parameter_arns
}
