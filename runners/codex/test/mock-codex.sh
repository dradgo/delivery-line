#!/bin/sh
# Story 3.3 AC4 — deterministic MOCK of the Codex CLI for contract-conformance
# tests. Baked into the image only when the Dockerfile is built with
# --build-arg INSTALL_CODEX_CLI=false. Real Codex-API execution is story 3.8
# (profile-gated). This mock:
#   * answers `--version` (so --self-test passes) with the build-pinned version,
#   * otherwise prints fixed, deterministic output and exits 0,
#   * NEVER reads or echoes environment variables (so an injected secret value
#     can never leak into runner.stdout/.stderr — negative-log assertion).
set -eu

if [ "${1:-}" = "--version" ]; then
  echo "${CODEX_CLI_VERSION:-0.0.0-mock}"
  exit 0
fi

# Deterministic multi-line output so the implementation-plan variant has >=1 step.
echo "Codex mock: generated deterministic conformance output"
echo "Investigate the ticket requirements"
echo "Produce the requested artifact"
exit 0
