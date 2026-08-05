# network 모듈 변수

variable "name_prefix" {
  description = "리소스 이름 접두어 (예: imlate-prod)"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.20.0.0/16"
}

variable "availability_zones" {
  description = "사용할 가용영역 목록. 비우면 리전에서 자동 선택한다."
  type        = list(string)
  default     = []
}

variable "subnet_newbits" {
  description = "cidrsubnet 에 사용할 추가 비트 수(/16 + 8 = /24)"
  type        = number
  default     = 8
}

variable "public_subnet_offset" {
  description = "퍼블릭 서브넷 시작 인덱스 (10.20.0.0/24 부터)"
  type        = number
  default     = 0
}

variable "private_subnet_offset" {
  description = "프라이빗 서브넷 시작 인덱스 (10.20.10.0/24 부터)"
  type        = number
  default     = 10
}

variable "enable_nat_gateway" {
  description = "NAT Gateway 생성 여부"
  type        = bool
  default     = true
}

variable "single_nat_gateway" {
  description = "NAT Gateway 를 1개만 생성해 비용을 절감할지 여부"
  type        = bool
  default     = true
}

variable "tags" {
  description = "추가 태그(provider default_tags 와 병합됨)"
  type        = map(string)
  default     = {}
}
