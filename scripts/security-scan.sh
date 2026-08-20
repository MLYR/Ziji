#!/usr/bin/env bash

# 安全扫描必须完整执行各阶段；不得因先前阶段失败而跳过后续证据。
set -uo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TRIVY_BIN="${TRIVY_BIN:-trivy}"

usage() {
  cat <<'EOF'
用法：scripts/security-scan.sh <dependencies|licenses|secrets|all>

依赖漏洞、许可证和密钥扫描均会在失败时返回非零。all 模式始终执行全部三阶段，
并以汇总状态退出；未安装所需扫描器属于环境失败，绝不被记为扫描通过。
EOF
}

run_stage() {
  local stage_name="$1"
  shift

  printf '\n==> 安全扫描阶段：%s\n' "$stage_name"
  "$@"
  local stage_status=$?
  if [ "$stage_status" -eq 0 ]; then
    printf '<== %s：通过\n' "$stage_name"
  else
    printf '<== %s：失败（退出码 %s）\n' "$stage_name" "$stage_status" >&2
  fi
  return "$stage_status"
}

require_trivy() {
  if command -v "$TRIVY_BIN" >/dev/null 2>&1; then
    return 0
  fi

  printf '缺少 Trivy：请安装固定版本后再运行本脚本；CI 会在扫描前校验固定发布包。\n' >&2
  return 2
}

scan_dependencies() {
  local result=0

  # pnpm audit 是 Node 生产依赖的权威门禁，HIGH/CRITICAL 均会阻断。
  run_stage 'Node production dependency audit' \
    pnpm audit --prod --audit-level=high || result=$?

  # Trivy 同时解析 pnpm 锁文件和 Maven POM，避免 Java 依赖落在 Node 审计之外。
  if require_trivy; then
    run_stage 'cross-language dependency vulnerability scan' \
      "$TRIVY_BIN" fs --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false \
      --exit-code 1 --skip-dirs .git --skip-dirs node_modules . || result=$?
  else
    result=$?
  fi

  return "$result"
}

scan_licenses() {
  # 许可证策略由仓库内受审计的清单定义；未知或未批准许可会明确失败。
  run_stage 'production dependency license policy' \
    node scripts/check-licenses.mjs
}

scan_secrets() {
  if ! require_trivy; then
    # 反转条件会把失败状态变成 0，缺少扫描器必须保持为环境失败。
    return 2
  fi

  # 子 shell 的信号陷阱只覆盖密钥扫描，保证 all 模式仍可继续汇总其他阶段。
  (
    local scan_status=0
    local report_path=''
    local stderr_path=''

    cleanup_secret_artifacts() {
      # 报告和 Trivy stderr 都可能含敏感匹配内容，任何退出路径均不得遗留。
      rm -f -- "$report_path" "$stderr_path" 2>/dev/null || true
    }

    trap cleanup_secret_artifacts EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM

    if ! report_path="$(mktemp "${TMPDIR:-/tmp}/ziji-trivy-secret-report.XXXXXX")"; then
      printf '<== repository secret scan：无法创建受控临时报告\n' >&2
      return 2
    fi
    if ! stderr_path="$(mktemp "${TMPDIR:-/tmp}/ziji-trivy-secret-stderr.XXXXXX")"; then
      printf '<== repository secret scan：无法创建受控临时错误输出\n' >&2
      return 2
    fi

    printf '\n==> 安全扫描阶段：repository secret scan\n'
    # 禁止 Trivy stdout/stderr 直接进入日志；只输出脱敏后的阶段状态。
    "$TRIVY_BIN" fs --scanners secret --exit-code 1 --quiet --format json --output "$report_path" \
      --skip-dirs .git --skip-dirs node_modules . >/dev/null 2>"$stderr_path"
    scan_status=$?

    if ! node -e 'JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));' "$report_path" >/dev/null 2>&1; then
      printf '<== repository secret scan：扫描器未生成有效报告（退出码 %s）\n' "$scan_status" >&2
      if [ "$scan_status" -eq 0 ]; then
        return 2
      fi
      return "$scan_status"
    fi

    if [ "$scan_status" -eq 0 ]; then
      printf '<== repository secret scan：通过\n'
    else
      printf '<== repository secret scan：发现密钥或扫描失败（详情已从日志隐藏，退出码 %s）\n' "$scan_status" >&2
    fi

    return "$scan_status"
  )
}

main() {
  if [ "$#" -ne 1 ]; then
    usage >&2
    return 64
  fi

  cd "$ROOT_DIR"
  case "$1" in
    dependencies)
      scan_dependencies
      ;;
    licenses)
      scan_licenses
      ;;
    secrets)
      scan_secrets
      ;;
    all)
      local result=0
      # 保持三个调用独立，保证任一失败不会短路后续扫描。
      scan_dependencies || result=$?
      scan_licenses || result=$?
      scan_secrets || result=$?
      return "$result"
      ;;
    *)
      usage >&2
      return 64
      ;;
  esac
}

main "$@"
