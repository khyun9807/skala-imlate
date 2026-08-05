# =====================================================================
# waf 모듈 — AWS WAFv2 Web ACL (ALB 연결)
#
#   ■ 2단 방어 구조 (R14)
#     1단 - 여기(WAF, 엣지):   IP 당 5분간 rate_limit 초과 요청을 **ALB 도달 전에** 차단한다.
#                              L7 DDoS·스크래핑처럼 볼륨이 큰 트래픽을 애플리케이션이
#                              아예 보지 않게 만드는 것이 목적이다. 정밀도는 낮지만 싸고 빠르다.
#     2단 - 애플리케이션(Redis 토큰 버킷, ratelimit 모듈):
#                              엔드포인트별로 훨씬 촘촘하게(등록 8회/분, 조회 40회/분 …)
#                              제한한다. 200명 규모에서 실제 남용을 막는 주 방어선이다.
#
#     WAF 만으로는 "한 IP 가 등록 API 만 분당 100번" 같은 패턴을 못 막고,
#     애플리케이션 리미터만으로는 대량 트래픽이 EC2/Redis 까지 도달한다.
#     두 층을 같이 두는 이유가 이것이다.
#
#   ■ AWSManagedRulesCommonRuleSet
#     SQLi / XSS / 비정상 요청 등 일반적인 공격 시그니처를 차단한다.
#     한글 본문·긴 UA 등으로 오탐이 나올 수 있으므로, 도입 초기에는
#     managed_rules_count_only = true 로 카운트만 하며 지표를 본 뒤 차단으로 전환한다.
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

resource "aws_wafv2_web_acl" "this" {
  name        = "${var.name_prefix}-web-acl"
  description = "imlate ALB WAF - rate based + AWS managed common rules"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  # ---- 1) IP 기반 레이트 리밋 (5분 고정 윈도우) ----
  rule {
    name     = "RateLimitPerIp"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.rate_limit_per_5min
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.metric_prefix}RateLimitPerIp"
      sampled_requests_enabled   = true
    }
  }

  # ---- 2) AWS 관리형 공통 규칙 ----
  dynamic "rule" {
    for_each = var.enable_common_rule_set ? [1] : []

    content {
      name     = "AWSManagedRulesCommonRuleSet"
      priority = 2

      override_action {
        dynamic "none" {
          for_each = var.managed_rules_count_only ? [] : [1]
          content {}
        }

        dynamic "count" {
          for_each = var.managed_rules_count_only ? [1] : []
          content {}
        }
      }

      statement {
        managed_rule_group_statement {
          name        = "AWSManagedRulesCommonRuleSet"
          vendor_name = "AWS"
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.metric_prefix}CommonRuleSet"
        sampled_requests_enabled   = true
      }
    }
  }

  # ---- 3) 알려진 악성 입력(선택) ----
  dynamic "rule" {
    for_each = var.enable_known_bad_inputs_rule_set ? [1] : []

    content {
      name     = "AWSManagedRulesKnownBadInputsRuleSet"
      priority = 3

      override_action {
        dynamic "none" {
          for_each = var.managed_rules_count_only ? [] : [1]
          content {}
        }

        dynamic "count" {
          for_each = var.managed_rules_count_only ? [1] : []
          content {}
        }
      }

      statement {
        managed_rule_group_statement {
          name        = "AWSManagedRulesKnownBadInputsRuleSet"
          vendor_name = "AWS"
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.metric_prefix}KnownBadInputs"
        sampled_requests_enabled   = true
      }
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.metric_prefix}WebAcl"
    sampled_requests_enabled   = true
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-web-acl"
  })
}

resource "aws_wafv2_web_acl_association" "this" {
  resource_arn = var.alb_arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}
