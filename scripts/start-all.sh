#!/usr/bin/env bash
# scripts/start-all.sh — Bring up the unified docker compose stack (incl. observability profile).
# Story 1.17. This is the "give me the full local stack" wrapper: Postgres PLUS the story-3.7 ELK
# observability services (Elasticsearch + Logstash + Kibana), which are tagged with the
# `observability` profile. `docker compose --profile observability up -d` starts the default
# services AND every observability-tagged service. (No `stop-all` counterpart exists today; use
# `docker compose down` to tear the stack down.)
#
# Linux hosts: Elasticsearch requires `vm.max_map_count=262144` — see docs/setup-local.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

echo "Starting docker compose stack (--profile observability up -d) from ${REPO_ROOT}..."
exec docker compose --profile observability up -d
