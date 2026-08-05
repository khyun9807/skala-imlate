# elasticache 모듈 변수

variable "replication_group_id" {
  description = "복제 그룹 ID (예: imlate-prod-redis). 소문자/숫자/하이픈 40자 이내."
  type        = string
}

variable "name_prefix" {
  description = "부속 리소스(서브넷 그룹, 파라미터 그룹) 이름 접두어"
  type        = string
}

variable "subnet_ids" {
  description = "캐시 서브넷 그룹에 넣을 프라이빗 서브넷 ID 목록"
  type        = list(string)
}

variable "security_group_ids" {
  description = "복제 그룹에 붙일 보안 그룹 ID 목록"
  type        = list(string)
}

variable "engine_version" {
  description = "Redis 엔진 버전"
  type        = string
  default     = "7.1"
}

variable "parameter_group_family" {
  description = "파라미터 그룹 패밀리"
  type        = string
  default     = "redis7"
}

variable "node_type" {
  description = "노드 타입"
  type        = string
  default     = "cache.t4g.micro"
}

variable "num_cache_clusters" {
  description = "노드 수. 1 이면 단일 노드, 2 이상이면 자동 장애조치 활성화."
  type        = number
  default     = 1
}

variable "port" {
  description = "Redis 포트"
  type        = number
  default     = 6379
}

variable "multi_az_enabled" {
  description = "Multi-AZ 여부(num_cache_clusters >= 2 일 때만 적용)"
  type        = bool
  default     = false
}

variable "at_rest_encryption_enabled" {
  description = "저장 데이터 암호화"
  type        = bool
  default     = true
}

variable "transit_encryption_enabled" {
  description = "전송 구간 암호화(TLS)"
  type        = bool
  default     = true
}

variable "auth_enabled" {
  description = "AUTH token 사용 여부"
  type        = bool
  default     = true
}

variable "auth_token" {
  description = "AUTH token. null 이면 random_password 로 생성한다."
  type        = string
  default     = null
  sensitive   = true
}

variable "maxmemory_policy" {
  description = "메모리 축출 정책. 통계 누계 키(TTL 없음)를 보호하려면 volatile-* 계열을 쓴다."
  type        = string
  default     = "volatile-lru"
}

variable "snapshot_retention_limit" {
  description = "자동 스냅샷 보존 일수. 0 이면 백업 비활성(WAL 은 DB 로 대사되므로 기본 0)."
  type        = number
  default     = 0
}

variable "maintenance_window" {
  description = "유지보수 창(UTC). 기본값은 KST 화요일 04:00~05:00."
  type        = string
  default     = "tue:19:00-tue:20:00"
}

variable "apply_immediately" {
  description = "변경 즉시 적용 여부"
  type        = bool
  default     = false
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
