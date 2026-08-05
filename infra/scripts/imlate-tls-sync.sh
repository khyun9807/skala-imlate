#!/usr/bin/env bash
# =====================================================================
# imlate — 인증서 상태에 맞춰 nginx 구성을 맞춘다
#
#   배치 위치: /usr/local/bin/imlate-tls-sync.sh
#
#   이 스크립트는 /etc/nginx/imlate/ 아래 조각 파일만 다시 쓴다.
#   서비스 규칙 본체(app-locations.conf)와 :80 서버(conf.d/imlate.conf)는 건드리지 않는다.
#
#     인증서 있음  →  tls-server.conf(:443) + http-mode-redirect.conf(80→443)
#     인증서 없음  →  http-mode-plain.conf(:80 에서 그대로 서비스)
#
#   ★ 원칙: 어떤 경우에도 nginx 가 죽으면 안 된다.
#     TLS 구성을 얹었다가 nginx -t 가 실패하면 즉시 평문 구성으로 되돌린다.
#
#   호출 시점
#     - EC2 부팅 직후(user_data)
#     - imlate-tls.sh 발급/갱신 성공 후 (certbot --deploy-hook)
#     - deploy.sh 가 nginx 설정을 갱신한 뒤
# =====================================================================
set -uo pipefail

ENV_FILE="/etc/imlate/tls.env"
# shellcheck disable=SC1090
[ -r "$ENV_FILE" ] && . "$ENV_FILE"

TLS_ENABLED="${IMLATE_TLS_ENABLED:-false}"
DOMAINS="${IMLATE_TLS_DOMAINS:-}"
SNIPPET_DIR="${IMLATE_NGINX_SNIPPET_DIR:-/etc/nginx/imlate}"
APP_LOCATIONS="$SNIPPET_DIR/app-locations.conf"

log() { echo "[imlate-tls-sync] $*"; }

install -d -m 0755 "$SNIPPET_DIR"

PRIMARY="$(printf '%s\n' "$DOMAINS" | awk '{print $1}')"
LIVE_DIR="/etc/letsencrypt/live/$PRIMARY"

# ---------------------------------------------------------------------
# 조각 파일 생성
# ---------------------------------------------------------------------
write_plain_mode() {
  rm -f "$SNIPPET_DIR/tls-server.conf" "$SNIPPET_DIR/http-mode-redirect.conf"
  cat > "$SNIPPET_DIR/http-mode-plain.conf" <<PLAIN
# imlate-tls-sync.sh 가 생성한 파일입니다. 직접 수정하지 마세요.
# 인증서가 없으므로 :80 에서 그대로 서비스한다.
include $APP_LOCATIONS;
PLAIN
}

write_tls_mode() {
  rm -f "$SNIPPET_DIR/http-mode-plain.conf"

  # $host / $request_uri 는 nginx 변수이므로 확장되면 안 된다(따옴표 붙인 heredoc).
  cat > "$SNIPPET_DIR/http-mode-redirect.conf" <<'REDIRECT'
# imlate-tls-sync.sh 가 생성한 파일입니다. 직접 수정하지 마세요.
# 인증서가 있으므로 ACME 챌린지를 제외한 나머지는 전부 HTTPS 로 보낸다.
location / {
    return 301 https://$host$request_uri;
}
REDIRECT

  cat > "$SNIPPET_DIR/tls-server.conf" <<TLS_SERVER
# imlate-tls-sync.sh 가 생성한 파일입니다. 직접 수정하지 마세요.
server {
    listen       443 ssl default_server;
    listen       [::]:443 ssl default_server;
    server_name  $DOMAINS;

    server_tokens off;

    ssl_certificate     $LIVE_DIR/fullchain.pem;
    ssl_certificate_key $LIVE_DIR/privkey.pem;

    ssl_protocols             TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache         shared:imlate_ssl:10m;
    ssl_session_timeout       1d;
    ssl_session_tickets       off;

    add_header Strict-Transport-Security "max-age=31536000" always;

    include $APP_LOCATIONS;
}
TLS_SERVER
}

# ---------------------------------------------------------------------
# 검증 후 reload
# ---------------------------------------------------------------------
validate_and_reload() {
  if ! nginx -t >/dev/null 2>&1; then
    nginx -t 2>&1 | tail -n 5
    return 1
  fi

  if systemctl is-active --quiet nginx; then
    systemctl reload nginx
  else
    systemctl start nginx
  fi
}

if [ ! -r "$APP_LOCATIONS" ]; then
  log "경고: $APP_LOCATIONS 이 없습니다. deploy.sh 로 nginx 설정을 배포하세요."
fi

if [ "$TLS_ENABLED" = "true" ] && [ -n "$PRIMARY" ] &&
  [ -s "$LIVE_DIR/fullchain.pem" ] && [ -s "$LIVE_DIR/privkey.pem" ]; then

  write_tls_mode
  if validate_and_reload; then
    log "HTTPS 활성화 완료 (server_name=$DOMAINS)"
    exit 0
  fi

  log "경고: TLS 구성 검증 실패 — 평문(80) 구성으로 되돌립니다"
fi

write_plain_mode
if validate_and_reload; then
  log "HTTP(80) 구성 적용 완료"
else
  log "경고: nginx 설정 검증/reload 실패 — 이전 상태가 유지됩니다"
fi

exit 0
