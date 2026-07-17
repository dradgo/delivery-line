#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# scripts/ci/configure-branch-protection.ps1
#
# Story 1.23 — Windows PowerShell counterpart of configure-branch-protection.sh.
# Applies branch-protection configuration to the default branch (`main`) so the
# `foundation-gate` aggregator is a required status check. Idempotent.

# REQUIRED_CHECKS_START
$REQUIRED_CHECKS = @(
    'foundation-gate'
    'runner-contract-real-gate'
    'recovery-integration-gate'
    'format-static-checks (ubuntu-latest)'
    'format-static-checks (windows-latest)'
    'frontend-build-tests (ubuntu-latest)'
    'frontend-build-tests (windows-latest)'
    'backend-unit-tests (ubuntu-latest)'
    'backend-unit-tests (windows-latest)'
    'doctor-smoke (ubuntu-latest)'
)
# REQUIRED_CHECKS_END

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Error 'gh CLI not found on PATH - install GitHub CLI before running this script.'
    exit 2
}

if (-not $env:OWNER) {
    $env:OWNER = (gh repo view --json owner -q '.owner.login').Trim()
}
if (-not $env:REPO) {
    $env:REPO = (gh repo view --json name -q '.name').Trim()
}
if (-not $env:OWNER -or -not $env:REPO) {
    Write-Error 'Could not resolve OWNER/REPO - set them explicitly or run inside a repo cloned via gh.'
    exit 2
}

Write-Host "Configuring branch protection for $($env:OWNER)/$($env:REPO) (branch: main)..."

$ghArgs = @(
    'api',
    '--method', 'PUT',
    '-H', 'Accept: application/vnd.github+json',
    '-H', 'X-GitHub-Api-Version: 2022-11-28',
    "/repos/$($env:OWNER)/$($env:REPO)/branches/main/protection",
    '-F', 'required_status_checks[strict]=true'
)
foreach ($ctx in $REQUIRED_CHECKS) {
    $ghArgs += '-F'
    $ghArgs += "required_status_checks[contexts][]=$ctx"
}
$ghArgs += @(
    '-F', 'enforce_admins=true',
    '-F', 'required_pull_request_reviews[required_approving_review_count]=1',
    '-F', 'restrictions=null'
)

& gh @ghArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "gh api call failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host 'Branch protection updated. foundation-gate is now a required status check on main.'
