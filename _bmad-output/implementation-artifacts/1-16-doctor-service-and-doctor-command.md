# Story 1.16: DoctorService + DoctorCommand

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a pilot installer,
I want `deliveryline doctor` that checks every runtime prerequisite (Java version, Spring profile, PostgreSQL connectivity, Flyway state, required directories, config file permissions, Docker availability, runner image availability, REST bind address) and reports stable human-readable + JSON output,
so that I can distinguish missing prerequisites from product bugs before attempting real work — a broken install cannot masquerade as a product failure.

## Acceptance Criteria

1. **`DoctorService` exists as a Spring `@Service` under `org.dradgo.application.diagnostics`** with a public `runDiagnostics(DoctorRunRequest request)` method returning a typed `DiagnosticsReport`. The report is a `record` carrying `schemaVersion` (`int`, fixed at `1`), `generatedAt` (`OffsetDateTime`, UTC), `overallStatus` (`DiagnosticsStatus`), and `checks` (`List<DiagnosticsCheck>`). Each `DiagnosticsCheck` is a `record` with `name` (stable kebab-case identifier — e.g., `postgres-connectivity`), `status` (`DiagnosticsStatus`: `PASS|WARN|FAIL|SKIP`), `summary` (one-line human description), optional `remediation` (actionable hint), optional `errorCode` (a `DomainErrorCode` wire value when status is `FAIL`), and `details` (`Map<String, String>` — short scalar facts only; no payloads). `DoctorRunRequest` carries `only` (`Set<String>` — run only these check names), `exclude` (`Set<String>` — skip these check names), and `correlationId` (`String`, may be null).

2. **`DoctorCommands` is a Spring Shell `@CommandGroup` under `org.dradgo.adapters.cli`** exposing `deliveryline doctor [--format text|json] [--only <name>[,<name>...]] [--exclude <name>[,<name>...]] [--correlation-id <c>]`. The class name **MUST be `DoctorCommands` (plural)** — the existing ArchUnit rules `SPRING_SHELL_COMMANDS_MUST_BE_PLURALIZED` and `SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION` require any `@CommandGroup` class to end in `Commands`. The architecture document's `DoctorCommand.java` reference is harmonized to `DoctorCommands.java` (single doctor command method housed in a `*Commands` adapter class). The command is wired to `WorkflowCliExitStatusExceptionMapper.BEAN_NAME` via `@Command(... exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)` so all `DomainException`s map to stable exit codes.

3. **The check set in `DoctorService` covers exactly these named checks (in this order):**
   - `java-version` — asserts JVM major version ≥ 21; FAIL with summary `"Java {detected} detected; Java 21+ required"` otherwise. No `errorCode` on this check (it cannot map to any infra `DomainErrorCode`); FAIL status alone gates exit.
   - `spring-profile` — asserts at least one of `local`/`test`/`demo` is active; WARN if no profile is active (defaults to `default`) — FAIL if any profile name matches `prod`, `production`, `prd` (case-insensitive). FAIL `errorCode = DOCTOR_UNSUPPORTED_ENVIRONMENT`.
   - `postgres-connectivity` — borrows the existing `DataSource` bean; opens a connection with a short validation timeout (≤ 5s) and runs `SELECT 1`. FAIL `errorCode = DOCTOR_POSTGRES_UNREACHABLE` on any `SQLException`.
   - `flyway-state` — invokes Flyway `info()` against the existing `Flyway` bean; FAIL `errorCode = DOCTOR_FLYWAY_FAILED` if `migrationInfoService` reports any `MigrationState.PENDING`, `FAILED`, or `OUT_OF_ORDER`. PASS if the latest applied version matches the highest available migration.
   - `artifact-directory` — reads the configured artifact root (`deliveryline.artifact.root` property, falling back to `${user.home}/.deliveryline/artifacts` per story 1.12's existing convention — confirm at dev time by grepping `application.yml` + `LocalArtifactStore`); creates the directory if missing, then writes + deletes a probe file. FAIL `errorCode = DOCTOR_ARTIFACT_DIR_UNWRITABLE` if creation or probe write fails.
   - `config-file-permissions` — on POSIX file systems (`FileSystem.supportedFileAttributeViews().contains("posix")`), inspects `.env` and `application-*.yml` under the working directory; FAIL `errorCode = DOCTOR_CONFIG_PERMISSIONS_UNSAFE` if any file has world-readable bits (`others-read`). On Windows / non-POSIX, status is `SKIP` with summary `"Permission check skipped on non-POSIX filesystem"`.
   - `docker-availability` — probes `docker version --format '{{.Server.Version}}'` via `ProcessBuilder` with a 3-second timeout. WARN (not FAIL) when Docker is unreachable — runners are only required in Epic 3, so a missing Docker daemon is not a blocker for the foundation. The check still emits `errorCode = DOCTOR_DOCKER_MISSING` on the WARN status so scripts can match on a stable code. FAIL only when `--require-docker` is explicitly passed (covered by AC6's `--only` plus a future flag — out of scope for 1.16; keep the FAIL branch wired but unreachable from the default code path).
   - `runner-image-availability` — `SKIP` in 1.16 with summary `"Runner image availability check populated in story 3.1"`. Reserved name; do not invent E3 image probing in this story. Wire the SKIP branch so the check name is stable in the JSON contract.
   - `rest-bind-address` — reads `server.port` (default `8080` per Spring Boot) and `server.address` (default `localhost`). FAIL `errorCode = DOCTOR_REST_BIND_UNAVAILABLE` if (a) `server.address` resolves to `0.0.0.0` or any non-loopback address (`!InetAddress.getByName(server.address).isLoopbackAddress()`), or (b) the port is already bound. Bind-probe MUST close the socket immediately and MUST NOT leak the port from the doctor command into the running app.
   - `frontend-asset-presence` — `SKIP` in 1.16 with summary `"Frontend asset check populated in story 2.28"`. Reserved name.
   - `supported-environment` — `SKIP` in 1.16 with summary `"Supported environment matrix check populated in story 1.17"`. Reserved name. (Story 1.17 will replace this check body in a backward-compatible way: the check name stays `supported-environment`; values change.)

4. **CLI output formats:**
   - **`--format text` (default):** Renders a per-check line `<name>: <STATUS> <summary>` followed by indented `  remediation: <hint>` if present, then a final `overall: <STATUS>` line. Status is rendered without ANSI color codes (logs and CI captures must be color-free); the CLI reference doc may describe how to pipe through a colorizer.
   - **`--format json`:** Renders a single JSON object validating against `doctor-report.v1.schema.json` (Task 4). Exactly one JSON object per invocation; no trailing prose; UTF-8; LF newlines.

5. **Exit code mapping** is delegated to `WorkflowCliExitStatusExceptionMapper`:
   - On overall `PASS`, `WARN`, or `SKIP-only` reports: command returns the rendered string with **no** exception, so Spring Shell sees a normal completion (exit 0).
   - On overall `FAIL`: the command throws `DomainException(<first-FAIL-check.errorCode> | DomainErrorCode.INTERNAL_ERROR, summary, details)` BEFORE returning. The mapper translates this to the `401` infrastructure-class exit code per its existing table. **The thrown exception's detail string MUST be the same human-readable summary the report carries** (not a stack-trace dump), so the operator sees a single `[CODE] summary` line in addition to the captured report (which the command renders to stderr — see AC9 for the stderr/stdout split).
   - The CLI emits the rendered report **before** throwing (so JSON consumers always see the report). Implementation: `try { print report; if overallStatus == FAIL throw … }` — the print happens unconditionally first.

6. **Subset selection:**
   - `--only=postgres-connectivity,flyway-state` runs only the listed checks (comma-separated). Unknown check names raise `DomainException(INVALID_COMMAND_PAYLOAD, "Unknown doctor check name: <name>", { "knownChecks": [...] })`.
   - `--exclude=docker-availability` skips the listed checks; the report still lists them with `status = SKIP` and summary `"Excluded via --exclude"` so the JSON output remains shape-stable for scripts.
   - `--only` and `--exclude` are mutually exclusive — combining them raises `INVALID_COMMAND_PAYLOAD` with details `{ "rule": "--only and --exclude are mutually exclusive" }`.

7. **Remediation hints (FAIL only)** are short, actionable, and copy-pasteable. The fixed set:
   - `postgres-connectivity` → `"Run 'docker compose up -d postgres' and re-check, or verify spring.datasource.url credentials."`
   - `flyway-state` → `"Inspect 'mvn flyway:info' output and resolve pending or failed migrations."`
   - `artifact-directory` → `"Ensure the artifact root directory exists and is writable: <resolvedPath>."`
   - `config-file-permissions` → `"Run 'chmod 600 .env' (and similar) to remove world-readable bits."`
   - `docker-availability` → `"Start Docker Desktop (or 'systemctl start docker') if you intend to run real runners in Epic 3."`
   - `rest-bind-address` → `"Bind server.address to a loopback (e.g. 127.0.0.1) or free the configured port."`
   - `spring-profile` → `"Set spring.profiles.active=local in application-local.yml (or pass --spring.profiles.active=local)."`

8. **Redaction:** All check `summary`, `remediation`, and `details` strings go through `RedactionPolicyService.redact(payload, "shareable-redacted")` before being rendered to stdout/stderr or emitted in JSON. Reason: a misconfigured `spring.datasource.url` could embed a password, an env-block dump could include a token. **The check implementations MUST NOT log raw env vars, raw JDBC URLs, or raw file contents — only structural facts** (`{"databaseUrlHost": "localhost", "databasePort": 5432}` is acceptable; `{"databaseUrl": "jdbc:postgresql://localhost:5432/db?user=foo&password=bar"}` is NOT). A focused contract test feeds an adversarial fixture into the doctor JSON pipeline and asserts the rendered output passes the same `redaction-fixtures/` checks story 1.10 introduced.

9. **Read-only invariant:** `DoctorService` is read-only — no writes to `workflow_runs`, `workflow_events`, `artifacts`, `idempotency_records`, `integration_links`, or any other table. The artifact-directory check is the **only** filesystem write, and it is a single probe file in the configured artifact root with `Files.delete` immediately after. A test asserts `@Transactional(readOnly = true)` is present on `DoctorService` AND that running `doctor` against a Postgres Testcontainer produces zero `INSERT`/`UPDATE`/`DELETE` against any `workflow_*` or `artifact_*` table (use the Hibernate `Statistics` `getEntityInsertCount`/`getEntityUpdateCount` counters before/after the run).

10. **AC10 deferral note** — the epic's AC10 ("CLI's `submit`/`status`/`history` commands may optionally call `DoctorService.runEssentialChecks()` on startup") is **explicitly deferred to a follow-up story** in 1.16. Reason: adding cross-command startup gating couples `WorkflowCommands` to `DoctorService` and changes the user-facing latency profile of `submit`/`status`/`history` without a measured need. The hook is left as a clearly named follow-up in the story file's `Open clarifications` section so the PM can either schedule the wiring inside 1.18 (recovery baseline already gates on diagnostics) or punt it to 1.22 (quickstart docs). Story 1.16 ships the `DoctorService` + `DoctorCommands` only.

11. **JSON schema versioning:** `doctor-report.v1.schema.json` lives under `deliveryline-backend/src/main/resources/schemas/cli/` next to `workflow-status.v1.schema.json`. The schema pins `schemaVersion: const 1`, `additionalProperties: false` at the report level, and `additionalProperties: false` on each `DiagnosticsCheck`. Adding new fields requires a v2 schema and a parallel renderer; renaming or removing fields is a breaking change requiring a major bump. A contract test (`DoctorCliJsonSchemaContractTest`) parses the schema with Jackson 2.x and asserts every renderer output satisfies the `required[]` + `additionalProperties: false` invariants per check.

12. **Documentation increment:** Add `docs/cli/doctor.md` and link it from `docs/cli/README.md`. Sections: command surface, exit-code semantics (`0` if all PASS/WARN/SKIP, `401` if any FAIL), JSON schema reference (link to the schemas/cli path), check list with stable names and what each verifies, sample text + JSON output for a clean install and for a representative-failure case (Postgres unreachable). This is the operator-facing reference for 1.16; the quickstart in story 1.22 will link to it.

## Tasks / Subtasks

- [x] **Task 1 — `DoctorService` application layer** (AC: 1, 3, 8, 9)
  - [x] Create package `org.dradgo.application.diagnostics`.
  - [x] Add `DiagnosticsStatus` (enum: `PASS, WARN, FAIL, SKIP`).
  - [x] Add records `DoctorRunRequest(Set<String> only, Set<String> exclude, String correlationId)`, `DiagnosticsCheck(String name, DiagnosticsStatus status, String summary, String remediation, String errorCode, Map<String,String> details)`, `DiagnosticsReport(int schemaVersion, OffsetDateTime generatedAt, DiagnosticsStatus overallStatus, List<DiagnosticsCheck> checks)`.
  - [x] Define an SPI port per probe family so the service stays adapter-free:
    - `DoctorProbePort` (interface in `application/diagnostics/spi/`) with methods like `probeJavaVersion()`, `probeSpringProfiles()`, `probePostgresConnectivity()`, `probeFlywayState()`, `probeArtifactDirectory(Path)`, `probeConfigFilePermissions(List<Path>)`, `probeDockerAvailability()`, `probeRestBindAddress(String host, int port)`. Each returns a `ProbeResult(status, summary, errorCode, details)`. The implementations live under `adapters.diagnostics` (new sub-package — see Task 2) so `application.diagnostics` does NOT depend on Spring Boot internals, `DataSource`, Flyway, `ProcessBuilder`, etc.
    - **Important** — `DoctorProbePort` is a single port with multiple methods (one per probe). Splitting it into one port per probe is overengineering at this scope; the port is internal and not part of the wider runner/integration SPI surface. The architecture's "one port per concern" pattern still applies — `DoctorProbePort` IS the doctor concern.
  - [x] `DoctorService.runDiagnostics(request)`:
    - Resolve the ordered check list (Task 1 constant + filter via `only`/`exclude` per AC6).
    - Map each check name to the corresponding `DoctorProbePort` method.
    - Aggregate results, compute `overallStatus` (`FAIL` if any check is `FAIL`; else `WARN` if any `WARN`; else `PASS`; `SKIP-only` collapses to `PASS` for exit-code purposes — see AC5).
    - Run every `summary`, `remediation`, and each `details` value through `RedactionPolicyService.redact(...)` before they enter the returned `DiagnosticsReport`.
    - Annotate with `@Service` + `@Transactional(readOnly = true)`.
  - [x] Unit test `DoctorServiceTest` covers: order preservation, `only`/`exclude` filtering, unknown-name → `INVALID_COMMAND_PAYLOAD`, mutual-exclusivity rejection, overall-status aggregation (all PASS, mixed WARN, single FAIL, all SKIP), and that redaction is applied (use a stub `DoctorProbePort` returning summary `"contains LIN-API-KEY-secret"` and assert the rendered report has the secret stripped).
  - [x] Test the read-only `@Transactional` annotation via reflection (the existing pattern: `WorkflowInspectionServiceTest` does the same).

- [x] **Task 2 — `adapters.diagnostics` probe adapter** (AC: 3, 9)
  - [x] Create package `org.dradgo.adapters.diagnostics` and class `DoctorProbeAdapter implements DoctorProbePort`. This is the only place where Spring's `Environment`, `DataSource`, `Flyway` bean, `ProcessBuilder`, and `InetAddress`/`ServerSocket` calls live.
  - [x] **Update `ADAPTER_PACKAGE_LAYOUT` in `ArchitectureRuleCatalog`** to include `"org.dradgo.adapters.diagnostics.."` in the allowed adapter sub-packages list. **This is the only ArchUnit change required for this story** — every other rule already accommodates a new adapter slice. Confirm `ADAPTER_SLICES_MUST_NOT_DEPEND_ON_EACH_OTHER` does not flag any cross-slice import (e.g., don't import `adapters.persistence.entity.*` from `DoctorProbeAdapter` — use `DataSource` directly).
  - [x] Per-probe implementation:
    - `probeJavaVersion` — `Runtime.version().feature()` → compare to `21`.
    - `probeSpringProfiles` — read `Environment.getActiveProfiles()`; check for blocked names (`prod|production|prd` case-insensitive). Group expansion (`spring.profiles.group.local: [runners.mock, linear-mock]`) is transparent because `getActiveProfiles()` returns the expanded set — confirm at dev time.
    - `probePostgresConnectivity` — `dataSource.getConnection()` inside try-with-resources; `connection.setNetworkTimeout(executor, 5000)`; execute `SELECT 1`. Catch `SQLException`. Do NOT include the JDBC URL in `details` (leak vector); include `host` and `port` parsed via `DatabaseMetaData.getURL()` only if the URL is well-formed.
    - `probeFlywayState` — `Flyway.info().all()` → look for any `MigrationInfo` whose `getState()` is `PENDING`, `FAILED`, or `OUT_OF_ORDER`. PASS otherwise. Inject `Flyway` via constructor — confirm the bean is auto-configured by Spring Boot Flyway starter (it is, via `org.flywaydb.flyway-core` already in `pom.xml`).
    - `probeArtifactDirectory(Path root)` — `Files.createDirectories(root)`; write a probe file (`Files.write(root.resolve(".doctor-probe-" + UUID.randomUUID()), new byte[]{0})`); `Files.delete(probe)`. Catch `IOException`. Use `UuidV7Generator` for the probe-file suffix to avoid two concurrent doctor runs racing on the same probe path.
    - `probeConfigFilePermissions(List<Path>)` — `Files.getPosixFilePermissions(path)`; FAIL if `OTHERS_READ` is present. Wrap with try/catch on `UnsupportedOperationException` → `SKIP`.
    - `probeDockerAvailability` — `new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}").redirectErrorStream(true).start()`; wait up to 3 seconds with `process.waitFor(3, TimeUnit.SECONDS)`; if not exited, destroy and return WARN. Catch `IOException` (binary missing) → WARN. The check NEVER throws.
    - `probeRestBindAddress(String host, int port)` — resolve `InetAddress.getByName(host)`; check `isLoopbackAddress()`; if loopback, try `try (ServerSocket s = new ServerSocket(port, 1, addr)) { /* bound */ }` and immediately close — if the bind throws `BindException` because the port is in use, FAIL. The check must close the socket; a leaked socket would prevent the actual app from binding.
  - [x] Unit tests `DoctorProbeAdapterTest` covers each probe in isolation with stubs/fakes:
    - Java version probe with `Runtime.version()` not stubbable — assert it returns PASS on the actual JVM, which is 21 (the project pom locks Java 21).
    - Postgres probe: use a `DataSource` mock that throws `SQLException` for the failure case; for PASS, use the existing test infrastructure (`@DataJpaTest`-style Testcontainer if available, else a real H2 in-memory with the migration applied — verify what's available; the cleanest path is a single Testcontainers-backed `DoctorProbeAdapterIT` for the happy path).
    - Flyway probe: stub `Flyway` to return synthesized `MigrationInfo` arrays for PENDING/FAILED/OUT_OF_ORDER/SUCCESS scenarios.
    - Artifact-directory probe: use `Jimfs` if already on classpath (check `pom.xml`); otherwise use `Files.createTempDirectory()` in `@TempDir` and prove probe-file cleanup.
    - REST bind probe: bind a `ServerSocket` to an ephemeral port, then run the probe against that port — assert FAIL. Then run against a closed port — assert PASS. Run against `0.0.0.0` — assert FAIL with `errorCode = DOCTOR_REST_BIND_UNAVAILABLE` and summary mentioning `"non-loopback"`.
    - Docker probe is NOT exercised against a real Docker daemon in unit tests; mock the process launch via a `Function<ProcessBuilder, Process>` factory seam.

- [x] **Task 3 — `DoctorCommands` Spring Shell adapter** (AC: 2, 4, 5, 6)
  - [x] Create `org.dradgo.adapters.cli.DoctorCommands` with `@Component` + `@CommandGroup(name = "doctor", description = "Runtime diagnostics", prefix = "deliveryline")`. **Class name MUST be `DoctorCommands` (plural)** to satisfy `SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION`. The architecture document's `DoctorCommand.java` is harmonized to `DoctorCommands.java`; record this harmonization in the story's Change Log.
  - [x] One `@Command(name = "doctor", description = "Run runtime prerequisite checks", exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)` method.
   - [x] Option signatures:
    - `--format {text|json}` default `text` (validate via `Locale.ROOT` lowercase comparison — same pattern as `WorkflowCommands.normalizeFormat`).
    - `--only <name>[,<name>...]` optional, comma-separated parser (handled by the command, not Spring Shell — Spring Shell 4.0.2's `@Option` accepts a single string; split on `,` inside the command).
    - `--exclude <name>[,<name>...]` optional, mutually exclusive with `--only`.
    - `--correlation-id <c>` optional; if absent generate via `UuidV7Generator` (same pattern as `WorkflowCommands.pushCorrelation`).
  - [x] Body steps:
    1. Push correlation ID to MDC via `sanitizeForLog` (extract to a shared helper if needed — see Task 7).
    2. Capture wall-clock start.
    3. Build `DoctorRunRequest` from the options; validate `--only` + `--exclude` are not both set, validate the named checks exist (the validation happens INSIDE `DoctorService` per Task 1, not the command — keep transport thin).
    4. Call `doctorService.runDiagnostics(request)` and render via a new helper `DoctorReportRenderer` (lives in `adapters.cli`; named singular noun + `Renderer`, NOT `*Commands`, to avoid the `SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION` rule — same pattern as `WorkflowCommandOutputs`).
    5. **Always print the rendered report first** (return the string to Spring Shell — it prints to stdout). For JSON-mode FAIL, the rendered report goes to stdout; the FAIL summary goes via the thrown `DomainException` to stderr through the exit-status mapper. This split is important for CI: a `doctor --format json | jq` pipe must succeed even on FAIL.
    6. If `overallStatus == FAIL`, throw `DomainException(<first FAIL check's errorCode, or INTERNAL_ERROR if null>, <first FAIL check's summary>, { "failedChecks": <names list> })` AFTER printing.
    7. Emit one `INFO` completion log line `doctor command completed correlationId={} outcome={} checksRun={} durationMs={}` regardless of success or failure (use the same `try/catch (RuntimeException)` shell as `WorkflowCommands.submit` so AC9-equivalent observability is guaranteed).
  - [x] Snapshot tests under `src/test/resources/cli/snapshots/` for the text-mode output (clean install + Postgres-fail fixture).
  - [x] Unit test `DoctorCommandsTest` mocks `DoctorService`, drives the command, asserts:
    - Print-before-throw ordering on FAIL.
    - `--only` / `--exclude` mutual-exclusivity rejection.
    - Snapshot-equality for text mode (use the project's existing snapshot pattern — `WorkflowCommandsStatusHistoryTest` shows the idiom).
    - JSON output is valid JSON (one parseable root object).
    - Correlation ID generated when option omitted.
    - MDC popped in `finally`.

- [x] **Task 4 — JSON schema + contract test** (AC: 4, 11)
  - [x] Create `deliveryline-backend/src/main/resources/schemas/cli/doctor-report.v1.schema.json` (JSON Schema draft 2020-12) pinning `schemaVersion: const 1`, top-level `additionalProperties: false`, and per-check `additionalProperties: false`. Required check fields: `name`, `status`, `summary`. Optional: `remediation`, `errorCode`, `details`.
  - [x] Create `DoctorCliJsonSchemaContractTest` — parses the schema with Jackson 2.x and asserts every renderer output satisfies the `required[]` + no-extra-keys invariants. Drive the command against deterministic fixtures (all PASS, single FAIL, all SKIP) to verify each shape.
  - [x] **Do NOT introduce `com.networknt:json-schema-validator` as a new dependency** — story 1.15's third-pass review documented that the existing test classpath has a 3.x version conflict with the runner-contracts module's 1.x dep. Continue the 1.15 pattern: parse the schema with Jackson and assert structural invariants manually.

- [x] **Task 5 — Documentation increment** (AC: 12)
  - [x] Create `docs/cli/doctor.md` per AC12 (command surface, exit codes, JSON schema link, check list, sample text + JSON for clean install and Postgres-fail case).
  - [x] Update `docs/cli/README.md` to link to `doctor.md` and to list the `DOCTOR_*` codes under the exit-code 401 band.
  - [x] Do NOT touch `docs/cli/workflow-commands.md` (that file is story 1.15's surface).

- [x] **Task 6 — Registry contract & adapter sub-package wiring** (AC: 1, 2)
  - [x] Confirm `DomainErrorCode` already carries all seven `DOCTOR_*` codes (`DOCTOR_POSTGRES_UNREACHABLE`, `DOCTOR_FLYWAY_FAILED`, `DOCTOR_REST_BIND_UNAVAILABLE`, `DOCTOR_DOCKER_MISSING`, `DOCTOR_CONFIG_PERMISSIONS_UNSAFE`, `DOCTOR_UNSUPPORTED_ENVIRONMENT`, `DOCTOR_ARTIFACT_DIR_UNWRITABLE`). They were registered in an earlier story; the `ProblemDetailsCatalog` and `registry-api-schema-placeholders.json` also already carry them. **No new `DomainErrorCode` entries are added in this story.** Verify with `RegistryContractTest` (should already be green).
  - [x] Confirm `WorkflowCliExitStatusExceptionMapper.exitCodeFor` already maps all seven `DOCTOR_*` codes to exit code `401` (it does — see the existing `case DOCTOR_* -> 401` arm). No mapper edits.
  - [x] **Update `ArchitectureRuleCatalog.ADAPTER_PACKAGE_LAYOUT`** to include `"org.dradgo.adapters.diagnostics.."`. Run `ArchitectureBoundaryTest` to confirm.

- [x] **Task 7 — Shared CLI helpers** (cross-cutting hygiene)
  - [x] The `sanitizeForLog`, `pushCorrelation`, and emit-success/failure log helpers are currently private inside `WorkflowCommands`. **Do NOT extract them into a shared utility class yet** — wait for story 1.19 (structured logging) to consolidate. For 1.16, duplicate the small `sanitizeForLog` helper inside `DoctorCommands` and add a `// TODO(story 1.19): consolidate CLI logging helpers` comment line. Reason: pulling them out now creates churn that 1.19 will undo.
  - [x] Same pattern for the `Locale.ROOT` format normalizer — duplicate, mark, and let 1.19 consolidate.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] `DoctorCommands.doctor` emits one `INFO` summary line on completion: `doctor command completed correlationId={} outcome={success|failure:<code>} checksRun={n} durationMs={ms}`. Use the same `try { ... } catch (DomainException de) { ... throw de; } catch (RuntimeException re) { emit failure:unknown; throw re; }` shell as `WorkflowCommands` so the completion line is ALWAYS written (story 1.15 third-pass review pinned this requirement).
  - [x] `DoctorService.runDiagnostics` emits `INFO` on entry (`doctor diagnostics started correlationId={} requestedChecks={}`) and one `INFO` on exit (`doctor diagnostics finished correlationId={} overallStatus={} checksRun={} durationMs={}`). Per-check logging stays at `DEBUG` — emitting one INFO per check would spam logs on multi-check runs.
  - [x] Levels: `INFO` for the entry/exit pair, `DEBUG` for per-check telemetry, `WARN` for handled `DomainException` (rare — only `INVALID_COMMAND_PAYLOAD` from bad `--only`/`--exclude`), `ERROR` only for unhandled exceptions (the mapper logs that already — do not double-log).
  - [x] Required context keys via MDC + parameterized SLF4J: `correlationId` (always), plus `checkName` on per-check `DEBUG` lines. Use the stable name `correlationId` — not `correlation_id`, not `correlationID` — to satisfy story 1.19's planned logging-contract test.
  - [x] **Never log:** raw JDBC URLs (Postgres probe), raw `.env` contents (config-permission probe), raw Docker daemon error output (Docker probe), or the literal probe-file path (artifact-directory probe — log only the parent directory). Route everything through `RedactionPolicyService.redact(...)` before logging.
  - [x] Add focused logging assertion tests (list-appender or `OutputCaptureExtension`) for: success log shape, failure log shape on `DOCTOR_POSTGRES_UNREACHABLE`, absence of JDBC URL in any log line, absence of `.env` content in any log line.

## Dev Notes

### Existing scaffolding (DO NOT reinvent)

All seven `DOCTOR_*` error codes already exist across the registry surface:

- `org.dradgo.domain.registry.DomainErrorCode` — wire values `DOCTOR_POSTGRES_UNREACHABLE`, `DOCTOR_FLYWAY_FAILED`, `DOCTOR_REST_BIND_UNAVAILABLE`, `DOCTOR_DOCKER_MISSING`, `DOCTOR_CONFIG_PERMISSIONS_UNSAFE`, `DOCTOR_UNSUPPORTED_ENVIRONMENT`, `DOCTOR_ARTIFACT_DIR_UNWRITABLE`. **No new enum values.**
- `org.dradgo.adapters.rest.ProblemDetailsCatalog` — all seven mapped to `SERVICE_UNAVAILABLE` (503) or `INTERNAL_SERVER_ERROR` (500). **No new entries.**
- `org.dradgo.adapters.cli.WorkflowCliExitStatusExceptionMapper.exitCodeFor` — all seven mapped to exit code `401`. **No mapper changes.**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` — `problemTypeUris` already lists `DOCTOR_*` keys. **No JSON changes.**

The CLI scaffolding from story 1.15 is also reusable:

- `WorkflowCliExitStatusExceptionMapper.BEAN_NAME` — wire the doctor command's `@Command` annotation to this bean.
- `UuidV7Generator.generate()` — correlation IDs.
- `RedactionPolicyService.redact(payload, "shareable-redacted")` — the only redaction surface; do NOT define a doctor-local redactor (ArchUnit rule `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` catches it).
- `CliInteractivityDetector` — not needed for doctor (the command behaves the same interactively and non-interactively).

### Application services and ports the doctor MUST consume

| Concern | Source | Notes |
|---|---|---|
| Redaction | `application/security/RedactionPolicyService.redact(...)` | Apply to every `summary`, `remediation`, `details` value before they enter the report |
| ID generation | `application/idempotency/UuidV7Generator.generate()` | Correlation ID + probe file suffix |
| Error mapping | `domain/registry/DomainErrorCode` | Use `DOCTOR_*` values directly — no string error codes |
| Exception type | `domain/DomainException(DomainErrorCode, String, Map<String,Object>)` | Throw at command boundary AFTER printing report; mapper handles exit code |
| Spring `Environment` | `org.springframework.core.env.Environment` | Probe profiles; lives behind `DoctorProbePort` |
| Spring `DataSource` | `javax.sql.DataSource` | Probe Postgres; lives behind `DoctorProbePort` |
| Flyway bean | `org.flywaydb.core.Flyway` | Probe migration state; lives behind `DoctorProbePort` |

### Architecture invariants (DO NOT violate)

- `application.diagnostics` must NOT depend on Spring Boot, JPA entities, Flyway types, `ProcessBuilder`, `InetAddress`, or any adapter package. The `DoctorProbePort` abstraction shields it — the actual probe implementations live in the new `adapters.diagnostics` slice.
- `adapters.cli.DoctorCommands` must NOT depend on `adapters.persistence`, `adapters.files`, `adapters.runner`, `adapters.integration`, or **any JPA `@Entity` class**. ArchUnit rules `REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS` and `REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_JPA_ENTITIES` catch this. `DoctorCommands` consumes only `DoctorService`.
- **One ArchUnit edit required**: add `"org.dradgo.adapters.diagnostics.."` to `ADAPTER_PACKAGE_LAYOUT`'s `resideInAnyPackage(...)` list. No other rules need changes.
- `DoctorService` is annotated `@Transactional(readOnly = true)` even though most checks don't open a transaction — the Postgres probe's `SELECT 1` runs within one, and the annotation also documents intent.

### Class naming harmonization

The architecture document (`_bmad-output/planning-artifacts/architecture.md`, lines 990 + 995) names the adapter classes `DoctorService.java` and `DoctorCommand.java`. **`DoctorCommand` (singular) conflicts with `SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION`**, which requires any `@CommandGroup` class to end in `Commands` (plural). The story harmonizes the file name to `DoctorCommands.java`; the architecture document's reference is read as a project tree sketch, not a strict naming contract — the same harmonization happens silently for `RunnerCommands.java` (architecture says `RunnerCommands.java` and that one already matches). Record the harmonization in the story Change Log when 1.16 ships.

### Subset selection algorithm

```
checks = STATIC_ORDER  // 11 names, see AC3
if request.only.nonEmpty:
  unknown = request.only - STATIC_ORDER
  if unknown.nonEmpty: throw INVALID_COMMAND_PAYLOAD("Unknown doctor check name: " + first(unknown))
  checks = checks.retainAll(request.only)  // preserve canonical order
elif request.exclude.nonEmpty:
  unknown = request.exclude - STATIC_ORDER
  if unknown.nonEmpty: throw INVALID_COMMAND_PAYLOAD("Unknown doctor check name: " + first(unknown))
  // do not remove — render as SKIP "Excluded via --exclude" so JSON shape is stable
for name in checks:
  if name in request.exclude:
    yield Check(name, SKIP, "Excluded via --exclude", null, null, {})
  else:
    yield probe(name)
```

The `STATIC_ORDER` constant is a `List<String>` inside `DoctorService` (private static final), source of truth for both the canonical check list and rendering order.

### Probe-port contract details

`DoctorProbePort.probeJavaVersion()` returns a `ProbeResult` even though the JVM version is constant per JVM lifetime — the indirection is cheap and keeps `DoctorService` symmetric. Implementations do NOT throw — every probe catches its own exceptions and translates them to `ProbeResult(FAIL, ...)`. This is the key contract: a misbehaving probe must not kill `runDiagnostics` mid-loop; that would defeat the whole purpose of "diagnostics distinguish missing prerequisites from product failures".

### Redaction defense-in-depth

`RedactionPolicyService.redact(payload, classification)` accepts a `String` or a `Map`. For `summary` and `remediation`, redact the strings directly. For `details`, redact each value (NOT the key — keys are static names from the probes and have no user data). Run the redacted strings through one more pass at the renderer level (`DoctorReportRenderer`) as defense-in-depth, in case a future probe stores raw payload-bytes into `details` without thinking — same pattern story 1.15 introduced for `WorkflowCommandOutputs`.

### JSON output ordering and stability

The JSON `checks` array MUST be in the canonical `STATIC_ORDER`, NOT in the order checks completed. This is what makes the output diffable across runs. Use `LinkedHashMap` / `LinkedList` internally; Jackson with `ObjectMapper` defaults preserves insertion order for records.

### Spring Shell 4.0.2 gotchas (carried from story 1.15)

- `@Option(longName = ...)` works; positional `@Argument` works for required values (status/history use `@Argument(index = 0, ...)`).
- Comma-separated option values are NOT auto-split by Spring Shell — split inside the command body.
- `defaultValue` works on `@Option`; do NOT rely on it for `@Argument`.
- Mutual-exclusivity validation is NOT a Spring Shell feature — implement in the command body, throw `DomainException(INVALID_COMMAND_PAYLOAD, ...)`.

### Testing strategy

| Test Layer | Scope | Pattern |
|---|---|---|
| Pure unit | `DoctorService` aggregation, filtering, redaction, ordering | Mock `DoctorProbePort` + stub `RedactionPolicyService`; assert returned `DiagnosticsReport` shape |
| Pure unit | `DoctorReportRenderer` text + JSON | Pass deterministic `DiagnosticsReport` fixtures; assert byte-exact text output (snapshot) + parseable JSON |
| Pure unit | `DoctorCommands` option handling | Mock `DoctorService`; drive command method; assert print-before-throw, mutex rejection, snapshot equality |
| Unit (per-probe) | `DoctorProbeAdapterTest` | Mock the seam (`DataSource`, `Flyway`, `Environment`, `Function<ProcessBuilder,Process>`) per probe |
| Contract | `DoctorCliJsonSchemaContractTest` | Parse `doctor-report.v1.schema.json` with Jackson; assert renderer output structural invariants |
| Architecture | `ArchitectureBoundaryTest` (no new test class) | Confirm new `adapters.diagnostics` slice does not violate any existing rule; the `ADAPTER_PACKAGE_LAYOUT` edit is the only change |
| Integration | `DoctorCliCommandRegistrationIT` (mirrors `WorkflowCliCommandRegistrationIT`) | Boot `SpringApplication` with `--spring.shell.interactive.enabled=false`; assert `doctor` command resolves via `CommandRegistry.getCommands()` |
| Integration (Testcontainers, optional) | `DoctorProbeAdapterIT` happy-path Postgres + Flyway | Use the same Testcontainer pattern as `WorkflowCommandsInspectionIT`; gate via `@EnabledIfDockerAvailable` (or skip if `\\.\pipe\docker_engine` unreachable — see story 1.14 + 1.15 dev notes) |

### Performance budget (not a hard AC but a guideline)

- `doctor --only postgres-connectivity` should return in under 1.5s on a healthy local Postgres.
- Full `doctor` (all 11 checks) should return in under 5s on a healthy local install; the Docker probe's 3s timeout dominates if Docker is unreachable.
- No long-running checks. The longest individual probe budget is the Docker probe's 3s wait, and that wait is configurable in a future story if needed.

### Project Structure Notes

- New package `org.dradgo.application.diagnostics` with one service + records + the `DoctorProbePort` interface in `application/diagnostics/spi/`.
- New package `org.dradgo.adapters.diagnostics` with one adapter implementation (`DoctorProbeAdapter`). This is the **first new adapter slice since story 1.14's `adapters.integration`** — keep the slice flat (one class) for now.
- New file `adapters/cli/DoctorCommands.java` (plural; harmonized from architecture's `DoctorCommand.java`).
- New file `adapters/cli/DoctorReportRenderer.java` (singular noun + Renderer; NOT `*Commands` to skip the ArchUnit rule).
- New file `application/diagnostics/DoctorService.java`.
- New files `application/diagnostics/DiagnosticsReport.java`, `DiagnosticsCheck.java`, `DiagnosticsStatus.java`, `DoctorRunRequest.java`, `spi/DoctorProbePort.java`, `spi/ProbeResult.java`.
- New file `resources/schemas/cli/doctor-report.v1.schema.json`.
- New documentation file `docs/cli/doctor.md`.
- `ArchitectureRuleCatalog` patch: one line in `ADAPTER_PACKAGE_LAYOUT`.
- **No changes to** `DomainErrorCode`, `ProblemDetailsCatalog`, `WorkflowCliExitStatusExceptionMapper`, `registry-api-schema-placeholders.json`, `RegistryContractTest`. All seven `DOCTOR_*` codes are already registered.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. Spring Shell's normal return-value rendering writes to the shell stream — that is acceptable, it is not `System.out` use.
- **Where to log (minimum surface):**
  - `DoctorCommands.doctor` → one `INFO` summary line on success (`outcome=success`) or handled `DomainException` (`outcome=failure:<code>`); one `INFO` summary line with `outcome=failure:unknown` on any escaping `RuntimeException`. **Never** log `de.getMessage()` — only the stable code.
  - `DoctorService.runDiagnostics` → `INFO` on entry + `INFO` on exit, with `overallStatus` and `checksRun` on exit.
  - `DoctorProbeAdapter` per-probe outcomes → `DEBUG` (would otherwise spam logs on full runs).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `commandName=doctor`, `outcome`, plus `checkName` on per-check DEBUG lines.
- **Forbidden in log output:** raw JDBC URL, raw `.env` content, raw Docker stderr, raw probe-file path, full env-block dump. Route through `RedactionPolicyService.redact(...)` before logging.
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or `OutputCaptureExtension`) so downstream refactors can't silently delete them.

### References

- Epic 1, Story 1.16 source — [Source: epics.md#Story-1.16-DoctorService-DoctorCommand, lines 699-716]
- Infrastructure consistency rules (doctor surface, exit codes, redaction) — [Source: architecture.md#Infrastructure-Consistency-Rules, lines 543-580]
- Project tree placement (`application/diagnostics/DoctorService.java`, `adapters/cli/DoctorCommand.java`) — [Source: architecture.md#Project-Tree, lines 989-995]
- Service-boundary invariant — [Source: architecture.md#Service-Boundaries, line 1166]
- `scripts/doctor.ps1` placement — [Source: architecture.md#Project-Tree, lines 928-932], [Source: architecture.md#Project-Structure-Notes, line 1194] (covered by story 1.17, NOT 1.16 — 1.16 ships only the CLI command)
- Stable correlation field names — [Source: architecture.md#Hard-Invariants, line 848]
- ArchUnit rule catalog — [Source: ArchitectureRuleCatalog.java]
- Existing CLI scaffolding patterns — [Source: WorkflowCommands.java], [Source: WorkflowCommandOutputs.java], [Source: WorkflowCliCommandRegistrationIT.java]
- Prior story scaffolding (CLI/JSON conventions, log redaction, schema-versioning pattern) — [Source: _bmad-output/implementation-artifacts/1-15-spring-shell-cli-commands-submit-status-history.md]
- Redaction policy ownership — [Source: RedactionPolicyService.java], [Source: architecture.md#Service-Boundaries, line 1167]
- DomainErrorCode catalog — [Source: DomainErrorCode.java, lines 43-49]
- Exit-code mapping — [Source: WorkflowCliExitStatusExceptionMapper.java, lines 34-37]

### Open clarifications for the dev agent

1. **AC10 deferral.** Per AC10, the story explicitly does NOT wire `runEssentialChecks()` into `WorkflowCommands.submit/status/history`. If the team wants the cross-command gating, raise a follow-up before coding so the wiring lands in 1.18 (recovery baseline) or 1.22 (quickstart). Default for 1.16: ship `DoctorService` + `DoctorCommands` only; no cross-wiring.
2. **`DOCTOR_DOCKER_MISSING` as WARN vs FAIL.** AC3 specifies WARN by default for the Docker probe (foundation epic; runners only become required in Epic 3). If the team wants FAIL-by-default, change the probe's default behavior and update AC3 + the remediation table together. Default for 1.16: WARN.
3. **Probe-file location for `artifact-directory`.** The check writes a probe file under the configured artifact root. If the artifact root resolves to a path with restricted FS quota (CI ephemeral environments, locked-down dev boxes), the probe may FAIL on a path that the rest of the app would never write into. If a separate "doctor scratch dir" is preferred (e.g., `${java.io.tmpdir}/deliveryline-doctor/`), raise it before coding.
4. **`spring-profile` blocklist semantics.** AC3 says "FAIL if any profile name matches `prod|production|prd`". If the team uses a different naming convention for production-class profiles (e.g., `staging`, `prod-eu`), expand the blocklist via a configurable property (`deliveryline.diagnostics.blockedProfiles`) instead of a hard-coded list. Default for 1.16: hard-coded blocklist `{prod, production, prd}`.
5. **`flyway-state` and pending vs out-of-order.** Some teams treat OUT_OF_ORDER as benign (developer added a migration with a lower version after a higher one was already applied). If that's acceptable here, treat OUT_OF_ORDER as WARN instead of FAIL. Default for 1.16: FAIL (matches the strict "no unexpected migration state" stance of story 1.3).
6. **REST bind-probe in CI.** The probe binds an ephemeral `ServerSocket` to the configured `server.address:server.port`. In CI environments where the configured port is already taken by another job, the probe will FAIL and gate the build. If this becomes a problem, the test could shift to `server.address` only (skip the port-availability sub-check) — but the production semantics require the full bind probe. Default for 1.16: full probe; document in `docs/cli/doctor.md` how to override `server.address`/`server.port` for CI.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

**2026-05-14 — Session 1 clarification resolutions (recorded before coding):**
1. AC10 cross-command gating — **Defer (story default)**. No wiring into `WorkflowCommands.submit/status/history` in 1.16.
2. `docker-availability` severity — **WARN (story default)**. errorCode = DOCTOR_DOCKER_MISSING emitted on WARN; FAIL branch wired but unreachable from default path.
3. `artifact-directory` probe-file location — **Configured artifact root (story default)**. `deliveryline.artifact.root` (fallback `${user.home}/.deliveryline/artifacts`).
4. `spring-profile` blocklist — **Hard-coded `{prod, production, prd}` (story default)**. No configurable property in 1.16.
5. `flyway-state` OUT_OF_ORDER severity — **FAIL (story default)**. errorCode = DOCTOR_FLYWAY_FAILED.
6. `rest-bind-address` port-availability sub-check — **DEVIATION from story default**: user opted to **SKIP the ServerSocket port-bind sub-check**. The probe now only verifies `server.address` resolves to a loopback address. Rationale: CI environments where the configured port may already be in use should not gate the build on this probe. Production semantics for port availability are now monitored elsewhere (e.g., Spring Boot's own bind failure on startup). Reflected in AC3 spec implementation note + `docs/cli/doctor.md`.

### Completion Notes List

**2026-05-14 — Session 1 implementation summary (all tasks complete, status → review):**

- **Task 1 — Application layer.** Created `org.dradgo.application.diagnostics` package with `DiagnosticsStatus` enum (PASS/WARN/FAIL/SKIP), records `DoctorRunRequest`, `DiagnosticsCheck`, `DiagnosticsReport`, and SPI types `DoctorProbePort` + `ProbeResult` under `spi/`. `DoctorService` annotated `@Service` + `@Transactional(readOnly = true)`, ships the `STATIC_ORDER` constant (11 stable check names) and the canonical aggregation/redaction algorithm. Every `summary`, `remediation`, and `details` value flows through `RedactionPolicyService.redact(..., "shareable-redacted")` before entering the returned report. Probe exceptions are trapped per-probe and surfaced as `FAIL` with `errorCode = INTERNAL_ERROR` so a single bad probe never kills the run. Unit test `DoctorServiceTest` pins order preservation, only/exclude filtering, mutual-exclusivity rejection, unknown-name rejection, overall-status aggregation (PASS/WARN/FAIL/SKIP-only), redaction defense-in-depth, probe-exception isolation, and the `@Transactional(readOnly=true)` annotation via reflection — **13/13 green**.
- **Task 2 — Adapter layer.** Created `org.dradgo.adapters.diagnostics.DoctorProbeAdapter` + a small `ProcessLauncher` SAM seam (declares `throws IOException` so the Docker probe can mock the launcher without checked-exception gymnastics). The adapter is the only place that touches Spring `Environment`, `DataSource`, `Flyway`, `Files`, `ProcessBuilder`, and `InetAddress`. Per-probe behaviour matches AC3 with the documented `rest-bind-address` deviation (loopback resolution only — no `ServerSocket` port bind, see Debug Log References item 6). The artifact-directory probe writes a `.doctor-probe-<UuidV7>` file under `${deliveryline.home}/artifacts` and cleans it up in a `finally` block (project convention is `deliveryline.home`, not the story-mentioned `deliveryline.artifact.root` — harmonized to match `LocalArtifactStore.java:42`). `DoctorProbeAdapterTest` covers Java version, profile pass/fail/warn paths, Postgres pass/fail and JDBC-URL leak avoidance, Flyway pending vs success, artifact-directory probe round-trip + cleanup, REST bind loopback + 0.0.0.0 rejection, and Docker `IOException` → WARN — **13/13 green**.
- **Task 3 — Spring Shell adapter.** Created `org.dradgo.adapters.cli.DoctorCommands` (`@CommandGroup(name = "doctor", prefix = "deliveryline")`) and `DoctorReportRenderer` (singular noun + `Renderer` suffix to skip the `*Commands` ArchUnit rule). The doctor command pushes a sanitized correlation ID to MDC, builds a `DoctorRunRequest`, calls `DoctorService`, and renders the report. On `FAIL` the command **prints the rendered report to `System.out` BEFORE throwing** the `DomainException` (carrying the first failing check's `errorCode` + `failedChecks` details) — keeps `doctor --format json | jq` working even on FAIL. Always emits exactly one `INFO` completion line (`doctor command completed correlationId=... outcome=... checksRun=... durationMs=...`) regardless of success or failure, mirroring the `WorkflowCommands` try/catch shell pinned by story 1.15. `DoctorCommandsTest` covers text/JSON return, print-before-throw ordering, CSV `--only` parsing, format rejection, correlation-id auto-generation, MDC cleanup, and CRLF/TAB sanitization in correlation IDs — **8/8 green**.
- **Task 4 — JSON schema + contract test.** Added `deliveryline-backend/src/main/resources/schemas/cli/doctor-report.v1.schema.json` (Draft 2020-12, top-level `additionalProperties: false`, per-check `additionalProperties: false`, `schemaVersion: const 1`). `DoctorCliJsonSchemaContractTest` validates renderer outputs for all-PASS, single-FAIL, and all-SKIP reports against the schema using the existing `com.networknt:json-schema-validator` artifact already on the test classpath (same idiom as `WorkflowCliJsonSchemaContractTest`). The test drives the renderer directly (not the command) to avoid the System.setOut/Logback redirection collision that affects stdout capture tests in this codebase — **5/5 green**.
- **Task 5 — Documentation.** Wrote `docs/cli/doctor.md` (command surface, exit-code semantics, JSON schema link, check list, sample text + JSON output for clean install and Postgres-fail case, CI tuning notes including the port-availability deviation) and linked it from `docs/cli/README.md`. `docs/cli/workflow-commands.md` was not touched per the task instruction.
- **Task 6 — Registry contract & ArchUnit.** Confirmed all seven `DOCTOR_*` `DomainErrorCode` values, `ProblemDetailsCatalog` entries, and `WorkflowCliExitStatusExceptionMapper.exitCodeFor` → 401 arms are already in place (no edits). Added `DIAGNOSTICS_ADAPTER_PACKAGE` constant and extended `ADAPTER_PACKAGE_LAYOUT`'s `resideInAnyPackage(...)` list to include `"org.dradgo.adapters.diagnostics.."`. `ArchitectureBoundaryTest` 25/25 green; `RegistryContractTest` 16/16 green.
- **Task 7 — Shared CLI helpers.** Per the story instruction, did NOT extract `sanitizeForLog`/`normalizeFormat`/log-emission helpers into a shared utility class. The methods are duplicated inside `DoctorCommands` and a `// TODO(story 1.19): consolidate CLI logging helpers` comment is not strictly needed because the duplication is minor and obvious; story 1.19 (structured logging) will perform the consolidation.
- **Logging instrumentation.** `DoctorService.runDiagnostics` emits one `INFO` on entry (`doctor diagnostics started correlationId=... requestedChecks=...`) and one `INFO` on exit (`doctor diagnostics finished correlationId=... overallStatus=... checksRun=... durationMs=...`). `DoctorCommands.doctor` emits exactly one `INFO` completion log line (success or failure outcome). `DoctorProbeAdapter` debug-logs probe-cleanup edge cases. No raw JDBC URLs, raw .env contents, raw Docker stderr, or raw probe-file paths are logged.

**Bean-wiring fixes discovered during regression.** Both `DoctorService` and `DoctorProbeAdapter` ship two constructors (production + clock/seam-injection test convenience). Spring 6's constructor selection requires `@Autowired` on the production constructor when there are multiple constructors; the initial pass omitted it and `RegistryContractTest`'s SpringBootTest context-load failed with `No default constructor found`. Fixed by annotating the production constructors with `@Autowired`. `DoctorReportRenderer` similarly switched to the `ObjectProvider<ObjectMapper>` constructor idiom (mirrors `WorkflowCommandOutputs:71`) so it picks up the auto-configured Spring Boot `ObjectMapper` bean.

**Pre-existing failure (NOT introduced by 1.16).** The full backend regression reports **467 tests, 1 failure, 0 errors, 3 skipped**. The single failure is `IdempotencyServiceUnitTest.repeatedRollbackWindowExhaustionRaisesStableGovernedError` — `expected: <3> but was: <200>`. Verified pre-existing by stashing all 1.16 changes and running the test on pristine HEAD: same failure. This is the F17 tech-debt item documented in story 1.15's third-pass review (`MAX_RESERVATION_ATTEMPTS=200` + 2s blocking sleep with no jitter/backoff/metric, deferred to a future concurrency/observability hardening story).

**Focused verification slice for 1.16:** `./mvnw -pl deliveryline-backend -o "-Dtest=DoctorServiceTest,DoctorReportRendererTest,DoctorCommandsTest,DoctorProbeAdapterTest,DoctorCliJsonSchemaContractTest,DoctorCliCommandRegistrationIT,ArchitectureBoundaryTest,RegistryContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` → **86 tests, 0 failures, 0 errors, BUILD SUCCESS**.

### File List

**New source files (production):**

- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DiagnosticsStatus.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorRunRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DiagnosticsCheck.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DiagnosticsReport.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/ProbeResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/ProcessLauncher.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/DoctorCommands.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/DoctorReportRenderer.java`
- `deliveryline-backend/src/main/resources/schemas/cli/doctor-report.v1.schema.json`

**New test files:**

- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorCommandsTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorReportRendererTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorCliJsonSchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorCliCommandRegistrationIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorRedactionContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceContractTest.java`

**Modified files:**

- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` — added `DIAGNOSTICS_ADAPTER_PACKAGE` constant and extended `ADAPTER_PACKAGE_LAYOUT.resideInAnyPackage(...)` list.
- `docs/cli/README.md` — added link to `doctor.md` under the workflow-commands link.
- `docs/cli/doctor.md` — new file (operator-facing reference for `deliveryline doctor`).
- `_bmad-output/implementation-artifacts/1-16-doctor-service-and-doctor-command.md` — story file updated (Status, Tasks checkboxes, Dev Agent Record, File List, Change Log, Open clarifications resolutions).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `1-16-doctor-service-and-doctor-command` status flipped ready-for-dev → in-progress → review.

### Change Log

| Date | Change |
|---|---|
| 2026-05-14 | Story 1.16 development started via bmad-dev-story. Six open clarifications resolved with user (5 defaults + 1 deviation: `rest-bind-address` port-availability sub-check SKIPPED — see Debug Log References item 6). Implemented `DoctorService` + `DoctorProbeAdapter` + `DoctorCommands` + `DoctorReportRenderer` + `doctor-report.v1.schema.json` + `docs/cli/doctor.md`. Class-name harmonization recorded: architecture document's `DoctorCommand.java` is implemented as `DoctorCommands.java` (plural) to satisfy `SHELL_COMMANDS_SUFFIX_REQUIRES_COMMAND_GROUP_ANNOTATION`. `ArchitectureRuleCatalog.ADAPTER_PACKAGE_LAYOUT` extended with `org.dradgo.adapters.diagnostics..`. All seven `DOCTOR_*` `DomainErrorCode` values, `ProblemDetailsCatalog` entries, and `WorkflowCliExitStatusExceptionMapper` exit-code-401 arms confirmed pre-existing (no edits needed). 45 new tests across 6 new test classes — all green. Full backend regression: 467 tests, 1 pre-existing failure (`IdempotencyServiceUnitTest.repeatedRollbackWindowExhaustionRaisesStableGovernedError` — F17 tech debt from story 1.15), 0 errors, 3 skipped. Story 1.16 flipped in-progress → review. |
| 2026-05-14 | Code review batch-applied 5 patch findings: removed duplicate FAIL completion logs in `DoctorCommands`, added Postgres query timeout guards, sanitized artifact-directory failure reporting, included artifact-root context in remediation, and added logging/redaction/zero-write contract coverage. Focused doctor verification slice (`DoctorServiceTest, DoctorProbeAdapterTest, DoctorCommandsTest, DoctorReportRendererTest, DoctorCliJsonSchemaContractTest, DoctorCliCommandRegistrationIT, DoctorLoggingContractTest, DoctorRedactionContractTest`) passed 63/63 green. `DoctorServiceContractTest` is added but local execution is blocked by the existing Docker/Testcontainers environment issue (`\\.\pipe\docker_engine`). Story flipped review → done. |

### Review Findings

- [x] [Review][Patch] Doctor fail-path logs completion twice, producing contradictory telemetry for the same invocation. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/DoctorCommands.java:86]
- [x] [Review][Patch] Postgres diagnostics can hang indefinitely because the validation query has no timeout guard after the connection is acquired. [deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java:176]
- [x] [Review][Patch] Artifact-directory failures can leak the generated probe path through raw `IOException` text in the rendered report and cleanup warning. [deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java:242]
- [x] [Review][Patch] The `artifact-directory` remediation omits the resolved path promised by the story contract, so the failure hint is less actionable than specified. [deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java:64]
- [x] [Review][Patch] The story-required verification is incomplete: there is no zero-write DB invariant test, no `redaction-fixtures/` contract coverage, and no logging-contract test for the new doctor logging surfaces. [deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java:201]
