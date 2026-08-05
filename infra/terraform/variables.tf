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
  description = <<-EOT
    프라이빗 서브넷의 아웃바운드용 NAT Gateway 생성 여부.

    기본값 false — 200명 규모 서비스에 NAT 는 오버스펙이다(1개당 월 $35~40 + 데이터 전송료).
    앱 EC2 를 퍼블릭 서브넷에 두면(public_app = true) 인터넷 게이트웨이로 직접 나가므로
    NAT 없이도 SSM / SES / 알리고 / dnf 저장소에 모두 접근할 수 있다.

    트레이드오프
      false : 비용 0. 앱이 퍼블릭 서브넷에 놓이므로 보안 그룹이 유일한 방벽이다
              (인바운드는 80/443 만 열고 8080 은 열지 않는다).
      true  : 앱을 프라이빗 서브넷에 숨길 수 있다. public_app = false 와 함께 쓴다.
              RDS / ElastiCache 는 어느 쪽이든 프라이빗 서브넷에 남는다.
  EOT
  type        = bool
  default     = false
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
  description = "ALB 80/443 인바운드를 허용할 CIDR 목록(enable_alb = true 일 때만 사용)"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "direct_ingress_cidrs" {
  description = <<-EOT
    ALB 없이 인터넷 → EC2 로 직접 들어오는 80/443 을 허용할 CIDR 목록.
    enable_alb = false 일 때만 규칙이 생성된다(ALB 를 쓰면 우회 경로가 되므로 만들지 않는다).

    트레이드오프: 기본값 0.0.0.0/0 은 전면 공개다. 교육생 누구나 휴대폰으로 접속해야 하는
    서비스이므로 이게 맞다. 사내에서만 쓸 거라면 사무실/VPN 대역으로 좁힌다.
    Spring 포트(8080)는 어떤 경우에도 열지 않는다 — nginx 가 127.0.0.1 로만 프록시한다.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "app_port" {
  description = <<-EOT
    Spring Boot 애플리케이션 포트.
    ※ infra/nginx/imlate-app.conf 가 127.0.0.1:8080 을 고정으로 프록시하므로,
       이 값을 바꾸면 nginx 설정도 함께 바꿔야 한다.
  EOT
  type        = number
  default     = 8080
}

variable "web_port" {
  description = "EC2 nginx HTTP 포트(프론트 정적 서빙 + /api 리버스 프록시)"
  type        = number
  default     = 80
}

variable "web_tls_port" {
  description = "EC2 nginx HTTPS 포트(Let's Encrypt 인증서로 직접 종단)"
  type        = number
  default     = 443
}

# ---------------------------------------------------------------------
# 도메인 / DNS / TLS
#
#   고객이 보는 URL 을 고정하기 위한 설정이다(피드백 3번).
#   도메인은 Route53 콘솔에서 직접 구매한다. 구매하면 호스팅 영역이 자동으로 생기므로
#   Terraform 은 그 영역을 **조회만** 하고 A 레코드만 만든다(영역을 새로 만들지 않는다).
# ---------------------------------------------------------------------
variable "domain_name" {
  description = <<-EOT
    서비스 도메인(예: "skala-imlate.com"). Route53 에서 구매한 도메인을 그대로 적는다.

    비워 두면 DNS 레코드도 TLS 도 만들지 않고 EIP 주소로만 접속하게 된다.
    값을 넣으면
      1) 이 도메인의 A 레코드가 앱 EIP 를 가리키도록 생성되고,
      2) app_base_url 이 비어 있을 때 https://{domain_name} 이 서비스 base URL 이 되며,
      3) EC2 부팅 후 Let's Encrypt 인증서를 자동 발급한다(enable_tls).
  EOT
  type        = string
  default     = ""
}

variable "domain_aliases" {
  description = <<-EOT
    추가로 같은 서버를 가리키게 할 도메인 목록(예: ["www.skala-imlate.com"]).
    A 레코드와 인증서 SAN 에 함께 들어간다. 호스팅 영역은 domain_name 것과 같아야 한다.
  EOT
  type        = list(string)
  default     = []
}

variable "route53_zone_name" {
  description = <<-EOT
    A 레코드를 만들 기존 호스팅 영역 이름(예: "skala-imlate.com").
    비우면 domain_name 의 상위 2개 라벨로 추정한다
    (imlate.skala-imlate.com → skala-imlate.com).
    영역은 조회만 하며 Terraform 이 생성하지 않는다.
  EOT
  type        = string
  default     = ""
}

variable "create_dns_record" {
  description = <<-EOT
    Route53 A 레코드를 Terraform 이 관리할지 여부. domain_name 이 비어 있으면 아무것도 만들지 않는다.
    false 로 두면 DNS 는 손으로 관리하고 Terraform 은 EIP 만 알려 준다(outputs.app_public_ip).
  EOT
  type        = bool
  default     = true
}

variable "dns_record_ttl" {
  description = "A 레코드 TTL(초). 짧을수록 주소 변경 반영이 빠르지만 조회 비용이 는다."
  type        = number
  default     = 300
}

variable "enable_tls" {
  description = <<-EOT
    nginx 에서 Let's Encrypt 인증서로 HTTPS 를 종단할지 여부.
    domain_name 이 비어 있으면 이 값이 true 여도 아무 일도 하지 않는다(도메인 없이는 발급 불가).

    ★ 발급은 best-effort 다. EC2 안의 systemd 타이머가 1시간마다 시도하며,
      실패해도 부팅/배포는 성공으로 끝나고 nginx 는 80 포트로 계속 서비스한다.
      성공하면 그때부터 80 → 443 리다이렉트가 켜진다.
  EOT
  type        = bool
  default     = true
}

variable "tls_contact_email" {
  description = <<-EOT
    Let's Encrypt 계정 이메일. 인증서 만료 임박 알림이 이 주소로 온다.
    비우면 --register-unsafely-without-email 로 등록되어 알림을 받지 못한다(권장하지 않음).
  EOT
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------
# ALB
# ---------------------------------------------------------------------
variable "enable_alb" {
  description = <<-EOT
    Application Load Balancer 생성 여부.

    기본값 false — 인스턴스가 1대인 구성에서 ALB 는 오버스펙이다
    (월 $18 고정 + LCU 과금인데 얻는 건 헬스체크 정도다).
    끄면 EC2 의 nginx 가 직접 80/443 을 받고 Let's Encrypt 로 TLS 를 종단한다.

    트레이드오프
      false : 비용 절감, 구조가 단순. 대신 인스턴스 교체 중에는 서비스가 잠깐 끊긴다.
              고정 주소는 EIP + Route53 A 레코드로 보장한다.
      true  : 무중단 배포·다중 AZ·ACM 인증서·WAF 연동이 가능해진다.
              다시 켤 때는 acm_certificate_arn 과 app_base_url 도 함께 정리할 것.
              modules/alb 와 modules/waf 는 지우지 않고 남겨 두었다.
  EOT
  type        = bool
  default     = false
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
  description = <<-EOT
    ALB 앞단 AWS WAFv2 Web ACL 생성 여부.

    기본값 false — WAF 는 ALB 에만 붙일 수 있어서 enable_alb = false 면 어차피 만들어지지 않는다.
    또 Web ACL 월 $5 + 규칙당 $1 + 요청당 과금이라 200명 규모에는 과하다.

    트레이드오프: 끄면 L7 DDoS 1차 방어가 없어진다. 대신 nginx limit_req(10r/s)와
    애플리케이션 Redis 토큰 버킷(등록 8회/분 등) 2단 방어는 그대로 남는다.
  EOT
  type        = bool
  default     = false
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
  description = <<-EOT
    앱 EC2 를 퍼블릭 서브넷에 배치할지 여부.

    기본값 true — ALB 도 NAT 도 없는 구성이므로 앱이 직접 인터넷에 붙어야 한다.
    보안은 보안 그룹으로 지킨다: 인바운드는 80/443 만, 8080 은 열지 않는다.

    트레이드오프
      true  : NAT 비용 0. 아웃바운드도 EIP 로 나가므로 알리고 화이트리스트가 IP 하나로 끝난다.
      false : 앱이 프라이빗 서브넷으로 숨는다. 대신 enable_nat_gateway = true 가 필수이고
              (SSM/SES/dnf 접근) 인바운드는 ALB 를 통해야 한다.
  EOT
  type        = bool
  default     = true
}

variable "associate_elastic_ip" {
  description = <<-EOT
    앱 EC2 에 고정 EIP 를 붙일지 여부(public_app = true 일 때만 의미 있음).

    기본값 true — 고객이 보는 URL 을 고정하려면(피드백 3번) 반드시 필요하다.
    EIP 없이 자동 할당 퍼블릭 IP 를 쓰면 인스턴스를 재시작/교체할 때마다 주소가 바뀌어
    Route53 A 레코드와 알리고 화이트리스트를 매번 다시 등록해야 한다.

    ※ 이 EIP 는 인스턴스가 아니라 루트 모듈에 따로 생성되고
      aws_eip_association 으로만 연결된다. 따라서 인스턴스를 교체(-replace)해도
      주소는 그대로 유지된다.
  EOT
  type        = bool
  default     = true
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
  description = <<-EOT
    RDS 삭제 방지. **운영에서는 반드시 true.**

    이 DB 에는 매일의 야간복귀 등록 이력과 발송 이력이 들어 있고 백업 말고는 원본이 없다.
    false 로 두면 terraform destroy 나 실수로 만든 -target 한 줄에 통째로 사라진다.

    ※ terraform.tfvars 가 이 값을 덮어쓰면 여기 기본값은 아무 소용이 없다.
      현재 배포된 값 확인:
        terraform state show module.rds.aws_db_instance.this | grep deletion_protection
  EOT
  type        = bool
  default     = true
}

variable "db_skip_final_snapshot" {
  description = <<-EOT
    삭제 시 최종 스냅샷 생략 여부. **운영에서는 반드시 false.**

    true 면 DB 를 지울 때 스냅샷 없이 그냥 사라진다. 되돌릴 방법이 없다.
    테스트 환경을 빠르게 만들고 부수려고 true 로 뒤집는 경우가 있는데,
    운영 스택에서는 절대 그대로 두면 안 된다.

    ※ terraform.tfvars 가 이 값을 덮어쓰면 여기 기본값은 아무 소용이 없다.
      현재 배포된 값 확인:
        terraform state show module.rds.aws_db_instance.this | grep skip_final_snapshot
  EOT
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
  description = <<-EOT
    사감 조회 링크의 base URL(imlate.lookup.base-url). 보통 비워 둔다.

    비우면 아래 순서로 자동 결정된다.
      1) domain_name 이 있으면  https://{domain_name}  (enable_tls = false 면 http://)
      2) enable_alb = true 이면 ALB URL
      3) EIP 가 있으면          http://{EIP}
      4) 그 외                  http://127.0.0.1:{app_port}
  EOT
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

# ---------------------------------------------------------------------
# 모니터링 / 알람
#
#   발송 실패 = 교육생이 기숙사에 못 들어감. 그러므로 "조용한 실패"가 최대 위험이다.
#   여기 변수는 전부 기본값이 있고, alert_email 만 채우면 알람이 살아난다.
#   상세 설계와 한계는 modules/monitoring/README.md 를 볼 것.
# ---------------------------------------------------------------------
variable "enable_monitoring" {
  description = <<-EOT
    CloudWatch 알람 + SNS 통보(modules/monitoring) 생성 여부.

    기본 true 지만 alert_email 이 비어 있으면 어차피 아무것도 만들지 않는다.
    울려도 아무도 듣지 못하는 알람은 만들 이유가 없기 때문이다.
    (SNS 주제 + 알람은 사실상 무료다. 알람 10개까지 무료, 이후 개당 월 $0.10.)
  EOT
  type        = bool
  default     = true
}

variable "alert_email" {
  description = <<-EOT
    운영 알람을 받을 **운영자** 이메일 주소. 비우면 모니터링 모듈을 만들지 않는다.

    ★ 사감 수신 주소(supervisor1_email / supervisor2_email)와는 완전히 다른 채널이다.
      사감에게 가는 것은 "오늘 야간복귀 명단"뿐이고, 이 주소로 가는 것은
      "시스템이 고장났다"는 신호다. 여기에 사감 개인 주소를 넣으면 사감이 새벽에
      RDS CPU 알람 메일을 받게 된다. modules/monitoring 은 사감 변수를 입력으로
      받지도 않으므로 두 채널은 설정 경로부터 분리되어 있다.

    ★ apply 만으로는 알림이 오지 않는다. AWS 가 이 주소로 보낸 확인 메일의
      "Confirm subscription" 링크를 눌러야 구독이 활성화된다.
      terraform output alert_subscription_notice 에 확인 절차가 나온다.
  EOT
  type        = string
  default     = ""

  validation {
    condition = (
      length(trimspace(var.alert_email)) == 0 ||
      can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", trimspace(var.alert_email)))
    )
    error_message = "alert_email 은 비워 두거나 유효한 이메일 주소여야 합니다."
  }
}

variable "enable_dispatch_heartbeat_alarm" {
  description = <<-EOT
    발송 하트비트(Imlate/DispatchCompleted) 누락 알람 생성 여부.

    앱이 21:50 발송 배치를 끝내고 올리는 커스텀 지표가 최근 24시간 동안 하나도 없으면
    ALARM 이 된다(21:55~22:10 KST 판정 → 통금 22:30 전에 알아챈다).

    ★ 앱이 아직 이 지표를 올리지 않는 상태에서 켜면 만들자마자 ALARM 메일이 한 통 온다.
      앱 배포 전이라면 false 로 두었다가 앱이 올라간 뒤 true 로 바꾸는 편이 깔끔하다.
      설계 근거와 한계는 modules/monitoring/README.md 의 "하트비트 알람" 절에 있다.
  EOT
  type        = bool
  default     = true
}

variable "alarm_rds_free_storage_gib" {
  description = "RDS 여유 스토리지 하한(GiB). 기본 2 = 초기 할당 20 GiB 의 10%."
  type        = number
  default     = 2

  validation {
    condition     = var.alarm_rds_free_storage_gib > 0
    error_message = "alarm_rds_free_storage_gib 는 0 보다 커야 합니다."
  }
}

variable "alarm_rds_cpu_percent" {
  description = "RDS CPU 사용률 알람 임계값(%). 15분 연속 초과 시 알람."
  type        = number
  default     = 80

  validation {
    condition     = var.alarm_rds_cpu_percent > 0 && var.alarm_rds_cpu_percent <= 100
    error_message = "alarm_rds_cpu_percent 는 1~100 이어야 합니다."
  }
}

variable "alarm_redis_memory_percent" {
  description = "Redis 메모리 사용률 알람 임계값(%). maxmemory-policy 가 volatile-lru 라 가득 차면 축출이 아니라 쓰기 실패가 난다."
  type        = number
  default     = 80

  validation {
    condition     = var.alarm_redis_memory_percent > 0 && var.alarm_redis_memory_percent <= 100
    error_message = "alarm_redis_memory_percent 는 1~100 이어야 합니다."
  }
}

variable "alarm_disk_used_percent" {
  description = "EC2 루트 디스크 사용률 알람 임계값(%). install_cloudwatch_agent = true 일 때만 알람이 만들어진다."
  type        = number
  default     = 85

  validation {
    condition     = var.alarm_disk_used_percent > 0 && var.alarm_disk_used_percent <= 100
    error_message = "alarm_disk_used_percent 는 1~100 이어야 합니다."
  }
}

# ---------------- GitHub Actions (CI/CD) ----------------
variable "enable_github_oidc" {
  description = <<-EOT
    GitHub Actions 배포용 OIDC 역할을 만들지 여부.

    true 면 IAM OIDC 공급자(선택) + 배포 역할이 생성된다. IAM 리소스라 과금은 없다.
    생성 후 `terraform output -raw github_actions_role_arn` 값을 리포지터리 시크릿
    AWS_ROLE_ARN 에 넣으면 .github/workflows/deploy.yml 이 이 역할을 맡는다.
  EOT
  type        = bool
  default     = true
}

variable "github_oidc_create_provider" {
  description = <<-EOT
    IAM OIDC 공급자를 이 스택이 직접 만들지 여부.
    한 AWS 계정에 GitHub OIDC 공급자는 하나만 존재할 수 있다. 다른 프로젝트가 이미
    만들어 두었다면 false 로 두어 기존 것을 참조한다(EntityAlreadyExists 회피).
  EOT
  type        = bool
  default     = true
}

variable "github_owner" {
  description = "GitHub 소유자(사용자 또는 조직)"
  type        = string
  default     = "khyun9807"
}

variable "github_repo" {
  description = "GitHub 리포지터리 이름"
  type        = string
  default     = "skala-imlate"
}

variable "github_allowed_branches" {
  description = "이 역할을 맡을 수 있는 브랜치 목록. 배포는 main 에서만 하는 것을 권장한다."
  type        = list(string)
  default     = ["main"]
}
