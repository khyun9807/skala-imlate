# =====================================================================
# network 모듈 — VPC / 서브넷 / IGW / NAT / 라우팅
#
#   구성
#     VPC 10.20.0.0/16
#       ├─ public  10.20.0.0/24, 10.20.1.0/24    (ALB, NAT)
#       └─ private 10.20.10.0/24, 10.20.11.0/24  (EC2 app, RDS, ElastiCache)
#
#   NAT Gateway 는 시간당 과금이 크므로 single_nat_gateway = true 로 1개만 두는 것을
#   기본으로 한다(교육생 200명 규모에서 AZ 이중화보다 비용이 중요).
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

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  # 가용영역 2개 선택 (명시값 우선)
  azs = slice(
    length(var.availability_zones) > 0 ? var.availability_zones : data.aws_availability_zones.available.names,
    0,
    2
  )

  public_subnet_cidrs  = [for i in range(2) : cidrsubnet(var.vpc_cidr, var.subnet_newbits, var.public_subnet_offset + i)]
  private_subnet_cidrs = [for i in range(2) : cidrsubnet(var.vpc_cidr, var.subnet_newbits, var.private_subnet_offset + i)]

  nat_gateway_count = var.enable_nat_gateway ? (var.single_nat_gateway ? 1 : 2) : 0
}

# ---------------------------------------------------------------------
# VPC / IGW
# ---------------------------------------------------------------------
resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-vpc"
  })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-igw"
  })
}

# ---------------------------------------------------------------------
# 서브넷
# ---------------------------------------------------------------------
resource "aws_subnet" "public" {
  count = 2

  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.public_subnet_cidrs[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-public-${local.azs[count.index]}"
    Tier = "public"
  })
}

resource "aws_subnet" "private" {
  count = 2

  vpc_id                  = aws_vpc.this.id
  cidr_block              = local.private_subnet_cidrs[count.index]
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = false

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-private-${local.azs[count.index]}"
    Tier = "private"
  })
}

# ---------------------------------------------------------------------
# NAT Gateway (프라이빗 서브넷 아웃바운드: SSM / SES / 패키지 저장소)
# ---------------------------------------------------------------------
resource "aws_eip" "nat" {
  count = local.nat_gateway_count

  domain = "vpc"

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-nat-eip-${count.index + 1}"
  })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  count = local.nat_gateway_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-nat-${count.index + 1}"
  })

  depends_on = [aws_internet_gateway.this]
}

# ---------------------------------------------------------------------
# 라우팅 — 퍼블릭
# ---------------------------------------------------------------------
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rt-public"
  })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = 2

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ---------------------------------------------------------------------
# 라우팅 — 프라이빗 (AZ 별 라우팅 테이블, NAT 는 공유 가능)
# ---------------------------------------------------------------------
resource "aws_route_table" "private" {
  count = 2

  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-rt-private-${local.azs[count.index]}"
  })
}

resource "aws_route" "private_nat" {
  count = var.enable_nat_gateway ? 2 : 0

  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.single_nat_gateway ? 0 : count.index].id
}

resource "aws_route_table_association" "private" {
  count = 2

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}
