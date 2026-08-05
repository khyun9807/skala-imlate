# =====================================================================
# iam 모듈 — 애플리케이션 EC2 역할 / 인스턴스 프로파일
#
#   부여 권한
#     - ses:SendEmail, ses:SendRawEmail            (R11 메일 발송)
#     - ssm:GetParameter(s), GetParametersByPath   (시크릿 로드)
#     - kms:Decrypt                                (SecureString 복호화)
#     - AmazonSSMManagedInstanceCore               (Session Manager 접속/배포)
#     - CloudWatchAgentServerPolicy                (지표/로그 수집)
#
#   SSM 권한은 /imlate/{env}/* 경로로만 제한한다.
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

# SecureString 기본 키(aws/ssm) — 고객 관리 키를 쓰면 var.kms_key_arns 로 추가한다.
data "aws_kms_alias" "ssm" {
  name = "alias/aws/ssm"
}

locals {
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.name
  account_id = data.aws_caller_identity.current.account_id

  ssm_parameter_arns = [
    "arn:${local.partition}:ssm:${local.region}:${local.account_id}:parameter${var.ssm_path_prefix}",
    "arn:${local.partition}:ssm:${local.region}:${local.account_id}:parameter${var.ssm_path_prefix}/*",
  ]

  # SES 아이덴티티가 아직 없으면(모듈 비활성) 와일드카드로 둔다.
  ses_resource_arns = length(var.ses_resource_arns) > 0 ? var.ses_resource_arns : ["*"]

  kms_key_arns = distinct(concat([data.aws_kms_alias.ssm.target_key_arn], var.kms_key_arns))

  managed_policy_arns = distinct(concat([
    "arn:${local.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore",
    "arn:${local.partition}:iam::aws:policy/CloudWatchAgentServerPolicy",
  ], var.extra_managed_policy_arns))
}

data "aws_iam_policy_document" "assume_role" {
  statement {
    sid     = "Ec2AssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "app" {
  # ---- SES 발송 ----
  statement {
    sid    = "SesSendEmail"
    effect = "Allow"

    actions = [
      "ses:SendEmail",
      "ses:SendRawEmail",
      "ses:SendTemplatedEmail",
    ]

    resources = local.ses_resource_arns
  }

  # ---- SSM Parameter Store 읽기 (경로 제한) ----
  statement {
    sid    = "SsmReadImlateParameters"
    effect = "Allow"

    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]

    resources = local.ssm_parameter_arns
  }

  # ---- SecureString 복호화 ----
  statement {
    sid    = "KmsDecryptForParameters"
    effect = "Allow"

    actions = [
      "kms:Decrypt",
    ]

    resources = local.kms_key_arns
  }

  # ---- 배포 아티팩트(jar / dist) 다운로드 — deploy.sh --mode ssm 용 ----
  dynamic "statement" {
    for_each = length(var.artifact_bucket_names) > 0 ? [1] : []

    content {
      sid    = "S3ReadDeployArtifacts"
      effect = "Allow"

      actions = [
        "s3:GetObject",
        "s3:GetObjectVersion",
      ]

      resources = [
        for bucket in var.artifact_bucket_names :
        "arn:${local.partition}:s3:::${bucket}/*"
      ]
    }
  }

  dynamic "statement" {
    for_each = length(var.artifact_bucket_names) > 0 ? [1] : []

    content {
      sid    = "S3ListDeployArtifacts"
      effect = "Allow"

      actions = [
        "s3:ListBucket",
      ]

      resources = [
        for bucket in var.artifact_bucket_names :
        "arn:${local.partition}:s3:::${bucket}"
      ]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${var.name_prefix}-app-role"
  description        = "imlate application EC2 role"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-role"
  })
}

resource "aws_iam_role_policy" "app" {
  name   = "${var.name_prefix}-app-policy"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app.json
}

resource "aws_iam_role_policy_attachment" "managed" {
  for_each = toset(local.managed_policy_arns)

  role       = aws_iam_role.app.name
  policy_arn = each.value
}

resource "aws_iam_instance_profile" "app" {
  name = "${var.name_prefix}-app-profile"
  role = aws_iam_role.app.name

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-profile"
  })
}
