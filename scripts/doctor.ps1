#Requires -Version 5.1
# scripts/doctor.ps1 — DeliveryLine doctor entry point (Windows PowerShell 5.1+)
# Story 1.17. Runs `deliveryline doctor` against the local checkout.
# In Epic 1 this uses `mvnw spring-boot:run`; story 1.21 will switch to the packaged jar.
# Reusable orchestration MUST stay in deliveryline-backend; this script is a thin entry point.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RepoRoot

$MvnwCmd = Join-Path $RepoRoot "mvnw.cmd"
if (-not (Test-Path $MvnwCmd)) {
    Write-Error "mvnw.cmd not found at $MvnwCmd"
    exit 1
}

$RunArgsList = [System.Collections.Generic.List[string]]::new()
$RunArgsList.Add('doctor')
foreach ($arg in $args) {
    $RunArgsList.Add(($arg -replace ',', '\,'))
}
$RunArgs = [string]::Join(',', $RunArgsList)
$PowerShellVersion = $PSVersionTable.PSVersion.ToString()

# Step 1 — compile upstream siblings (runner-contracts) into the reactor so
# backend's classpath resolves on a fresh CI checkout where ~/.m2 is empty.
# Done as a separate invocation because `-am spring-boot:run` would also try
# to run the spring-boot:run goal on the parent pom and runner-contracts —
# neither has a main class, so the goal fails on those modules.
& $MvnwCmd -B -ntp -pl deliveryline-backend -am compile
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Step 2 — run the backend only. No `-am`, so spring-boot:run is invoked
# strictly on deliveryline-backend, which is the only module with a main
# class. Upstream classes are already on the reactor classpath from step 1.
& $MvnwCmd -B -ntp "-Ddeliveryline.shell=powershell" "-Ddeliveryline.shell.version=$PowerShellVersion" `
    -pl deliveryline-backend spring-boot:run "-Dspring-boot.run.arguments=$RunArgs"
exit $LASTEXITCODE
