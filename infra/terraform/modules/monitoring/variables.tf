# =====================================================================
# monitoring 모듈 변수
#
#   원칙
#   - 임계값은 전부 "실제로 사람이 조치해야 하는 수준"에 맞춘다.
#     조치할 수 없는 알람은 알람이 아니라 소음이다.
#   - 모든 값에 기본값이 있다. tfvars 를 고치지 않아도 동작해야 한다.
# =====================================================================

variable "name_prefix" {
  description = "리소스 이름 접두어(예: imlate-prod)"
  type        = string
}

variable "alert_email" {
  description = <<-EOT
    운영자 알림 수신 이메일.

    ★ 사감 수신 주소(supervisor*_email)와 **의미가 다른 별도 채널**이다.
      사감에게 가는 것은 "오늘 야간복귀 명단"뿐이고, 이 주소로 가는 것은
      "시스템이 고장났다"는 운영자용 신호다. 절대 이 값에 사감 연락처를
      그대로 넣지 말 것(문자 발송 대상과 알람 대상이 섞이면 사감이 새벽에
      CloudWatch 알람 메일을 받게 된다).

    ※ 구독은 **수신자가 AWS 확인 메일의 링크를 눌러야** 활성화된다.
      누르기 전까지 알람은 울려도 메일이 오지 않는다(PendingConfirmation).
  EOT
  type        = string

  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", trimspace(var.alert_email)))
    error_message = "alert_email 은 유효한 이메일 주소여야 합니다(모듈은 alert_email 이 비면 아예 생성되지 않습니다)."
  }
}

variable "tags" {
  description = "추가 태그(provider default_tags 위에 병합된다)"
  type        = map(string)
  default     = {}
}

# ---------------------------------------------------------------------
# 감시 대상 식별자
# ---------------------------------------------------------------------
variable "ec2_instance_id" {
  description = "애플리케이션 EC2 인스턴스 ID(StatusCheckFailed / 디스크 알람의 차원)"
  type        = string
}

variable "rds_identifier" {
  description = "RDS 인스턴스 식별자. 비우면 RDS 알람을 만들지 않는다."
  type        = string
  default     = ""
}

variable "redis_replication_group_id" {
  description = <<-EOT
    ElastiCache 복제 그룹 ID. 비우면 Redis 알람을 만들지 않는다.

    ※ ElastiCache 지표의 차원은 복제 그룹이 아니라 **노드 클러스터(CacheClusterId)** 다.
      복제 그룹 하위 노드 이름은 AWS 규칙상 "<복제그룹ID>-001", "-002" … 로 매겨진다.
      그래서 여기서는 이름을 계산해서 쓴다(data source 로 조회하면 최초 apply 때
      for_each 값이 unknown 이 되어 plan 이 실패한다).
      확인: aws elasticache describe-replication-groups --replication-group-id <ID>
  EOT
  type        = string
  default     = ""
}

variable "redis_num_cache_clusters" {
  description = "복제 그룹의 노드 수. 노드마다 메모리 알람을 하나씩 만든다."
  type        = number
  default     = 1

  validation {
    condition     = var.redis_num_cache_clusters >= 1 && var.redis_num_cache_clusters <= 6
    error_message = "redis_num_cache_clusters 는 1~6 이어야 합니다."
  }
}

# ---------------------------------------------------------------------
# 발송 하트비트 (이 모듈의 존재 이유)
# ---------------------------------------------------------------------
variable "enable_dispatch_heartbeat_alarm" {
  description = <<-EOT
    발송 하트비트(Imlate/DispatchCompleted) 누락 알람 생성 여부.

    ★ 앱이 이 지표를 아직 올리지 않는 상태에서 켜면, 최근 24시간 데이터가 통째로
      없으므로 알람이 만들어지자마자 ALARM 으로 들어가 메일이 한 통 온다.
      앱 배포 전이라면 false 로 두었다가 앱이 올라간 다음 날 true 로 바꾸는 것이 깔끔하다.
  EOT
  type        = bool
  default     = true
}

variable "dispatch_metric_namespace" {
  description = "앱이 발송 지표를 올리는 CloudWatch 네임스페이스"
  type        = string
  default     = "Imlate"
}

variable "dispatch_completed_metric_name" {
  description = "발송 완료 하트비트 지표 이름. 앱은 21:50 배치가 끝나면 값 1 이상으로 1회 올린다."
  type        = string
  default     = "DispatchCompleted"
}

variable "dispatch_failures_metric_name" {
  description = "발송 실패 건수 지표 이름. 실패가 없으면 아예 올리지 않아도 되고 0 을 올려도 된다."
  type        = string
  default     = "DispatchFailures"
}

variable "dispatch_metric_dimensions" {
  description = <<-EOT
    발송 지표의 차원. 기본은 빈 맵 = **차원 없이** 올린다는 뜻이다.

    앱과 이 값이 어긋나면 알람은 영원히 데이터를 찾지 못한다(하트비트 알람은
    그 상태에서 계속 ALARM 이므로 최소한 조용히 실패하지는 않는다).
    앱이 예를 들어 Env 차원을 붙인다면 여기에도 { Env = "prod" } 를 넣어야 한다.
  EOT
  type        = map(string)
  default     = {}
}

# ---------------------------------------------------------------------
# 인프라 임계값
# ---------------------------------------------------------------------
variable "rds_free_storage_threshold_bytes" {
  description = <<-EOT
    RDS 여유 스토리지 하한(바이트). 이보다 적으면 알람.

    기본 2 GiB — 초기 할당 20 GiB 의 10% 다. 스토리지 오토스케일링(최대 100 GiB)이
    켜져 있으므로 보통은 자동으로 늘어나지만, 오토스케일링은 6시간 쿨다운이 있고
    상한에 닿으면 더는 늘지 않는다. 그때 DB 쓰기가 막히면 등록 자체가 실패한다.
  EOT
  type        = number
  default     = 2147483648
}

variable "rds_cpu_threshold_percent" {
  description = "RDS CPU 사용률 상한(%). 15분 연속 초과 시 알람."
  type        = number
  default     = 80
}

variable "redis_memory_threshold_percent" {
  description = <<-EOT
    Redis 메모리 사용률 상한(%). DatabaseMemoryUsagePercentage 기준.

    maxmemory-policy 가 volatile-lru 라 TTL 없는 통계 키는 축출되지 않는다.
    즉 메모리가 차면 축출이 아니라 **쓰기 실패(OOM)** 가 난다. 여유 있게 80% 에서 잡는다.
  EOT
  type        = number
  default     = 80
}

variable "enable_disk_alarm" {
  description = "EC2 루트 파일시스템 사용률 알람 생성 여부. CloudWatch Agent 를 설치한 경우에만 의미가 있다."
  type        = bool
  default     = true
}

variable "cw_agent_namespace" {
  description = "CloudWatch Agent 지표 네임스페이스(에이전트 기본값 CWAgent)"
  type        = string
  default     = "CWAgent"
}

variable "disk_used_percent_threshold" {
  description = "EC2 루트 파일시스템 사용률 상한(%). 로그가 쌓여 디스크가 차면 앱이 기동/기록에 실패한다."
  type        = number
  default     = 85
}

variable "disk_path" {
  description = "감시할 마운트 경로. CloudWatch Agent 설정(modules/ec2)이 \"/\" 만 수집한다."
  type        = string
  default     = "/"
}

variable "disk_devices" {
  description = <<-EOT
    루트 파일시스템의 후보 디바이스 이름 목록.

    CloudWatch Agent 의 disk 플러그인은 지표에 path/device/fstype 차원을 함께 붙인다.
    device 이름은 인스턴스 세대에 따라 달라서(Nitro=nvme0n1p1, Xen=xvda1) 하나로 못 박을 수 없다.
    그래서 후보를 모두 조회한 뒤 metric math MAX([...]) 로 합친다.
    존재하지 않는 후보는 데이터가 없을 뿐 오류가 아니다.

    실제 값 확인:
      aws cloudwatch list-metrics --namespace CWAgent --metric-name disk_used_percent
  EOT
  type        = list(string)
  default     = ["nvme0n1p1", "xvda1"]

  validation {
    condition     = length(var.disk_devices) >= 1
    error_message = "disk_devices 는 최소 1개 이상이어야 합니다."
  }
}

variable "disk_fstype" {
  description = "루트 파일시스템 종류(Amazon Linux 2023 = xfs)"
  type        = string
  default     = "xfs"
}
