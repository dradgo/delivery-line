#!/bin/sh
# Story 3g-3 follow-up (FR74) — end-to-end entrypoint tier for REAL token-usage capture. The
# entrypoint now runs codex with `--json` (JSONL events on stdout) and builds the runner-result via
# `--events-file $STDOUT_LOG`. This drives the REAL entrypoint.sh with a mock codex that emits the
# codex `--json` event shape (an agent_message item + a turn.completed.usage), and asserts the
# emitted runner-result.v1 carries normalizedOutput.usage lifted from those events.
# POSIX sh, node required (for lib/runner.mjs build). Run: `sh runners/codex/test/entrypoint-token-usage.test.sh`
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNNER_DIR/../.." && pwd)"
ENTRYPOINT="$RUNNER_DIR/entrypoint.sh"
RUNNER_LIB="$RUNNER_DIR/lib/runner.mjs"
BUNDLE_FIXTURE="$REPO_ROOT/deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json"

NODE_BIN="${NODE_BIN:-node}"
FAILURES=0
WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/codex-ep-tokens.XXXXXX")"
trap 'rm -rf "$WORKROOT"' EXIT INT TERM

pass() { printf 'ok   - %s\n' "$1"; }
fail() { printf 'FAIL - %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
assert_contains() { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 (missing: $3)" ;; esac; }

printf '# entrypoint-token-usage.test.sh (codex)\n'
[ -f "$BUNDLE_FIXTURE" ] || { printf 'FATAL: bundle fixture not found at %s\n' "$BUNDLE_FIXTURE"; exit 2; }

_w="$(mktemp -d "$WORKROOT/run.XXXXXX")"
mkdir -p "$_w/input" "$_w/output" "$_w/logs" "$_w/repo"
cp "$BUNDLE_FIXTURE" "$_w/input/context-bundle.v1.json"

# Mock codex emitting the `--json` JSONL event stream (drains the combined-prompt stdin first).
_mock="$_w/mock-codex.sh"
cat > "$_mock" <<'MOCK'
#!/bin/sh
cat > /dev/null 2>&1 || true
cat <<'JSONL'
{"type":"thread.started","thread_id":"t1"}
{"type":"turn.started"}
{"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"Reconstructed plan step one"}}
{"type":"turn.completed","usage":{"input_tokens":5000,"cached_input_tokens":1000,"output_tokens":300,"reasoning_output_tokens":40}}
JSONL
MOCK
chmod +x "$_mock"

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
  CODEX_STALL_SECONDS="0" \
  sh "$ENTRYPOINT" >"$_w/entrypoint.stdout" 2>"$_w/entrypoint.stderr"
RUN_RC=$?
set -e

[ "$RUN_RC" -eq 0 ] && pass "entrypoint exits 0" || fail "entrypoint exit ($RUN_RC): $(cat "$_w/entrypoint.stderr")"
[ -f "$RESULT_FILE" ] && pass "runner-result written" || fail "no runner-result"
RESULT="$(cat "$RESULT_FILE" 2>/dev/null || echo '')"
# input_tokens 5000 + output_tokens 300 = 5300 (blended total; cached/reasoning subsets dropped).
assert_contains "usage.inputTokens captured" "$RESULT" '"inputTokens": 5000'
assert_contains "usage.outputTokens captured" "$RESULT" '"outputTokens": 300'
assert_contains "usage.totalTokens = input+output" "$RESULT" '"totalTokens": 5300'
# The artifact text was reconstructed from the agent_message, not the raw JSONL.
assert_contains "artifact reconstructed from agent_message" "$RESULT" 'Reconstructed plan step one'
# The JSONL must NOT leak the informational subset keys into the emitted usage.
case "$RESULT" in *cached_input_tokens*|*reasoning_output_tokens*) fail "raw usage subset keys leaked into result" ;; *) pass "no raw usage subset keys leaked" ;; esac

printf '\n# %d failure(s)\n' "$FAILURES"
[ "$FAILURES" -eq 0 ]
