#!/bin/sh
# Story 3e (clarification emission activation) — entrypoint unit tier for the spec-stage
# OPEN CLARIFYING QUESTIONS directive (codex). Twin of
# runners/claude/test/entrypoint-clarifications-directive.test.sh.
#
# The 3e-1 clarification CREATE loop was wired on BOTH ends — the runner parses a
# ```clarifications fence out of the agent stdout (lib/runner.mjs splitClarificationsFence)
# and the backend ingests it into `open` clarifications — but NOTHING ever instructed the
# spec-stage model to EMIT the fence, so real runs never produced open questions. This test
# pins the missing half: the SPEC (investigation) stage PROMPT_INSTRUCTION must carry the
# directive that teaches the model how + when to raise the fenced clarifications block, and NO
# other stage may.
#
# Runs the REAL entrypoint.sh with NO container + a mock Codex CLI that captures its stdin.
# POSIX sh, dependency-free besides `node`.
# Run locally: `sh runners/codex/test/entrypoint-clarifications-directive.test.sh`
set -eu

RUNNER_KIND="codex"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNNER_DIR/../.." && pwd)"
ENTRYPOINT="$RUNNER_DIR/entrypoint.sh"
RUNNER_LIB="$RUNNER_DIR/lib/runner.mjs"
BUNDLE_FIXTURE="$REPO_ROOT/deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json"

DIRECTIVE_MARKER="OPEN CLARIFYING QUESTIONS"
FENCE_TOKEN='```clarifications'

NODE_BIN="${NODE_BIN:-node}"
FAILURES=0
WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/codex-ep-clarif-directive.XXXXXX")"
trap 'rm -rf "$WORKROOT"' EXIT INT TERM

pass() { printf 'ok   - %s\n' "$1"; }
fail() {
  printf 'FAIL - %s\n' "$1"
  FAILURES=$((FAILURES + 1))
}
assert_contains() { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 (missing: $3)" ;; esac; }
assert_absent() { case "$2" in *"$3"*) fail "$1 (unexpected: $3)" ;; *) pass "$1" ;; esac; }

run_stage() {
  _stage="$1"
  _w="$(mktemp -d "$WORKROOT/run.XXXXXX")"
  mkdir -p "$_w/input" "$_w/output" "$_w/logs" "$_w/repo"
  cp "$BUNDLE_FIXTURE" "$_w/input/context-bundle.v1.json"

  RUN_PROMPT="$_w/captured-prompt.txt"
  _mock="$_w/mock-codex.sh"
  {
    printf '#!/bin/sh\n'
    printf 'cat > "%s"\n' "$RUN_PROMPT"
    printf 'printf "mock-codex stage output\\n"\n'
  } >"$_mock"
  chmod +x "$_mock"

  set +e
  env \
    DELIVERYLINE_INPUT_DIR="$_w/input" \
    DELIVERYLINE_OUTPUT_DIR="$_w/output" \
    DELIVERYLINE_LOGS_DIR="$_w/logs" \
    DELIVERYLINE_REPO_DIR="$_w/repo" \
    DELIVERYLINE_RUNNER_LIB="$RUNNER_LIB" \
    DELIVERYLINE_RUNNER_STAGE="$_stage" \
    DELIVERYLINE_RUNNER_SKIP_AUTH="true" \
    NODE_BIN="$NODE_BIN" \
    CODEX_CLI_BIN="$_mock" \
    sh "$ENTRYPOINT" >"$_w/entrypoint.stdout" 2>"$_w/entrypoint.stderr"
  RUN_RC=$?
  set -e
}

printf '# entrypoint-clarifications-directive.test.sh (%s)\n' "$RUNNER_KIND"
[ -f "$BUNDLE_FIXTURE" ] || { printf 'FATAL: bundle fixture not found at %s\n' "$BUNDLE_FIXTURE"; exit 2; }

# --- SPEC stage: the directive IS present, teaching the exact fence convention ---
run_stage "spec-investigation"
[ "$RUN_RC" -eq 0 ] && pass "spec stage exits 0" || fail "spec stage exit ($RUN_RC)"
assert_contains "spec prompt carries the OPEN CLARIFYING QUESTIONS directive" \
  "$(cat "$RUN_PROMPT")" "$DIRECTIVE_MARKER"
assert_contains "spec prompt teaches the exact clarifications fence token" \
  "$(cat "$RUN_PROMPT")" "$FENCE_TOKEN"

# --- other stages: the directive is ABSENT (stage-gated, like the 3d-2 review directive) ---
run_stage "implementation-plan"
assert_absent "implementation-plan prompt has NO clarifications directive" \
  "$(cat "$RUN_PROMPT")" "$DIRECTIVE_MARKER"

run_stage "pr-output"
assert_absent "pr-output prompt has NO clarifications directive" \
  "$(cat "$RUN_PROMPT")" "$DIRECTIVE_MARKER"

run_stage "review"
assert_absent "review prompt has NO clarifications directive" \
  "$(cat "$RUN_PROMPT")" "$DIRECTIVE_MARKER"

printf '\n# %d failure(s)\n' "$FAILURES"
[ "$FAILURES" -eq 0 ]
