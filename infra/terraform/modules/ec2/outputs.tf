# ec2 모듈 출력

output "instance_id" {
  description = "애플리케이션 EC2 인스턴스 ID (deploy.sh --instance-id 에 사용)"
  value       = aws_instance.app.id
}

output "instance_arn" {
  description = "인스턴스 ARN"
  value       = aws_instance.app.arn
}

output "private_ip" {
  description = "인스턴스 프라이빗 IP"
  value       = aws_instance.app.private_ip
}

output "public_ip" {
  description = "인스턴스 퍼블릭 IP(할당하지 않았으면 빈 문자열)"
  value       = aws_instance.app.public_ip
}

output "elastic_ip" {
  description = "연결된 고정 EIP(없으면 null)"
  value       = one(aws_eip.app[*].public_ip)
}

output "ami_id" {
  description = "사용된 AMI ID"
  value       = local.ami_id
}

output "availability_zone" {
  description = "인스턴스가 위치한 가용영역"
  value       = aws_instance.app.availability_zone
}

output "subnet_id" {
  description = "인스턴스가 배치된 서브넷 ID"
  value       = aws_instance.app.subnet_id
}
