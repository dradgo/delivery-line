#!/usr/bin/env bash
# scripts/reset-local.sh — Wipe local DeliveryLine state and reset the Flyway schema.
# Story 1.17. Stops the unified docker compose stack, removes the named Postgres volume,
# and clears the artifact tree under ${DELIVERYLINE_HOME}.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

DELIVERYLINE_HOME_RESOLVED="${DELIVERYLINE_HOME:-${REPO_ROOT}/deliveryline-data}"
# Require absolute path so a relative DELIVERYLINE_HOME cannot resolve to cwd surprises.
case "${DELIVERYLINE_HOME_RESOLVED}" in
  /*) ;;
  *)
    echo "Refusing to clear DELIVERYLINE_HOME at ${DELIVERYLINE_HOME_RESOLVED} (must be an absolute path)" >&2
    exit 1
    ;;
esac
# Strip all trailing slashes for a canonical comparison.
CANONICAL_HOME="${DELIVERYLINE_HOME_RESOLVED}"
while [ "${CANONICAL_HOME}" != "/" ] && [ "${CANONICAL_HOME%/}" != "${CANONICAL_HOME}" ]; do
  CANONICAL_HOME="${CANONICAL_HOME%/}"
done
# Denylist values that would recursively delete user/system trees.
case "${CANONICAL_HOME}" in
  /|/usr|/var|/etc|/tmp|/bin|/sbin|/lib|/opt|/home|/root|"${HOME}"|"${REPO_ROOT}")
    echo "Refusing to clear DELIVERYLINE_HOME at ${CANONICAL_HOME}" >&2
    exit 1
    ;;
esac
ARTIFACTS_DIR="${CANONICAL_HOME}/artifacts"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker not found on PATH — cannot stop compose stack. Aborting reset." >&2
  exit 1
fi
echo "Stopping docker compose stack and removing named volumes..."
docker compose down -v || echo "docker compose down -v returned non-zero (continuing)"

if [ -d "${ARTIFACTS_DIR}" ]; then
  echo "Removing artifact directory at ${ARTIFACTS_DIR}"
  rm -rf -- "${ARTIFACTS_DIR}"
else
  echo "Artifact directory at ${ARTIFACTS_DIR} does not exist (nothing to remove)"
fi

echo "Reset complete. Run scripts/start-all.sh followed by scripts/doctor.sh to bring the stack back up."
