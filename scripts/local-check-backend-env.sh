#!/usr/bin/env bash
# 用与 nightly-batch.yml 的 web-proxy-e2e 作业等价的临时密钥，在本地验证后端能否启动。
# 目的：在推到 CI 之前确认 application-local.properties 的必填变量清单完整。
# 密钥只在当前 shell 临时生成，不写入仓库。
set -euo pipefail
cd "$(dirname "$0")/.."

tmpdir="$(mktemp -d)"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmpdir/key.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$tmpdir/key.pem" -outform DER -out "$tmpdir/key.der"
openssl pkey -in "$tmpdir/key.pem" -pubout -outform DER -out "$tmpdir/pub.der"

export ZIJI_DB_URL="jdbc:postgresql://localhost:5432/ziji"
export ZIJI_DB_USER="ziji"
export ZIJI_DB_PASSWORD="ziji-local"
export ZIJI_MAIL_HOST="localhost"
export ZIJI_MAIL_PORT="1025"
export ZIJI_THS_SMOKE_ENABLED="false"
export ZIJI_FX_PROVIDER_MODE="mock"

export ZIJI_ACCOUNT_CURSOR_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_LIQUIDITY_HOLD_CURSOR_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_AUTH_HMAC_CURRENT_KEY_VERSION=1
export ZIJI_AUTH_HMAC_CURRENT_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_AUTH_IDEMPOTENCY_CURRENT_KEY_VERSION=1
export ZIJI_AUTH_IDEMPOTENCY_CURRENT_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_AUTH_ENVELOPE_KEK_VERSION=1
export ZIJI_AUTH_ENVELOPE_KEK_BASE64="$(openssl rand -base64 32)"
export ZIJI_AUTH_ACCESS_TOKEN_CURRENT_KID="local-check-1"
export ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PRIVATE_KEY_PKCS8_BASE64="$(base64 < "$tmpdir/key.der" | tr -d '\n')"
export ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PUBLIC_KEY_X509_BASE64="$(base64 < "$tmpdir/pub.der" | tr -d '\n')"
rm -rf "$tmpdir"

echo "== starting backend (equivalent env to CI) =="
cd backend
./mvnw --batch-mode --no-transfer-progress spring-boot:run > /tmp/ziji_backend_run.log 2>&1 &
BE_PID=$!
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    echo "backend healthy after ${i} attempts"
    curl -fsS http://127.0.0.1:8080/actuator/health; echo
    kill "$BE_PID" 2>/dev/null || true
    exit 0
  fi
  sleep 2
done
echo "backend did not become healthy" >&2
grep -E "APPLICATION FAILED|Description:|Reason:|Property:|caused by" /tmp/ziji_backend_run.log | head -20 >&2
kill "$BE_PID" 2>/dev/null || true
exit 1
