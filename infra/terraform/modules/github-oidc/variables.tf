# =====================================================================
# github-oidc 모듈 — 입력 변수
# =====================================================================

variable "name_prefix" {
  description = "리소스 이름 접두어(예: imlate-prod)"
  type        = string
}

variable "tags" {
  description = "추가 태그. 공통 태그는 provider default_tags 가 붙이므로 여기엔 Name 정도만 넣는다."
  type        = map(string)
  default     = {}
}

# ---------------------------------------------------------------------
# OIDC 공급자
# ---------------------------------------------------------------------
variable "create_oidc_provider" {
  description = <<-EOT
    GitHub OIDC 공급자(token.actions.githubusercontent.com)를 이 모듈이 생성할지 여부.
    IAM OIDC 공급자는 **계정당 URL 하나만** 존재할 수 있다. 다른 프로젝트가 이미 만들어 뒀다면
    false 로 두어 data 로 참조한다(EntityAlreadyExists 오류 회피).
  EOT
  type        = bool
  default     = true
}

variable "oidc_thumbprints" {
  description = <<-EOT
    OIDC 공급자 인증서 지문 목록. AWS 는 2023년부터 GitHub 지문을 자체 신뢰 저장소로 검증하므로
    실질적인 의미는 거의 없지만, 프로바이더 버전에 따라 값이 요구될 수 있어 알려진 지문을 기본값으로 둔다.
  EOT
  type        = list(string)
  default = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]
}

# ---------------------------------------------------------------------
# 신뢰할 GitHub 리포지터리 / 브랜치 / 환경
# ---------------------------------------------------------------------
variable "github_owner" {
  description = "GitHub 소유자(사용자 또는 조직) 이름"
  type        = string
  default     = "khyun9807"
}

variable "github_repo" {
  description = "GitHub 리포지터리 이름"
  type        = string
  default     = "skala-imlate"
}

variable "allowed_branches" {
  description = <<-EOT
    AssumeRole 을 허용할 브랜치 목록.
    `repo:<owner>/<repo>:ref:refs/heads/<branch>` 형태의 sub 조건으로 변환된다.
    비우면 브랜치 기반 신뢰를 만들지 않는다.
  EOT
  type        = list(string)
  default     = ["main"]
}

variable "allowed_environments" {
  description = <<-EOT
    AssumeRole 을 허용할 GitHub Environment 목록.

    ※ 중요: 워크플로 잡에 `environment:` 가 선언되면 OIDC 토큰의 sub 가
      `repo:<owner>/<repo>:environment:<name>` 로 **바뀐다**(ref 기반 sub 가 아니다).
      deploy.yml 이 `environment: production` 을 쓰므로 여기에 production 이 반드시 있어야 한다.
  EOT
  type        = list(string)
  default     = ["production"]
}

variable "additional_subjects" {
  description = "위 규칙으로 표현하기 어려운 sub 를 직접 추가할 때 사용(예: repo:owner/repo:pull_request)"
  type        = list(string)
  default     = []
}

variable "max_session_duration" {
  description = "AssumeRole 세션 최대 길이(초). 배포 한 번에 충분한 정도로만 둔다."
  type        = number
  default     = 3600
}

# ---------------------------------------------------------------------
# 부여할 권한 대상
# ---------------------------------------------------------------------
variable "artifact_bucket_name" {
  description = "배포 아티팩트(jar/dist)를 올릴 S3 버킷 이름. deploy.sh --mode ssm 이 사용한다."
  type        = string
}

variable "artifact_key_prefix" {
  description = "아티팩트 S3 키 접두어. deploy.sh 의 --s3-prefix 와 같아야 한다. 빈 문자열이면 버킷 전체를 대상으로 한다."
  type        = string
  default     = "deploy"
}

variable "target_instance_ids" {
  description = <<-EOT
    SSM RunCommand 를 보낼 EC2 인스턴스 ID 목록(예: ["i-0abc..."]).
    값이 있으면 SendCommand 대상이 이 인스턴스로만 제한된다(가장 강한 최소 권한).
    인스턴스를 재생성하면 ID 가 바뀌므로 root 에서 module.ec2.instance_id 를 넘겨 주는 것이 좋다.
  EOT
  type        = list(string)
  default     = []
}

variable "target_instance_tags" {
  description = <<-EOT
    target_instance_ids 를 비웠을 때 사용할 태그 조건(예: { Project = "imlate", Env = "prod" }).
    SendCommand 대상이 instance/* 로 넓어지는 대신 ssm:resourceTag 조건으로 좁힌다.
    인스턴스 ID 가 자주 바뀌는 환경에서 쓴다. 둘 다 비워 두면 apply 가 실패한다.
  EOT
  type        = map(string)
  default     = {}
}

variable "ssm_document_name" {
  description = "SendCommand 로 실행을 허용할 SSM 문서 이름"
  type        = string
  default     = "AWS-RunShellScript"
}
