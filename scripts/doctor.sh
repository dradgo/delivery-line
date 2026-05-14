#!/usr/bin/env bash
# scripts/doctor.sh — DeliveryLine doctor entry point (bash/zsh).
# Story 1.17. Runs `deliveryline doctor` against the local checkout.
# In Epic 1 this uses `mvnw spring-boot:run`; story 1.21 will switch to the packaged jar.
# Reusable orchestration MUST stay in deliveryline-backend; this script is a thin entry point.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

if [ ! -x "${REPO_ROOT}/mvnw" ]; then
  echo "mvnw not found or not executable at ${REPO_ROOT}/mvnw" >&2
  exit 1
fi

DETECTED_SHELL="${SHELL##*/}"
if [ -z "${DETECTED_SHELL}" ]; then
  # Don't claim "bash" when $SHELL was unset (cron, systemd, launchd, fresh CI).
  # Surface "unknown" so the adapter's detection path reports honestly.
  DETECTED_SHELL="unknown"
fi

run_args=("doctor")
for arg in "$@"; do
  run_args+=("${arg//,/\\,}")
done
printf -v RUN_ARGS '%s,' "${run_args[@]}"
RUN_ARGS="${RUN_ARGS%,}"

exec "${REPO_ROOT}/mvnw" -pl deliveryline-backend spring-boot:run \
  "-Ddeliveryline.shell=${DETECTED_SHELL}" \
  "-Dspring-boot.run.arguments=${RUN_ARGS}"
