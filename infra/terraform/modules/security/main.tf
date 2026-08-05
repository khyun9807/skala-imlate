# =====================================================================
# security 모듈 — 보안 그룹 4종
#
#   alb   : 인터넷 → 80 / 443
#   app   : ALB SG → 8080(Spring), 80(nginx) / 지정 CIDR → 22(SSH)
#   db    : app SG → 3306
#   redis : app SG → 6379
#
#   원칙: 데이터 계층(db/redis)과 앱 계층은 **SG 참조로만** 개방한다.
#         CIDR 직접 개방은 ALB 인바운드와 SSH 두 곳뿐이며, SSH 는 기본값이
#         빈 목록이라 아무 규칙도 만들어지지 않는다(SSM Session Manager 권장).
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

# ---------------------------------------------------------------------
# 보안 그룹 정의
# ---------------------------------------------------------------------
resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb-sg"
  description = "imlate ALB - inbound 80/443 from internet"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-alb-sg"
  })
}

resource "aws_security_group" "app" {
  name        = "${var.name_prefix}-app-sg"
  description = "imlate application EC2 - inbound from ALB SG only"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-sg"
  })
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db-sg"
  description = "imlate RDS MySQL - inbound 3306 from app SG only"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-db-sg"
  })
}

resource "aws_security_group" "redis" {
  name        = "${var.name_prefix}-redis-sg"
  description = "imlate ElastiCache Redis - inbound 6379 from app SG only"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis-sg"
  })
}

# ---------------------------------------------------------------------
# ALB 인바운드 (유일하게 인터넷에 열리는 지점)
# ---------------------------------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each = toset(var.alb_ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  description       = "HTTP from internet"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-alb-http"
  })
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each = var.enable_https_ingress ? toset(var.alb_ingress_cidrs) : toset([])

  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from internet"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-alb-https"
  })
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  description       = "ALB to targets"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-alb-egress"
  })
}

# ---------------------------------------------------------------------
# 앱 인바운드 — ALB SG 참조만 허용 (+ 선택적 SSH)
# ---------------------------------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "Spring Boot port from ALB"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = var.app_port
  to_port                      = var.app_port

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-from-alb"
  })
}

resource "aws_vpc_security_group_ingress_rule" "app_web_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "nginx port from ALB"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = var.web_port
  to_port                      = var.web_port

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-web-from-alb"
  })
}

# SSH 는 ssh_allowed_cidrs 가 비어 있으면 규칙 자체가 생성되지 않는다.
resource "aws_vpc_security_group_ingress_rule" "app_ssh" {
  for_each = toset(var.ssh_allowed_cidrs)

  security_group_id = aws_security_group.app.id
  description       = "SSH from operator network"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-ssh"
  })
}

resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  description       = "App outbound (RDS, Redis, SSM, SES, Aligo)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-egress"
  })
}

# ---------------------------------------------------------------------
# 데이터 계층 — app SG 참조만 허용
# ---------------------------------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "MySQL from application"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = var.db_port
  to_port                      = var.db_port

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-db-from-app"
  })
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_app" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from application"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = var.redis_port
  to_port                      = var.redis_port

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-redis-from-app"
  })
}
