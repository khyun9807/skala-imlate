# =====================================================================
# imlate — 루트 모듈 변수
#
#   원칙
#   - 모든 변수에 description + type 을 붙인다.
#   - 인프라 형태를 바꾸는 값은 합리적 기본값을 둔다(= tfvars 없이도 plan 가능).
#   - 진짜 비밀값(알리고 키, 사감 연락처)은 default 없이 sensitive = true.
#   - 자동 생성 가능한 비밀값(DB 비밀번호, 조회 토큰 시크릿, 관리자 키, Redis
#     auth token)은 default = null 로 두고 비어 있으면 random_password 로 생성한다.
# =====================================================================

# ---------------------------------------------------------------------
# 공통
# ---------------------------------------------------------------------
variable "aws_region" {
  description = "리소스를 생성할 AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project" {
  description = "프로젝트 식별자. 리소스 이름 접두어와 SSM 파라미터 경로에 사용된다."
  type        = string
  default     = "imlate"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.project))
    error_message = "project 는 소문자/숫자/하이픈 2~21자여야 합니다."
  }
}

variable "environment" {
  description = "환경 이름(prod, stage, dev …). 태그 Env 와 SSM 경로에 사용된다."
  type        = string
  default     = "prod"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.environment))
    error_message = "environment 는 소문자/숫자/하이픈 2~16자여야 합니다."
  }
}

variable "additional_tags" {
  description = "모든 리소스에 추가로 붙일 태그(default_tags 에 병합)"
  type        = map(string)
  default     = {}
}

variable "timezone" {
  description = "서비스 표준 시간대. EC2 시스템 시간과 imlate.timezone 프로퍼티에 사용된다."
  type        = string
  default     = "Asia/Seoul"
}

# ---------------------------------------------------------------------
# 네트워크
# ---------------------------------------------------------------------
variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.20.0.0/16"
}

variable "availability_zones" {
  description = "사용할 가용영역 목록(2개). 비우면 리전에서 자동 선택한다."
  type        = list(string)
  default     = []
}

variable "enable_nat_gateway" {
  description = "프라이빗 서브넷의 아웃바운드용 NAT Gateway 생성 여부. false 로 두면 프라이빗 EC2 가 SSM/SES 에 접근하지 못한다."
  type        = bool
  default     = true
}

variable "single_nat_gateway" {
  description = "NAT Gateway 를 1개만 만들어 비용을 절감할지 여부(true 권장, 200명 규모). false 면 AZ 당 1개."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------
# 보안 그룹 / 접근 제어
# ---------------------------------------------------------------------
variable "ssh_allowed_cidrs" {
  description = "앱 서버 22 번 포트 접근을 허용할 CIDR 목록. 비우면 SSH 인바운드를 열지 않는다(SSM Session Manager 사용 권장)."
  type        = list(string)
  default     = []
}

variable "alb_ingress_cidrs" {
  description = "ALB 80/443 인바운드를 허용할 CIDR 목록"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "app_port" {
  description = "Spring Boot 애플리케이션 포트"
  type        = number
  default     = 8080
}

variable "web_port" {
  description = "EC2 nginx 포트(프론트 정적 서빙 + /api 리버스 프록시)"
  type        = number
  default     = 80
}

# ---------------------------------------------------------------------
# ALB
# ---------------------------------------------------------------------
variable "enable_alb" {
  description = "Application Load Balancer 생성 여부"
  type        = bool
  default     = true
}

variable "alb_internal" {
  description = "ALB 를 내부 전용(internal)으로 만들지 여부"
  type        = bool
  default     = false
}

variable "alb_target_port" {
  description = "ALB 타겟 그룹이 바라볼 EC2 포트. 8080 = Spring 직결, 80 = nginx 경유(프론트 정적 서빙 포함)."
  type        = number
  default     = 8080
}

variable "alb_health_check_path" {
  description = <<-EOT
    ALB 헬스체크 경로. 기본값은 Redis 를 제외한 전용 헬스 그룹(db + ping)이다.
    전체 /actuator/health 를 쓰면 ElastiCache 장애 때 모든 인스턴스가 unhealthy 로 빠져
    서비스가 통째로 내려간다. 이 앱은 Redis 없이도 등록을 계속 처리하도록 설계되어 있으므로
    로드밸런서 판정에서는 Redis 를 빼는 것이 맞다.
  EOT
  type        = string
  default     = "/actuator/health/alb"
}

variable "acm_certificate_arn" {
  description = "HTTPS 리스너에 사용할 ACM 인증서 ARN. 비우면 443 리스너를 만들지 않는다."
  type        = string
  default     = ""
}

variable "alb_redirect_http_to_https" {
  description = "ACM 인증서가 있을 때 80 → 443 리다이렉트를 걸지 여부"
  type        = bool
  default     = true
}

variable "alb_deletion_protection" {
  description = "ALB 삭제 방지"
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------
# WAF (rate limiting 2단 방어)
# ---------------------------------------------------------------------
variable "enable_waf" {
  description = "ALB 앞단 AWS WAFv2 Web ACL 생성 여부. 애플리케이션 Redis 리미터와 2단 방어를 구성한다."
  type        = bool
  default     = true
}

variable "waf_rate_limit_per_5min" {
  description = "WAF rate-based rule: 5분 동안 동일 IP 가 보낼 수 있는 최대 요청 수(초과 시 차단)"
  type        = number
  default     = 2000

  validation {
    condition     = var.waf_rate_limit_per_5min >= 100
    error_message = "AWS WAF rate-based rule 의 최소값은 100 입니다."
  }
}

variable "waf_managed_rules_count_only" {
  description = "AWSManagedRulesCommonRuleSet 을 차단이 아닌 카운트 모드로 둘지 여부(초기 튜닝 시 true 권장)"
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------
# EC2 (애플리케이션 서버)
# ---------------------------------------------------------------------
variable "instance_type" {
  description = "애플리케이션 EC2 인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "instance_architecture" {
  description = "AMI 아키텍처. instance_type 과 반드시 맞춰야 한다(t3 계열 = x86_64, t4g 계열 = arm64)."
  type        = string
  default     = "x86_64"

  validation {
    condition     = contains(["x86_64", "arm64"], var.instance_architecture)
    error_message = "instance_architecture 는 x86_64 또는 arm64 여야 합니다."
  }
}

variable "ami_id" {
  description = "직접 지정할 AMI ID. 비우면 최신 Amazon Linux 2023 AMI 를 조회한다."
  type        = string
  default     = ""
}

variable "public_app" {
  description = "true 면 앱 EC2 를 퍼블릭 서브넷에 배치하고 퍼블릭 IP 를 부여한다(ALB 없이 단독 운영용). 기본은 프라이빗 서브넷."
  type        = bool
  default     = false
}

variable "associate_elastic_ip" {
  description = "앱 EC2 에 고정 EIP 를 붙일지 여부(public_app = true 일 때만 의미 있음)"
  type        = bool
  default     = false
}

variable "key_pair_name" {
  description = "SSH 접속에 사용할 EC2 키페어 이름. 비우면 키페어를 붙이지 않는다(SSM Session Manager 사용)."
  type        = string
  default     = ""
}

variable "root_volume_size" {
  description = "루트 EBS 볼륨 크기(GiB). gp3 + 암호화 고정."
  type        = number
  default     = 30
}

variable "jvm_opts" {
  description = "애플리케이션 JVM 옵션(systemd JAVA_TOOL_OPTIONS 로 주입)"
  type        = string
  default     = "-Xms256m -Xmx1024m -XX:+UseSerialGC -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8"
}

variable "install_nginx" {
  description = "EC2 부팅 시 nginx 를 설치하고 imlate.conf 를 배치할지 여부(프론트 dist 정적 서빙)"
  type        = bool
  default     = true
}

variable "install_cloudwatch_agent" {
  description = "CloudWatch Agent 설치 여부(메모리/디스크 지표 + 애플리케이션 로그 수집)"
  type        = bool
  default     = true
}

variable "spring_profile" {
  description = "애플리케이션 Spring 프로파일"
  type        = string
  default     = "prod"
}

variable "artifact_bucket_name" {
  description = "배포 아티팩트(jar/dist)를 올릴 S3 버킷 이름. deploy.sh --mode ssm 을 쓸 때 지정한다(버킷 자체는 별도 관리). 비우면 EC2 에 S3 권한을 주지 않는다."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------
# RDS (MySQL 8)
# ---------------------------------------------------------------------
variable "db_instance_class" {
  description = "RDS 인스턴스 클래스"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "MySQL 엔진 버전(메이저만 지정하면 최신 마이너가 선택된다)"
  type        = string
  default     = "8.0"
}

variable "db_name" {
  description = "생성할 데이터베이스 이름"
  type        = string
  default     = "imlate"
}

variable "db_username" {
  description = "RDS 마스터 사용자 이름(MySQL 은 최대 16자)"
  type        = string
  default     = "imlate_admin"
}

variable "db_password" {
  description = "RDS 마스터 비밀번호. 비우면(null) random_password 로 생성하고 SSM SecureString 에 저장한다."
  type        = string
  default     = null
  sensitive   = true
}

variable "db_allocated_storage" {
  description = "RDS 초기 스토리지(GiB). gp3 최소값은 20."
  type        = number
  default     = 20
}

variable "db_max_allocated_storage" {
  description = "RDS 스토리지 오토스케일링 상한(GiB). 0 이면 비활성."
  type        = number
  default     = 100
}

variable "db_multi_az" {
  description = "RDS Multi-AZ 배치 여부"
  type        = bool
  default     = false
}

variable "db_backup_retention_days" {
  description = "자동 백업 보존 일수"
  type        = number
  default     = 7
}

variable "db_deletion_protection" {
  description = "RDS 삭제 방지"
  type        = bool
  default     = true
}

variable "db_skip_final_snapshot" {
  description = "삭제 시 최종 스냅샷 생략 여부(운영은 false 권장)"
  type        = bool
  default     = false
}

variable "db_apply_immediately" {
  description = "RDS 변경을 즉시 적용할지(false 면 다음 유지보수 창)"
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------
# ElastiCache (Redis 7)
# ---------------------------------------------------------------------
variable "redis_node_type" {
  description = "ElastiCache 노드 타입"
  type        = string
  default     = "cache.t4g.micro"
}

variable "redis_engine_version" {
  description = "Redis 엔진 버전"
  type        = string
  default     = "7.1"
}

variable "redis_num_cache_clusters" {
  description = "복제 그룹의 노드 수. 1 이면 단일 노드(개발/소규모), 2 이상이면 자동 장애조치가 켜진다."
  type        = number
  default     = 1

  validation {
    condition     = var.redis_num_cache_clusters >= 1 && var.redis_num_cache_clusters <= 6
    error_message = "redis_num_cache_clusters 는 1~6 이어야 합니다."
  }
}

variable "redis_multi_az_enabled" {
  description = "Redis Multi-AZ 여부(redis_num_cache_clusters >= 2 일 때만 적용)"
  type        = bool
  default     = false
}

variable "redis_transit_encryption_enabled" {
  description = "Redis 전송 구간 암호화(TLS). true 면 애플리케이션의 IMLATE_REDIS_SSL_ENABLED 도 true 로 주입된다."
  type        = bool
  default     = true
}

variable "redis_at_rest_encryption_enabled" {
  description = "Redis 저장 데이터 암호화"
  type        = bool
  default     = true
}

variable "redis_auth_enabled" {
  description = "Redis AUTH token 사용 여부. true 로 두려면 redis_transit_encryption_enabled 도 true 여야 한다."
  type        = bool
  default     = true
}

variable "redis_auth_token" {
  description = "Redis AUTH token. 비우면(null) random_password 로 생성한다. 16~128자, / \" @ 문자는 사용 불가."
  type        = string
  default     = null
  sensitive   = true
}

# ---------------------------------------------------------------------
# SES (메일 발송)
# ---------------------------------------------------------------------
variable "enable_ses" {
  description = "SES 이메일 아이덴티티를 Terraform 으로 관리할지 여부"
  type        = bool
  default     = true
}

variable "ses_identity" {
  description = "SES 검증 대상. 도메인(example.com) 또는 이메일 주소(noreply@example.com)."
  type        = string
  default     = ""
}

variable "ses_verify_supervisor_emails" {
  description = <<-EOT
    사감 수신 이메일을 SES 아이덴티티로 함께 등록할지 여부.

    SES 샌드박스(프로덕션 액세스 미승인)에서는 **수신자도 검증되어 있어야** 메일이 전달된다.
    프로덕션 액세스를 받았다면 false 로 두어도 된다.
    등록하면 각 주소로 AWS 확인 메일이 가고, 주소 소유자가 링크를 눌러야 Verified 가 된다.
  EOT
  type        = bool
  default     = true
}

variable "ses_additional_verified_emails" {
  description = "사감 외에 추가로 검증할 수신 이메일 주소 목록(운영자 알림용 등)"
  type        = list(string)
  default     = []
}

variable "ses_from_address" {
  description = "애플리케이션이 사용할 발신 주소(imlate.email.ses.from). 비우면 ses_identity 를 그대로 쓴다."
  type        = string
  default     = ""
}

variable "ses_from_name" {
  description = "메일 발신자 표시 이름"
  type        = string
  default     = "기숙사 야간복귀 시스템"
}

variable "enable_ses_configuration_set" {
  description = "SES configuration set 생성/사용 여부(전송 이벤트 지표 수집)"
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------
# 애플리케이션 시크릿 / 설정 (SSM Parameter Store 로 저장)
# ---------------------------------------------------------------------
variable "app_base_url" {
  description = "사감 조회 페이지 base URL(imlate.lookup.base-url). 비우면 ALB DNS 이름으로 자동 구성한다."
  type        = string
  default     = ""
}

variable "allowed_origin" {
  description = "CORS 허용 오리진(imlate.web.allowed-origins[0]). 비우면 app_base_url 과 동일하게 설정한다."
  type        = string
  default     = ""
}

variable "lookup_token_secret" {
  description = "조회 링크 HMAC 서명 시크릿. 비우면(null) 자동 생성한다."
  type        = string
  default     = null
  sensitive   = true
}

variable "admin_api_key" {
  description = "관리 API 보호 키(X-Admin-Key). 비우면(null) 자동 생성한다."
  type        = string
  default     = null
  sensitive   = true
}

variable "aligo_api_key" {
  description = "알리고 문자 API 키"
  type        = string
  sensitive   = true
}

variable "aligo_user_id" {
  description = "알리고 사용자 ID"
  type        = string
  sensitive   = true
}

variable "aligo_sender" {
  description = "알리고 발신번호(사전 등록된 번호)"
  type        = string
  sensitive   = true
}

variable "supervisor1_name" {
  description = "사감 1 이름"
  type        = string
  default     = "사감1"
}

variable "supervisor1_phone" {
  description = "사감 1 휴대폰 번호(01012345678)"
  type        = string
  sensitive   = true
}

variable "supervisor1_email" {
  description = "사감 1 이메일 주소"
  type        = string
  sensitive   = true
}

variable "supervisor2_name" {
  description = "사감 2 이름"
  type        = string
  default     = "사감2"
}

variable "supervisor2_phone" {
  description = "사감 2 휴대폰 번호(01012345678)"
  type        = string
  sensitive   = true
}

variable "supervisor2_email" {
  description = "사감 2 이메일 주소"
  type        = string
  sensitive   = true
}
