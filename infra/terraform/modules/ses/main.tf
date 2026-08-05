# =====================================================================
# ses 모듈 — Amazon SES v2 이메일 아이덴티티 (R11)
#
#   identity 가 도메인(example.com)이면 Easy DKIM(RSA 2048) 을 켜고,
#   outputs.dkim_dns_records 에 등록해야 할 CNAME 3건을 그대로 뱉는다.
#   identity 가 이메일 주소면 AWS 가 해당 주소로 검증 메일을 보낸다.
#
#   ※ 계정이 SES 샌드박스 상태면 "검증된 주소"로만 발송된다.
#     사감 2명의 이메일을 검증하거나, 프로덕션 액세스를 신청해야 한다(README 참고).
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
  enabled   = var.enabled && length(trimspace(var.identity)) > 0
  is_domain = local.enabled && !can(regex("@", var.identity))

  create_configuration_set = local.enabled && var.enable_configuration_set

  dkim_tokens = try(aws_sesv2_email_identity.this[0].dkim_signing_attributes[0].tokens, [])
}

resource "aws_sesv2_configuration_set" "this" {
  count = local.create_configuration_set ? 1 : 0

  configuration_set_name = var.configuration_set_name

  delivery_options {
    tls_policy = "REQUIRE"
  }

  reputation_options {
    reputation_metrics_enabled = true
  }

  sending_options {
    sending_enabled = true
  }

  suppression_options {
    suppressed_reasons = ["BOUNCE", "COMPLAINT"]
  }

  tags = merge(var.tags, {
    Name = var.configuration_set_name
  })
}

/**
 * 수신 전용 아이덴티티.
 *
 * 샌드박스에서는 수신자도 검증되어 있어야 메일이 전달된다. 발신 아이덴티티(this)와
 * 중복되지 않는 주소만 넘어온다(루트 locals 에서 정리). configuration set 은 붙이지 않는다.
 */
# 수신 주소는 sensitive 변수(사감 연락처)에서 오므로 for_each 키로 쓸 수 없다
# (민감값이 리소스 주소로 노출되기 때문). 대신 count + 인덱스를 쓴다.
# 목록은 루트에서 정렬해 넘기므로 순서가 안정적이다.
resource "aws_sesv2_email_identity" "recipients" {
  count = local.enabled ? length(var.recipient_identities) : 0

  email_identity = var.recipient_identities[count.index]

  tags = merge(var.tags, {
    Purpose = "sandbox-recipient"
  })
}

resource "aws_sesv2_email_identity" "this" {
  count = local.enabled ? 1 : 0

  email_identity         = var.identity
  configuration_set_name = one(aws_sesv2_configuration_set.this[*].configuration_set_name)

  # 도메인 아이덴티티에서만 Easy DKIM 설정이 유효하다.
  dynamic "dkim_signing_attributes" {
    for_each = local.is_domain ? [1] : []

    content {
      next_signing_key_length = var.dkim_signing_key_length
    }
  }

  tags = merge(var.tags, {
    Name = var.identity
  })
}
