# =====================================================================
# rds 모듈 — MySQL 8 (프라이빗 서브넷, 암호화, utf8mb4/Asia-Seoul)
#
#   - 프라이빗 서브넷 그룹에만 배치(publicly_accessible = false)
#   - storage_encrypted = true (기본 aws/rds KMS 키 또는 지정 키)
#   - 파라미터 그룹에서 utf8mb4 / utf8mb4_unicode_ci / time_zone=Asia/Seoul 강제
#     → 한글 반/이름/호수가 깨지지 않고, DB 함수의 시간도 KST 로 맞는다.
#   - 비밀번호는 var.password 를 주면 그대로, 비우면 random_password 로 생성한다.
#     생성된 값은 outputs 를 통해 ssm 모듈이 SecureString 으로 저장한다.
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

# MySQL 마스터 비밀번호에 쓸 수 없는 문자( / " @ 공백 )는 제외한다.
resource "random_password" "master" {
  length           = 28
  special          = true
  override_special = "!#$%^&*()-_=+[]{}<>:?"
  min_lower        = 2
  min_upper        = 2
  min_numeric      = 2
  min_special      = 2
}

locals {
  master_password = coalesce(var.password, random_password.master.result)

  # skip_final_snapshot = false 인 경우에만 최종 스냅샷 이름이 필요하다.
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${var.identifier}-final-snapshot"
}

resource "aws_db_subnet_group" "this" {
  name        = "${var.name_prefix}-db-subnet-group"
  description = "imlate RDS private subnet group"
  subnet_ids  = var.subnet_ids

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-db-subnet-group"
  })
}

resource "aws_db_parameter_group" "this" {
  name        = "${var.name_prefix}-mysql8"
  family      = var.parameter_group_family
  description = "imlate MySQL 8 parameter group (utf8mb4 / Asia-Seoul)"

  # 문자셋 계열은 정적 파라미터라 재부팅이 필요하다.
  parameter {
    name         = "character_set_server"
    value        = "utf8mb4"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "character_set_client"
    value        = "utf8mb4"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "character_set_connection"
    value        = "utf8mb4"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "character_set_results"
    value        = "utf8mb4"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "character_set_database"
    value        = "utf8mb4"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "collation_server"
    value        = "utf8mb4_unicode_ci"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "collation_connection"
    value        = "utf8mb4_unicode_ci"
    apply_method = "pending-reboot"
  }

  # time_zone 은 동적 파라미터 — 즉시 적용 가능
  parameter {
    name         = "time_zone"
    value        = var.db_timezone
    apply_method = "immediate"
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-mysql8"
  })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier = var.identifier

  engine         = "mysql"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage > 0 ? var.max_allocated_storage : null
  storage_type          = var.storage_type
  storage_encrypted     = true
  kms_key_id            = var.kms_key_id

  db_name  = var.db_name
  username = var.username
  password = local.master_password
  port     = var.port

  db_subnet_group_name   = aws_db_subnet_group.this.name
  parameter_group_name   = aws_db_parameter_group.this.name
  vpc_security_group_ids = var.security_group_ids
  publicly_accessible    = false
  multi_az               = var.multi_az
  ca_cert_identifier     = var.ca_cert_identifier

  backup_retention_period = var.backup_retention_days
  backup_window           = var.backup_window
  maintenance_window      = var.maintenance_window
  copy_tags_to_snapshot   = true

  auto_minor_version_upgrade = true
  apply_immediately          = var.apply_immediately
  deletion_protection        = var.deletion_protection
  skip_final_snapshot        = var.skip_final_snapshot
  final_snapshot_identifier  = local.final_snapshot_identifier

  enabled_cloudwatch_logs_exports = var.enabled_cloudwatch_logs_exports
  performance_insights_enabled    = var.performance_insights_enabled

  tags = merge(var.tags, {
    Name = var.identifier
  })

  lifecycle {
    # 마이너 버전 자동 업그레이드로 인한 불필요한 diff 방지
    ignore_changes = [engine_version]
  }
}
