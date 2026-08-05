#!/usr/bin/env bash
# =====================================================================
# imlate 배포 스크립트
#
#   백엔드 : backend/gradlew bootJar → /opt/imlate/imlate.jar → systemctl restart imlate
#   프론트 : frontend npm run build  → /var/www/imlate (nginx 문서 루트) 동기화
#
#   전송 방식 두 가지
#     --mode ssh   scp/ssh 로 직접 전송 (퍼블릭 배치 또는 배스천/터널 사용 시)
#     --mode ssm   S3 업로드 + SSM RunCommand (프라이빗 서브넷 권장, SSH 불필요)
#
#   사용 예
#     infra/scripts/deploy.sh --mode ssh --host 3.35.1.2 --key ~/.ssh/imlate.pem
#     infra/scripts/deploy.sh --mode ssm --instance-id i-0abc... --bucket imlate-artifacts
#     infra/scripts/deploy.sh --mode ssh --host imlate.example.com --backend-only
#     infra/scripts/deploy.sh --mode ssm --instance-id i-0abc... --bucket b --no-build
# =====================================================================
set -euo pipefail

# ---------------------------------------------------------------------
# 경로 / 기본값
# ---------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BACKEND_DIR="$REPO_ROOT/backend"
FRONTEND_DIR="$REPO_ROOT/frontend"
NGINX_CONF="$REPO_ROOT/infra/nginx/imlate.conf"
SYSTEMD_UNIT="$REPO_ROOT/infra/systemd/imlate.service"

REMOTE_APP_DIR="/opt/imlate"
REMOTE_JAR="$REMOTE_APP_DIR/imlate.jar"
REMOTE_WEB_ROOT="/var/www/imlate"
REMOTE_STAGE="/tmp/imlate-deploy"

MODE="ssh"
SSH_HOST=""
SSH_USER="ec2-user"
SSH_KEY=""
SSH_PORT="22"
INSTANCE_ID=""
BUCKET=""
S3_PREFIX="deploy"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-northeast-2}}"

DO_BACKEND=1
DO_FRONTEND=1
DO_BUILD=1
DO_CONFIG=1
HEALTH_URL=""
HEALTH_TIMEOUT=180

# ---------------------------------------------------------------------
# 로깅
# ---------------------------------------------------------------------
log()  { printf '\033[0;36m[deploy]\033[0m %s %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '\033[0;33m[deploy]\033[0m %s %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die()  { printf '\033[0;31m[deploy] 오류:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'USAGE'

옵션
  --mode <ssh|ssm>        전송 방식 (기본: ssh)
  --host <주소>           [ssh] 대상 호스트
  --user <계정>           [ssh] SSH 계정 (기본: ec2-user)
  --key <경로>            [ssh] 개인키 파일
  --port <포트>           [ssh] SSH 포트 (기본: 22)
  --instance-id <i-...>   [ssm] 대상 EC2 인스턴스 ID
  --bucket <이름>         [ssm] 아티팩트 S3 버킷
  --s3-prefix <접두어>    [ssm] S3 키 접두어 (기본: deploy)
  --region <리전>         AWS 리전 (기본: ap-northeast-2)
  --backend-only          백엔드만 배포
  --frontend-only         프론트만 배포
  --no-build              빌드를 건너뛰고 기존 산출물을 배포
  --no-config             nginx/systemd 설정 파일 갱신 생략
  --health-url <URL>      배포 후 확인할 헬스체크 URL
  -h, --help              이 도움말
USAGE
}

# ---------------------------------------------------------------------
# 인자 파싱
# ---------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode)         MODE="${2:-}"; shift 2 ;;
    --host)         SSH_HOST="${2:-}"; shift 2 ;;
    --user)         SSH_USER="${2:-}"; shift 2 ;;
    --key)          SSH_KEY="${2:-}"; shift 2 ;;
    --port)         SSH_PORT="${2:-}"; shift 2 ;;
    --instance-id)  INSTANCE_ID="${2:-}"; shift 2 ;;
    --bucket)       BUCKET="${2:-}"; shift 2 ;;
    --s3-prefix)    S3_PREFIX="${2:-}"; shift 2 ;;
    --region)       AWS_REGION="${2:-}"; shift 2 ;;
    --backend-only) DO_FRONTEND=0; shift ;;
    --frontend-only) DO_BACKEND=0; shift ;;
    --no-build)     DO_BUILD=0; shift ;;
    --no-config)    DO_CONFIG=0; shift ;;
    --health-url)   HEALTH_URL="${2:-}"; shift 2 ;;
    -h|--help)      usage; exit 0 ;;
    *)              die "알 수 없는 옵션: $1 (--help 참고)" ;;
  esac
done

case "$MODE" in
  ssh)
    [[ -n "$SSH_HOST" ]] || die "--mode ssh 에는 --host 가 필요합니다."
    ;;
  ssm)
    [[ -n "$INSTANCE_ID" ]] || die "--mode ssm 에는 --instance-id 가 필요합니다."
    [[ -n "$BUCKET" ]]      || die "--mode ssm 에는 --bucket(아티팩트 S3 버킷)이 필요합니다."
    command -v aws >/dev/null 2>&1 || die "aws CLI 를 찾을 수 없습니다."
    ;;
  *)
    die "--mode 는 ssh 또는 ssm 이어야 합니다 (입력값: $MODE)"
    ;;
esac

(( DO_BACKEND == 1 || DO_FRONTEND == 1 )) || die "--backend-only 와 --frontend-only 를 동시에 쓸 수 없습니다."

STAMP="$(date '+%Y%m%d-%H%M%S')"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/imlate-deploy.XXXXXX")"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

JAR_FILE=""
DIST_ARCHIVE=""

# ---------------------------------------------------------------------
# 1) 백엔드 빌드
# ---------------------------------------------------------------------
build_backend() {
  [[ -d "$BACKEND_DIR" ]] || die "백엔드 디렉터리가 없습니다: $BACKEND_DIR"

  if (( DO_BUILD == 1 )); then
    log "백엔드 빌드 시작 (gradlew bootJar)"
    [[ -x "$BACKEND_DIR/gradlew" ]] || chmod +x "$BACKEND_DIR/gradlew" 2>/dev/null || true
    ( cd "$BACKEND_DIR" && ./gradlew --no-daemon clean bootJar )
  else
    log "백엔드 빌드 생략(--no-build)"
  fi

  # bootJar 산출물만 고른다(-plain.jar 는 라이브러리 jar).
  JAR_FILE="$(find "$BACKEND_DIR/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' 2>/dev/null | sort | tail -n 1 || true)"
  [[ -n "$JAR_FILE" ]] || die "실행 가능한 jar 을 찾지 못했습니다. backend/build/libs 를 확인하세요."

  log "백엔드 산출물: $JAR_FILE ($(du -h "$JAR_FILE" | cut -f1))"
}

# ---------------------------------------------------------------------
# 2) 프론트엔드 빌드
# ---------------------------------------------------------------------
build_frontend() {
  [[ -d "$FRONTEND_DIR" ]] || die "프론트엔드 디렉터리가 없습니다: $FRONTEND_DIR"

  if (( DO_BUILD == 1 )); then
    log "프론트엔드 빌드 시작 (npm run build)"
    command -v npm >/dev/null 2>&1 || die "npm 을 찾을 수 없습니다."

    if [[ -f "$FRONTEND_DIR/package-lock.json" ]]; then
      ( cd "$FRONTEND_DIR" && npm ci )
    else
      ( cd "$FRONTEND_DIR" && npm install )
    fi

    ( cd "$FRONTEND_DIR" && npm run build )
  else
    log "프론트엔드 빌드 생략(--no-build)"
  fi

  [[ -d "$FRONTEND_DIR/dist" ]] || die "프론트엔드 dist 디렉터리가 없습니다: $FRONTEND_DIR/dist"
  [[ -f "$FRONTEND_DIR/dist/index.html" ]] || die "dist/index.html 이 없습니다. 빌드가 정상인지 확인하세요."

  DIST_ARCHIVE="$WORK_DIR/imlate-dist.tar.gz"
  tar -czf "$DIST_ARCHIVE" -C "$FRONTEND_DIR/dist" .
  log "프론트엔드 산출물: $DIST_ARCHIVE ($(du -h "$DIST_ARCHIVE" | cut -f1))"
}

# ---------------------------------------------------------------------
# 원격 설치 스크립트 (ssh / ssm 공통으로 대상 서버에서 실행된다)
# ---------------------------------------------------------------------
write_remote_install_script() {
  cat > "$WORK_DIR/remote-install.sh" <<'REMOTE'
#!/usr/bin/env bash
set -euo pipefail

STAGE="/tmp/imlate-deploy"
APP_DIR="/opt/imlate"
JAR="$APP_DIR/imlate.jar"
WEB_ROOT="/var/www/imlate"

say() { echo "[remote] $*"; }

install -d -o imlate -g imlate -m 0755 "$APP_DIR"
install -d -m 0755 "$WEB_ROOT"

# ---- 설정 파일 (있을 때만) ----
if [ -f "$STAGE/imlate.service" ]; then
  install -o root -g root -m 0644 "$STAGE/imlate.service" /etc/systemd/system/imlate.service
  systemctl daemon-reload
  say "systemd 유닛 갱신"
fi

if [ -f "$STAGE/imlate-nginx.conf" ] && [ -d /etc/nginx/conf.d ]; then
  install -o root -g root -m 0644 "$STAGE/imlate-nginx.conf" /etc/nginx/conf.d/imlate.conf
  if nginx -t; then
    systemctl reload nginx || systemctl restart nginx
    say "nginx 설정 갱신 및 reload"
  else
    say "nginx 설정 검증 실패 — 이전 설정을 유지합니다"
  fi
fi

# ---- 프론트엔드 ----
if [ -f "$STAGE/imlate-dist.tar.gz" ]; then
  TMP_WEB="$(mktemp -d /tmp/imlate-web.XXXXXX)"
  tar -xzf "$STAGE/imlate-dist.tar.gz" -C "$TMP_WEB"

  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "$TMP_WEB"/ "$WEB_ROOT"/
  else
    rm -rf "${WEB_ROOT:?}"/*
    cp -a "$TMP_WEB"/. "$WEB_ROOT"/
  fi

  chown -R root:root "$WEB_ROOT"
  chmod -R a+rX "$WEB_ROOT"
  restorecon -R "$WEB_ROOT" >/dev/null 2>&1 || true
  rm -rf "$TMP_WEB"
  say "프론트엔드 동기화 완료: $WEB_ROOT"
fi

# ---- 백엔드 ----
if [ -f "$STAGE/imlate.jar" ]; then
  if [ -f "$JAR" ]; then
    cp -f "$JAR" "$APP_DIR/imlate.jar.bak"
    say "이전 jar 백업: $APP_DIR/imlate.jar.bak"
  fi

  install -o imlate -g imlate -m 0644 "$STAGE/imlate.jar" "$JAR"

  # 환경변수를 최신 SSM 값으로 갱신한 뒤 재기동
  systemctl restart imlate-env.service || say "imlate-env 갱신 실패(기존 환경변수 유지)"
  systemctl restart imlate.service
  say "imlate 서비스 재시작 요청"

  for i in $(seq 1 30); do
    if systemctl is-active --quiet imlate.service; then
      say "imlate 서비스 active (${i}s)"
      break
    fi
    sleep 1
  done

  if ! systemctl is-active --quiet imlate.service; then
    say "서비스가 기동되지 않았습니다. 최근 로그:"
    journalctl -u imlate.service -n 50 --no-pager || true
    exit 1
  fi
fi

rm -rf "$STAGE"
say "배포 완료"
REMOTE

  chmod +x "$WORK_DIR/remote-install.sh"
}

# ---------------------------------------------------------------------
# 스테이징 디렉터리 구성 (전송할 파일 모으기)
# ---------------------------------------------------------------------
stage_artifacts() {
  mkdir -p "$WORK_DIR/stage"

  if (( DO_BACKEND == 1 )); then
    cp "$JAR_FILE" "$WORK_DIR/stage/imlate.jar"
  fi

  if (( DO_FRONTEND == 1 )); then
    cp "$DIST_ARCHIVE" "$WORK_DIR/stage/imlate-dist.tar.gz"
  fi

  if (( DO_CONFIG == 1 )); then
    if [[ -f "$SYSTEMD_UNIT" ]]; then
      cp "$SYSTEMD_UNIT" "$WORK_DIR/stage/imlate.service"
    fi
    if [[ -f "$NGINX_CONF" ]]; then
      cp "$NGINX_CONF" "$WORK_DIR/stage/imlate-nginx.conf"
    fi
  fi

  cp "$WORK_DIR/remote-install.sh" "$WORK_DIR/stage/remote-install.sh"
}

# ---------------------------------------------------------------------
# 3-A) SSH 전송
# ---------------------------------------------------------------------
deploy_via_ssh() {
  local ssh_opts=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -p "$SSH_PORT")
  local scp_opts=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -P "$SSH_PORT")

  if [[ -n "$SSH_KEY" ]]; then
    [[ -f "$SSH_KEY" ]] || die "개인키 파일을 찾을 수 없습니다: $SSH_KEY"
    ssh_opts+=(-i "$SSH_KEY")
    scp_opts+=(-i "$SSH_KEY")
  fi

  local target="$SSH_USER@$SSH_HOST"

  log "원격 스테이징 디렉터리 준비: $target:$REMOTE_STAGE"
  ssh "${ssh_opts[@]}" "$target" "rm -rf '$REMOTE_STAGE' && mkdir -p '$REMOTE_STAGE'"

  log "아티팩트 전송 중"
  scp "${scp_opts[@]}" -r "$WORK_DIR/stage/." "$target:$REMOTE_STAGE/"

  log "원격 설치 실행"
  ssh "${ssh_opts[@]}" "$target" "chmod +x '$REMOTE_STAGE/remote-install.sh' && sudo '$REMOTE_STAGE/remote-install.sh'"
}

# ---------------------------------------------------------------------
# 3-B) S3 + SSM RunCommand 전송
# ---------------------------------------------------------------------
deploy_via_ssm() {
  local s3_base="s3://$BUCKET/$S3_PREFIX/$STAMP"

  log "S3 업로드: $s3_base"
  aws s3 cp "$WORK_DIR/stage/" "$s3_base/" --recursive --region "$AWS_REGION" --only-show-errors

  local commands_file="$WORK_DIR/ssm-commands.json"
  cat > "$commands_file" <<JSON
{
  "commands": [
    "set -eu",
    "rm -rf '$REMOTE_STAGE'",
    "mkdir -p '$REMOTE_STAGE'",
    "aws s3 cp '$s3_base/' '$REMOTE_STAGE/' --recursive --region '$AWS_REGION' --only-show-errors",
    "chmod +x '$REMOTE_STAGE/remote-install.sh'",
    "'$REMOTE_STAGE/remote-install.sh'"
  ],
  "executionTimeout": ["1800"]
}
JSON

  # Windows(Git Bash/MSYS)에서 실행하면 aws.exe 는 네이티브 Windows 경로만 이해한다.
  # "/c/Users/..." 를 그대로 넘기면 "Unable to load paramfile" 로 실패하므로 변환한다.
  local commands_file_uri="$commands_file"
  if command -v cygpath >/dev/null 2>&1; then
    commands_file_uri="$(cygpath -m "$commands_file")"
  fi

  log "SSM RunCommand 전송: $INSTANCE_ID"
  local command_id
  command_id="$(aws ssm send-command \
    --region "$AWS_REGION" \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --comment "imlate deploy $STAMP" \
    --timeout-seconds 600 \
    --parameters "file://$commands_file_uri" \
    --query 'Command.CommandId' \
    --output text)"

  log "CommandId=$command_id — 완료 대기"

  local status="Pending"
  for _ in $(seq 1 120); do
    sleep 5
    status="$(aws ssm list-command-invocations \
      --region "$AWS_REGION" \
      --command-id "$command_id" \
      --details \
      --query 'CommandInvocations[0].Status' \
      --output text 2>/dev/null || echo "Pending")"

    case "$status" in
      Success|Failed|Cancelled|TimedOut) break ;;
    esac
  done

  aws ssm list-command-invocations \
    --region "$AWS_REGION" \
    --command-id "$command_id" \
    --details \
    --query 'CommandInvocations[0].CommandPlugins[0].Output' \
    --output text || true

  [[ "$status" == "Success" ]] || die "SSM 배포 실패 (status=$status, commandId=$command_id)"
  log "SSM 배포 성공"
}

# ---------------------------------------------------------------------
# 4) 헬스체크
# ---------------------------------------------------------------------
health_check() {
  [[ -n "$HEALTH_URL" ]] || return 0
  command -v curl >/dev/null 2>&1 || { warn "curl 이 없어 헬스체크를 건너뜁니다."; return 0; }

  log "헬스체크 대기: $HEALTH_URL (최대 ${HEALTH_TIMEOUT}s)"

  local waited=0
  while (( waited < HEALTH_TIMEOUT )); do
    if curl -fsS --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then
      log "헬스체크 성공 (${waited}s)"
      return 0
    fi
    sleep 5
    waited=$(( waited + 5 ))
  done

  die "헬스체크 실패: $HEALTH_URL (${HEALTH_TIMEOUT}s 초과)"
}

# ---------------------------------------------------------------------
# 실행
# ---------------------------------------------------------------------
log "배포 시작 (mode=$MODE, region=$AWS_REGION, stamp=$STAMP)"

if (( DO_BACKEND == 1 )); then
  build_backend
fi

if (( DO_FRONTEND == 1 )); then
  build_frontend
fi

write_remote_install_script
stage_artifacts

case "$MODE" in
  ssh) deploy_via_ssh ;;
  ssm) deploy_via_ssm ;;
esac

health_check

log "완료"
