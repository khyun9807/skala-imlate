# network 모듈 출력

output "vpc_id" {
  description = "생성된 VPC ID"
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "VPC CIDR 블록"
  value       = aws_vpc.this.cidr_block
}

output "availability_zones" {
  description = "실제로 사용된 가용영역 목록"
  value       = local.azs
}

output "public_subnet_ids" {
  description = "퍼블릭 서브넷 ID 목록(ALB, NAT 용)"
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "프라이빗 서브넷 ID 목록(EC2, RDS, ElastiCache 용)"
  value       = aws_subnet.private[*].id
}

output "public_subnet_cidrs" {
  description = "퍼블릭 서브넷 CIDR 목록"
  value       = aws_subnet.public[*].cidr_block
}

output "private_subnet_cidrs" {
  description = "프라이빗 서브넷 CIDR 목록"
  value       = aws_subnet.private[*].cidr_block
}

output "internet_gateway_id" {
  description = "인터넷 게이트웨이 ID"
  value       = aws_internet_gateway.this.id
}

output "nat_gateway_ids" {
  description = "NAT Gateway ID 목록"
  value       = aws_nat_gateway.this[*].id
}

output "nat_public_ips" {
  description = "NAT Gateway 의 고정 공인 IP 목록(외부 API 화이트리스트 등록용)"
  value       = aws_eip.nat[*].public_ip
}

output "public_route_table_id" {
  description = "퍼블릭 라우팅 테이블 ID"
  value       = aws_route_table.public.id
}

output "private_route_table_ids" {
  description = "프라이빗 라우팅 테이블 ID 목록"
  value       = aws_route_table.private[*].id
}
