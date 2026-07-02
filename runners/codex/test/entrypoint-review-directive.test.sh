#!/bin/sh
# Spec/plan-phase advisory review (story 3e-3 follow-up) — entrypoint unit tier for the REVIEW-stage
# prompt selection (codex). Twin of runners/claude/test/entrypoint-review-directive.test.sh.
#
# The advisory reviewer reused ONE implementation-shaped prompt ("review the produced output for
# correctness and completeness against the approved specification") for EVERY reviewed artifact.
# That is only correct for the actual implementation (prOutput). For the two read-only DESIGN
# DOCUMENTS reviewed before human approval — the spec (spec-approval gate) and the implementationPlan
# (plan-approval gate) — there is no implementation, patch, or repo checkout yet, so the model hunted
# for pom.xml / a patch / a git repo, found none, and returned VERDICT: fail (a false negative).
#
# This test pins the fix: the review prompt is selected by DL_REVIEWED_ARTIFACT_TYPE —
#   spec               -> ADVISORY SPECIFICATION REVIEW      (review the spec itself)
#   implementationPlan -> ADVISORY IMPLEMENTATION-PLAN REVIEW (review the plan vs the approved spec)
#   prOutput (else)    -> ADVISORY REVIEW                     (review the implementation vs the spec)
# and neither design-document review may demand an implementation.
#
# Runs the REAL entrypoint.sh with NO container + a mock Codex CLI that captures its stdin.
# POSIX sh, dependency-free besides `node`.
# Run locally: `sh runners/codex/test/entrypoint-review-directive.test.sh`
set -eu

RUNNER_KIND="codex"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENTRYPOINT="$RUNNER_DIR/entrypoint.sh"
RUNNER_LIB="$RUNNER_DIR/lib/runner.mjs"

MARKER_SPEC="OPERATING MODE: ADVISORY SPECIFICATION REVIEW"
MARKER_PLAN="OPERATING MODE: ADVISORY IMPLEMENTATION-PLAN REVIEW"
MARKER_IMPL="OPERATING MODE: ADVISORY REVIEW (read-only)"

NODE_BIN="${NODE_BIN:-node}"
FAILURES=0
WORKROOT="$(mktemp -d "${TMPDIR:-/tmp}/codex-ep-review-directive.XXXXXX")"
trap 'rm -rf "$WORKROOT"' EXIT INT TERM

pass() { printf 'ok   - %s\n' "$1"; }
fail() {
  printf 'FAIL - %s\n' "$1"
  FAILURES=$((FAILURES + 1))
}
assert_contains() { case "$2" in *"$3"*) pass "$1" ;; *) fail "$1 (missing: $3)" ;; esac; }
assert_absent() { case "$2" in *"$3"*) fail "$1 (unexpected: $3)" ;; *) pass "$1" ;; esac; }

# $1 = reviewed artifactType, $2 = approvedSpecificationReference JSON literal (null or object)
write_review_bundle() {
  cat <<JSON
{
  "schemaVersion": 1,
  "workflowRunId": "run_reviewdirective01",
  "runnerExecutionId": "rex_reviewdirective01",
  "ticketSummary": { "ticketRef": "DL-1", "title": "T", "summary": "S" },
  "approvedSpecificationReference": $2,
  "priorFeedbackReferences": [],
  "artifactReferences": [
    {
      "artifactId": "art_reviewed01",
      "artifactType": "$1",
      "artifactStatus": "available",
      "referenceAvailable": true,
      "referencePath": "artifact/v1.json",
      "unavailableReason": null
    }
  ],
  "executionConstraints": { "timeoutSeconds": 1800, "allowRawOutput": false },
  "classification": "shareable-redacted"
}
JSON
}

run_review() {
  _bundle_json="$1"
  _w="$(mktemp -d "$WORKROOT/run.XXXXXX")"
  mkdir -p "$_w/input" "$_w/output" "$_w/logs" "$_w/repo"
  printf '%s\n' "$_bundle_json" >"$_w/input/context-bundle.v1.json"

  RUN_PROMPT="$_w/captured-prompt.txt"
  _mock="$_w/mock-codex.sh"
  {
    printf '#!/bin/sh\n'
    printf 'cat > "%s"\n' "$RUN_PROMPT"
    printf 'printf "mock-codex review output\\nVERDICT: pass\\n"\n'
  } >"$_mock"
  chmod +x "$_mock"

  set +e
  env \
    DELIVERYLINE_INPUT_DIR="$_w/input" \
    DELIVERYLINE_OUTPUT_DIR="$_w/output" \
    DELIVERYLINE_LOGS_DIR="$_w/logs" \
    DELIVERYLINE_REPO_DIR="$_w/repo" \
    DELIVERYLINE_RUNNER_LIB="$RUNNER_LIB" \
    DELIVERYLINE_RUNNER_STAGE="review" \
    DELIVERYLINE_RUNNER_SKIP_AUTH="true" \
    NODE_BIN="$NODE_BIN" \
    CODEX_CLI_BIN="$_mock" \
    sh "$ENTRYPOINT" >"$_w/entrypoint.stdout" 2>"$_w/entrypoint.stderr"
  RUN_RC=$?
  set -e
}

APPROVED_SPEC='{ "artifactId": "art_spec01", "artifactType": "spec", "artifactStatus": "available", "referenceAvailable": true, "referencePath": "spec/v2.json", "unavailableReason": null }'

printf '# entrypoint-review-directive.test.sh (%s)\n' "$RUNNER_KIND"

# --- SPEC review: review the SPECIFICATION itself; no implementation expected ---
run_review "$(write_review_bundle spec null)"
_p="$(cat "$RUN_PROMPT")"
[ "$RUN_RC" -eq 0 ] && pass "spec review exits 0" || fail "spec review exit ($RUN_RC)"
assert_contains "spec review uses the specification-review directive" "$_p" "$MARKER_SPEC"
assert_absent "spec review is NOT the plan-review directive" "$_p" "$MARKER_PLAN"
assert_absent "spec review is NOT the implementation-review directive" "$_p" "$MARKER_IMPL"

# --- IMPLEMENTATION-PLAN review: review the PLAN vs the approved spec; no implementation expected ---
run_review "$(write_review_bundle implementationPlan "$APPROVED_SPEC")"
_p="$(cat "$RUN_PROMPT")"
[ "$RUN_RC" -eq 0 ] && pass "plan review exits 0" || fail "plan review exit ($RUN_RC)"
assert_contains "plan review uses the implementation-plan-review directive" "$_p" "$MARKER_PLAN"
assert_absent "plan review is NOT the specification-review directive" "$_p" "$MARKER_SPEC"
assert_absent "plan review is NOT the implementation-review directive" "$_p" "$MARKER_IMPL"

# --- EXECUTION review: the reviewed artifact is a prOutput; legacy impl-review prompt is kept ---
run_review "$(write_review_bundle prOutput "$APPROVED_SPEC")"
_p="$(cat "$RUN_PROMPT")"
[ "$RUN_RC" -eq 0 ] && pass "execution review exits 0" || fail "execution review exit ($RUN_RC)"
assert_contains "execution review keeps the implementation-review directive" "$_p" "$MARKER_IMPL"
assert_absent "execution review is NOT the specification-review directive" "$_p" "$MARKER_SPEC"
assert_absent "execution review is NOT the plan-review directive" "$_p" "$MARKER_PLAN"

printf '\n# %d failure(s)\n' "$FAILURES"
[ "$FAILURES" -eq 0 ]
