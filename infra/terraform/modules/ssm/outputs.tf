# ssm 모듈 출력

output "path_prefix" {
  description = "파라미터 경로 접두어"
  value       = local.path_prefix
}

output "parameter_names" {
  description = "생성된 파라미터 전체 이름 목록"
  value = concat(
    [for name in keys(local.parameter_descriptions) : "${local.path_prefix}/${name}"],
    aws_ssm_parameter.redis_password[*].name,
    aws_ssm_parameter.ses_configuration_set[*].name,
  )
}

output "environment_variable_names" {
  description = "EC2 에 주입되는 환경변수 이름 목록(파라미터 이름의 마지막 세그먼트)"
  value       = sort(keys(local.parameter_descriptions))
}

output "parameter_count" {
  description = "생성된 파라미터 개수"
  value = length(local.parameter_descriptions) +
  length(aws_ssm_parameter.redis_password) + length(aws_ssm_parameter.ses_configuration_set)
}
