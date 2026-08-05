# =====================================================================
# github-oidc 모듈 — GitHub Actions 가 임시 자격증명으로 배포하게 만드는 IAM
#
#   왜 OIDC 인가
#     리포지터리 시크릿에 장기 액세스 키(AKIA...)를 넣으면 유출 시 회수 전까지 계속 유효하다.
#     OIDC 를 쓰면 GitHub 이 잡마다 발급한 서명 토큰을 STS 가 검증하고 1시간짜리 임시 키를
#     내주므로, 저장된 비밀은 "역할 ARN"(민감하지 않은 식별자) 하나뿐이 된다.
#
#   신뢰 범위
#     sub 조건으로 리포지터리 + 브랜치(또는 Environment)까지 못 박는다.
#     포크나 다른 리포지터리의 워크플로는 토큰 sub 가 달라 AssumeRole 이 거부된다.
#
#   부여 권한 (deploy.sh --mode ssm 이 실제로 호출하는 API 만)
#     - s3:PutObject/GetObject           아티팩트 업로드
#     - s3:ListBucket                    aws s3 cp --recursive 가 필요로 함
#     - ssm:SendCommand                  대상 인스턴스 + AWS-RunShellScript 문서로 한정
#     - ssm:ListCommandInvocations       실행 결과 폴링
#     - ssm:GetCommandInvocation
#     EC2 제어(ec2:*), IAM 변경, terraform 상태 접근 권한은 주지 않는다.
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

data "aws_partition" "current" {}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

# create_oidc_provider = false 일 때만 기존 공급자를 조회한다.
data "aws_iam_openid_connect_provider" "existing" {
  count = var.create_oidc_provider ? 0 : 1

  url = local.oidc_provider_url
}

locals {
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.name
  account_id = data.aws_caller_identity.current.account_id

  oidc_provider_url    = "https://token.actions.githubusercontent.com"
  oidc_provider_host   = "token.actions.githubusercontent.com"
  oidc_audience        = "sts.amazonaws.com"
  github_repo_fullname = "${var.github_owner}/${var.github_repo}"

  # 생성했으면 그 ARN, 아니면 조회한 ARN. one() 은 count=0 일 때 null 을 준다.
  oidc_provider_arn = coalesce(
    one(aws_iam_openid_connect_provider.github[*].arn),
    one(data.aws_iam_openid_connect_provider.existing[*].arn),
  )

  # ---- 허용할 토큰 sub 목록 ----
  #   브랜치 푸시     repo:owner/repo:ref:refs/heads/main
  #   Environment 사용 repo:owner/repo:environment:production
  # 잡에 environment 가 선언되면 GitHub 은 environment 형태의 sub 만 발급한다.
  branch_subjects = [
    for branch in var.allowed_branches :
    "repo:${local.github_repo_fullname}:ref:refs/heads/${branch}"
  ]

  environment_subjects = [
    for env in var.allowed_environments :
    "repo:${local.github_repo_fullname}:environment:${env}"
  ]

  allowed_subjects = distinct(concat(
    local.branch_subjects,
    local.environment_subjects,
    var.additional_subjects,
  ))

  # ---- S3 대상 ----
  artifact_prefix = trim(trimspace(var.artifact_key_prefix), "/")

  artifact_bucket_arn = "arn:${local.partition}:s3:::${var.artifact_bucket_name}"

  artifact_object_arn = local.artifact_prefix != "" ? "${local.artifact_bucket_arn}/${local.artifact_prefix}/*" : "${local.artifact_bucket_arn}/*"

  # ---- SSM SendCommand 대상 ----
  use_instance_ids = length(var.target_instance_ids) > 0

  instance_arns = local.use_instance_ids ? [
    for id in var.target_instance_ids :
    "arn:${local.partition}:ec2:${local.region}:${local.account_id}:instance/${id}"
  ] : ["arn:${local.partition}:ec2:${local.region}:${local.account_id}:instance/*"]

  ssm_document_arn = "arn:${local.partition}:ssm:${local.region}::document/${var.ssm_document_name}"
}

# ---------------------------------------------------------------------
# OIDC 공급자 (계정당 1개)
# ---------------------------------------------------------------------
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url             = local.oidc_provider_url
  client_id_list  = [local.oidc_audience]
  thumbprint_list = var.oidc_thumbprints

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-github-oidc"
  })
}

# ---------------------------------------------------------------------
# 신뢰 정책 (누가 이 역할을 맡을 수 있는가)
# ---------------------------------------------------------------------
data "aws_iam_policy_document" "assume_role" {
  statement {
    sid     = "GitHubActionsAssumeRoleWithWebIdentity"
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    # aud 는 configure-aws-credentials 가 요청하는 고정값이다.
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider_host}:aud"
      values   = [local.oidc_audience]
    }

    # sub 로 리포지터리·브랜치·환경을 못 박는다. 와일드카드를 쓸 수 있도록 StringLike 를 쓰되,
    # 기본값에는 와일드카드가 없어 사실상 완전 일치로 동작한다.
    condition {
      test     = "StringLike"
      variable = "${local.oidc_provider_host}:sub"
      values   = local.allowed_subjects
    }
  }
}

# ---------------------------------------------------------------------
# 배포 권한 정책
# ---------------------------------------------------------------------
data "aws_iam_policy_document" "deploy" {
  # ---- 아티팩트 업로드 / 재다운로드 ----
  statement {
    sid    = "S3WriteDeployArtifacts"
    effect = "Allow"

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]

    resources = [local.artifact_object_arn]
  }

  # aws s3 cp --recursive 는 대상 접두어를 확인하려고 ListObjectsV2 를 호출한다.
  statement {
    sid    = "S3ListArtifactBucket"
    effect = "Allow"

    actions = [
      "s3:ListBucket",
      "s3:GetBucketLocation",
    ]

    resources = [local.artifact_bucket_arn]
  }

  # ---- SSM RunCommand: 실행 문서 제한 ----
  # SendCommand 는 "문서"와 "인스턴스" 두 리소스를 모두 평가한다. 문서를 AWS-RunShellScript
  # 하나로 묶어 두면 임의의 자동화 문서(예: 인스턴스 재생성 계열)를 실행할 수 없다.
  statement {
    sid       = "SsmSendCommandDocument"
    effect    = "Allow"
    actions   = ["ssm:SendCommand"]
    resources = [local.ssm_document_arn]
  }

  # ---- SSM RunCommand: 대상 인스턴스 제한 ----
  statement {
    sid       = "SsmSendCommandInstances"
    effect    = "Allow"
    actions   = ["ssm:SendCommand"]
    resources = local.instance_arns

    # 인스턴스 ID 를 못 박지 않은 경우에만 태그 조건으로 좁힌다.
    dynamic "condition" {
      for_each = local.use_instance_ids ? {} : var.target_instance_tags

      content {
        test     = "StringEquals"
        variable = "ssm:resourceTag/${condition.key}"
        values   = [condition.value]
      }
    }
  }

  # ---- 실행 결과 폴링 ----
  # 이 두 API 는 IAM 이 리소스 수준 제한을 지원하지 않아 "*" 로만 쓸 수 있다.
  # 둘 다 읽기 전용이며 CommandId 를 알아야 의미 있는 값을 돌려준다.
  statement {
    sid    = "SsmReadCommandStatus"
    effect = "Allow"

    actions = [
      "ssm:ListCommandInvocations",
      "ssm:GetCommandInvocation",
    ]

    resources = ["*"]
  }
}

# ---------------------------------------------------------------------
# 역할
# ---------------------------------------------------------------------
resource "aws_iam_role" "github_actions" {
  name                 = "${var.name_prefix}-github-actions-deploy"
  description          = "GitHub Actions(OIDC) 배포 역할 — ${local.github_repo_fullname}"
  assume_role_policy   = data.aws_iam_policy_document.assume_role.json
  max_session_duration = var.max_session_duration

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-github-actions-deploy"
  })

  lifecycle {
    precondition {
      condition     = length(local.allowed_subjects) > 0
      error_message = "allowed_branches / allowed_environments / additional_subjects 중 최소 하나는 값이 있어야 합니다(신뢰 조건이 비면 아무도 AssumeRole 할 수 없습니다)."
    }

    precondition {
      condition     = length(var.target_instance_ids) > 0 || length(var.target_instance_tags) > 0
      error_message = "target_instance_ids 또는 target_instance_tags 중 하나는 지정해야 합니다(SendCommand 대상을 제한하지 않는 정책은 만들지 않습니다)."
    }

    precondition {
      condition     = length(trimspace(var.artifact_bucket_name)) > 0
      error_message = "artifact_bucket_name 은 비울 수 없습니다(deploy.sh --mode ssm 이 S3 를 경유합니다)."
    }
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "${var.name_prefix}-github-actions-deploy-policy"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.deploy.json
}
