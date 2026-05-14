#Requires -Version 5.1
# scripts/start-all.ps1 — Bring up the unified docker compose stack (incl. observability profile).
# Story 1.17. Epic 1 ships only Postgres in docker-compose.yml (AR24); the observability profile
# services land in Epic 3. `docker compose --profile observability up -d` only starts services
# tagged with that profile, so an empty profile is a no-op — not an error.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RepoRoot

Write-Output "Starting docker compose stack (--profile observability up -d) from $RepoRoot..."
docker compose --profile observability up -d
exit $LASTEXITCODE
