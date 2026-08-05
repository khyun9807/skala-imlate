# =====================================================================
# ec2 모듈 — 애플리케이션 서버 (Amazon Linux 2023)
#
#   부팅 순서 (templates/user_data.sh.tftpl)
#     1) corretto-21 / awscli / (선택) nginx / (선택) CloudWatch Agent 설치
#     2) imlate 시스템 사용자 · 디렉터리 생성
#     3) /usr/local/bin/imlate-load-env.sh 설치
#        → SSM Parameter Store(/imlate/{env}/*) 를 읽어 /etc/imlate/imlate.env 생성
#     4) systemd 유닛 등록: imlate-env.service(oneshot) → imlate.service
#     5) jar 이 이미 있으면 기동, 없으면 배포(deploy.sh) 후 자동으로 올라온다
#
#   기본은 프라이빗 서브넷 + ALB 경유. var.associate_public_ip = true 면
#   퍼블릭 서브넷에 두고 공인 IP 를 붙인다(단독 운영 모드).
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

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = [local.ami_name_filter]
  }

  filter {
    name   = "architecture"
    values = [var.architecture]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

locals {
  ami_name_filter = length(trimspace(var.ami_name_filter)) > 0 ? var.ami_name_filter : "al2023-ami-2023.*-${var.architecture}"

  ami_id = length(trimspace(var.ami_id)) > 0 ? var.ami_id : data.aws_ami.al2023.id

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    aws_region               = var.aws_region
    ssm_path_prefix          = trimsuffix(var.ssm_path_prefix, "/")
    app_user                 = var.app_user
    app_dir                  = var.app_dir
    jar_path                 = "${var.app_dir}/imlate.jar"
    env_dir                  = var.env_dir
    env_file                 = "${var.env_dir}/imlate.env"
    log_dir                  = var.log_dir
    web_root                 = var.web_root
    spring_profile           = var.spring_profile
    app_port                 = var.app_port
    timezone                 = var.timezone
    jvm_opts                 = var.jvm_opts
    install_nginx            = var.install_nginx
    install_cloudwatch_agent = var.install_cloudwatch_agent
    default_env_json         = jsonencode(var.default_env)
  })
}

resource "aws_instance" "app" {
  ami                    = local.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = var.security_group_ids
  iam_instance_profile   = var.iam_instance_profile_name
  key_name               = length(trimspace(var.key_name)) > 0 ? var.key_name : null

  associate_public_ip_address = var.associate_public_ip
  monitoring                  = var.detailed_monitoring

  user_data                   = local.user_data
  user_data_replace_on_change = var.user_data_replace_on_change

  # IMDSv2 강제 (SSRF 를 통한 자격증명 탈취 방지)
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "enabled"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    encrypted             = true
    kms_key_id            = var.ebs_kms_key_id
    delete_on_termination = true

    tags = merge(var.tags, {
      Name = "${var.name_prefix}-app-root"
    })
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app"
  })

  lifecycle {
    # AMI 가 갱신될 때마다 인스턴스가 교체되는 사고를 막는다.
    # AMI 를 올리고 싶으면 terraform apply -replace=module.ec2.aws_instance.app 로 명시 교체한다.
    ignore_changes = [ami]
  }
}

resource "aws_eip" "app" {
  count = var.associate_elastic_ip ? 1 : 0

  domain   = "vpc"
  instance = aws_instance.app.id

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-app-eip"
  })
}
