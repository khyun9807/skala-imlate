# =====================================================================
# imlate — Terraform / Provider 선언
#
#   - Terraform 1.6 이상 (테스트/체크 블록, moved 블록 등을 사용할 수 있는 버전)
#   - AWS Provider 5.x 계열 고정
#   - default_tags 로 모든 리소스에 Project/Env/ManagedBy 태그를 강제한다.
#     (각 모듈은 Name 태그만 추가로 붙인다 — 중복 태그로 인한 diff 방지)
# =====================================================================

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # 원격 상태를 쓰려면 아래 주석을 해제하고 버킷/테이블을 먼저 만든다.
  # backend "s3" {
  #   bucket         = "imlate-tfstate"
  #   key            = "prod/terraform.tfstate"
  #   region         = "ap-northeast-2"
  #   dynamodb_table = "imlate-tfstate-lock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.common_tags
  }
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}
