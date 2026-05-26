# Story 2.24 AC16 — regenerate the frontend's redaction-policy.generated.json
# from the canonical runner-contracts source. Windows mirror of
# regen-redaction-policy.sh (memory `openapi-regen-platform-shim.md`).
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$sourcePath = Join-Path $repoRoot 'deliveryline-runner-contracts\src\main\resources\runner-contracts\redaction-policy.json'
$targetPath = Join-Path $repoRoot 'deliveryline-frontend\src\lib\sanitization\redaction-policy.generated.json'

if (-not (Test-Path $sourcePath)) {
    Write-Error "Canonical policy missing at $sourcePath"
    exit 1
}

Copy-Item -Path $sourcePath -Destination $targetPath -Force
$hash = (Get-FileHash -Path $targetPath -Algorithm SHA256).Hash.ToLower()
Write-Output "Copied redaction-policy.json -> $targetPath"
Write-Output "sha256: $hash"
