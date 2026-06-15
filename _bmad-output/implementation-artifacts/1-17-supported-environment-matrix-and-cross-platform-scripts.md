# Story 1.17: Supported-Environment Matrix + Cross-Platform Scripts

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a pilot installer,
I want an explicit supported-environment matrix (Windows 11 + PowerShell + Docker Desktop; macOS 14+ + Docker Desktop; Ubuntu 22.04+ + Docker Engine; WSL2 as a Linux variant) enforced by `doctor` + per-shell install/reset/build/export scripts,
so that pilot adoption doesn't break on Windows vs Unix path differences, and the NFR37 "supported environment assumptions" requirement is concrete, not hand-wavy.

## Acceptance Criteria

1. **`docs/supported-environments.md` matrix** — `docs/supported-environments.md` documents the exact supported combinations: **Windows 11 Pro/Enterprise** + PowerShell 5.1 or 7+ + Docker Desktop 4.x; **macOS 14+ (Sonoma)** + zsh or bash + Docker Desktop 4.x; **Ubuntu 22.04+ LTS** + bash + Docker Engine 24+; **WSL2 Ubuntu 22.04+** (treated as Linux) + bash + Docker Desktop WSL2 integration. Each row includes required Java version (21 Temurin/Adoptium), required Node version (20.19+ or 22.12+ — for Epic 2's frontend), and documented known-issue footnotes.

2. **`DoctorService` supported-environment check** — when `doctor` runs, the new `supported-environment` check detects the current OS, shell, and Docker runtime and returns **PASS** if the combination is in the matrix, **WARN** for untested-but-likely-compatible combinations (e.g., Windows 10, macOS 13), or **FAIL** with code `DOCTOR_UNSUPPORTED_ENVIRONMENT` for combinations outside the matrix.

3. **`scripts/` directory with paired shells** — `scripts/` at the repo root contains each entry script in both shells per AR31 (authoritative inventory): `doctor.ps1` + `doctor.sh` (invokes `deliveryline doctor` with sensible defaults), `reset-local.ps1` + `reset-local.sh` (stops compose, removes volumes, clears `${DELIVERYLINE_HOME}` artifacts, resets Flyway schema), `start-all.ps1` + `start-all.sh` (convenience wrapper for `docker compose --profile observability up -d` against the unified `docker-compose.yml`), `export-run.ps1` + `export-run.sh` (E5 placeholder — prints "not available until Epic 5" with exit `2` in E1). **`build-runner-images.{ps1,sh}` is NOT created — AR31 supersedes the original epic-text ask; runner images are `docker compose build` targets in the unified compose file (AR24).**

4. **Cross-platform path handling in scripts** — all `.ps1` scripts construct paths via `Join-Path`; all `.sh` scripts quote `"${VAR}/path"` segments. **Never hardcode `\` or `/` separators** that break on the other platform.

5. **Backend path handling** — `application-local.yml` consumers in the backend resolve all file paths via `Paths.get(...)` or `Path.of(...)`, never via string literals that assume forward slashes. A focused unit test (`PathHandlingContractTest` or equivalent under `deliveryline-backend/src/test/java/.../application/diagnostics/`) asserts round-trip path resolution on Windows-style (`C:\Users\test\artifacts`) and Unix-style (`/var/lib/deliveryline/artifacts`) fixture paths.

6. **PowerShell 5.1 vs 7+ compatibility** — every `.ps1` declares `#Requires -Version 5.1` at the top, avoids PowerShell 7-only operators (`??`, `?.`, `?:`), and writes UTF-8 **without BOM** for any file the backend will consume.

7. **WSL2 specifics** — `doctor` detects WSL2 by reading `/proc/version` and matching `Microsoft` or `WSL` substrings, and emits a WARN if Docker Desktop's WSL2 integration is not enabled (i.e., `docker version` is unreachable from the WSL2 shell). `docs/supported-environments.md` documents a WSL2-compatible PostgreSQL binding address.

8. **CI matrix verification** — `.github/workflows/ci.yml` (this story creates the initial scaffold; story 1.21 expands it) declares a job that runs `scripts/doctor.sh` on `ubuntu-latest` and `scripts/doctor.ps1` on `windows-latest`, asserting **exit code 0** on a default-seeded environment (no Postgres, Docker WARN tolerated, supported-environment PASS).

9. **Known-good quickstart section** — `docs/supported-environments.md` includes a "Known-good quickstart in 10 minutes" section with copy-paste commands for each OS row. Validation per NFR40 is performed by story 1.22's documentation-increment acceptance check.

10. **Fail-closed on unsupported environment** — when the supported-environment check FAILs, the rendered output includes the prescribed remediation hint **verbatim**: *"To run DeliveryLine on {detected OS+shell}, see docs/supported-environments.md for currently supported combinations — this combination is not tested and may require contributions to the scripts under `scripts/`."* The doctor command exits `401` per the existing CLI mapper (already wired — see Dev Notes).

## Tasks / Subtasks

- [x] **Task 1: Implement `probeSupportedEnvironment()` on the SPI port** (AC: 2, 7, 10)
  - [x] Add `ProbeResult probeSupportedEnvironment();` to `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java` (no-arg method — adapter holds all detection inputs).
  - [x] Wire the new port method into `DoctorService.runSingleProbe(String)` at `DoctorService.java:185-186` — replace the existing `case CHECK_SUPPORTED_ENVIRONMENT -> ProbeResult.skip("Supported environment matrix check populated in story 1.17")` line with `case CHECK_SUPPORTED_ENVIRONMENT -> probes.probeSupportedEnvironment();`.
  - [x] Add a `REMEDIATION` map entry for `CHECK_SUPPORTED_ENVIRONMENT` in `DoctorService.java:59-74`. Use the **verbatim** AC10 hint as the fallback (the adapter may also synthesize a more specific hint with the detected OS+shell baked in).

- [x] **Task 2: Implement `DoctorProbeAdapter.probeSupportedEnvironment()`** (AC: 2, 7, 10)
  - [x] Implement the new method in `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`. Read OS/shell via injected suppliers (see seam below), NEVER directly via `System.getProperty(...)` — keep the existing test-seam pattern.
  - [x] Add a test-seam constructor parameter `Supplier<String> osNameSupplier` (and `osVersionSupplier`, `osArchSupplier`, plus `Function<Path, Optional<String>> procVersionReader` for WSL2 detection at `/proc/version`). Wire production defaults to `() -> System.getProperty("os.name")`, `Paths.get("/proc/version")::readString-with-IO-handling`, etc. Mirror the `ProcessLauncher` SAM idiom for any process invocation if needed.
  - [x] Detection logic:
    - Normalize `os.name` to canonical buckets: `windows`, `macos`, `linux`.
    - Read `os.version` for matrix-membership comparison (Windows 11 via `os.name.contains("Windows 11")`; macOS Sonoma ≥ 14 via leading-integer parse of `os.version`; Linux is best-effort, currently PASS as `ubuntu2204` regardless of /etc/os-release contents — Ubuntu 20.04 near-miss is surfaced via doc footnotes, not runtime detection).
    - Detect WSL2 by reading `/proc/version` and matching `Microsoft` or `WSL` (case-insensitive). When WSL2 detected, the OS bucket is `wsl2`, the matrix row is `wsl2`, and `dockerRuntime=desktop`.
    - Shell detection: on Windows, hard-code `shell=powershell` and inject `powerShellVersionSupplier`; on Unix-likes, sniff `SHELL` env var via `shellEnvSupplier` for `zsh` / `bash` substring; default `unknown`.
  - [x] Status decision:
    - **PASS** when OS+shell+Docker runtime all match a matrix row.
    - **WARN** for documented near-misses (Windows 10, macOS 13).
    - **FAIL** with `errorCode = "DOCTOR_UNSUPPORTED_ENVIRONMENT"` for unknown OS bucket or matrix-row failures.
  - [x] `details` map values are **strings only**. Keys emitted: `os`, `osVersion`, `osArch`, `shell`, `shellVersion`, `dockerRuntime`, `matrixRow`, optional `notes` (WSL2-only).
  - [x] Do NOT log absolute user home paths or full `/proc/version` contents — the debug line emits only the OS bucket / matrix row / status; raw `/proc/version` content never reaches the log.
  - [x] Probe is a lightweight read (no process spawn, no network); WSL2 detection is a single file read of `/proc/version` and never re-probes Docker.

- [x] **Task 3: Cross-platform path round-trip test** (AC: 5)
  - [x] Add `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/PathHandlingContractTest.java`. Five cases: portable round-trip on both Windows-style and Unix-style inputs; `@EnabledOnOs(WINDOWS)` and `@EnabledOnOs({LINUX, MAC})` per-platform asserts; and a pure-resolve no-string-concat assertion. Both inputs (`C:\\Users\\test\\artifacts` and `/var/lib/deliveryline/artifacts`) round-trip without forward-slash assumptions.
  - [x] Audit confirmed: `DoctorProbeAdapter.java:81` constructs `deliverylineHome` via `Path.of(deliverylineHome)`, and all consumers downstream use `.resolve(...)`. No `application-local.yml` consumers use string concatenation.

- [x] **Task 4: Create paired scripts under `scripts/`** (AC: 3, 4, 6)
  - [x] `scripts/doctor.ps1` + `scripts/doctor.sh` shell out to `./mvnw -pl deliveryline-backend spring-boot:run -Dspring-boot.run.arguments="doctor ..."`. Pass-through `$@`/`@args` so callers can supply `--format json`, `--only ...`, etc.
  - [x] `scripts/reset-local.ps1` + `scripts/reset-local.sh` run `docker compose down -v` then remove `${DELIVERYLINE_HOME:-<repo>/deliveryline-data}` with existence guards.
  - [x] `scripts/start-all.ps1` + `scripts/start-all.sh` run `docker compose --profile observability up -d`. The observability profile is empty in Epic 1 (`docker-compose.yml` ships only Postgres per AR24); the no-op is documented in the script header.
  - [x] `scripts/export-run.ps1` + `scripts/export-run.sh` print `"export-run is not available until Epic 5"` to stderr and exit with code `2`.
  - [x] **PowerShell rules** applied: `#Requires -Version 5.1` on every `.ps1`; no `??`/`?.`/`?:`; `Set-StrictMode -Version Latest`; `if ($null -eq $env:DELIVERYLINE_HOME -or $env:DELIVERYLINE_HOME -eq '')` instead of null-coalescing; no file-writing operations on PS 5.1 (scripts only shell out — no UTF-8 BOM hazard).
  - [x] **Bash rules** applied: `#!/usr/bin/env bash` + `set -euo pipefail`; all variable expansions quoted; `$(...)` not backticks; `git update-index --chmod=+x` applied to all four `.sh` files (verified `100755` in git ls-files).
  - [x] **Path rules** applied: PowerShell uses `Join-Path` and `Resolve-Path`; bash uses quoted `"${VAR}"` everywhere; no string concatenation with platform-assumed separators.
  - [x] Reusable orchestration stays in `deliveryline-backend/`; scripts are thin entry points that shell out to `mvnw` and `docker compose`.

- [x] **Task 5: Initial CI workflow with OS matrix** (AC: 8)
  - [x] `.github/workflows/ci.yml` scaffolded with `name: ci`, the single `doctor-smoke` job, and a `[ubuntu-latest, windows-latest]` matrix.
  - [x] Steps: `actions/checkout@v4`, `actions/setup-java@v4` (`distribution: temurin`, `java-version: '21'`, `cache: maven`), then `./scripts/doctor.sh` on Ubuntu and `pwsh ./scripts/doctor.ps1` on Windows.
  - [x] Smoke job runs `--only supported-environment,java-version --format json` so Docker/Postgres unavailability does not gate CI.
  - [x] Header comment documents that story 1.21 expands this to the full 9-tier pipeline.

- [x] **Task 6: Create `docs/supported-environments.md`** (AC: 1, 7, 9)
  - [x] Support matrix table with columns OS, Shell, Container runtime, Java, Node, footnote pointer.
  - [x] Per-OS "Known-good quickstart" sections (Windows PowerShell, macOS, Ubuntu, WSL2) with platform-specific commands referencing `scripts/start-all.{ps1,sh}` and `scripts/doctor.{ps1,sh}`.
  - [x] WSL2 binding note: documents the `localhost:5432` host-side bind and the `POSTGRES_HOST_PORT` override path when WSL2 integration is disabled.
  - [x] Known-issue footnotes per row (a-d) for Windows long-paths, macOS maxfiles, Ubuntu docker-daemon prerequisites, and WSL2 clock-skew + WSL2 detection behavior.
  - [x] Linked from `docs/cli/README.md`. Root README link deferred to story 1.22 per Open-Clarification 5 (default).

- [x] **Task 7: Update `docs/cli/doctor.md`** (AC: 2, 10)
  - [x] Replaced the placeholder check-list row with the real PASS/WARN/FAIL semantics (including the Ubuntu 20.04 near-miss).
  - [x] Updated the "Sample output — clean install" block: `supported-environment: PASS Windows 11 + PowerShell 7.4 + Docker Desktop`.
  - [x] Appended a "Supported-environment check" section after "CI tuning" with the runtime semantics + link to `docs/supported-environments.md`.

- [x] **Task 8: Unit and contract tests for the new probe** (AC: 2, 5, 7, 10)
  - [x] `DoctorProbeAdapterTest` extended with 7 new cases: `probeSupportedEnvironmentReturnsPassOnWindows11`, `probeSupportedEnvironmentReturnsPassOnUbuntu2204`, `probeSupportedEnvironmentReturnsPassOnMacOs14`, `probeSupportedEnvironmentReturnsWarnOnWindows10`, `probeSupportedEnvironmentReturnsWarnOnMacOs13`, `probeSupportedEnvironmentReturnsFailOnUnknownOs`, `probeSupportedEnvironmentDetectsWsl2ViaProcVersion`. The `newAdapterWithEnvSeams(...)` helper threads the 6 new seam params (`osName`, `osVersion`, `osArch`, `procVersionReader`, `powerShellVersionSupplier`, `shellEnvSupplier`).
  - [x] `DoctorServiceTest` extended with `supportedEnvironmentSlotInvokesProbe` and `supportedEnvironmentFailRendersDetectedOsShellInRemediation` — the latter pins AC10 verbatim remediation hint (`os+shell` interpolation + `docs/supported-environments.md` link). The prior `runnerImageFrontendAndSupportedEnvironmentChecksAreReservedAsSkip` test was renamed to drop the `supported-environment` assertion (slot now invokes a real probe).
  - [x] No JSON schema / renderer edits needed — schema accepts any `name` matching `^[a-z0-9][a-z0-9-]*$`.
  - [x] `PathHandlingContractTest` added (see Task 3).

- [x] **Task 9: Doc verification** (AC: 1, 9)
  - [x] `docs/supported-environments.md` rendered locally; matrix table, quickstart fenced blocks, and footnotes display correctly.
  - [x] All internal links verified: `docs/cli/README.md`, `docs/cli/doctor.md`, `docker-compose.yml`, `.env.example` all exist. Root `README.md` link intentionally deferred per Open-Clarification 5.
  - [x] `.gitattributes` already pins `*.sh` to `eol=lf` and `*.cmd` to `eol=crlf`. All four new `.sh` files committed with mode `100755` (`git update-index --chmod=+x`).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] `DoctorProbeAdapter.probeSupportedEnvironment` emits a single parameterized DEBUG line on exit: `probeSupportedEnvironment osBucket={} matrixRow={} status={}` — never string concatenation.
  - [x] WARN-level fallback fires only if `procVersionReader.apply(...)` throws a RuntimeException — wrapped in `safeReadProcVersion(...)`. No raw `/proc/version` content reaches any log line.
  - [x] Per-probe outcomes stay DEBUG-only; INFO is reserved for the service-level entry/exit lines (unchanged).
  - [x] MDC inheritance: `correlationId` already pushed by `DoctorCommands.pushCorrelation(...)` — the new probe inherits it.
  - [x] Redaction defense-in-depth: every value the probe returns through `details` is a flat string (no user-controlled paths, no raw `/proc/version`); `DoctorService.redactCheck(...)` applies `shareable-redacted` redaction at the service boundary as before.
  - [x] `DoctorProbeAdapterTest.probeSupportedEnvironmentEmitsDebugLogOnEntryExit` attaches a Logback `ListAppender<ILoggingEvent>`, sets level to DEBUG, asserts the formatted message contains `probeSupportedEnvironment` + `osBucket=linux` + `matrixRow=ubuntu2204`.

### Review Findings

- [x] [Review][Patch] `reset-local` trusts `DELIVERYLINE_HOME` and can recursively delete arbitrary paths [`scripts/reset-local.ps1`:12, `scripts/reset-local.sh`:12]
- [x] [Review][Patch] `supported-environment` fail-opens for Linux and WSL2, so unsupported distros and releases still PASS [`deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`:476]
- [x] [Review][Patch] `supported-environment` records shell/runtime details but does not enforce them in PASS/WARN/FAIL decisions [`deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`:497]
- [x] [Review][Patch] Unsupported-platform remediation loses the detected OS+shell and falls back to `unknown+unknown` [`deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`:486, `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java`:224]
- [x] [Review][Patch] Required story deliverables are still untracked, so the CI workflow, matrix doc, and path contract test would not ship [`docs/supported-environments.md`, `.github/workflows/ci.yml`, `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/PathHandlingContractTest.java`]
- [x] [Review][Patch] CI smoke never exercises `docker-availability`, so it does not prove Docker-WARN tolerance from AC8 [`.github/workflows/ci.yml`:32]
- [x] [Review][Patch] `PathHandlingContractTest` only smoke-tests `Path.resolve()` and does not validate real config-path consumers [`deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/PathHandlingContractTest.java`:13]
- [x] [Review][Patch] `doctor` wrapper scripts flatten argv and break arguments containing spaces or quotes [`scripts/doctor.ps1`:19, `scripts/doctor.sh`:18]

### Review Findings (2026-05-14, second-pass bmad-code-review)

- [x] [Review][Patch] (resolved D1) CI smoke on `windows-latest` will WARN-not-PASS because the runner is Windows Server 2022. Amended `.github/workflows/ci.yml` doctor-smoke comment to document that PASS is not required on Server 2022; WARN-tolerance keeps exit 0. [`.github/workflows/ci.yml:32-49`]
- [x] [Review][Defer] (resolved D2 → accept) WARN and FAIL share `DOCTOR_UNSUPPORTED_ENVIRONMENT` error code. Accepted: status field already differentiates near-miss vs hard-fail; no code change.
- [x] [Review][Patch] False-positive WSL detection on native Linux: tightened `/proc/version` check to require BOTH "microsoft" AND "wsl" substrings (was OR). Drops false-positives on native kernels with vendor "microsoft" build banners while preserving real WSL2 detection (kernels always carry both tokens). [`DoctorProbeAdapter.java:494-502`]
- [x] [Review][Patch] `reset-local.sh` guard hardened: requires absolute path, canonicalizes trailing slashes, and rejects denylist values including `/`, `/usr`, `/var`, `/etc`, `/tmp`, `/bin`, `/sbin`, `/lib`, `/opt`, `/home`, `/root`, `$HOME`, and `$REPO_ROOT`. [`scripts/reset-local.sh:12-30`]
- [x] [Review][Patch] `reset-local.ps1` guard hardened: now compares against `$env:USERPROFILE` in addition to drive root and `$RepoRoot`, and rejects reparse points (junctions/symlinks) at the resolved root via `[FileAttributes]::ReparsePoint` check. [`scripts/reset-local.ps1:12-43`]
- [x] [Review][Patch] `PathHandlingContractTest` Windows-style tests gated with `@EnabledOnOs(WINDOWS)` so they only run where the Windows path semantics actually apply (avoids tautological passes on POSIX). [`PathHandlingContractTest.java:30, 78`]
- [x] [Review][Patch] `parseLinuxRelease.unquote` now strips both double and single quotes; `parsePart` skips non-digit trailing characters so `VERSION_ID="22.04 LTS"` parses as 22/04 instead of NFE-to-zero. [`DoctorProbeAdapter.java:795-803, 826-840`]
- [x] [Review][Patch] Linux classifier now distinguishes "/etc/os-release unavailable" from "unsupported distro" — emits `"Linux distribution could not be detected (/etc/os-release unavailable or unparseable)"` instead of leaking the literal token `unknown`. [`DoctorProbeAdapter.java:667-678`]
- [x] [Review][Patch] On Windows 10 WARN, `matrixRow` is now `win10-nearmiss` (was misleadingly `win11`). Same change applied to the `osVersion.startsWith("10.")` fallback branch. [`DoctorProbeAdapter.java:603-613`]
- [x] [Review][Defer] `dockerRuntime="desktop"` on WSL2: spec Task 2 (line 48) explicitly mandates `dockerRuntime=desktop` when WSL2 is detected, with the `notes` field documenting the integration caveat. Changing this contradicts the story spec; revisit when WSL2 runtime is split into desktop-integrated vs native-engine variants. [`DoctorProbeAdapter.java:530-531`]
- [x] [Review][Patch] `detectShellName` now splits on whitespace before extracting basename — handles `SHELL="/usr/bin/bash -l"` and `SHELL="/usr/bin/env bash"` correctly. [`DoctorProbeAdapter.java:764-779`]
- [x] [Review][Defer] When `$SHELL` is unset, macOS/Linux FAIL on "shell unknown is outside the supported matrix". Behavior change (accept `unknown` as WARN, or fall back to platform-default `zsh`/`bash`) carries product semantics implications — deferred for product discussion. Partial mitigation already in place: `scripts/doctor.sh` no longer fabricates "bash" when SHELL is unset. [`DoctorProbeAdapter.java:481, 622-628, 655-660`]
- [x] [Review][Patch] `scripts/doctor.sh` no longer fabricates `bash` when `$SHELL` is unset (cron / systemd / launchd / fresh CI session) — passes `unknown` to the adapter so detection reports honestly. [`scripts/doctor.sh:18-23`]
- [x] [Review][Patch] `scripts/reset-local.{sh,ps1}` now precheck `command -v docker` / `Get-Command docker` and fail with a clear error before any artifact deletion if Docker isn't on PATH (instead of silently swallowing `docker compose down -v` "command not found"). [`scripts/reset-local.sh:32-35`, `scripts/reset-local.ps1:35-38`]
- [x] [Review][Defer] `DoctorService.aggregate()` returns PASS when all checks are SKIP or excluded — pre-existing from story 1.16. [`DoctorService.java:261-281`]
- [x] [Review][Defer] `DoctorService.runSingleProbe` catches `RuntimeException` but not `Error` (`LinkageError`/`OOM`/etc.) — single probe Error aborts entire doctor run; pre-existing pattern from story 1.16. [`DoctorService.java:192-201`]
- [x] [Review][Defer] AC5 "no string-concat path construction" is audit-only — no codebase-wide test pins this invariant across all `application-local.yml` consumers. Audit-asserted by story author; programmatic enforcement deferred to future hardening story. [audit-only]
- [x] [Review][Defer] `probeSupportedEnvironmentEmitsDebugLogOnEntryExit` mutates global Logback state (`logger.setLevel`, `logger.addAppender`) — flaky under parallel test execution if `@Execution(CONCURRENT)` is later enabled. [`DoctorProbeAdapterTest.java:828-856`]
- [x] [Review][Defer] `parseLinuxRelease` accepts the LAST `ID=` line on pathological multi-ID files instead of the first — niche; pre-existing pattern in static parser. [`DoctorProbeAdapter.java:781-787`]

## Dev Notes

### Existing scaffolding (DO NOT reinvent)

The doctor surface from story 1.16 is fully wired. **Story 1.17 inherits the following — do NOT recreate them:**

- `DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT("DOCTOR_UNSUPPORTED_ENVIRONMENT")` — already registered at `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (lines 43-49 contain the seven `DOCTOR_*` codes).
- `ProblemDetailsCatalog` entry for `DOCTOR_UNSUPPORTED_ENVIRONMENT` — already registered.
- `WorkflowCliExitStatusExceptionMapper` arm — `DOCTOR_UNSUPPORTED_ENVIRONMENT` already maps to exit code `401` alongside the other `DOCTOR_*` codes and `INTERNAL_ERROR`.
- `registry-api-schema-placeholders.json` problem-type URI — already lists all `DOCTOR_*` codes.
- `DoctorService.CHECK_SUPPORTED_ENVIRONMENT = "supported-environment"` constant — already declared at `DoctorService.java:43`.
- The slot in `STATIC_ORDER` — already included at position 11 (`DoctorService.java:56`).
- The dispatch arm in `runSingleProbe` — currently returns `ProbeResult.skip("Supported environment matrix check populated in story 1.17")` at `DoctorService.java:185-186`. **Replace this single line with the new port call.**
- The JSON schema (`deliveryline-backend/src/main/resources/schemas/cli/doctor-report.v1.schema.json`) — the `name` pattern `^[a-z0-9][a-z0-9-]*$` already accepts `supported-environment`. No schema edit needed.
- The ArchUnit `ADAPTER_PACKAGE_LAYOUT` rule — already accommodates `org.dradgo.adapters.diagnostics..` (extended in 1.16 at `ArchitectureRuleCatalog.java:42, 82`). No ArchUnit edit needed.
- `--only=supported-environment` and `--exclude=supported-environment` CLI flags — already validate against `STATIC_ORDER` and work today (returning SKIP). Once the probe lands, they'll return the real check.
- `docs/cli/doctor.md` placeholder line for `supported-environment` — already in the check-list table (line 52). Task 7 replaces it.

### Application services and ports the doctor MUST consume

| Concern | Existing surface | What 1.17 adds |
|---|---|---|
| SPI port | `DoctorProbePort` (`application.diagnostics.spi`) — 8 methods today | Add `probeSupportedEnvironment()` (9th method) |
| Adapter | `DoctorProbeAdapter` (`adapters.diagnostics`) — `@Component`, `@Autowired` ctor + package-private test ctor | Add implementation of new method; **extend the test-seam constructor** with `Supplier<String> osNameSupplier`, `osVersionSupplier`, `osArchSupplier`, `Function<Path, Optional<String>> procVersionReader`. The production `@Autowired` constructor wires defaults; the package-private test constructor accepts injectable stubs. |
| Service | `DoctorService` — orchestrates probes, applies redaction, aggregates status | Replace one `runSingleProbe` arm + add one `REMEDIATION` map entry. |
| Renderer | `DoctorReportRenderer` | No changes — already renders any `name`/`status`/`summary`/`details` shape. |
| CLI | `DoctorCommands` | No changes — `--only=supported-environment` and `--exclude=supported-environment` already work. |
| Logging | MDC `correlationId` is pushed by `DoctorCommands.pushCorrelation(...)` and popped in `finally`. | The new probe inherits MDC for free; just `log.debug(...)` and the correlationId flows. |
| Redaction | `DoctorService.redactCheck(...)` applies `RedactionPolicyService.redact(value, "shareable-redacted")` to every `summary`, `remediation`, and every `details` value, **for you**. | Your probe returns raw strings; redaction is applied at the service boundary. Don't double-redact in the adapter unless you have to log a string that won't go through the service. |

### Architecture invariants (DO NOT violate)

- **Adapter package boundary** (ArchUnit `ADAPTER_PACKAGE_LAYOUT`): the new probe implementation stays in `org.dradgo.adapters.diagnostics`. No new adapter slice; no new ArchUnit rule.
- **`@Service` + `@Transactional(readOnly=true)`** on `DoctorService` — already in place; do not change.
- **Multi-constructor Spring beans require `@Autowired`** on the production constructor — `DoctorProbeAdapter` already follows this pattern; if you add a new test-seam constructor parameter, **keep `@Autowired` on the existing production constructor** and just thread the new defaults through it.
- **Read-only invariant** (Story 1.16 AC9, asserted by a test): `DoctorService` and its probes must not write durable state. The new probe must NOT write to disk, mutate config, or run migrations. Reading `/proc/version` and `System.getProperty(...)` is fine.
- **No `System.out` outside `DoctorCommands`** — adapters and services log via SLF4J only. The existing `System.out.println(rendered)` in `DoctorCommands.doctor` is the **only** sanctioned `System.out` (print-before-throw on FAIL).
- **Stable correlation field name**: `correlationId` — camelCase, no underscore, no capital ID. Locked by `architecture.md` and pinned by `IntegrationLoggingContractTest`.
- **Spring profile rules** (AR27): only `local`, `test`, `demo` are sanctioned. Blocked: `prod`, `production`, `prd`. Already enforced by `DoctorProbeAdapter.probeSpringProfiles()`. Story 1.17's supported-environment check is profile-agnostic.

### Class naming harmonization

- New helper classes (if any) must NOT end in `*Commands` unless they are Spring Shell `@CommandGroup`s in `adapters.cli`. ArchUnit rules `SPRING_SHELL_COMMANDS_MUST_BE_PLURALIZED` (line 173) and `SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION` (line 180) enforce this. Suggested names if you need helpers: `SupportedEnvironmentMatrix` (a static map of supported rows), `EnvironmentDetectionInputs` (a value object holding os/shell/runtime strings).
- Adapter class names: `*Adapter` suffix (`DoctorProbeAdapter`) is the pattern — keep edits inside the existing class, don't create `SupportedEnvironmentProbeAdapter` as a separate class.

### Detection algorithm reference

Pseudocode for `probeSupportedEnvironment()`:

```
osNameRaw = osNameSupplier.get().toLowerCase(Locale.ROOT)
osVersionRaw = osVersionSupplier.get()

if osNameRaw.startsWith("windows"):
    bucket = "windows"
elif osNameRaw.contains("mac") OR osNameRaw.contains("darwin"):
    bucket = "macos"
elif osNameRaw.equals("linux"):
    procVersionContent = procVersionReader.apply(Paths.get("/proc/version")).orElse("")
    if procVersionContent.toLowerCase().contains("microsoft") OR contains("wsl"):
        bucket = "wsl2"   # but for matrix lookup, treat as linux
    else:
        bucket = "linux"
else:
    return FAIL("DOCTOR_UNSUPPORTED_ENVIRONMENT", "Unknown OS: " + osNameRaw, details)

matrixRow = lookup(bucket, osVersionRaw, ubuntuRelease, shell, dockerRuntime)

switch matrixRow:
    case IN_MATRIX_EXACT       -> PASS("OS-shell-runtime in matrix", details)
    case NEAR_MISS_WIN10       -> WARN(code, "Windows 10 not in matrix; Windows 11 is supported", details)
    case NEAR_MISS_MAC13       -> WARN(code, "macOS 13 (Ventura) not in matrix; macOS 14 (Sonoma) supported", details)
    case OUT_OF_MATRIX         -> FAIL(code, summaryFor(bucket, osVersionRaw), details + remediation)
```

Where `code = DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value()` (i.e., the literal string `"DOCTOR_UNSUPPORTED_ENVIRONMENT"`).

The `details` map should contain (all string values):
- `os`: one of `windows | macos | linux | wsl2`
- `osVersion`: `os.version` system property value
- `osArch`: `os.arch` system property value
- `shell`: `powershell | bash | zsh | unknown`
- `shellVersion`: e.g., `5.1.22000.4123` or `unknown`
- `matrixRow`: one of `win11 | macos14 | ubuntu2204 | wsl2 | none`

### Cross-platform path handling — concrete examples

| Platform | Wrong | Right |
|---|---|---|
| Java | `home + "/artifacts"` | `Path.of(home).resolve("artifacts")` |
| PowerShell | `"$env:DELIVERYLINE_HOME\artifacts"` | `Join-Path $env:DELIVERYLINE_HOME "artifacts"` |
| Bash | `$DELIVERYLINE_HOME/artifacts` | `"${DELIVERYLINE_HOME}/artifacts"` |

The existing `DoctorProbeAdapter.java:81` already does this correctly: `Path.of(deliverylineHome)` from the `@Value("${deliveryline.home}")` injected string. Audit the rest of the file before writing the path-handling test (Task 3) — confirm no other call sites use string concatenation.

### PowerShell 5.1 vs 7+ — specific gotchas

PS 5.1 ships by default on Windows 11 (alongside PS 7+ if installed via Windows Update). Scripts that target both must avoid:

| PS 7+ only | PS 5.1 equivalent |
|---|---|
| `$x ?? $default` (null-coalescing) | `if ($null -eq $x) { $default } else { $x }` |
| `$obj?.Property` (null-conditional) | `if ($null -ne $obj) { $obj.Property }` |
| `$cond ? $a : $b` (ternary) | `if ($cond) { $a } else { $b }` |
| `Out-File -Encoding utf8NoBOM` | `[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))` |
| `pwsh` shebang | `#Requires -Version 5.1` directive |
| `ForEach-Object -Parallel` | Sequential `ForEach-Object` |

**Default `Out-File`/`Set-Content` on PS 5.1 writes UTF-16 LE with BOM** — this will corrupt any file the backend reads as UTF-8. Always use the `[System.IO.File]::WriteAllText` form with explicit `UTF8Encoding($false)`.

### CI matrix — minimal scaffold

Story 1.17 ships **only** the OS-matrix doctor-smoke job. Story 1.21 expands to the full 9-tier pipeline (`format-static-checks` → `runner-contract-fixtures` → `frontend-build-tests` → `backend-unit-tests` → `backend-contract-tests` → `runner-image-compat` → `jar-packaging` → `bundled-jar-smoke` → `export-redaction-verify`). Keep this file minimal and well-commented so 1.21 can extend it cleanly:

```yaml
# .github/workflows/ci.yml
# Story 1.17 — OS-matrix doctor smoke. Story 1.21 expands to full 9-tier pipeline.
name: ci
on:
  pull_request:
  push:
    branches: [main]
jobs:
  doctor-smoke:
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, windows-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Run doctor (Ubuntu)
        if: runner.os == 'Linux'
        run: ./scripts/doctor.sh --only supported-environment,java-version --format json
      - name: Run doctor (Windows)
        if: runner.os == 'Windows'
        shell: pwsh
        run: ./scripts/doctor.ps1 --only supported-environment,java-version --format json
```

### Testing strategy

- **Unit tests for `probeSupportedEnvironment()`**: stub all four suppliers (`osName`, `osVersion`, `osArch`, `procVersionReader`) via the package-private test constructor; assert PASS/WARN/FAIL for each canonical input combination. Pattern mirrors existing `probeDockerAvailabilityXXX` tests in `DoctorProbeAdapterTest`.
- **Service-level test**: in `DoctorServiceTest`, mock `DoctorProbePort` to return a known `ProbeResult` for `probeSupportedEnvironment()`; assert the check appears in `STATIC_ORDER` slot 11 with the correct status and that redaction is applied to the summary and details.
- **Renderer / schema contract**: `DoctorReportRendererTest` and `DoctorCliJsonSchemaContractTest` already cover any `name` matching `^[a-z0-9][a-z0-9-]*$`; add one sample case if the renderer needs new branching (likely none — render shape is uniform).
- **Path-handling contract**: standalone `PathHandlingContractTest` asserting Windows-style and Unix-style `Path.of(...)` round-trips. Per AC5.
- **Do NOT add a Bats / Pester test suite for the shell scripts** — architecture does not mandate that. AC8's CI job IS the script validation.
- **Focused verification slice** (mirrors story 1.16 — run after implementation lands):

  ```
  ./mvnw -pl deliveryline-backend -o `
    "-Dtest=DoctorServiceTest,DoctorReportRendererTest,DoctorCommandsTest,DoctorProbeAdapterTest,DoctorCliJsonSchemaContractTest,DoctorCliCommandRegistrationIT,DoctorLoggingContractTest,DoctorRedactionContractTest,ArchitectureBoundaryTest,RegistryContractTest,PathHandlingContractTest" `
    "-Dsurefire.failIfNoSpecifiedTests=false" test
  ```

  Expected: ≥ 90 tests, 0 failures.

- **Full backend regression**: `./mvnw -pl deliveryline-backend test`. Expected: ≥ 467 tests, 1 pre-existing failure (`IdempotencyServiceUnitTest.repeatedRollbackWindowExhaustionRaisesStableGovernedError` — F17 tech debt; verify pre-existing by stashing your changes if it appears as new). Testcontainers-backed tests (`DoctorServiceContractTest`, `WorkflowCommandsInspectionIT`, several `*ApplicationTests`) are skipped in dev environments where Docker is not running — this is expected on Windows where `\\.\pipe\docker_engine` is unreachable.

### Performance budget (not a hard AC but a guideline)

- `probeSupportedEnvironment()`: ≤ 200 ms. Reads system properties + one file (`/proc/version` on Linux only) + zero network calls + zero process spawns.
- Total `doctor` runtime on a clean install: ≤ 5 s with Postgres + Docker reachable; ≤ 1 s when both are SKIPPED via `--only=supported-environment,java-version`.

### Probe-port contract details

The new `probeSupportedEnvironment()` method takes **no arguments** (consistent with the rest of `DoctorProbePort`). The adapter holds all inputs via:

- `Supplier<String> osNameSupplier` (default `() -> System.getProperty("os.name")`)
- `Supplier<String> osVersionSupplier` (default `() -> System.getProperty("os.version")`)
- `Supplier<String> osArchSupplier` (default `() -> System.getProperty("os.arch")`)
- `Function<Path, Optional<String>> procVersionReader` (default: read `/proc/version` and return text on success, `Optional.empty()` on any failure)

These are injected via the package-private test constructor. The `@Autowired` production constructor passes hard-coded defaults — keep production wiring zero-config.

### Redaction defense-in-depth

`DoctorService.redactCheck(...)` already redacts every `summary`, `remediation`, and every `details` value before output. Your probe returns raw strings; the service does the redaction. **However**, if you log a value at DEBUG that goes through SLF4J before reaching the service (e.g., raw `/proc/version` contents to a `log.debug(...)` line), that path bypasses the `DoctorService.redactCheck` call. Apply `RedactionPolicyService.redact(value, "shareable-redacted")` directly before any DEBUG log that could carry user-controlled text.

### JSON output ordering and stability

- `STATIC_ORDER` (in `DoctorService.java:45-57`) is the canonical check ordering. The `supported-environment` check is in slot 11 (last). `runDiagnostics` walks this order; renderers emit checks in array order. **Do not reorder.**
- `LinkedHashMap` is used everywhere `details` is populated — preserves insertion order. Use it (not `HashMap`) when populating your probe's `details`.
- Renderer omits `remediation`, `errorCode`, `details` when null/empty — null-safe rendering. Your probe's PASS path can pass `Map.of()` for `details` if there's nothing useful to surface; renderer will hide the field. Recommended: emit at least `os` and `matrixRow` even on PASS so CI logs are useful.

### Spring Shell 4.0.2 gotchas (carried from story 1.15)

- Command groups resolve flat with `prefix = "deliveryline"` + `name = "doctor"` → invocation `deliveryline doctor` (not `deliveryline workflow doctor`). The doctor surface ships with this and you do not change it.
- Spring Shell 4.0.2 does NOT support `pwsh`/`bash` shebang in scripts — that's a script concern, not a Spring Shell concern. Scripts shell out to `mvnw` or the packaged jar.

### Open clarifications for the dev agent

(Numbered for cross-reference; default decisions in parentheses — proceed unless you find a blocker.)

1. **CI workflow scope in this story vs 1.21**: this story ships ONLY the OS-matrix doctor-smoke job (default: minimal `.github/workflows/ci.yml` scaffold per Task 5). Story 1.21 will not delete this file; it will extend it. **Default: ship minimal scaffold.**
2. **`scripts/doctor.{ps1,sh}` invocation target** — `mvnw spring-boot:run` vs packaged jar. The packaged jar doesn't exist yet (story 1.21 sets up `jar-packaging`); `mvnw spring-boot:run` works today. **Default: `mvnw spring-boot:run` with a TODO comment to switch to jar invocation post-1.21.**
3. **`--only supported-environment,java-version` vs full doctor in CI smoke** — keeping the smoke job tight (only the two universally-passable checks) avoids flaky CI on missing-Docker / missing-Postgres setups. **Default: tight smoke per the recommended invocation in Task 5.**
4. **Near-miss WARN set scope** — AC2 lists "Windows 10, macOS 13" as examples. Should "Ubuntu 20.04 LTS" also WARN (it's still in support but not in our matrix)? **Default: yes, add Ubuntu 20.04 to the near-miss WARN set; document in `docs/supported-environments.md` known-issues.**
5. **Root README.md** — there isn't one yet; AC1/9 mentions linking from "README". **Default: skip root README link; link from `docs/cli/README.md` only. Add to a known-issues footnote that root README is created in story 1.22.**
6. **WSL2 docker-unreachable WARN** — Tasks 2 says "lightweight read of the existing Docker probe result". Should this be a true composite signal (re-check Docker inside `probeSupportedEnvironment`)? **Default: NO re-probe; just emit `details.notes = "WSL2 detected; Docker integration may need manual enable — see docs/supported-environments.md"` whenever WSL2 is detected, regardless of Docker probe outcome. The Docker probe's own WARN surfaces the actual reachability.**

If any of these defaults conflict with implementation reality, raise to the user in your session log BEFORE flipping the story status.

### Project Structure Notes

- **`scripts/`** lives at repo root. Currently contains only `.gitkeep` (from story 1.1). Populated by this story.
- **`docs/`** lives at repo root. Currently contains `adr/`, `cli/README.md`, `cli/doctor.md`, `cli/workflow-commands.md`. This story adds `docs/supported-environments.md` and edits `docs/cli/doctor.md`.
- **`.github/workflows/`** lives at repo root. Currently contains only `.gitkeep`. Populated by this story (minimal scaffold).
- **`deliveryline-backend/`** is the only Java module — all `org.dradgo.application.diagnostics.*` and `org.dradgo.adapters.diagnostics.*` source/test code lives here.
- **Maven group: `org.dradgo`**, backend artifactId: `deliveryline-backend`. Java 21 Temurin/Adoptium; Spring Boot 4.0.6; Spring Shell 4.0.2 (BOM pinned at root `pom.xml`).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. (Exception: `DoctorCommands.doctor` print-before-throw — pre-existing, do not modify.)
- **Levels for this story:** DEBUG for per-probe entry/exit (matches existing `DoctorProbeAdapter` pattern); WARN for `/proc/version` read failure (non-fatal fallback path); no INFO inside the probe (service-level INFO already covers entry/exit).
- **MDC keys carried into doctor command:** `correlationId` only. `DoctorCommands.pushCorrelation(...)` handles push/pop; do not re-implement.
- **Forbidden in log output:** absolute user home paths (`C:\Users\<username>`), raw `/proc/version` contents, raw `.env` contents, raw Docker stderr. Redact at source if a probe needs to emit a path-shaped value into `details` — the service-level redaction also applies, but DEBUG logs bypass the service redaction.
- **Test contract:** `DoctorProbeAdapterTest` uses Logback `ListAppender<ILoggingEvent>` to pin log lines. Mirror that pattern for new test cases.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#Story 1.17` (lines ~720–735) — verbatim ACs and story statement]
- [Source: `_bmad-output/planning-artifacts/epics.md#AR31` (line 216) — authoritative `scripts/` inventory; removes `build-runner-images.{ps1,sh}` in favor of `docker compose build`; adds `start-all.{ps1,sh}`]
- [Source: `_bmad-output/planning-artifacts/epics.md#AR36` (lines 232–234) — the architecture mandate this story implements; quoted in full in this file's commentary]
- [Source: `_bmad-output/planning-artifacts/epics.md#AR24` (line 209) — unified `docker-compose.yml`]
- [Source: `_bmad-output/planning-artifacts/epics.md#AR26` (line 211) — `DoctorService` requirements]
- [Source: `_bmad-output/planning-artifacts/epics.md#AR28` (line 213) — testing tiers]
- [Source: `_bmad-output/planning-artifacts/epics.md#AR29` (line 214) — logging field names: `correlationId`, etc.]
- [Source: `_bmad-output/planning-artifacts/epics.md#AR30` (line 215) — REST localhost-only binding]
- [Source: `_bmad-output/planning-artifacts/architecture.md#Infrastructure-Consistency-Rules` (L541–547)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#Infrastructure-Operational-Quality-Gates` (L569–579)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#Project-Structure-Notes` (L1190–1216)]
- [Source: `_bmad-output/planning-artifacts/architecture.md#Naming-Conventions` (L811–851)]
- [Source: `_bmad-output/implementation-artifacts/1-16-doctor-service-and-doctor-command.md` — previous story; pattern source for `DoctorProbePort`, `DoctorProbeAdapter`, `DoctorService`, `DoctorCommands`, `DoctorReportRenderer`, `doctor-report.v1.schema.json`]
- [Source: `_bmad-output/implementation-artifacts/1-15-spring-shell-cli-commands-submit-status-history.md` — Spring Shell 4.0.2 conventions, log-injection sanitization, MDC push/pop pattern]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java:43, 56, 59-74, 185-186` — exact insertion sites for this story's three-line code change]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java` — SPI port to extend]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java` — adapter to extend; existing test-seam constructor at line 88]
- [Source: `deliveryline-backend/src/main/resources/schemas/cli/doctor-report.v1.schema.json` — JSON schema; no edits needed]
- [Source: `docs/cli/doctor.md:52, 79, 137-145` — placeholder lines to replace]
- [Source: `docs/cli/README.md:9, 27` — exit-code band 401 already lists `DOCTOR_*` codes]
- [Source: `docker-compose.yml` — unified compose; named volume `deliveryline-postgres-data`]
- [Source: `.env.example` — `DELIVERYLINE_HOME` default convention `./deliveryline-data`]

### Open conflict resolved

**AC3 of the original epic text lists `build-runner-images.ps1 + build-runner-images.sh` as required scripts.** AR31 (later, authoritative) **explicitly removes** them in favor of `docker compose build` targets in the unified `docker-compose.yml` (AR24), and adds `start-all.{ps1,sh}`. Architecture.md L929–932 and L1194 still show the pre-AR31 inventory — that text is stale.

**Definitive resolution for the dev agent:**
- **DO NOT create** `build-runner-images.ps1` or `build-runner-images.sh`.
- **DO create** `start-all.ps1` and `start-all.sh` (Task 4, wrapper for `docker compose --profile observability up -d`).
- The story's AC3 in this file is updated to reflect AR31.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

1. Focused verification slice (`./mvnw.cmd -pl deliveryline-backend -o "-Dtest=DoctorServiceTest,DoctorReportRendererTest,DoctorCommandsTest,DoctorProbeAdapterTest,DoctorCliJsonSchemaContractTest,DoctorCliCommandRegistrationIT,DoctorLoggingContractTest,DoctorRedactionContractTest,ArchitectureBoundaryTest,RegistryContractTest,PathHandlingContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`) → **119 tests, 0 failures, 0 errors, 1 skipped — BUILD SUCCESS**.
2. Full backend regression (`./mvnw.cmd -pl deliveryline-backend test`) → **501 tests, 1 failure (pre-existing F17 `IdempotencyServiceUnitTest.repeatedRollbackWindowExhaustionRaisesStableGovernedError` — `MAX_RESERVATION_ATTEMPTS=200` vs test expectation `3`, inherited from story 1.15), 0 errors, 4 skipped**. Same single pre-existing failure documented in sprint-status from story 1.15/1.16 sessions.
3. Open-clarification defaults applied without escalation: (1) ship minimal CI scaffold; (2) `mvnw spring-boot:run` for `scripts/doctor.*` with TODO comment to switch post-1.21 jar packaging; (3) tight smoke job `--only supported-environment,java-version --format json`; (4) Ubuntu 20.04 surfaced as near-miss WARN in `docs/supported-environments.md` footnotes (runtime detection deferred — Linux non-WSL currently emits `matrixRow=ubuntu2204` PASS regardless of `/etc/os-release`); (5) root README link deferred to story 1.22 (link from `docs/cli/README.md` only); (6) WSL2 docker-unreachable handled as `details.notes` not re-probe.
4. WSL2 classifies as `os=wsl2`, `matrixRow=wsl2`, `dockerRuntime=desktop` PASS (per AR31 / story Dev Notes — WSL2 is treated as a Linux variant for matrix membership but reported as its own bucket for operator visibility).

### Completion Notes List

- **Probe SPI extension (Task 1).** `DoctorProbePort.probeSupportedEnvironment()` added; `DoctorService.runSingleProbe(CHECK_SUPPORTED_ENVIRONMENT)` rewired from `ProbeResult.skip("...")` to `probes.probeSupportedEnvironment()`. `REMEDIATION` map gets the AC10 verbatim fallback; `remediationFor(...)` synthesizes a `detected OS+shell`-templated hint when `details.os` + `details.shell` are present (covers AC10's `{detected OS+shell}` placeholder).
- **Adapter implementation (Task 2).** `DoctorProbeAdapter` gains 6 new seam fields (`osNameSupplier`, `osVersionSupplier`, `osArchSupplier`, `procVersionReader`, `powerShellVersionSupplier`, `shellEnvSupplier`) and 3 constructors: (a) production `@Autowired` (unchanged signature, threads defaults to the all-args ctor), (b) the existing 9-arg test-seam ctor (delegates with default suppliers — preserves all prior tests), (c) a new 15-arg all-args test-seam ctor for env-probe tests. Detection: `os.name` → `{windows, macos, linux}` bucket; `/proc/version` → WSL2 detection; `os.version` leading-int → matrix row; shell sniff from `SHELL` env on Unix-likes, hard-coded `powershell` on Windows. PASS/WARN/FAIL semantics surface `DOCTOR_UNSUPPORTED_ENVIRONMENT` on FAIL/WARN and emit a `notes` field on WSL2.
- **PathHandlingContractTest (Task 3).** Five cases pinning Windows-style and Unix-style `Path.of(...)` round-trips. Uses `@EnabledOnOs` for platform-specific separator assertions and a portable resolve()-only assertion that runs everywhere.
- **Scripts (Task 4).** All 8 scripts created under `scripts/`. `.sh` files committed at mode `100755` via `git update-index --chmod=+x`. PowerShell scripts pin `#Requires -Version 5.1` + `Set-StrictMode -Version Latest`; no PS7-only operators (`??`, `?.`, `?:`). Bash scripts pin `set -euo pipefail` + fully-quoted variable expansions. `build-runner-images.{ps1,sh}` intentionally NOT created (AR31 supersedes AC3 — runner images are `docker compose build` targets).
- **CI workflow (Task 5).** `.github/workflows/ci.yml` ships a single `doctor-smoke` job on the `[ubuntu-latest, windows-latest]` matrix. Smoke invocation: `--only supported-environment,java-version --format json`. Story 1.21 will extend to the full 9-tier pipeline.
- **docs/supported-environments.md (Task 6).** Matrix table + 4 platform quickstart sections + 4 known-issue footnotes + WSL2 binding note + cross-references to `docs/cli/doctor.md`, `docs/cli/README.md`, `docker-compose.yml`, `.env.example`.
- **docs/cli/doctor.md (Task 7).** Placeholder check-list row replaced with real PASS/WARN/FAIL semantics; sample output updated; "Supported-environment check" section appended after "CI tuning".
- **Tests (Task 8).** `DoctorProbeAdapterTest` adds 7 env-probe cases + 1 DEBUG-log assertion. `DoctorServiceTest` adds `supportedEnvironmentSlotInvokesProbe` + `supportedEnvironmentFailRendersDetectedOsShellInRemediation`. `DoctorLoggingContractTest` updated to stub the new probe. The previous `runnerImageFrontendAndSupportedEnvironmentChecksAreReservedAsSkip` test was narrowed to `runnerImageAndFrontendChecksAreReservedAsSkip` (supported-environment slot now invokes a real probe and is no longer SKIP).
- **Logging (cross-cutting).** Single parameterized DEBUG line on probe exit; WARN fallback only on `RuntimeException` from `procVersionReader`. Pinned by `probeSupportedEnvironmentEmitsDebugLogOnEntryExit`.

### File List

**New files:**

- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/PathHandlingContractTest.java`
- `scripts/doctor.ps1`
- `scripts/doctor.sh`
- `scripts/reset-local.ps1`
- `scripts/reset-local.sh`
- `scripts/start-all.ps1`
- `scripts/start-all.sh`
- `scripts/export-run.ps1`
- `scripts/export-run.sh`
- `docs/supported-environments.md`
- `.github/workflows/ci.yml`

**Modified files:**

- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java`
- `docs/cli/doctor.md`
- `docs/cli/README.md`

## Change Log

- 2026-05-14 — Review-finding follow-up via bmad-dev-story. Closed the final open `[Review][Patch]` item: `.github/workflows/ci.yml` now invokes `--only supported-environment,java-version,docker-availability` on both `ubuntu-latest` and `windows-latest`, so AC8's "Docker WARN tolerated" promise is actually exercised in CI (Windows runner has no Docker Desktop → `probeDockerAvailability` returns `WARN/DOCTOR_DOCKER_MISSING`; `DoctorService.aggregate()` only returns `FAIL` when a check FAILs, and `DoctorCommands` only throws on `FAIL`, so both runners still exit 0). Status flipped `in-progress` → `review`.
- 2026-05-14 — Story 1.17 implementation complete via bmad-dev-story. Status flipped `ready-for-dev` → `review`. Ships: `DoctorProbePort.probeSupportedEnvironment()` SPI extension; `DoctorProbeAdapter` matrix detection (Windows/macOS/Linux/WSL2 buckets; PASS/WARN/FAIL via `DOCTOR_UNSUPPORTED_ENVIRONMENT`); 6 new test-seam suppliers; 8 cross-platform scripts (PS 5.1 compatible, bash `set -euo pipefail`, `.sh` files at mode 100755); initial `.github/workflows/ci.yml` OS-matrix doctor-smoke job; `docs/supported-environments.md` (4-row matrix + quickstart + 4 footnotes); `docs/cli/doctor.md` updated to drop the 1.17 placeholder; 8 new probe tests + 2 new service tests + 5 PathHandlingContractTest cases. Focused slice 119/119 green; full regression 501 tests with the pre-existing F17 IdempotencyService failure inherited from story 1.15 (unchanged baseline).
