#!/bin/sh
# Finished-but-hung guard — entrypoint unit tier (codex). A large Codex turn can complete (full
# output flushed) yet never exit, wedging the run until the broker's stdout-silence timeout fires
# ~20 min later and DISCARDS the completed artifact. The entrypoint now runs Codex in the
# background with an INACTIVITY watchdog: if runner.stdout stays idle for CODEX_STALL_SECONDS while
# Codex is still alive, it terminates Codex and SALVAGES the captured output into a normal result.
#
# This test drives the REAL entrypoint.sh with NO container and a mock Codex CLI that hangs after
# (or without) emitting output. POSIX sh, node required (for lib/runner.mjs build).
# Run locally: `sh runners/codex/test/entrypoint-stall-salvage.test.sh`
set -eu

RUNNER_KIND="codex"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNNER_DIR/../.." && pwd)"
ENTRYPOINT="$RUNNER_DIR/entrypoint.sh"
RUNNER_LIB="$RUNNER_DIR/lib/runner.mjs"
BUNDLE_FIXTURE="$REPO_ROOT/deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json"

NODE_BIN="${NODE_BIN:-node}"
FAILURES=0
WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/codex-ep-stall.XXXXXX")"
trap 'rm -rf "$WORKROOT"' EXIT INT TERM

pass() { printf 'ok   - %s\n' "$1"; }
fail() {
  printf 'FAIL - %s\n' "$1"
  FAILURES=$((FAILURES + 1))
}
assert_contains() { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 (missing: $3)" ;; esac; }

# run_case <mock-body-file> <stall-seconds> : runs the entrypoint with the given mock Codex.
# Sets RUN_RC, RUN_STDERR (path), RESULT_FILE (path).
run_case() {
  _mockbody="$1"
  _stall="$2"
  _w="$(mktemp -d "$WORKROOT/run.XXXXXX")"
  mkdir -p "$_w/input" "$_w/output" "$_w/logs" "$_w/repo"
  cp "$BUNDLE_FIXTURE" "$_w/input/context-bundle.v1.json"

  _mock="$_w/mock-codex.sh"
  {
    printf '#!/bin/sh\n'
    printf 'cat > /dev/null 2>&1 || true\n'   # drain the combined-prompt stdin
    cat "$_mockbody"
  } >"$_mock"
  chmod +x "$_mock"

  RUN_STDERR="$_w/entrypoint.stderr"
  RESULT_FILE="$_w/output/runner-result.v1.json"

  set +e
  env \
    DELIVERYLINE_INPUT_DIR="$_w/input" \
    DELIVERYLINE_OUTPUT_DIR="$_w/output" \
    DELIVERYLINE_LOGS_DIR="$_w/logs" \
    DELIVERYLINE_REPO_DIR="$_w/repo" \
    DELIVERYLINE_RUNNER_LIB="$RUNNER_LIB" \
    DELIVERYLINE_RUNNER_STAGE="implementation-plan" \
    DELIVERYLINE_RUNNER_SKIP_AUTH="true" \
    NODE_BIN="$NODE_BIN" \
    CODEX_CLI_BIN="$_mock" \
    CODEX_STALL_SECONDS="$_stall" \
    CODEX_STALL_POLL_SECONDS="1" \
    sh "$ENTRYPOINT" >"$_w/entrypoint.stdout" 2>"$RUN_STDERR"
  RUN_RC=$?
  set -e
}

printf '# entrypoint-stall-salvage.test.sh (%s)\n' "$RUNNER_KIND"
[ -f "$BUNDLE_FIXTURE" ] || { printf 'FATAL: bundle fixture not found at %s\n' "$BUNDLE_FIXTURE"; exit 2; }

# --- Case 1: normal — Codex prints output and exits 0 (guard never fires). ---
NORMAL_BODY="$WORKROOT/body-normal.sh"
printf "printf 'Normal plan step one\\\\n'\n" >"$NORMAL_BODY"
run_case "$NORMAL_BODY" 900
[ "$RUN_RC" -eq 0 ] && pass "normal: exits 0" || fail "normal: exit ($RUN_RC)"
[ -f "$RESULT_FILE" ] && pass "normal: result file written" || fail "normal: no result file"

# --- Case 2: finished-but-hung WITH output — guard fires, output is SALVAGED, run succeeds. ---
SALVAGE_BODY="$WORKROOT/body-salvage.sh"
{
  printf "printf 'Salvaged plan step one\\\\n'\n"
  printf "printf 'Salvaged plan step two\\\\n'\n"
  printf 'sleep 30\n'   # hang after output; the inactivity guard must terminate this
} >"$SALVAGE_BODY"
run_case "$SALVAGE_BODY" 2
[ "$RUN_RC" -eq 0 ] && pass "salvage: exits 0 (recovered)" || fail "salvage: exit ($RUN_RC)"
[ -f "$RESULT_FILE" ] && pass "salvage: result file written" || fail "salvage: no result file"
assert_contains "salvage: WARN announces salvage" "$(cat "$RUN_STDERR")" "salvaging"
assert_contains "salvage: result carries the salvaged output" "$(cat "$RESULT_FILE" 2>/dev/null || echo '')" "Salvaged plan step"

# --- Case 3: stall with EMPTY output — nothing to salvage → runner_timeout failure. ---
EMPTY_BODY="$WORKROOT/body-empty.sh"
printf 'sleep 30\n' >"$EMPTY_BODY"   # no output, then hang
run_case "$EMPTY_BODY" 2
[ "$RUN_RC" -ne 0 ] && pass "empty-stall: exits non-zero" || fail "empty-stall: expected non-zero exit"
assert_contains "empty-stall: ERROR notes empty output" "$(cat "$RUN_STDERR")" "EMPTY output"
assert_contains "empty-stall: result category is runner_timeout" "$(cat "$RESULT_FILE" 2>/dev/null || echo '')" "runner_timeout"

printf '\n# %d failure(s)\n' "$FAILURES"
[ "$FAILURES" -eq 0 ]
