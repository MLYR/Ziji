#!/usr/bin/env bash
# 本地起一个与 nightly-batch.yml 的 web-proxy-e2e 作业等价的后端，用于本地复现
# CI 上的 Playwright 失败（避免每次都推到 GitHub Actions 才能看到结果）。
# 密钥在运行时临时生成，不写入仓库。后端在前台运行，Ctrl-C 或用 kill 结束。
set -euo pipefail
cd "$(dirname "$0")/.."

tmpdir="$(mktemp -d)"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmpdir/key.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$tmpdir/key.pem" -outform DER -out "$tmpdir/key.der"
openssl pkey -in "$tmpdir/key.pem" -pubout -outform DER -out "$tmpdir/pub.der"

export ZIJI_DB_URL="${ZIJI_DB_URL:-jdbc:postgresql://localhost:5432/ziji}"
export ZIJI_DB_USER="${ZIJI_DB_USER:-ziji}"
export ZIJI_DB_PASSWORD="${ZIJI_DB_PASSWORD:-ziji-local}"
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

cd backend
exec ./mvnw --batch-mode --no-transfer-progress spring-boot:run
