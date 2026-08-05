# monitoring 모듈 출력

output "sns_topic_arn" {
  description = "모든 알람이 게시되는 SNS 주제 ARN"
  value       = aws_sns_topic.alerts.arn
}

output "sns_topic_name" {
  description = "SNS 주제 이름"
  value       = aws_sns_topic.alerts.name
}

output "alert_email" {
  description = "알림 수신 이메일(운영자 전용 — 사감 수신 주소와 별개 채널이다)"
  value       = local.alert_email
}

output "alert_subscription_notice" {
  description = <<-EOT
    ★ 이메일 구독은 apply 만으로 활성화되지 않는다.

    AWS 가 수신 주소로 "AWS Notification - Subscription Confirmation" 메일을 보내고,
    **수신자가 그 메일의 Confirm subscription 링크를 눌러야** 구독이 살아난다.
    누르기 전까지는 알람이 ALARM 으로 전이해도 메일이 오지 않는다.

    확인 명령:
      aws sns list-subscriptions-by-topic --topic-arn <TOPIC_ARN> \
        --query "Subscriptions[].{Endpoint:Endpoint,Status:SubscriptionArn}" --output table
      Status 가 "PendingConfirmation" 이면 아직 확인 전이다.

    테스트 발송:
      aws sns publish --topic-arn <TOPIC_ARN> --subject "imlate alert test" --message "test"
  EOT
  value = join("\n", [
    "SNS 주제: ${aws_sns_topic.alerts.arn}",
    "수신 주소: ${local.alert_email}",
    "1) 위 주소로 온 AWS 확인 메일의 링크를 눌러야 구독이 활성화된다(PendingConfirmation → Confirmed).",
    "2) 확인: aws sns list-subscriptions-by-topic --topic-arn ${aws_sns_topic.alerts.arn} --query \"Subscriptions[].SubscriptionArn\"",
    "3) 테스트: aws sns publish --topic-arn ${aws_sns_topic.alerts.arn} --subject \"imlate alert test\" --message \"test\"",
  ])
}

output "alarm_names" {
  description = "생성된 CloudWatch 알람 이름 목록"
  value = sort(concat(
    [aws_cloudwatch_metric_alarm.ec2_status_check.alarm_name],
    [aws_cloudwatch_metric_alarm.dispatch_failures.alarm_name],
    aws_cloudwatch_metric_alarm.dispatch_heartbeat_missing[*].alarm_name,
    aws_cloudwatch_metric_alarm.rds_free_storage[*].alarm_name,
    aws_cloudwatch_metric_alarm.rds_cpu[*].alarm_name,
    aws_cloudwatch_metric_alarm.ec2_disk_used[*].alarm_name,
    [for alarm in aws_cloudwatch_metric_alarm.redis_memory : alarm.alarm_name],
  ))
}

output "alarm_arns" {
  description = "생성된 CloudWatch 알람 ARN 목록"
  value = sort(concat(
    [aws_cloudwatch_metric_alarm.ec2_status_check.arn],
    [aws_cloudwatch_metric_alarm.dispatch_failures.arn],
    aws_cloudwatch_metric_alarm.dispatch_heartbeat_missing[*].arn,
    aws_cloudwatch_metric_alarm.rds_free_storage[*].arn,
    aws_cloudwatch_metric_alarm.rds_cpu[*].arn,
    aws_cloudwatch_metric_alarm.ec2_disk_used[*].arn,
    [for alarm in aws_cloudwatch_metric_alarm.redis_memory : alarm.arn],
  ))
}

output "dispatch_metric_contract" {
  description = <<-EOT
    앱(애플리케이션 담당)이 지켜야 하는 커스텀 지표 계약.
    이 값과 앱의 PutMetricData 호출이 어긋나면 하트비트 알람이 영원히 데이터를 못 찾는다.
  EOT
  value = {
    namespace  = var.dispatch_metric_namespace
    completed  = var.dispatch_completed_metric_name
    failures   = var.dispatch_failures_metric_name
    dimensions = var.dispatch_metric_dimensions
    unit       = "Count"
    note       = "DispatchCompleted 는 21:50 배치 종료 시 값 1 이상으로 1회. DispatchFailures 는 실패 건수(0 이면 올리지 않아도 된다)."
  }
}

output "heartbeat_alarm_window" {
  description = "하트비트 알람의 평가 설계 요약(운영자가 알람 메일을 받았을 때 해석하는 근거)"
  value = var.enable_dispatch_heartbeat_alarm ? join(" ", [
    "period 300초 × evaluation_periods 288 = 24시간 창.",
    "최근 24시간에 DispatchCompleted 가 한 번이라도 있으면 OK, 하나도 없으면 ALARM(missing = breaching).",
    "정상 운영 시 21:50 하트비트 덕분에 하루 종일 OK 를 유지하고, 발송이 없는 날에만 21:55~22:10 KST 에 한 번 전이한다.",
  ]) : "비활성(enable_dispatch_heartbeat_alarm = false)"
}
