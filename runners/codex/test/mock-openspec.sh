#!/bin/sh
# Story 3a-6 AC2 — deterministic MOCK of the OpenSpec CLI (`@fission-ai/openspec`,
# binary `openspec`) for the runner contract-conformance tier. Baked into the image
# only when the Dockerfile is built with --build-arg INSTALL_CODEX_CLI=false (the
# offline/mock build the conformance ITs + the CI runner-image-compat line use), so
# the entrypoint's --self-test openspec assertion (AC3) passes WITHOUT a network
# `npm install`. Real OpenSpec is installed in the production branch. This mock:
#   * answers `--version` (so --self-test passes) with the build-pinned version,
#   * otherwise prints one fixed, deterministic line and exits 0,
#   * NEVER reads or echoes any environment variable other than OPENSPEC_VERSION
#     (so an injected secret value can never leak — preserves the negative-log
#     assertion the conformance IT pins).
set -eu

if [ "${1:-}" = "--version" ]; then
  echo "${OPENSPEC_VERSION:-0.0.0-mock}"
  exit 0
fi

# Story 3a-8 — recognize the pr-output assembly subcommands so the offline conformance
# build (INSTALL_*_CLI=false) can exercise the flag-on pr-output path. Both are env-blind
# no-ops that exit 0: `init` (the entrypoint lays the pre-baked skeleton itself, so this is
# a no-op) and `validate <change-id>` (a structural guard the entrypoint runs best-effort).
case "${1:-}" in
  init)
    echo "openspec mock: init (no-op; entrypoint scaffolds the skeleton)"
    exit 0
    ;;
  validate)
    echo "openspec mock: validate ${2:-} OK"
    exit 0
    ;;
esac

# Deterministic, env-blind output for any other invocation.
echo "openspec mock: deterministic conformance output"
exit 0
