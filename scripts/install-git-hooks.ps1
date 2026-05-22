#Requires -Version 5.1
# scripts/install-git-hooks.ps1 — DeliveryLine optional git-hook installer (story 2.30).
#
# Installs an OPT-IN pre-commit hook that runs a fast backend format + style
# check (Spotless + Checkstyle) before each commit. SpotBugs is deliberately
# excluded — its effort=Max analysis is too slow for a commit hook.
#
# This is recommended but NOT required: nothing installs or runs the hook for
# you. CI's format-static-checks tier is the real gate. See docs/setup-local.md.
#
# Usage:
#   scripts/install-git-hooks.ps1              install (or refresh) the pre-commit hook
#   scripts/install-git-hooks.ps1 -Uninstall  remove the DeliveryLine pre-commit hook
#   scripts/install-git-hooks.ps1 -Force      replace a non-DeliveryLine pre-commit
#                                             hook (the existing one is backed up first)
#
# Bypass the hook for a single commit with:  git commit --no-verify

[CmdletBinding()]
param(
    [switch]$Uninstall,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = git -C (Join-Path $PSScriptRoot "..") rev-parse --show-toplevel 2>$null
if (-not $RepoRoot) {
    Write-Error "Could not resolve the git worktree root from $PSScriptRoot\.."
    exit 1
}
$RepoRoot = $RepoRoot.Trim()

$HooksDir = git -C $RepoRoot rev-parse --path-format=absolute --git-path hooks 2>$null
if (-not $HooksDir) {
    Write-Error "Could not resolve the active Git hooks directory via 'git rev-parse --git-path hooks'."
    exit 1
}
$HooksDir = $HooksDir.Trim()
$PreCommit = Join-Path $HooksDir "pre-commit"
$BackupPreCommit = "$PreCommit.backup"
$Marker = "# DeliveryLine-managed pre-commit hook (story 2.30)"

if (-not (Test-Path $HooksDir)) {
    New-Item -ItemType Directory -Path $HooksDir | Out-Null
}

if ($Uninstall) {
    if ((Test-Path $PreCommit) -and ((Get-Content -Raw $PreCommit) -match [regex]::Escape($Marker))) {
        Remove-Item $PreCommit -Force
        Write-Host "Removed the DeliveryLine pre-commit hook."
    } else {
        Write-Host "No DeliveryLine pre-commit hook installed — nothing to do."
    }
    exit 0
}

# Non-destructive: never clobber a hook we did not write without -Force.
if ((Test-Path $PreCommit) -and -not ((Get-Content -Raw $PreCommit) -match [regex]::Escape($Marker))) {
    if ($Force) {
        Copy-Item $PreCommit $BackupPreCommit -Force
        Write-Host "Existing pre-commit hook backed up to $BackupPreCommit"
    } else {
        Write-Error "A non-DeliveryLine pre-commit hook already exists at $PreCommit. Re-run with -Force to back it up and replace it."
        exit 1
    }
}

# The hook body is a bash script — Git for Windows runs hooks with its bundled
# sh, so the same script works on Windows, macOS and Linux.
$HookBody = @'
#!/usr/bin/env bash
# DeliveryLine-managed pre-commit hook (story 2.30)
# Installed by scripts/install-git-hooks.ps1 — do not edit by hand; re-run the
# installer to refresh it, or scripts/install-git-hooks.ps1 -Uninstall to remove.
# Runs a fast backend format + style check before each commit.
# Bypass once:  git commit --no-verify
set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "${REPO_ROOT}"
echo "[pre-commit] Spotless + Checkstyle (deliveryline-backend)…"
MVNW="./mvnw"
if [ ! -x "${MVNW}" ]; then MVNW="sh ./mvnw"; fi
if ! ${MVNW} -B -ntp -q -pl deliveryline-backend -am spotless:check checkstyle:check; then
  echo "[pre-commit] FAILED — run './mvnw spotless:apply' to auto-fix formatting," >&2
  echo "[pre-commit]          then address any remaining Checkstyle violations and re-stage." >&2
  echo "[pre-commit] To commit without this check: git commit --no-verify" >&2
  exit 1
fi
echo "[pre-commit] OK"
'@

# Write the hook with LF line endings and no BOM so Git's bundled sh can
# execute it on Windows (CRLF or a BOM would break the shebang line).
$HookLf = $HookBody -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($PreCommit, $HookLf, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Installed the DeliveryLine pre-commit hook at $PreCommit"
Write-Host "It runs Spotless + Checkstyle before each commit. The target hook path came from 'git rev-parse --git-path hooks'."
Write-Host "Bypass once with 'git commit --no-verify'."
