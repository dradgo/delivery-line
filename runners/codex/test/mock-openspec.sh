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

# Deterministic, env-blind output for any other invocation.
echo "openspec mock: deterministic conformance output"
exit 0
