# =====================================================================
# elasticache 모듈 — Redis 7 복제 그룹 (프라이빗 서브넷)
#
#   용도 3가지
#     1) 등록 WAL   (imlate:wal:{date}, TTL 있음)
#     2) rate limit (imlate:rl:{scope}:{ip}, TTL 있음)
#     3) 통계       (imlate:stats:*, 일부는 TTL 없음)
#
#   → maxmemory-policy 는 volatile-lru 를 기본으로 둔다.
#     TTL 이 없는 통계 누계 키가 축출되지 않도록 하기 위함이다.
#
#   num_cache_clusters 가 2 이상이면 자동 장애조치를 켠다(단일 노드는 불가).
#   auth_token 을 쓰려면 transit_encryption_enabled = true 여야 한다.
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
}

# ElastiCache AUTH token 제약: 16~128자, 인쇄 가능 ASCII, / " @ 사용 불가
resource "random_password" "auth" {
  length           = 48
  special          = true
  override_special = "!#$%^&*()-_=+"
  min_lower        = 2
  min_upper        = 2
  min_numeric      = 2
  min_special      = 2
}

locals {
  auth_token = var.auth_enabled ? coalesce(var.auth_token, random_password.auth.result) : null

  # 단일 노드에서는 자동 장애조치/Multi-AZ 를 켤 수 없다.
  failover_enabled = var.num_cache_clusters > 1
  multi_az_enabled = var.num_cache_clusters > 1 && var.multi_az_enabled
}

resource "aws_elasticache_subnet_group" "this" {
  name        = "${var.name_prefix}-redis-subnet-group"
  description = "imlate ElastiCache private subnet group"
  subnet_ids  = var.subnet_ids

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis-subnet-group"
  })
}

resource "aws_elasticache_parameter_group" "this" {
  name        = "${var.name_prefix}-redis7"
  family      = var.parameter_group_family
  description = "imlate Redis 7 parameter group"

  parameter {
    name  = "maxmemory-policy"
    value = var.maxmemory_policy
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis7"
  })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.replication_group_id
  description          = "imlate WAL / rate-limit / stats cache"

  engine         = "redis"
  engine_version = var.engine_version
  node_type      = var.node_type
  port           = var.port

  num_cache_clusters         = var.num_cache_clusters
  automatic_failover_enabled = local.failover_enabled
  multi_az_enabled           = local.multi_az_enabled

  subnet_group_name    = aws_elasticache_subnet_group.this.name
  parameter_group_name = aws_elasticache_parameter_group.this.name
  security_group_ids   = var.security_group_ids

  at_rest_encryption_enabled = var.at_rest_encryption_enabled
  transit_encryption_enabled = var.transit_encryption_enabled
  auth_token                 = local.auth_token

  snapshot_retention_limit = var.snapshot_retention_limit
  maintenance_window       = var.maintenance_window
  apply_immediately        = var.apply_immediately

  auto_minor_version_upgrade = true

  tags = merge(var.tags, {
    Name = var.replication_group_id
  })

  lifecycle {
    precondition {
      condition     = !var.auth_enabled || var.transit_encryption_enabled
      error_message = "auth_enabled = true 이면 transit_encryption_enabled 도 true 여야 합니다(ElastiCache 제약)."
    }

    ignore_changes = [engine_version]
  }
}
