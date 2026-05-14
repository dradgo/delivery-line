#Requires -Version 5.1
# scripts/reset-local.ps1 — Wipe local DeliveryLine state and reset the Flyway schema.
# Story 1.17. Stops the unified docker compose stack, removes the named Postgres volume,
# and clears the artifact tree under ${DELIVERYLINE_HOME}.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RepoRoot

$DeliverylineHome = if ($null -eq $env:DELIVERYLINE_HOME -or $env:DELIVERYLINE_HOME -eq '') {
    Join-Path $RepoRoot "deliveryline-data"
} else {
    $env:DELIVERYLINE_HOME
}
$DeliverylineHomeFullPath = [System.IO.Path]::GetFullPath($DeliverylineHome)
$RepoRootFullPath = [System.IO.Path]::GetFullPath($RepoRoot.Path)
$HomeRoot = [System.IO.Path]::GetPathRoot($DeliverylineHomeFullPath)
$UserProfileFullPath = if ($null -ne $env:USERPROFILE -and $env:USERPROFILE -ne '') {
    [System.IO.Path]::GetFullPath($env:USERPROFILE)
} else {
    $null
}
$Denylist = @($HomeRoot, $RepoRootFullPath)
if ($null -ne $UserProfileFullPath) {
    $Denylist += $UserProfileFullPath
}
foreach ($denied in $Denylist) {
    if ($DeliverylineHomeFullPath -eq $denied) {
        Write-Error "Refusing to clear DELIVERYLINE_HOME at $DeliverylineHomeFullPath (matches denylist entry $denied)"
        exit 1
    }
}
# Reject reparse points (junctions/symlinks) at the resolved root — Path.GetFullPath is lexical only.
if (Test-Path -LiteralPath $DeliverylineHomeFullPath) {
    $item = Get-Item -LiteralPath $DeliverylineHomeFullPath -Force
    if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
        Write-Error "Refusing to clear DELIVERYLINE_HOME at $DeliverylineHomeFullPath (path is a reparse point/junction; resolve before retrying)"
        exit 1
    }
}
$ArtifactsRoot = Join-Path $DeliverylineHomeFullPath "artifacts"

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "docker not found on PATH — cannot stop compose stack. Aborting reset."
    exit 1
}
Write-Output "Stopping docker compose stack and removing named volumes..."
docker compose down -v
if ($LASTEXITCODE -ne 0) {
    Write-Output "docker compose down -v returned exit code $LASTEXITCODE (continuing)"
}

if (Test-Path $ArtifactsRoot) {
    Write-Output "Removing artifact directory at $ArtifactsRoot"
    Remove-Item -LiteralPath $ArtifactsRoot -Recurse -Force
} else {
    Write-Output "Artifact directory at $ArtifactsRoot does not exist (nothing to remove)"
}

Write-Output "Reset complete. Run scripts/start-all.ps1 followed by scripts/doctor.ps1 to bring the stack back up."
