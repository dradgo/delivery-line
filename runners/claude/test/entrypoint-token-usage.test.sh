#!/bin/sh
# Story 3g-5 (FR74) — end-to-end entrypoint tier for REAL Claude token-usage capture. The entrypoint
# now runs claude with `--output-format stream-json --verbose` (JSONL events on stdout) and builds the
# runner-result via `--events-file $STDOUT_LOG`. This drives the REAL entrypoint.sh with a mock claude
# that emits the Claude `stream-json` event shape (incremental `assistant` events + a terminal
# `type:"result"` carrying the final answer + cumulative usage), and asserts the emitted
# runner-result.v1 carries normalizedOutput.usage lifted from those events — with
# DELIVERYLINE_USAGE_MOCK_FILE UNSET, so this proves the REAL parse path (parseClaudeEvents), not the
# buildUsage() mock seam (D1a real-producer verification). Twin of the codex
# entrypoint-token-usage.test.sh.
# POSIX sh, node required (for lib/runner.mjs build). Run: `sh runners/claude/test/entrypoint-token-usage.test.sh`
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNNER_DIR/../.." && pwd)"
ENTRYPOINT="$RUNNER_DIR/entrypoint.sh"
RUNNER_LIB="$RUNNER_DIR/lib/runner.mjs"
BUNDLE_FIXTURE="$REPO_ROOT/deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json"

NODE_BIN="${NODE_BIN:-node}"
FAILURES=0
WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/claude-ep-tokens.XXXXXX")"
trap 'rm -rf "$WORKROOT"' EXIT INT TERM

pass() { printf 'ok   - %s\n' "$1"; }
fail() { printf 'FAIL - %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
assert_contains() { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 (missing: $3)" ;; esac; }

printf '# entrypoint-token-usage.test.sh (claude)\n'
[ -f "$BUNDLE_FIXTURE" ] || { printf 'FATAL: bundle fixture not found at %s\n' "$BUNDLE_FIXTURE"; exit 2; }

_w="$(mktemp -d "$WORKROOT/run.XXXXXX")"
mkdir -p "$_w/input" "$_w/output" "$_w/logs" "$_w/repo"
cp "$BUNDLE_FIXTURE" "$_w/input/context-bundle.v1.json"

# Mock claude emitting the `--output-format stream-json` JSONL event stream (drains the prompt stdin
# first). Incremental `assistant` progress events + a terminal `result` event carrying the final
# answer (result) and cumulative snake_case usage (cache_* are informational subsets dropped).
_mock="$_w/mock-claude.sh"
cat > "$_mock" <<'MOCK'
#!/bin/sh
cat > /dev/null 2>&1 || true
cat <<'JSONL'
{"type":"system","subtype":"init","session_id":"s1"}
{"type":"assistant","message":{"content":[{"type":"text","text":"Progress: analysing the ticket."}]}}
{"type":"result","subtype":"success","is_error":false,"session_id":"s1","num_turns":2,"result":"Reconstructed Claude plan step one","total_cost_usd":0.0031,"usage":{"input_tokens":5000,"output_tokens":300,"cache_creation_input_tokens":0,"cache_read_input_tokens":1000}}
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
  DELIVERYLINE_USAGE_MOCK_FILE="" \
  NODE_BIN="$NODE_BIN" \
  CLAUDE_CLI_BIN="$_mock" \
  CLAUDE_STALL_SECONDS="0" \
  sh "$ENTRYPOINT" >"$_w/entrypoint.stdout" 2>"$_w/entrypoint.stderr"
RUN_RC=$?
set -e

[ "$RUN_RC" -eq 0 ] && pass "entrypoint exits 0" || fail "entrypoint exit ($RUN_RC): $(cat "$_w/entrypoint.stderr")"
[ -f "$RESULT_FILE" ] && pass "runner-result written" || fail "no runner-result"
RESULT="$(cat "$RESULT_FILE" 2>/dev/null || echo '')"
# input_tokens 5000 + output_tokens 300 = 5300 (blended total; cache_* subsets dropped).
assert_contains "usage.inputTokens captured (REAL parse path)" "$RESULT" '"inputTokens": 5000'
assert_contains "usage.outputTokens captured" "$RESULT" '"outputTokens": 300'
assert_contains "usage.totalTokens = input+output" "$RESULT" '"totalTokens": 5300'
# The artifact text was reconstructed from the terminal result event, not the raw JSONL / narration.
assert_contains "artifact reconstructed from result event" "$RESULT" 'Reconstructed Claude plan step one'
case "$RESULT" in *"Progress: analysing"*) fail "progress narration leaked into the artifact" ;; *) pass "progress narration not leaked" ;; esac
# The JSONL must NOT leak the informational subset keys into the emitted usage.
case "$RESULT" in *cache_creation_input_tokens*|*cache_read_input_tokens*) fail "raw usage subset keys leaked into result" ;; *) pass "no raw usage subset keys leaked" ;; esac
# The runner stderr marker reports usage present (built usage present=true).
assert_contains "runner emitted built-usage-present marker" "$(cat "$_w/entrypoint.stderr" 2>/dev/null || echo '')" 'built usage present=true'

printf '\n# %d failure(s)\n' "$FAILURES"
[ "$FAILURES" -eq 0 ]
