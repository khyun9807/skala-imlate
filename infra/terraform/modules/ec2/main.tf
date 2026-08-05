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
#   기본은 퍼블릭 서브넷 단독 운영(ALB/NAT 없음). var.associate_public_ip = false 면
#   프라이빗 서브넷 + ALB/NAT 구성이 된다.
#
#   EIP 는 이 모듈이 만들지 않는다. 루트 모듈의 aws_eip.app / aws_eip_association.app
#   가 담당한다 — 인스턴스를 교체(-replace)해도 주소가 그대로 유지되어야 하기 때문이다.
#   (자세한 이유는 루트 main.tf 의 "고정 공인 IP" 절 주석 참고)
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
    enable_tls               = var.enable_tls
    tls_domains              = join(" ", var.tls_domains)
    tls_contact_email        = var.tls_contact_email
    acme_webroot             = var.acme_webroot
    nginx_snippet_dir        = var.nginx_snippet_dir

    # 아래 5개는 infra/nginx, infra/scripts, infra/systemd 의 파일 내용을 그대로 받는다.
    # 저장소 파일이 유일한 원본이고 user_data 는 그것을 심을 뿐이라, 부팅 직후 상태와
    # deploy.sh 가 배포한 상태가 절대 어긋나지 않는다.
    nginx_conf       = var.nginx_conf
    nginx_app_conf   = var.nginx_app_conf
    tls_script       = var.tls_script
    tls_sync_script  = var.tls_sync_script
    tls_service_unit = var.tls_service_unit
    tls_timer_unit   = var.tls_timer_unit
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

  # user_data 는 16KiB 제한이 있다. nginx/TLS 부트스트랩까지 들어가면서 한계에 가까워졌으므로
  # gzip 으로 압축해 넣는다(cloud-init 이 gzip 사용자 데이터를 자동으로 풀어 준다).
  user_data_base64            = base64gzip(local.user_data)
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
