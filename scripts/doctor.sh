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

# Command path is `deliveryline doctor` — the @CommandGroup prefix on
# DoctorCommands prepends "deliveryline" to every command in the group.
run_args=("deliveryline" "doctor")
for arg in "$@"; do
  run_args+=("${arg//,/\\,}")
done
printf -v RUN_ARGS '%s,' "${run_args[@]}"
RUN_ARGS="${RUN_ARGS%,}"

# Step 1 — install upstream siblings (notably deliveryline-runner-contracts)
# into the local Maven repo. `install` (not `compile`) is required because
# step 2 starts a fresh Maven process that resolves sibling SNAPSHOT deps
# from ~/.m2 — `target/classes/` from a prior invocation isn't visible
# across process boundaries. CI hits this every run because ~/.m2 starts
# empty; local devs typically already have runner-contracts installed.
"${REPO_ROOT}/mvnw" -B -ntp -pl deliveryline-backend -am install -DskipTests

# Step 2 — run the backend only. No `-am`, so spring-boot:run is invoked
# strictly on deliveryline-backend, which is the only module with a main
# class. Upstream artifacts are now resolvable from ~/.m2 after step 1.
exec "${REPO_ROOT}/mvnw" -B -ntp -pl deliveryline-backend spring-boot:run \
  "-Ddeliveryline.shell=${DETECTED_SHELL}" \
  "-Dspring-boot.run.arguments=${RUN_ARGS}"
