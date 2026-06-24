#!/bin/sh
# Story 3.3 AC4 — deterministic MOCK of the Codex CLI for contract-conformance
# tests. Baked into the image only when the Dockerfile is built with
# --build-arg INSTALL_CODEX_CLI=false. Real Codex-API execution is story 3.8
# (profile-gated). This mock:
#   * answers `--version` (so --self-test passes) with the build-pinned version,
#   * otherwise prints fixed, deterministic output and exits 0,
#   * NEVER reads or echoes any environment variable OTHER THAN CODEX_CLI_VERSION
#     and DELIVERYLINE_RUNNER_STAGE (both NON-SECRET) — so an injected secret
#     value can never leak into runner.stdout/.stderr (negative-log assertion).
set -eu

if [ "${1:-}" = "--version" ]; then
  echo "${CODEX_CLI_VERSION:-0.0.0-mock}"
  exit 0
fi

# Deterministic multi-line output so the implementation-plan variant has >=1 step.
echo "Codex mock: generated deterministic conformance output"
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
case "$RESOLVED_STAGE" in
  spec | spec-investigation | investigation)
    printf '```clarifications\n'
    printf '[{"questionId":"Q-MOCK-001","questionText":"Mock clarification: confirm scope?"}]\n'
    printf '```\n'
    ;;
esac
exit 0
