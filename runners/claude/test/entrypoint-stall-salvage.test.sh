#!/bin/sh
# Finished-but-hung guard — entrypoint unit tier (claude). Twin of
# runners/codex/test/entrypoint-stall-salvage.test.sh. A CLI can complete a large turn (full
# output flushed) yet never exit, wedging the run until the broker's stdout-silence timeout fires
# and DISCARDS the completed artifact. The entrypoint now runs the CLI backgrounded with an
# INACTIVITY watchdog: if runner.stdout stays idle for CLAUDE_STALL_SECONDS while the CLI is still
# alive, it terminates the CLI and SALVAGES the captured output into a normal result.
#
# Drives the REAL entrypoint.sh with NO container and a mock Claude CLI that hangs after (or
# without) emitting output. POSIX sh, node required. Because a repo dir is present, this also
# exercises the backgrounded `( cd && exec )` subshell branch.
# Run locally: `sh runners/claude/test/entrypoint-stall-salvage.test.sh`
set -eu

RUNNER_KIND="claude"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNNER_DIR/../.." && pwd)"
ENTRYPOINT="$RUNNER_DIR/entrypoint.sh"
RUNNER_LIB="$RUNNER_DIR/lib/runner.mjs"
BUNDLE_FIXTURE="$REPO_ROOT/deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json"

NODE_BIN="${NODE_BIN:-node}"
FAILURES=0
WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/claude-ep-stall.XXXXXX")"
trap 'rm -rf "$WORKROOT"' EXIT INT TERM

pass() { printf 'ok   - %s\n' "$1"; }
fail() {
  printf 'FAIL - %s\n' "$1"
  FAILURES=$((FAILURES + 1))
}
assert_contains() { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 (missing: $3)" ;; esac; }

run_case() {
  _mockbody="$1"
  _stall="$2"
  _w="$(mktemp -d "$WORKROOT/run.XXXXXX")"
  mkdir -p "$_w/input" "$_w/output" "$_w/logs" "$_w/repo"
  cp "$BUNDLE_FIXTURE" "$_w/input/context-bundle.v1.json"

  _mock="$_w/mock-claude.sh"
  {
    printf '#!/bin/sh\n'
    printf 'cat > /dev/null 2>&1 || true\n'
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
    CLAUDE_CLI_BIN="$_mock" \
    CLAUDE_STALL_SECONDS="$_stall" \
    CLAUDE_STALL_POLL_SECONDS="1" \
    sh "$ENTRYPOINT" >"$_w/entrypoint.stdout" 2>"$RUN_STDERR"
  RUN_RC=$?
  set -e
}

printf '# entrypoint-stall-salvage.test.sh (%s)\n' "$RUNNER_KIND"
[ -f "$BUNDLE_FIXTURE" ] || { printf 'FATAL: bundle fixture not found at %s\n' "$BUNDLE_FIXTURE"; exit 2; }

# --- Case 1: normal — CLI prints output and exits 0 (guard never fires). ---
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
  printf 'sleep 30\n'
} >"$SALVAGE_BODY"
run_case "$SALVAGE_BODY" 2
[ "$RUN_RC" -eq 0 ] && pass "salvage: exits 0 (recovered)" || fail "salvage: exit ($RUN_RC)"
[ -f "$RESULT_FILE" ] && pass "salvage: result file written" || fail "salvage: no result file"
assert_contains "salvage: WARN announces salvage" "$(cat "$RUN_STDERR")" "salvaging"
assert_contains "salvage: result carries the salvaged output" "$(cat "$RESULT_FILE" 2>/dev/null || echo '')" "Salvaged plan step"

# --- Case 3: stall with EMPTY output — nothing to salvage → runner_timeout failure. ---
EMPTY_BODY="$WORKROOT/body-empty.sh"
printf 'sleep 30\n' >"$EMPTY_BODY"
run_case "$EMPTY_BODY" 2
[ "$RUN_RC" -ne 0 ] && pass "empty-stall: exits non-zero" || fail "empty-stall: expected non-zero exit"
assert_contains "empty-stall: ERROR notes empty output" "$(cat "$RUN_STDERR")" "EMPTY output"
assert_contains "empty-stall: result category is runner_timeout" "$(cat "$RESULT_FILE" 2>/dev/null || echo '')" "runner_timeout"

printf '\n# %d failure(s)\n' "$FAILURES"
[ "$FAILURES" -eq 0 ]
