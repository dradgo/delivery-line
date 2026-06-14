#!/bin/sh
# Story 3a-8 — entrypoint unit tier for the opt-in OpenSpec authoring layer (codex).
#
# Runs the REAL entrypoint.sh with NO container, using the documented
# DELIVERYLINE_*_DIR overrides + a mock Codex CLI (captures its stdin so we can
# assert prompt augmentation) + the offline mock-openspec. Proves:
#   * flag OFF  -> byte-identical legacy path: no openspec/ folder, no prompt
#                  delta, no "openspec" log lines on the entrypoint's stderr,
#   * flag ON spec-investigation -> PROMPT_INSTRUCTION augmented with the fence
#                  convention, deterministic change-id logged, repo UNTOUCHED
#                  (read-only stage emits to stdout only — T-READONLY-NO-REPO-WRITE),
#   * flag ON pr-output -> openspec/changes/<id>/ assembled into the repo + the
#                  (mock) openspec validate invoked, same deterministic change-id.
#
# POSIX sh, dependency-free besides `node` (for the real runner.mjs prepare/build).
# Run locally: `sh runners/codex/test/entrypoint-openspec.test.sh`
# (Linux / WSL2 / Git Bash). Mirrors runners/claude/test/entrypoint-openspec.test.sh.
set -eu

RUNNER_KIND="codex"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNNER_DIR/../.." && pwd)"
ENTRYPOINT="$RUNNER_DIR/entrypoint.sh"
RUNNER_LIB="$RUNNER_DIR/lib/runner.mjs"
MOCK_OPENSPEC="$SCRIPT_DIR/mock-openspec.sh"
BUNDLE_FIXTURE="$REPO_ROOT/deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.valid.json"
# The fixture carries ticketRef=DL-101 + workflowRunId=run_abcd1234, so the derived
# change-id is deterministic (slug(DL-101) + short slug(run_abcd1234)).
EXPECTED_CHANGE_ID="dl-101-run_abcd1234"

NODE_BIN="${NODE_BIN:-node}"
FAILURES=0
# NOTE: the work-dir name MUST NOT contain "openspec" — the entrypoint logs dir paths,
# and the flag-off byte-identity assertion greps stderr for that substring.
WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/codex-ep-spec-authoring.XXXXXX")"
trap 'rm -rf "$WORKROOT"' EXIT INT TERM

pass() { printf 'ok   - %s\n' "$1"; }
fail() {
  printf 'FAIL - %s\n' "$1"
  FAILURES=$((FAILURES + 1))
}
assert_contains() { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 (missing: $3)" ;; esac; }
assert_absent() { case "$2" in *"$3"*) fail "$1 (unexpected: $3)" ;; *) pass "$1" ;; esac; }
assert_exists() { if [ -e "$2" ]; then pass "$1"; else fail "$1 (no such path: $2)"; fi; }
assert_missing() { if [ -e "$2" ]; then fail "$1 (path exists: $2)"; else pass "$1"; fi; }

# Runs the entrypoint once for $stage with the OpenSpec flag $openspec (true|""),
# returns via globals: RUN_RC, RUN_STDERR (entrypoint diagnostics), RUN_REPO,
# RUN_PROMPT (the stdin the mock CLI received).
run_stage() {
  _stage="$1"
  _openspec="$2"
  _w="$(mktemp -d "$WORKROOT/run.XXXXXX")"
  mkdir -p "$_w/input" "$_w/output" "$_w/logs" "$_w/repo"
  cp "$BUNDLE_FIXTURE" "$_w/input/context-bundle.v1.json"

  # Mock Codex CLI: capture the prompt (stdin) for assertions, emit canned stdout
  # so runner.mjs `build` produces a schema-valid result.
  RUN_PROMPT="$_w/captured-prompt.txt"
  _mock="$_w/mock-codex.sh"
  {
    printf '#!/bin/sh\n'
    printf 'cat > "%s"\n' "$RUN_PROMPT"
    printf 'printf "mock-codex stage output\\n"\n'
  } >"$_mock"
  chmod +x "$_mock" "$MOCK_OPENSPEC"

  RUN_REPO="$_w/repo"
  RUN_RESULT="$_w/output/runner-result.v1.json"
  _stderr="$_w/entrypoint.stderr"
  set +e
  env \
    DELIVERYLINE_INPUT_DIR="$_w/input" \
    DELIVERYLINE_OUTPUT_DIR="$_w/output" \
    DELIVERYLINE_LOGS_DIR="$_w/logs" \
    DELIVERYLINE_REPO_DIR="$RUN_REPO" \
    DELIVERYLINE_RUNNER_LIB="$RUNNER_LIB" \
    DELIVERYLINE_RUNNER_STAGE="$_stage" \
    DELIVERYLINE_RUNNER_SKIP_AUTH="true" \
    DELIVERYLINE_RUNNER_OPENSPEC="$_openspec" \
    NODE_BIN="$NODE_BIN" \
    CODEX_CLI_BIN="$_mock" \
    OPENSPEC_CLI_BIN="$MOCK_OPENSPEC" \
    sh "$ENTRYPOINT" >"$_w/entrypoint.stdout" 2>"$_stderr"
  RUN_RC=$?
  set -e
  RUN_STDERR="$(cat "$_stderr")"
}

printf '# entrypoint-openspec.test.sh (%s)\n' "$RUNNER_KIND"
[ -f "$BUNDLE_FIXTURE" ] || { printf 'FATAL: bundle fixture not found at %s\n' "$BUNDLE_FIXTURE"; exit 2; }

# --- flag OFF: byte-identical legacy path (pr-output, repo mounted) ---
run_stage "pr-output" ""
[ "$RUN_RC" -eq 0 ] && pass "flag-off pr-output exits 0" || fail "flag-off pr-output exit ($RUN_RC)"
assert_missing "flag-off authors NO openspec/ folder" "$RUN_REPO/openspec"
assert_absent "flag-off emits no openspec log lines" "$RUN_STDERR" "openspec"

# --- flag ON: read-only spec stage augments the prompt, never writes the repo ---
run_stage "spec-investigation" "true"
[ "$RUN_RC" -eq 0 ] && pass "flag-on spec exits 0" || fail "flag-on spec exit ($RUN_RC)"
assert_contains "flag-on spec logs the authoring layer + deterministic change-id" \
  "$RUN_STDERR" "openspec enabled changeId=$EXPECTED_CHANGE_ID"
assert_contains "flag-on spec augments PROMPT_INSTRUCTION with the fence convention" \
  "$(cat "$RUN_PROMPT")" "=== FILE: proposal.md ==="
assert_contains "flag-on spec prompt names the OpenSpec authoring directive" \
  "$(cat "$RUN_PROMPT")" "OPENSPEC AUTHORING"
assert_missing "read-only spec stage leaves the repo untouched" "$RUN_REPO/openspec"

# --- flag ON: pr-output assembles the change folder + invokes validate ---
run_stage "pr-output" "true"
[ "$RUN_RC" -eq 0 ] && pass "flag-on pr-output exits 0" || fail "flag-on pr-output exit ($RUN_RC)"
assert_contains "flag-on pr-output uses the SAME deterministic change-id" \
  "$RUN_STDERR" "changeId=$EXPECTED_CHANGE_ID"
assert_exists "pr-output lays down the openspec skeleton" "$RUN_REPO/openspec/AGENTS.md"
assert_exists "pr-output assembles the change folder" "$RUN_REPO/openspec/changes/$EXPECTED_CHANGE_ID"
assert_contains "pr-output invokes (mock) openspec validate" \
  "$RUN_STDERR" "openspec validate ok"
assert_contains "pr-output surfaces the validate outcome in the result summary (AC5/D4)" \
  "$(cat "$RUN_RESULT")" "[openspec: validate ok]"

printf '\n# %d failure(s)\n' "$FAILURES"
[ "$FAILURES" -eq 0 ]
