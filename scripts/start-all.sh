#!/usr/bin/env bash
# scripts/start-all.sh — Bring up the unified docker compose stack (incl. observability profile).
# Story 1.17. Epic 1 ships only Postgres in docker-compose.yml (AR24); the observability profile
# services land in Epic 3. `docker compose --profile observability up -d` only starts services
# tagged with that profile, so an empty profile is a no-op — not an error.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

echo "Starting docker compose stack (--profile observability up -d) from ${REPO_ROOT}..."
exec docker compose --profile observability up -d
