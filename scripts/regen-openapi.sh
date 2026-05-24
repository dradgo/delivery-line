#!/usr/bin/env bash
# scripts/regen-openapi.sh — combined OpenAPI snapshot + frontend client regen.
#
# The committed backend snapshot (deliveryline-backend/src/main/resources/openapi/openapi.json)
# is a tripwire: OpenApiSnapshotContractTest fails CI whenever the live /v3/api-docs drifts.
# The frontend TypeScript client (deliveryline-frontend/src/lib/api/schema.d.ts) is generated
# from that snapshot. A backend REST change therefore requires BOTH files to be regenerated and
# committed together — historically a two-step ritual that was easy to half-do.
#
# This script does both in one shot. It does NOT auto-commit; review the diff first.
#
# Requirements:
#   - Docker (Testcontainers spins up Postgres for the backend Spring context).
#   - Node + npm (for the frontend regen).
#   - Run from any CWD; the script anchors itself to the repo root.
#
# Windows: run inside WSL2 (the project's standard Linux-CI repro environment) or use
# scripts/regen-openapi.ps1 with Docker Desktop.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

BACKEND_SNAPSHOT="deliveryline-backend/src/main/resources/openapi/openapi.json"
FRONTEND_CLIENT="deliveryline-frontend/src/lib/api/schema.d.ts"

echo "==> Step 1/2 — regenerating backend OpenAPI snapshot via OpenApiSnapshotContractTest"
echo "    (the test FAILS by design when -Dopenapi.snapshot.write=true is set; that is expected)"
# Scope to OpenApiSnapshotContractTest only — full `verify` would also run JaCoCo gate,
# ArchUnit, and the rest of the failsafe tier, which we don't need for regen.
# Skip the frontend Maven module entirely (-Dfrontend-maven-plugin.skip=true): `-am` pulls
# deliveryline-frontend into the reactor because backend depends on its bundle, and that
# would otherwise spend ~4 min on Node install + npm ci + lint + Prettier check + Vite
# build before even getting to the backend test. The frontend regen step below runs
# `npm run generate-api` directly, which is the only frontend work we actually need.
set +e
"${REPO_ROOT}/mvnw" -B -ntp -pl deliveryline-backend -am \
  -Dfrontend-maven-plugin.skip=true \
  -Dit.test=OpenApiSnapshotContractTest \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dopenapi.snapshot.write=true \
  test-compile failsafe:integration-test
MVN_EXIT=$?
set -e

# The intentional "snapshot (re)generated, review + re-run" failure exits non-zero.
# A genuine compile/Spring-context failure ALSO exits non-zero, so we cannot trust
# $MVN_EXIT alone. Use git to disambiguate: if the snapshot file is modified, the
# write happened and the failure was the by-design one.
if ! git diff --quiet -- "${BACKEND_SNAPSHOT}" 2>/dev/null \
   || [ -n "$(git status --porcelain -- "${BACKEND_SNAPSHOT}" 2>/dev/null)" ]; then
  echo "    backend snapshot updated."
elif [ "${MVN_EXIT}" -eq 0 ]; then
  echo "    backend snapshot already in sync — nothing to write."
else
  echo "ERROR: mvn exited ${MVN_EXIT} and the snapshot file is unchanged." >&2
  echo "       This is a real test failure (context load, compile error, etc.)," >&2
  echo "       not the by-design regen-and-fail. Scroll up for the root cause." >&2
  exit "${MVN_EXIT}"
fi

echo
echo "==> Step 2/2 — regenerating frontend TypeScript client (openapi-typescript)"
( cd "${REPO_ROOT}/deliveryline-frontend" && npm run --silent generate-api )

echo
echo "==> Done. Review and commit:"
git --no-pager diff --stat -- "${BACKEND_SNAPSHOT}" "${FRONTEND_CLIENT}"
echo
echo "    git add ${BACKEND_SNAPSHOT} ${FRONTEND_CLIENT}"
echo "    git commit -m \"chore: regenerate OpenAPI snapshot + frontend client\""
