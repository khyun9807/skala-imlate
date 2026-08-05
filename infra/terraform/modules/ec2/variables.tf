# ec2 모듈 변수

variable "name_prefix" {
  description = "리소스 이름 접두어 (예: imlate-prod)"
  type        = string
}

variable "aws_region" {
  description = "user_data 의 aws CLI 호출에 사용할 리전"
  type        = string
}

variable "subnet_id" {
  description = "인스턴스를 배치할 서브넷 ID"
  type        = string
}

variable "security_group_ids" {
  description = "인스턴스에 붙일 보안 그룹 ID 목록"
  type        = list(string)
}

variable "iam_instance_profile_name" {
  description = "인스턴스 프로파일 이름(SSM/SES/CloudWatch 권한)"
  type        = string
}

variable "instance_type" {
  description = "인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "architecture" {
  description = "AMI 아키텍처 (x86_64 | arm64). instance_type 과 일치해야 한다."
  type        = string
  default     = "x86_64"
}

variable "ami_id" {
  description = "직접 지정할 AMI ID. 비우면 최신 Amazon Linux 2023 을 조회한다."
  type        = string
  default     = ""
}

variable "ami_name_filter" {
  description = "AMI 이름 필터. 비우면 al2023-ami-2023.*-{architecture} 를 사용한다."
  type        = string
  default     = ""
}

variable "key_name" {
  description = "EC2 키페어 이름. 비우면 키페어 없이 생성한다(SSM Session Manager 로 접속)."
  type        = string
  default     = ""
}

variable "associate_public_ip" {
  description = "퍼블릭 IP 자동 할당 여부"
  type        = bool
  default     = false
}

variable "associate_elastic_ip" {
  description = "고정 EIP 할당 여부"
  type        = bool
  default     = false
}

variable "detailed_monitoring" {
  description = "CloudWatch 상세 모니터링(1분 간격) 사용 여부"
  type        = bool
  default     = false
}

variable "root_volume_size" {
  description = "루트 EBS 크기(GiB)"
  type        = number
  default     = 30
}

variable "ebs_kms_key_id" {
  description = "EBS 암호화 KMS 키. null 이면 계정 기본 EBS 키."
  type        = string
  default     = null
}

variable "user_data_replace_on_change" {
  description = "user_data 가 바뀌면 인스턴스를 교체할지 여부. 기본 false(운영 중 갑작스러운 교체 방지)."
  type        = bool
  default     = false
}

# ---------------- 애플리케이션 배치 경로 ----------------
variable "ssm_path_prefix" {
  description = "시크릿을 읽어올 SSM 경로 접두어 (예: /imlate/prod)"
  type        = string
}

variable "app_user" {
  description = "서비스 실행 계정"
  type        = string
  default     = "imlate"
}

variable "app_dir" {
  description = "애플리케이션 디렉터리(jar 위치)"
  type        = string
  default     = "/opt/imlate"
}

variable "env_dir" {
  description = "환경변수 파일 디렉터리"
  type        = string
  default     = "/etc/imlate"
}

variable "log_dir" {
  description = "애플리케이션 로그 디렉터리"
  type        = string
  default     = "/var/log/imlate"
}

variable "web_root" {
  description = "프론트 dist 를 배치할 nginx 문서 루트"
  type        = string
  default     = "/var/www/imlate"
}

variable "spring_profile" {
  description = "Spring 프로파일"
  type        = string
  default     = "prod"
}

variable "app_port" {
  description = "애플리케이션 포트"
  type        = number
  default     = 8080
}

variable "timezone" {
  description = "시스템 시간대"
  type        = string
  default     = "Asia/Seoul"
}

variable "jvm_opts" {
  description = "JAVA_TOOL_OPTIONS 로 주입할 JVM 옵션"
  type        = string
  default     = "-Xms256m -Xmx1024m -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8"
}

variable "install_nginx" {
  description = "nginx 설치 및 imlate.conf 배치 여부"
  type        = bool
  default     = true
}

variable "install_cloudwatch_agent" {
  description = "CloudWatch Agent 설치 여부"
  type        = bool
  default     = true
}

variable "default_env" {
  description = "SSM 값이 없을 때 사용할 기본 환경변수 맵(민감값을 넣지 말 것 — user_data 는 평문이다)"
  type        = map(string)
  default     = {}
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
