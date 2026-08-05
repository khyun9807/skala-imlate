# =====================================================================
# security 모듈 — 보안 그룹 4종
#
#   두 가지 노출 방식 중 하나를 고른다(루트 모듈의 enable_alb 로 결정).
#
#   (A) enable_alb = true  → enable_alb_ingress = true
#       alb : 인터넷 → 80 / 443
#       app : ALB SG → 8080(Spring), 80(nginx)
#
#   (B) enable_alb = false → enable_direct_ingress = true   ← 현재 기본값
#       app : 인터넷(direct_ingress_cidrs) → 80 / 443 (nginx 가 직접 종단)
#       ALB / NAT 가 없으므로 EC2 에 붙은 EIP 하나가 인바운드 주소이자
#       아웃바운드 IP(알리고 화이트리스트 대상)가 된다.
#
#   공통
#       app   : 지정 CIDR → 22(SSH, 기본은 빈 목록이라 규칙 없음)
#       db    : app SG → 3306
#       redis : app SG → 6379
#
#   원칙: 데이터 계층(db/redis)은 **SG 참조로만** 개방한다.
#         CIDR 직접 개방은 웹 인바운드(80/443)와 SSH 두 곳뿐이다.
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
  description = "imlate application EC2 - inbound from ALB SG or internet 80/443"
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
  for_each = var.enable_alb_ingress ? toset(var.alb_ingress_cidrs) : toset([])

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
  for_each = var.enable_alb_ingress && var.enable_https_ingress ? toset(var.alb_ingress_cidrs) : toset([])

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
# 앱 인바운드 (A) — ALB SG 참조
# ---------------------------------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  count = var.enable_alb_ingress ? 1 : 0

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
  count = var.enable_alb_ingress ? 1 : 0

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

# ---------------------------------------------------------------------
# 앱 인바운드 (B) — 인터넷 → EC2 직접 노출 (ALB 를 쓰지 않을 때)
#
#   ALB 가 없으면 app SG 에는 ALB SG 참조 규칙밖에 없어서 인터넷에서 들어올
#   경로가 하나도 없다. 여기서 nginx 가 듣는 80/443 을 직접 열어 준다.
#   Spring 포트(8080)는 절대 열지 않는다 — nginx 가 127.0.0.1:8080 으로만 프록시한다.
# ---------------------------------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "app_web_direct" {
  for_each = var.enable_direct_ingress ? toset(var.direct_ingress_cidrs) : toset([])

  security_group_id = aws_security_group.app.id
  description       = "HTTP from internet (direct, no ALB)"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = var.web_port
  to_port           = var.web_port

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-web-direct"
  })
}

resource "aws_vpc_security_group_ingress_rule" "app_tls_direct" {
  for_each = var.enable_direct_ingress ? toset(var.direct_ingress_cidrs) : toset([])

  security_group_id = aws_security_group.app.id
  description       = "HTTPS from internet (direct, no ALB)"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = var.web_tls_port
  to_port           = var.web_tls_port

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-tls-direct"
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
