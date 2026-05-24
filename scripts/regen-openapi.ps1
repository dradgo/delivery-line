#Requires -Version 5.1
# scripts/regen-openapi.ps1 — combined OpenAPI snapshot + frontend client regen.
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
#   - Docker Desktop (Testcontainers spins up Postgres for the backend Spring context).
#   - Node + npm (for the frontend regen).

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $RepoRoot

$MvnwCmd = Join-Path $RepoRoot 'mvnw.cmd'
if (-not (Test-Path $MvnwCmd)) {
    Write-Error "mvnw.cmd not found at $MvnwCmd"
    exit 1
}

$BackendSnapshot = 'deliveryline-backend/src/main/resources/openapi/openapi.json'
$FrontendClient  = 'deliveryline-frontend/src/lib/api/schema.d.ts'

Write-Host '==> Step 1/2 — regenerating backend OpenAPI snapshot via OpenApiSnapshotContractTest'
Write-Host '    (the test FAILS by design when -Dopenapi.snapshot.write=true is set; that is expected)'

# Skip the frontend Maven module entirely (-Dfrontend-maven-plugin.skip=true): `-am` pulls
# deliveryline-frontend into the reactor because backend depends on its bundle, and that
# would otherwise spend ~4 min on Node install + npm ci + lint + Prettier check + Vite
# build before even getting to the backend test. The frontend regen step below runs
# `npm run generate-api` directly, which is the only frontend work we actually need.
#
# Skip JaCoCo (-Djacoco.skip=true): we're not measuring coverage of a snapshot regen.
& $MvnwCmd -B -ntp -pl deliveryline-backend -am `
    '-Dfrontend-maven-plugin.skip=true' `
    '-Djacoco.skip=true' `
    '-Dit.test=OpenApiSnapshotContractTest' `
    '-Dfailsafe.failIfNoSpecifiedTests=false' `
    '-Dopenapi.snapshot.write=true' `
    test-compile failsafe:integration-test
$MvnExit = $LASTEXITCODE

# The intentional "snapshot (re)generated, review + re-run" failure exits non-zero.
# A genuine compile/Spring-context failure ALSO exits non-zero, so we cannot trust
# $MvnExit alone. Use git to disambiguate: if the snapshot file is modified, the
# write happened and the failure was the by-design one.
$snapshotStatus = & git status --porcelain -- $BackendSnapshot 2>$null
if ($LASTEXITCODE -ne 0) { $snapshotStatus = '' }

if (-not [string]::IsNullOrWhiteSpace($snapshotStatus)) {
    Write-Host '    backend snapshot updated.'
}
elseif ($MvnExit -eq 0) {
    Write-Host '    backend snapshot already in sync — nothing to write.'
}
else {
    Write-Error ("mvn exited $MvnExit and the snapshot file is unchanged. " +
                 'This is a real test failure (context load, compile error, etc.), ' +
                 'not the by-design regen-and-fail. Scroll up for the root cause.')
    exit $MvnExit
}

Write-Host ''
Write-Host '==> Step 2/2 — regenerating frontend TypeScript client (openapi-typescript)'
Push-Location (Join-Path $RepoRoot 'deliveryline-frontend')
try {
    & npm run --silent generate-api
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}

Write-Host ''
Write-Host '==> Done. Review and commit:'
& git --no-pager diff --stat -- $BackendSnapshot $FrontendClient
Write-Host ''
Write-Host "    git add $BackendSnapshot $FrontendClient"
Write-Host '    git commit -m "chore: regenerate OpenAPI snapshot + frontend client"'
