#!/usr/bin/env bash
# =====================================================================
# imlate — Let's Encrypt 인증서 발급 / 갱신 (best-effort)
#
#   배치 위치: /usr/local/bin/imlate-tls.sh
#   실행 주체: imlate-tls.timer → imlate-tls.service (부팅 3분 후, 이후 1시간마다)
#
#   ★ 이 스크립트는 **무슨 일이 있어도 exit 0** 이다.
#     인증서 발급 실패가 부트스트랩 실패나 서비스 중단으로 이어지면 안 되기 때문이다.
#     실패하면 경고 로그만 남기고 nginx 는 80 포트로 계속 서비스한다.
#
#   왜 certbot 을 pip 로 설치하는가
#     Amazon Linux 2023 기본 저장소에는 certbot 패키지가 없다(AL2 시절 EPEL 에만 있었다).
#     공식 권장 방식대로 python venv + pip 로 설치한다.
#     nginx 플러그인(certbot-nginx)은 쓰지 않는다 — webroot 방식이면 certbot 이
#     우리 nginx 설정을 건드릴 일이 없어 더 안전하다.
#
#   갱신 흐름
#     certbot renew → 성공 시 --deploy-hook 으로 imlate-tls-sync.sh → nginx reload
#
#   설정은 /etc/imlate/tls.env 에서 읽는다(terraform user_data 가 생성).
# =====================================================================
set -uo pipefail

ENV_FILE="/etc/imlate/tls.env"
# shellcheck disable=SC1090
[ -r "$ENV_FILE" ] && . "$ENV_FILE"

TLS_ENABLED="${IMLATE_TLS_ENABLED:-false}"
DOMAINS="${IMLATE_TLS_DOMAINS:-}"
EMAIL="${IMLATE_TLS_EMAIL:-}"
WEBROOT="${IMLATE_ACME_WEBROOT:-/var/www/acme}"

CERTBOT_HOME="/opt/certbot"
CERTBOT="$CERTBOT_HOME/bin/certbot"
SYNC_SCRIPT="/usr/local/bin/imlate-tls-sync.sh"

log() { echo "[imlate-tls] $(date -Is) $*"; }

# 어떤 경로로 끝나든 nginx 구성을 현재 인증서 상태에 맞춘 뒤 성공으로 종료한다.
finish() {
  [ -x "$SYNC_SCRIPT" ] && "$SYNC_SCRIPT"
  exit 0
}

if [ "$TLS_ENABLED" != "true" ] || [ -z "$DOMAINS" ]; then
  log "TLS 비활성 (enable_tls=false 이거나 domain_name 이 비어 있음) — HTTP(80)로만 서비스합니다"
  finish
fi

# ---------------------------------------------------------------------
# 1) ACME webroot 준비 — nginx 가 /.well-known/acme-challenge/ 를 여기서 서빙한다
# ---------------------------------------------------------------------
install -d -m 0755 "$WEBROOT/.well-known/acme-challenge"
chmod -R a+rX "$WEBROOT" 2>/dev/null || true

# HTTP-01 챌린지는 80 포트로 들어온다. nginx 가 떠 있어야 한다.
if ! systemctl is-active --quiet nginx; then
  log "nginx 가 내려가 있어 기동을 시도합니다"
  systemctl start nginx || log "경고: nginx 기동 실패"
fi

# ---------------------------------------------------------------------
# 2) certbot 준비 (python venv + pip)
# ---------------------------------------------------------------------
if [ ! -x "$CERTBOT" ]; then
  log "certbot 설치 시작 (python venv + pip)"

  PYTHON_BIN="python3"
  command -v python3.11 >/dev/null 2>&1 && PYTHON_BIN="python3.11"

  if ! "$PYTHON_BIN" -m venv "$CERTBOT_HOME" >/dev/null 2>&1; then
    log "경고: python venv 생성 실패 — TLS 를 건너뜁니다"
    finish
  fi

  "$CERTBOT_HOME/bin/pip" install --quiet --upgrade pip setuptools wheel >/dev/null 2>&1 || true

  if ! "$CERTBOT_HOME/bin/pip" install --quiet certbot >/dev/null 2>&1; then
    log "경고: certbot 설치 실패 — HTTP(80) 서비스는 계속됩니다"
    finish
  fi

  ln -sf "$CERTBOT" /usr/local/bin/certbot
  log "certbot 설치 완료: $("$CERTBOT" --version 2>&1 | head -n 1)"
fi

# ---------------------------------------------------------------------
# 3) 발급 또는 갱신
# ---------------------------------------------------------------------
PRIMARY="$(printf '%s\n' "$DOMAINS" | awk '{print $1}')"

DOMAIN_ARGS=()
for domain in $DOMAINS; do
  DOMAIN_ARGS+=(-d "$domain")
done

if [ -n "$EMAIL" ]; then
  MAIL_ARGS=(--email "$EMAIL")
else
  MAIL_ARGS=(--register-unsafely-without-email)
  log "경고: tls_contact_email 이 비어 있습니다 — 인증서 만료 알림 메일을 받을 수 없습니다"
fi

if [ -s "/etc/letsencrypt/live/$PRIMARY/fullchain.pem" ]; then
  log "기존 인증서 갱신 여부 확인 ($PRIMARY)"
  "$CERTBOT" renew --quiet --deploy-hook "$SYNC_SCRIPT" ||
    log "경고: 갱신 실패 — 기존 인증서를 그대로 사용합니다"
else
  log "인증서 발급 시도: $DOMAINS"
  if "$CERTBOT" certonly \
    --non-interactive --agree-tos --no-eff-email --keep-until-expiring \
    --webroot -w "$WEBROOT" \
    "${MAIL_ARGS[@]}" "${DOMAIN_ARGS[@]}"; then
    log "인증서 발급 완료"
  else
    log "경고: 인증서 발급 실패 — 다음 타이머(1시간 뒤)에 다시 시도합니다."
    log "      확인할 것: (1) DNS A 레코드가 이 서버의 EIP 를 가리키는지"
    log "                 (2) 보안 그룹 80 포트가 0.0.0.0/0 에 열려 있는지"
    log "      그동안에도 HTTP(80) 서비스는 정상 동작합니다."
  fi
fi

finish
