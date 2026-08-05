# =====================================================================
# monitoring 모듈 — CloudWatch 알람 + SNS 이메일 통보
#
#   이 도메인에서 "조용한 실패"는 곧 교육생이 기숙사에 못 들어가는 사고다.
#   운영 타임라인은 이렇게 흐른다.
#
#     00:00 등록 시작 → 21:45 마감 → 21:50 사감 발송
#       → 22:05 / 22:20 실패분 재시도 → 22:30 문 잠김 → 23:30 일괄 개방
#
#   그러므로 감시의 목표는 하나다. **22:30 전에 사람이 알아채는 것.**
#   여기서 만드는 알람은 그 목표에 직접 기여하는 것만 남겼다.
#
#     1) 앱 서버가 죽었다            → EC2 StatusCheckFailed
#     2) 21:50 발송 배치가 안 돌았다  → Imlate/DispatchCompleted 하트비트 누락  ★ 핵심
#     3) 발송이 부분 실패했다        → Imlate/DispatchFailures > 0
#     4) 곧 죽을 것 같다             → RDS 스토리지/CPU, Redis 메모리, EC2 디스크
#
#   알람은 전부 하나의 SNS 주제로 모이고, 그 주제는 운영자 이메일 하나만 구독한다.
#   사감 연락처(문자/메일)는 이 모듈에 **입력조차 되지 않는다** — 채널이 물리적으로 분리되어 있다.
# =====================================================================

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

locals {
  alert_email = trimspace(var.alert_email)

  rds_enabled   = length(trimspace(var.rds_identifier)) > 0
  redis_enabled = length(trimspace(var.redis_replication_group_id)) > 0
  disk_enabled  = var.enable_disk_alarm

  # ElastiCache 노드 이름 규칙: "<복제그룹ID>-001", "-002" …
  # data source 로 member_clusters 를 조회하면 최초 apply 때 값이 unknown 이라
  # for_each 가 "cannot be determined until apply" 로 터진다. 그래서 계산해서 쓴다.
  redis_cache_cluster_ids = local.redis_enabled ? [
    for i in range(1, var.redis_num_cache_clusters + 1) :
    format("%s-%03d", trimspace(var.redis_replication_group_id), i)
  ] : []

  # 알람 이름에는 노드 번호(001, 002 …)만 붙인다. 전체 클러스터 ID 를 붙이면
  # "imlate-prod-redis-memory-high-imlate-prod-redis-001" 처럼 접두어가 두 번 나온다.
  redis_node_suffixes = {
    for cluster_id in local.redis_cache_cluster_ids :
    cluster_id => element(split("-", cluster_id), length(split("-", cluster_id)) - 1)
  }

  # 디스크 알람: 후보 디바이스마다 metric_query 를 만들고 MAX 로 합친다(변수 주석 참고).
  disk_metric_ids = [for i, device in var.disk_devices : "m${i + 1}"]
  disk_expression = "MAX([${join(",", local.disk_metric_ids)}])"
}

# ---------------------------------------------------------------------
# SNS 주제 — 모든 알람의 단일 수신구
#
#   ※ KMS 암호화(kms_master_key_id)를 일부러 걸지 않았다.
#     AWS 관리형 키(alias/aws/sns)로 암호화하면 CloudWatch 알람이 주제에 게시할 때
#     kms:GenerateDataKey 권한이 없어 조용히 실패한다(알람은 울리는데 메일이 안 온다).
#     알람 본문에는 리소스 이름과 임계값뿐이고 개인정보/시크릿이 들어가지 않으므로
#     암호화보다 "반드시 도착하는 것"을 택했다.
#
#   ※ 주제 정책(aws_sns_topic_policy)도 두지 않는다. 같은 계정의 CloudWatch 알람은
#     기본 정책으로 게시할 수 있고, 정책을 명시하면 기본 정책을 통째로 덮어써서
#     실수 한 번에 알림이 끊긴다.
# ---------------------------------------------------------------------
resource "aws_sns_topic" "alerts" {
  name         = "${var.name_prefix}-alerts"
  display_name = "imlate alerts"

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-alerts"
  })
}

# ---------------------------------------------------------------------
# 이메일 구독
#
#   ★ apply 가 끝나도 구독은 아직 살아 있지 않다.
#     AWS 가 alert_email 로 "AWS Notification - Subscription Confirmation" 메일을 보내고,
#     **수신자가 그 링크를 눌러야** PendingConfirmation → Confirmed 로 바뀐다.
#     누르기 전까지는 알람이 ALARM 으로 전이해도 메일이 오지 않는다.
#
#     확인:
#       aws sns list-subscriptions-by-topic --topic-arn <ARN> \
#         --query "Subscriptions[].{Endpoint:Endpoint,Arn:SubscriptionArn}"
#       SubscriptionArn 이 "PendingConfirmation" 이면 아직 확인 전이다.
#
#   Terraform 은 확인 여부를 알 수 없으므로 이 리소스만으로는 "알림이 실제로 온다"를
#   보장하지 못한다. outputs.alert_subscription_notice 에 같은 내용을 다시 적어 두었다.
# ---------------------------------------------------------------------
resource "aws_sns_topic_subscription" "alert_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = local.alert_email
}

# =====================================================================
# 1) EC2 인스턴스 장애
#
#   EC2 1대 단일 AZ 구성이다. 21:50 직전에 이 인스턴스가 죽으면 발송이 통째로 날아간다.
#   systemd Restart=always 는 **프로세스** 재시작만 커버한다 — 커널 패닉, 하드웨어 장애,
#   네트워크 단절, 인스턴스 종료는 커버하지 못한다. 그 구멍을 여기서 막는다.
#
#   StatusCheckFailed 는 인스턴스 상태 검사(EC2)와 시스템 상태 검사(하드웨어)를 합친 지표다
#   (StatusCheckFailed_Instance + StatusCheckFailed_System). 둘 중 하나라도 실패하면 1 이다.
#
#   treat_missing_data = "breaching" 이 핵심이다.
#     인스턴스가 종료·중지되면 지표 자체가 **사라진다**. missing 을 정상으로 취급하면
#     "서버가 없어진 상황"이 알람에 잡히지 않는다 — 정확히 우리가 두려워하는 시나리오다.
#     대신 terraform apply -replace 로 인스턴스를 교체하는 동안에도 알람이 울린다.
#     계획된 교체 때 메일 한 통 오는 것은 감수할 만한 대가다.
# =====================================================================
resource "aws_cloudwatch_metric_alarm" "ec2_status_check" {
  alarm_name = "${var.name_prefix}-ec2-status-check-failed"
  alarm_description = join(" ", [
    "앱 EC2 상태 검사 실패(또는 인스턴스 소실).",
    "발송 배치가 이 인스턴스 하나에 올라가 있으므로 21:50 전에는 즉시 조치해야 한다.",
    "확인: aws ec2 describe-instance-status --instance-ids ${var.ec2_instance_id}",
  ])

  namespace   = "AWS/EC2"
  metric_name = "StatusCheckFailed"
  statistic   = "Maximum"

  dimensions = {
    InstanceId = var.ec2_instance_id
  }

  # 1분 주기로 2회 연속 실패해야 알람 — 순간적인 지표 튐으로 인한 오탐을 막으면서도
  # 2분이면 21:50 발송까지 손 쓸 시간이 충분하다.
  period              = 60
  evaluation_periods  = 2
  datapoints_to_alarm = 2

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1

  treat_missing_data = "breaching" # 위 주석 참고 — 인스턴스가 사라지면 지표도 사라진다

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn] # 복구되었는지도 알아야 한다

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-ec2-status-check-failed"
  })
}

# =====================================================================
# 2) 발송 하트비트 누락  ★ 이 모듈에서 가장 중요한 알람
#
#   앱은 21:50 발송 배치가 끝나면 Imlate/DispatchCompleted 를 1회 올린다(값 1 이상).
#   이 알람은 "그 하트비트가 오지 않았다"를 잡는다.
#
#   ─────────────────────────────────────────────────────────────────
#   먼저, 순진한 설정이 왜 위험한지
#   ─────────────────────────────────────────────────────────────────
#   "period 5분 + evaluation_periods 1 + treat_missing_data = breaching" 처럼 잡으면
#   하루 23시간 55분 동안 데이터가 없으므로 알람은 **거의 항상 ALARM** 이다.
#   그런데 CloudWatch 는 상태가 **바뀔 때만** SNS 에 알린다.
#
#     → 발송이 성공한 날: 21:50 에 ALARM→OK, 21:55 에 OK→ALARM. 매일 알림 2통. 소음.
#     → 발송이 실패한 날: 이미 ALARM 이라 **상태 변화가 없다 → 알림이 오지 않는다.**
#
#   즉 순진하게 만들면 "실패한 날에만 조용한" 알람이 된다. 없는 것보다 나쁘다.
#   그래서 설계를 뒤집었다. **정상일 때 OK 를 유지하고, 실패한 날에만 딱 한 번 전이한다.**
#
#   ─────────────────────────────────────────────────────────────────
#   실제 설계: "최근 24시간 안에 하트비트가 한 번이라도 있었는가"
#   ─────────────────────────────────────────────────────────────────
#     period              = 300  (5분)
#     evaluation_periods  = 288  (288 × 5분 = 86,400초 = 24시간)
#     datapoints_to_alarm = 288  (288개 구간이 "전부" 비어야 알람)
#     statistic           = Sum,  Sum < 1 이면 위반
#     treat_missing_data  = breaching  (데이터 없음 = 위반)
#
#   → 최근 24시간 안에 하트비트가 하나라도 있으면 위반 구간이 287개뿐이라 **OK**.
#     하나도 없어야 288/288 위반 → **ALARM**.
#
#   시간축으로 따라가 보면:
#     어제 21:50 의 하트비트는 [21:50, 21:55) 구간에 들어간다.
#     이 구간은 오늘 21:55 에 24시간 창 밖으로 밀려난다.
#       · 오늘 21:50 발송 성공 → 밀려나기 5분 전에 오늘 것이 들어온다 → OK 유지, 알림 없음
#       · 오늘 발송이 안 돎    → 창이 완전히 비는 순간 ALARM 전이
#                                → **21:55~22:10 KST 사이에 메일** (통금 22:30 까지 20분 이상 여유)
#
#   ─────────────────────────────────────────────────────────────────
#   왜 period=300 / evaluation_periods=288 인가 (숫자의 근거)
#   ─────────────────────────────────────────────────────────────────
#   · CloudWatch 알람의 평가 구간 상한은 24시간이다(period × evaluation_periods ≤ 86400).
#     "하루 한 번만 오는 신호"를 정상으로 유지하려면 창을 **정확히 24시간**으로 꽉 채워야 한다.
#     그래서 곱이 정확히 86,400 이 되는 조합만 후보다.
#   · period=3600 / N=24 도 곱이 86,400 이고, 판정 시각이 매일 22:00 정각으로 고정되어
#     예측 가능하다는 장점이 있다. 그런데 CloudWatch 는 늦게 도착한 데이터를 보정하려고
#     평가 구간보다 몇 개 뒤의 데이터포인트까지 조회한다. 그 "몇 개"가 1시간짜리면
#     전이가 최대 몇 시간 밀릴 수 있고, 그러면 22:30 을 놓친다.
#     period=300 이면 같은 지연이 10~15분에 그친다. **늦는 것보다 조금 이른 편이 맞다.**
#   · 하한도 있다. period 를 더 줄이면(60초) evaluation_periods 가 1440 이 되어
#     CloudWatch 상한(1440)에 딱 걸리고, 알람 평가 비용/지연만 늘 뿐 얻는 게 없다.
#
#   ─────────────────────────────────────────────────────────────────
#   한계 — 이 알람이 못 하는 것 (알고 쓰라고 적어 둔다)
#   ─────────────────────────────────────────────────────────────────
#   (a) 판정 시각이 "어제 하트비트가 찍힌 시각 + 5분"에 종속된다. 앱이 21:50 에 꾸준히
#       올리면 매일 21:55 전후로 판정된다. 어제 발송이 22:05 재시도에서야 성공했다면
#       오늘 판정도 22:10 으로 밀린다. 22:15 를 넘겨 성공한 날이 있으면 다음 날 판정이
#       22:30 을 넘길 수 있다 — 그런 날은 어차피 사람이 이미 붙어 있는 날이다.
#   (b) 판정이 22:05 / 22:20 재시도보다 이르다. 이건 의도한 것이다. DispatchCompleted 는
#       "21:50 배치가 돌기는 했는가"의 하트비트이지 "전원에게 도착했는가"가 아니다
#       (후자는 아래 DispatchFailures 알람이 본다). 21:55 에 하트비트가 없다 =
#       배치 자체가 안 돌았다 = 재시도를 기다릴 게 아니라 지금 손을 써야 한다.
#   (c) 이틀 연속 실패하면 이미 ALARM 이라 둘째 날에는 새 메일이 오지 않는다.
#       CloudWatch 알람의 공통 제약이다. 알람이 ALARM 에 머물러 있는 것 자체가 신호이므로
#       한 번 울리면 반드시 원인을 없애고 OK 로 되돌려야 한다.
#   (d) 완전히 결정적인 감시(예: "매일 22:25 정각에 판정")가 필요하면 CloudWatch 알람만으로는
#       불가능하다. 알람에는 시각 개념이 없기 때문이다. 그때는
#       EventBridge Scheduler(cron 25 22 * * ? *, Asia/Seoul) → Lambda → GetMetricData 판정
#       → SNS 게시 구조로 가야 한다. Lambda 코드가 필요해 이번 범위에서는 만들지 않았다.
# =====================================================================
resource "aws_cloudwatch_metric_alarm" "dispatch_heartbeat_missing" {
  count = var.enable_dispatch_heartbeat_alarm ? 1 : 0

  alarm_name = "${var.name_prefix}-dispatch-heartbeat-missing"
  alarm_description = join(" ", [
    "최근 24시간 동안 ${var.dispatch_metric_namespace}/${var.dispatch_completed_metric_name} 하트비트가 한 번도 없다.",
    "21:50 사감 발송 배치가 돌지 않았을 가능성이 크다. 22:30 문 잠김 전에 확인할 것.",
    "1) 앱 살아 있는지: curl -fsS https://skala-imlate.link/actuator/health",
    "2) 발송 이력: 관리 API 의 notification_dispatch 조회",
    "3) 필요하면 관리 API 로 수동 발송 트리거.",
  ])

  namespace   = var.dispatch_metric_namespace
  metric_name = var.dispatch_completed_metric_name
  statistic   = "Sum"
  dimensions  = var.dispatch_metric_dimensions

  # 숫자의 근거는 위 주석 "왜 period=300 / evaluation_periods=288 인가" 참고.
  period              = 300
  evaluation_periods  = 288
  datapoints_to_alarm = 288

  comparison_operator = "LessThanThreshold"
  threshold           = 1

  # 하트비트 감시의 핵심. "데이터가 없다" = "발송이 없었다" 이므로 위반으로 취급한다.
  treat_missing_data = "breaching"

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn] # 늦게라도 하트비트가 들어오면 알려 준다

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-dispatch-heartbeat-missing"
  })
}

# =====================================================================
# 3) 발송 실패 발생
#
#   실제로 운영 중 두 번 터졌던 유형이다(알리고 발신 IP 미등록, SES 아이덴티티 미검증).
#   그때는 notification_dispatch 테이블에 기록만 남고 아무도 몰랐다 — 관리 API 를
#   직접 조회하고 나서야 알았다. 그 침묵을 없애는 알람이다.
#
#   treat_missing_data = "notBreaching" 이 맞다.
#     이 지표는 "실패가 있을 때만" 올라온다. 데이터 없음 = 실패 없음 = 정상이다.
#     (하트비트 알람과 정반대다. 하트비트는 "없음"이 곧 사고지만, 실패 건수는 "없음"이 정상이다.)
#
#   ok_actions 는 일부러 비웠다. 5분 뒤 실패 지표가 안 올라온다고 해서 문제가 해결된 것은
#   아니다("복구됨" 메일은 거짓 안도감을 준다). ALARM 메일 한 통이면 충분하고,
#   상태가 OK 로 돌아가야 다음 실패 때 다시 울릴 수 있다.
# =====================================================================
resource "aws_cloudwatch_metric_alarm" "dispatch_failures" {
  alarm_name = "${var.name_prefix}-dispatch-failures"
  alarm_description = join(" ", [
    "사감 발송 실패가 발생했다(${var.dispatch_metric_namespace}/${var.dispatch_failures_metric_name} > 0).",
    "과거 원인 1순위는 알리고 발신 IP 화이트리스트 누락, 2순위는 SES 아이덴티티 미검증이다.",
    "터미널: terraform output -raw aligo_whitelist_ip 로 현재 발신 IP 를 확인해 알리고에 등록되어 있는지 대조.",
    "22:05 / 22:20 자동 재시도가 남아 있으므로 그 전에 원인을 제거하면 복구된다.",
  ])

  namespace   = var.dispatch_metric_namespace
  metric_name = var.dispatch_failures_metric_name
  statistic   = "Sum"
  dimensions  = var.dispatch_metric_dimensions

  # 5분 1회. 21:50 실패를 22:05 재시도 전에 알 수 있어야 의미가 있다.
  period              = 300
  evaluation_periods  = 1
  datapoints_to_alarm = 1

  comparison_operator = "GreaterThanThreshold"
  threshold           = 0

  treat_missing_data = "notBreaching" # 실패 지표 없음 = 실패 없음

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-dispatch-failures"
  })
}

# =====================================================================
# 4) 인프라 기본 — "곧 죽는다"를 미리 잡는 것만
#
#   여기에 알람을 더 넣고 싶은 유혹이 크지만 참았다.
#   알람이 많아지면 아무도 안 보고, 안 보는 알람은 없는 알람이다.
#   지금 없는 것들과 그 이유:
#     · RDS 연결 수 / 읽기 지연  → 200명 규모에서 임계값을 정할 근거가 없다. 먼저 관측부터.
#     · EC2 CPU                  → t3.small 은 크레딧 기반이라 CPU 100% 자체가 사고가 아니다.
#                                  진짜 신호는 CPUCreditBalance 인데, 이 앱은 21:50 몇 초를
#                                  빼면 사실상 유휴라 크레딧이 마를 일이 없다.
#     · 메모리                    → JVM 이 -Xmx1024m 로 고정되어 있고 OOM 이면 systemd 가
#                                  재시작한다. 그 재시작이 발송을 놓치는지는 하트비트가 잡는다.
#     · ALB / WAF                 → 기본 구성에서 아예 만들어지지 않는다.
# =====================================================================

# ---- RDS: 여유 스토리지 부족 ----
#   스토리지가 마르면 MySQL 쓰기가 막히고 = 등록도 발송 이력 기록도 실패한다.
#   오토스케일링(20→100 GiB)이 있어도 6시간 쿨다운과 상한이 있으므로 감시가 필요하다.
resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  count = local.rds_enabled ? 1 : 0

  alarm_name = "${var.name_prefix}-rds-free-storage-low"
  alarm_description = join(" ", [
    "RDS 여유 스토리지가 ${floor(var.rds_free_storage_threshold_bytes / 1073741824)} GiB 아래로 내려갔다.",
    "스토리지가 마르면 등록/발송 이력 쓰기가 통째로 실패한다.",
    "조치: db_max_allocated_storage 상한 확인 후 db_allocated_storage 증설 또는 오래된 로그 정리.",
  ])

  namespace   = "AWS/RDS"
  metric_name = "FreeStorageSpace"
  statistic   = "Average"

  dimensions = {
    DBInstanceIdentifier = var.rds_identifier
  }

  # 스토리지는 천천히 마른다. 10분(5분 × 2회)이면 오탐 없이 충분히 빠르다.
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2

  comparison_operator = "LessThanThreshold"
  threshold           = var.rds_free_storage_threshold_bytes

  # RDS 지표가 끊기는 것은 RDS 이벤트로 따로 드러난다. 여기서 breaching 으로 잡으면
  # 유지보수 창마다 오탐이 난다.
  treat_missing_data = "missing"

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rds-free-storage-low"
  })
}

# ---- RDS: CPU 과다 ----
#   db.t4g.micro 다. CPU 가 15분 연속 80% 를 넘으면 21:45 마감 직전 몰림을 못 버틴다는 뜻이다.
resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  count = local.rds_enabled ? 1 : 0

  alarm_name = "${var.name_prefix}-rds-cpu-high"
  alarm_description = join(" ", [
    "RDS CPU 사용률이 15분 연속 ${var.rds_cpu_threshold_percent}% 를 넘었다.",
    "21:45 마감 직전 등록 몰림을 못 버티면 등록 자체가 실패한다.",
    "조치: 느린 쿼리 확인 후 인스턴스 클래스 상향(db_instance_class).",
  ])

  namespace   = "AWS/RDS"
  metric_name = "CPUUtilization"
  statistic   = "Average"

  dimensions = {
    DBInstanceIdentifier = var.rds_identifier
  }

  # 짧은 스파이크는 정상이다(백업, 마감 직전 몰림). 15분 연속일 때만 사람을 부른다.
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.rds_cpu_threshold_percent

  treat_missing_data = "missing"

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rds-cpu-high"
  })
}

# ---- ElastiCache: 메모리 부족 ----
#   maxmemory-policy = volatile-lru 라 TTL 없는 통계 키는 축출되지 않는다.
#   즉 메모리가 차면 조용히 축출되는 게 아니라 **쓰기가 실패**한다(WAL 기록 포함).
resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  for_each = local.redis_node_suffixes

  alarm_name = "${var.name_prefix}-redis-memory-high-${each.value}"
  alarm_description = join(" ", [
    "Redis 노드 ${each.key} 의 메모리 사용률이 ${var.redis_memory_threshold_percent}% 를 넘었다.",
    "maxmemory-policy 가 volatile-lru 라 TTL 없는 통계 키는 축출되지 않는다 — 차면 쓰기가 실패한다.",
    "조치: imlate:stats:* 정리 또는 redis_node_type 상향.",
  ])

  namespace   = "AWS/ElastiCache"
  metric_name = "DatabaseMemoryUsagePercentage"
  statistic   = "Average"

  dimensions = {
    CacheClusterId = each.key
  }

  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.redis_memory_threshold_percent

  treat_missing_data = "missing"

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis-memory-high-${each.value}"
  })
}

# ---- EC2: 루트 디스크 사용률 ----
#   /var/log/imlate 의 로그가 쌓여 디스크가 차면 앱이 기동에 실패하거나 기록을 못 한다.
#   CloudWatch Agent 가 설치되어 있어야 지표가 올라온다(install_cloudwatch_agent).
#
#   disk 플러그인은 지표에 path/device/fstype 차원을 함께 붙이는데 device 이름이
#   인스턴스 세대에 따라 다르다. 후보를 각각 조회한 뒤 metric math MAX 로 합친다.
#   (SEARCH() 는 알람에서 쓸 수 없으므로 후보 나열 방식을 택했다.)
#
#   ※ 후보가 전부 빗나가면 이 알람은 데이터를 못 찾아 INSUFFICIENT_DATA 로 남는다.
#     apply 후 한 번은 상태를 확인할 것 — README 의 "설치 후 확인" 절 참고.
resource "aws_cloudwatch_metric_alarm" "ec2_disk_used" {
  count = local.disk_enabled ? 1 : 0

  alarm_name = "${var.name_prefix}-ec2-disk-used-high"
  alarm_description = join(" ", [
    "앱 EC2 루트 파일시스템 사용률이 ${var.disk_used_percent_threshold}% 를 넘었다.",
    "디스크가 차면 로그 기록과 배포(jar 교체)가 실패한다.",
    "조치: sudo journalctl --vacuum-time=3d, /var/log/imlate/*.gz 정리, 필요하면 root_volume_size 증설.",
  ])

  metric_query {
    id          = "e1"
    expression  = local.disk_expression
    label       = "루트 파일시스템 사용률(%)"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = { for i, device in var.disk_devices : local.disk_metric_ids[i] => device }

    content {
      id = metric_query.key

      metric {
        namespace   = var.cw_agent_namespace
        metric_name = "disk_used_percent"
        period      = 300
        stat        = "Average"

        dimensions = {
          InstanceId = var.ec2_instance_id
          path       = var.disk_path
          device     = metric_query.value
          fstype     = var.disk_fstype
        }
      }
    }
  }

  # 15분 연속 초과일 때만. 디스크는 천천히 차므로 급할 것 없다.
  evaluation_periods  = 3
  datapoints_to_alarm = 3

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.disk_used_percent_threshold

  # 에이전트가 죽어 지표가 끊기는 것을 "디스크 가득"으로 오해하면 안 된다.
  # 인스턴스 자체의 생사는 위 StatusCheckFailed 알람이 본다.
  treat_missing_data = "missing"

  alarm_actions = [aws_sns_topic.alerts.arn]

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-ec2-disk-used-high"
  })
}
