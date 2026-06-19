#!/usr/bin/env bash
# scripts/ci/configure-branch-protection.sh
#
# Story 1.23 — apply branch-protection configuration to the default branch
# (`main`) so the `foundation-gate` aggregator is a required status check.
# Idempotent: uses `gh api -X PUT` (full replace), not PATCH, so re-running
# produces no diff against the desired state recorded below.
#
# The REQUIRED_CHECKS_START / REQUIRED_CHECKS_END marker block is parsed by
# the backend test `BranchProtectionConfigSmokeTest` to assert that
# `foundation-gate` cannot be silently removed from the required-checks list.
# Do not rename the markers or split the array across lines.
#
# Usage:
#   OWNER=<owner> REPO=<repo> ./scripts/ci/configure-branch-protection.sh
#
# If OWNER/REPO are unset, the script defers to `gh repo view --json owner,name`
# to discover the current repository.

set -euo pipefail

# REQUIRED_CHECKS_START
REQUIRED_CHECKS=(
  "foundation-gate"
  "runner-contract-real-gate"
  "format-static-checks (ubuntu-latest)"
  "format-static-checks (windows-latest)"
  "frontend-build-tests (ubuntu-latest)"
  "frontend-build-tests (windows-latest)"
  "backend-unit-tests (ubuntu-latest)"
  "backend-unit-tests (windows-latest)"
  "doctor-smoke (ubuntu-latest)"
)
# REQUIRED_CHECKS_END

if ! command -v gh >/dev/null 2>&1; then
  echo "::error::gh CLI not found on PATH — install GitHub CLI before running this script."
  exit 2
fi

if [ -z "${OWNER:-}" ] || [ -z "${REPO:-}" ]; then
  # Discover from the current repo (CWD must be inside the target git tree).
  OWNER="${OWNER:-$(gh repo view --json owner -q '.owner.login')}"
  REPO="${REPO:-$(gh repo view --json name -q '.name')}"
fi

if [ -z "${OWNER}" ] || [ -z "${REPO}" ]; then
  echo "::error::Could not resolve OWNER/REPO — set them explicitly or run inside a repo cloned via gh."
  exit 2
fi

echo "Configuring branch protection for ${OWNER}/${REPO} (branch: main)..."

# Build the gh-api -F arguments dynamically from REQUIRED_CHECKS.
CONTEXTS_ARGS=()
for ctx in "${REQUIRED_CHECKS[@]}"; do
  CONTEXTS_ARGS+=(-F "required_status_checks[contexts][]=${ctx}")
done

gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "/repos/${OWNER}/${REPO}/branches/main/protection" \
  -F required_status_checks[strict]=true \
  "${CONTEXTS_ARGS[@]}" \
  -F enforce_admins=true \
  -F 'required_pull_request_reviews[required_approving_review_count]=1' \
  -F restrictions=null

echo "Branch protection updated. foundation-gate is now a required status check on main."
