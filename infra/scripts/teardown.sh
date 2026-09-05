#!/usr/bin/env bash
# =====================================================================
# imlate 서비스 종료 — 과금되는 것을 남김없이 정리한다
#
#   **terraform destroy 만으로는 비용이 끊기지 않는다.** 이 스크립트가 존재하는 이유가 그것이다.
#   destroy 가 손대지 못하는 것들이 실제로 있다(2026-09-05 계정 점검 결과):
#     · Route53 호스팅 영역 — main.tf 가 data 로 "조회"만 하므로 destroy 는 A 레코드만 지운다 (월 $0.50)
#     · 도메인 등록 자동 갱신 — terraform 이 registrar 를 관리하지 않는다 (만료일에 연 단위로 재청구)
#     · S3 아티팩트 버킷 — 배포 과정에서 만들어졌고 terraform 리소스가 아니다
#     · 수동 RDS 스냅샷 · 떠도는 EBS 스냅샷 — DB/인스턴스를 지워도 남는다
#     · CloudWatch 로그 그룹 — 보존기간이 없으면 영원히 남는다
#
#   실행 순서에 이유가 있다:
#     1) 자동 갱신부터 끈다. 뒤에서 무엇이 실패하든 "내년에 또 청구되는 것"만은 막는다.
#     2) 그다음 데이터를 지운다(화면에 "모두 파기됩니다"라고 공지했으므로 최종 스냅샷을 남기지 않는다).
#     3) terraform destroy 로 본체를 내린다.
#     4) destroy 가 못 지운 잔여물을 훑는다.
#
#   사용
#     infra/scripts/teardown.sh              # dry-run — 무엇을 지울지만 보여준다 (기본값)
#     infra/scripts/teardown.sh --execute    # 실제로 지운다
#
#   ※ --execute 는 되돌릴 수 없다. 서비스 마지막 운영일이 지난 뒤에 실행할 것.
# =====================================================================
set -euo pipefail

# Git Bash(MSYS)는 "/imlate/app" 같은 인자를 Windows 경로로 바꿔 버린다.
# 그대로 두면 CloudWatch 로그 그룹 이름이 망가져 "없음" 으로 오판하고 조용히 건너뛴다.
# (실제로 이 스크립트를 처음 돌렸을 때 로그 그룹 3개가 전부 "없음" 으로 나왔다)
# 리눅스·macOS 에서는 아무 영향이 없는 변수다.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TF_DIR="$REPO_ROOT/infra/terraform"
TMP_DIR="${TMPDIR:-/tmp}"

REGION="${AWS_REGION:-ap-northeast-2}"
DOMAIN="${IMLATE_DOMAIN:-skala-imlate.link}"
EXECUTE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --execute) EXECUTE=1 ;;
    --dry-run) EXECUTE=0 ;;
    --region)  REGION="$2"; shift ;;
    --domain)  DOMAIN="$2"; shift ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
  shift
done

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
info() { printf '   %s\n' "$*"; }
warn() { printf '\033[1;33m   ! %s\033[0m\n' "$*"; }

run() {
  if [ "$EXECUTE" -eq 1 ]; then
    info "실행: $*"
    "$@"
  else
    info "[dry-run] $*"
  fi
}

if [ "$EXECUTE" -eq 1 ]; then
  echo
  echo "  ---------------------------------------------------------"
  echo "   되돌릴 수 없는 작업입니다."
  echo "   DB·명단·인프라가 모두 삭제되고 최종 스냅샷도 남기지 않습니다"
  echo "   (화면 공지에 \"모두 파기됩니다\"라고 밝혔습니다)."
  echo "  ---------------------------------------------------------"
  echo
  read -r -p "  계속하려면 정확히 'destroy imlate' 를 입력하세요: " CONFIRM
  if [ "$CONFIRM" != "destroy imlate" ]; then
    echo "  취소했습니다."
    exit 1
  fi
else
  warn "dry-run 입니다. 실제로 지우려면 --execute 를 붙이세요."
fi

# ---------------------------------------------------------------------
# 1. 도메인 자동 갱신 해제  ← 가장 먼저. 뒤가 실패해도 이것만은 끝내 둔다.
#    (route53domains API 는 us-east-1 에만 있다)
# ---------------------------------------------------------------------
say "1. 도메인 자동 갱신 해제 ($DOMAIN)"
if aws route53domains get-domain-detail --region us-east-1 --domain-name "$DOMAIN" >/dev/null 2>&1; then
  CURRENT="$(aws route53domains get-domain-detail --region us-east-1 --domain-name "$DOMAIN" --query AutoRenew --output text 2>/dev/null || echo unknown)"
  info "현재 자동 갱신 = $CURRENT"
  if [ "$CURRENT" = "True" ]; then
    run aws route53domains disable-domain-auto-renew --region us-east-1 --domain-name "$DOMAIN"
    warn "만료일까지는 도메인이 살아 있고, 그 뒤 자동으로 사라집니다."
  else
    info "이미 꺼져 있습니다."
  fi
  warn "도메인 '등록' 자체는 환불·즉시 해지가 되지 않습니다. 만료일까지 두는 것 외에 방법이 없습니다."
else
  warn "이 계정의 Route53 registrar 에 $DOMAIN 이 없습니다(외부 등록기관일 수 있음). 직접 확인하세요."
fi

# ---------------------------------------------------------------------
# 2. RDS 삭제 방지 해제 + 최종 스냅샷을 남기지 않도록 전환
#    terraform.tfvars 를 고쳐 apply 한 뒤에야 destroy 가 통과한다.
# ---------------------------------------------------------------------
say "2. RDS 삭제 방지 해제 (terraform.tfvars)"
TFVARS="$TF_DIR/terraform.tfvars"
if grep -qE 'db_deletion_protection *= *true' "$TFVARS" 2>/dev/null; then
  info "db_deletion_protection = true -> false"
  run sed -i -E 's/db_deletion_protection *= *true/db_deletion_protection = false/' "$TFVARS"
else
  info "db_deletion_protection 은 이미 false 입니다."
fi
if grep -qE 'db_skip_final_snapshot *= *false' "$TFVARS" 2>/dev/null; then
  info "db_skip_final_snapshot = false -> true (최종 스냅샷을 남기지 않는다 = 명단 완전 파기)"
  run sed -i -E 's/db_skip_final_snapshot *= *false/db_skip_final_snapshot = true/' "$TFVARS"
else
  info "db_skip_final_snapshot 은 이미 true 입니다."
fi
run terraform -chdir="$TF_DIR" apply -auto-approve

# ---------------------------------------------------------------------
# 3. 본체 파괴
# ---------------------------------------------------------------------
say "3. terraform destroy"
run terraform -chdir="$TF_DIR" destroy -auto-approve

# ---------------------------------------------------------------------
# 4. Route53 호스팅 영역 — data 참조라 destroy 대상이 아니다 (월 $0.50)
# ---------------------------------------------------------------------
say "4. Route53 호스팅 영역"
ZONE_RAW="$(aws route53 list-hosted-zones-by-name --dns-name "$DOMAIN" --query "HostedZones[?Name=='${DOMAIN}.'].Id | [0]" --output text 2>/dev/null || echo None)"
if [ "$ZONE_RAW" != "None" ] && [ -n "$ZONE_RAW" ]; then
  ZONE_ID="${ZONE_RAW##*/}"
  info "호스팅 영역 $ZONE_ID"
  RRS_FILE="$TMP_DIR/imlate-rrs.json"
  DEL_FILE="$TMP_DIR/imlate-del.json"
  aws route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID" --query "ResourceRecordSets[?Type!='NS' && Type!='SOA']" --output json > "$RRS_FILE" 2>/dev/null || echo "[]" > "$RRS_FILE"
  COUNT="$(node -e "console.log(JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')).length)" "$RRS_FILE" 2>/dev/null || echo 0)"
  info "NS/SOA 를 뺀 레코드 $COUNT 건 — 남아 있으면 영역 삭제가 거부된다"
  if [ "$COUNT" -gt 0 ]; then
    if [ "$EXECUTE" -eq 1 ]; then
      node -e "const fs=require('fs');const rrs=JSON.parse(fs.readFileSync(process.argv[1],'utf8'));fs.writeFileSync(process.argv[2],JSON.stringify({Changes:rrs.map(r=>({Action:'DELETE',ResourceRecordSet:r}))}));" "$RRS_FILE" "$DEL_FILE"
      run aws route53 change-resource-record-sets --hosted-zone-id "$ZONE_ID" --change-batch "file://$DEL_FILE"
    else
      info "[dry-run] 레코드 $COUNT 건 삭제"
    fi
  fi
  run aws route53 delete-hosted-zone --id "$ZONE_ID"
else
  info "호스팅 영역이 없습니다(이미 삭제됨)."
fi

# ---------------------------------------------------------------------
# 5. S3 아티팩트 버킷 — terraform 리소스가 아니다
# ---------------------------------------------------------------------
say "5. S3 아티팩트 버킷"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || echo unknown)"
BUCKET="imlate-artifacts-$ACCOUNT"
if aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  info "버킷 $BUCKET"
  run aws s3 rm "s3://$BUCKET" --recursive
  run aws s3api delete-bucket --bucket "$BUCKET" --region "$REGION"
else
  info "버킷이 없습니다: $BUCKET"
fi

# ---------------------------------------------------------------------
# 6. CloudWatch 로그 그룹 — 보존기간이 없으면 영구 보관된다
# ---------------------------------------------------------------------
say "6. CloudWatch 로그 그룹"
for LG in "/imlate/app" "/imlate/app-error" "/aws/rds/instance/imlate-prod-mysql/error"; do
  FOUND="$(aws logs describe-log-groups --region "$REGION" --log-group-name-prefix "$LG" --query "length(logGroups[?logGroupName=='$LG'])" --output text 2>/dev/null || echo 0)"
  if [ "$FOUND" = "1" ]; then
    run aws logs delete-log-group --region "$REGION" --log-group-name "$LG"
  else
    info "없음: $LG"
  fi
done

# ---------------------------------------------------------------------
# 7. 사람이 판단해야 하는 것 — 자동으로 지우지 않는다
#
#    이 계정에는 imlate 소유가 아닐 수 있는 자원이 섞여 있다(2026-09-05 점검에서
#    payperv2-db-snapshot 과 다른 AMI 의 EBS 스냅샷을 발견했다). 남의 프로젝트 자산을
#    스크립트가 말없이 지우면 안 되므로 목록만 보여준다.
# ---------------------------------------------------------------------
say "7. 확인이 필요한 잔여 자원 (이 스크립트는 지우지 않는다)"
info "수동 RDS 스냅샷 — DB 를 지워도 남는다:"
aws rds describe-db-snapshots --region "$REGION" --snapshot-type manual --query "DBSnapshots[].{Id:DBSnapshotIdentifier,GB:AllocatedStorage,When:SnapshotCreateTime}" --output table 2>/dev/null || info "  (조회 실패)"
info "EBS 스냅샷:"
aws ec2 describe-snapshots --owner-ids self --region "$REGION" --query "Snapshots[].{Id:SnapshotId,GB:VolumeSize,Desc:Description}" --output table 2>/dev/null || info "  (조회 실패)"
info "직접 만든 AMI:"
aws ec2 describe-images --owners self --region "$REGION" --query "Images[].{Id:ImageId,Name:Name}" --output table 2>/dev/null || info "  (조회 실패)"

say "완료"
if [ "$EXECUTE" -eq 1 ]; then
  info "며칠 뒤 Billing 콘솔에서 실제로 0 에 수렴하는지 한 번 더 확인하세요."
  info "청구가 하루 이틀 늦게 반영되므로 9/10 전후로 보는 것이 정확합니다."
else
  info "실제로 지우려면: infra/scripts/teardown.sh --execute"
fi
