#!/usr/bin/env bash

# 离线回归：fake Trivy 的疑似密钥只能进入受控临时文件，所有退出路径均需清理。
# 任一用例断言失败必须让整个回归返回非零，避免末尾成功提示掩盖失败。
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TEST_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ziji-security-scan-test.XXXXXX")"
readonly FAKE_TRIVY="$TEST_TMP_DIR/fake-trivy"
readonly MOCK_SECRET='mock-secret-value-must-not-reach-log'

cleanup() {
  rm -rf -- "$TEST_TMP_DIR"
}
trap cleanup EXIT HUP INT TERM

cat >"$FAKE_TRIVY" <<'EOF'
#!/usr/bin/env bash
set -uo pipefail

output_path=''
scanner=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = '--scanners' ]; then
    scanner="$2"
    shift 2
    continue
  fi
  if [ "$1" = '--output' ]; then
    output_path="$2"
    shift 2
    continue
  fi
  shift
done

if [ "$scanner" != 'secret' ]; then
  exit 64
fi

printf '%s\n' 'mock-secret-value-must-not-reach-log' >&2
case "${FAKE_TRIVY_MODE:?}" in
  valid-zero)
    printf '%s\n' '{"Results":[]}' >"$output_path"
    exit 0
    ;;
  valid-one)
    # 合法报告内放入模拟匹配，验证报告内容也不会进入 stdout/stderr。
    printf '%s\n' '{"Results":[{"Target":"fixture.txt","Secrets":[{"RuleID":"mock-secret","Match":"mock-secret-value-must-not-reach-log"}]}]}' >"$output_path"
    exit 1
    ;;
  invalid-json)
    printf '%s\n' '{not-json' >"$output_path"
    exit 0
    ;;
  term)
    printf '%s\n' '{"Results":[]}' >"$output_path"
    # 向拥有陷阱的扫描子 shell 发送取消信号后立即退出，避免固定等待掩盖清理结果。
    kill -TERM "$PPID"
    exit 0
    ;;
  *)
    exit 64
    ;;
esac
EOF
chmod 700 "$FAKE_TRIVY"

assert_no_secret_artifacts() {
  if find "$TEST_TMP_DIR" -type f \( -name 'ziji-trivy-secret-report.*' -o -name 'ziji-trivy-secret-stderr.*' \) -print -quit | grep -q .; then
    printf '密钥扫描临时文件未清理\n' >&2
    return 1
  fi
}

run_case() {
  local mode="$1"
  local expected_status="$2"
  local stdout_path="$TEST_TMP_DIR/$mode.stdout"
  local stderr_path="$TEST_TMP_DIR/$mode.stderr"
  local status=0

  FAKE_TRIVY_MODE="$mode" TRIVY_BIN="$FAKE_TRIVY" TMPDIR="$TEST_TMP_DIR" \
    "$ROOT_DIR/scripts/security-scan.sh" secrets >"$stdout_path" 2>"$stderr_path" || status=$?

  if [ "$status" -ne "$expected_status" ]; then
    printf '%s 预期退出码 %s，实际为 %s\n' "$mode" "$expected_status" "$status" >&2
    return 1
  fi
  if grep -Fq "$MOCK_SECRET" "$stdout_path" "$stderr_path"; then
    printf '%s 将模拟密钥输出到了日志\n' "$mode" >&2
    return 1
  fi
  assert_no_secret_artifacts
}

run_case valid-zero 0
run_case valid-one 1
run_case invalid-json 2
run_case term 143
printf '密钥扫描离线回归通过\n'
