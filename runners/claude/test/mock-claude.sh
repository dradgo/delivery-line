#!/bin/sh
# Story 3.4 AC8 — deterministic MOCK of the Claude Code CLI for contract-
# conformance tests. Baked into the image only when the Dockerfile is built with
# --build-arg INSTALL_CLAUDE_CLI=false. Real Claude-API execution is story 3.8
# (profile-gated). This mock:
#   * answers `--version` (so --self-test passes) with the build-pinned version,
#   * otherwise prints fixed, deterministic output and exits 0,
#   * NEVER reads or echoes any environment variable OTHER THAN CLAUDE_CLI_VERSION,
#     DELIVERYLINE_RUNNER_STAGE, and (story 3f-4) DELIVERYLINE_SPLIT_PROPOSAL_REQUESTED
#     (all NON-SECRET) — so an injected secret value can never leak into
#     runner.stdout/.stderr (negative-log assertion).
set -eu

if [ "${1:-}" = "--version" ]; then
  echo "${CLAUDE_CLI_VERSION:-0.0.0-mock}"
  exit 0
fi

# Deterministic multi-line output so the implementation-plan variant has >=1 step.
echo "Claude mock: generated deterministic conformance output"
cat
echo "Investigate the ticket requirements"
echo "Produce the requested artifact"

# Story 3e-1 — at the SPEC (investigation) stage ONLY, emit one deterministic open
# clarification in the fenced ```clarifications block runner.mjs lifts into
# specArtifact.questions, so the broker ingest IT is deterministic and the
# runner-image-compat/conformance ITs stay green. Other stages stay byte-identical
# (no fence). Review 3e-1 (P2): resolve the stage from the SAME signal the entrypoint
# passes the real CLI — the --stage arg (precedence) — falling back to the NON-SECRET
# DELIVERYLINE_RUNNER_STAGE env the conformance ITs inject; absent => no fence (safe
# default). Only the --stage arg + that one env are read, so the negative-log
# secret-leak assertion stays intact.
RESOLVED_STAGE="${DELIVERYLINE_RUNNER_STAGE:-}"
expect_stage_value=""
for arg in "$@"; do
  if [ "$expect_stage_value" = "yes" ]; then
    RESOLVED_STAGE="$arg"
    expect_stage_value=""
    continue
  fi
  case "$arg" in
    --stage=*) RESOLVED_STAGE="${arg#*=}" ;;
    --stage) expect_stage_value="yes" ;;
  esac
done
# Story 3e-2 — at the SPEC stage also emit a deterministic clarificationAcknowledgements fence for
# the SAME fixed Q-MOCK-001 (addressed:true) that runner.mjs lifts into
# specArtifact.clarificationAcknowledgements. The full-loop incorporation IT drives addressed
# true/false via pre-built runner-result JSON fed to the broker (3e-1 precedent), so this fixed
# acknowledgement only needs to keep the runner-image/conformance + node tiers deterministic and
# schema-valid. Same --stage/env gating + secret-leak posture as the questions fence above.
case "$RESOLVED_STAGE" in
  spec | spec-investigation | investigation)
    printf '```clarifications\n'
    printf '[{"questionId":"Q-MOCK-001","questionText":"Mock clarification: confirm scope?"}]\n'
    printf '```\n'
    printf '```clarificationAcknowledgements\n'
    printf '[{"questionId":"Q-MOCK-001","addressed":true}]\n'
    printf '```\n'
    ;;
esac
# Story 3f-4 — at a SPLIT-mode REVIEW dispatch, emit a deterministic 2-subtask / 1-dependency
# proposal in the fenced ```split block runner.mjs lifts into split-proposal.v1, so the full-loop
# split IT is deterministic. Gated purely on the NON-SECRET DELIVERYLINE_SPLIT_PROPOSAL_REQUESTED
# marker the entrypoint exports from the bundle's splitProposalRequested flag (decoupled from stage
# resolution); a normal review dispatch never sets it and stays byte-identical (no fence). Same
# secret-leak posture as the clarifications fence above (only this non-secret marker is read).
if [ "${DELIVERYLINE_SPLIT_PROPOSAL_REQUESTED:-}" = "true" ]; then
  printf '```split\n'
  printf '{"schemaVersion":1,"subtasks":[{"ordinal":1,"title":"Mock subtask one","scope":"Mock split scope one"},{"ordinal":2,"title":"Mock subtask two","scope":"Mock split scope two"}],"dependencies":[{"fromOrdinal":2,"toOrdinal":1}]}\n'
  printf '```\n'
fi
exit 0
