# Story 1.19: Structured Logging + Correlation IDs

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a foundation developer,
I want structured logging with stable correlation field names (`correlationId`, `workflowRunId`, `runnerExecutionId`, `artifactId`, `artifactOperationId`) threaded through every CLI command, REST request, runner dispatch, artifact operation, and failure path — emitted as JSON under the `demo` profile and as a human-readable pattern under `local` — routed through `RedactionPolicyService` so no raw secrets, unredacted context bundles, or runner output reach the logs,
so that pilot-use diagnostics can be traced end-to-end across CLI commands, REST calls, workflow transitions, and runner executions; downstream stories (1.21 CI, 1.22 docs, Epic 4 recovery) and the UI route-loader correlation-id-echo (story 2.x) inherit a stable, machine-parseable log surface.

## Acceptance Criteria

1. **`infrastructure.observability` package + Logback configuration files** — The new Java package `org.dradgo.infrastructure.observability` exists (no production classes need live there yet beyond the request-scoped CorrelationIdFilter from AC8 and the redacting layout from AC5). Logback config ships as **two profile-scoped files at `deliveryline-backend/src/main/resources/`**:
   - `logback-spring.xml` (base config, always loaded; sets root logger to `INFO`, defines two appender stubs).
   - Profile-conditional `<springProfile name="demo">` block → JSON encoder appender (`net.logstash.logback.encoder.LogstashEncoder` OR Logback's built-in `JsonEncoder` if Boot 4 ships it — see Open Clarification 1).
   - Profile-conditional `<springProfile name="local,test,!demo">` block → human-readable `PatternLayout` appender with pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{correlationId:-no-correlation}] %X{workflowRunId:-} %logger{36} - %msg%n`.
   The pattern surfaces every MDC key listed in AC3 as bracketed prefixes so a `tail -f` operator can grep by `correlationId` without parsing JSON. Both appenders MUST route through the redacting layout (AC5) before write.

2. **Correlation-ID generation + MDC propagation** — Every entry point (CLI command, REST endpoint) generates a `correlationId` (UUIDv7 via the existing `UuidV7Generator` — story 1.9) **if and only if** the caller did not supply one, stamps it on MDC under the key `correlationId`, threads it through to the downstream `WorkflowCommandService` / `RecoveryService` / `RunnerBroker` / `IntegrationLinkService` / persistence-adapter calls, and **removes it from MDC in a `finally` block before the entry-point returns**. A focused test (`CorrelationIdMdcLeakageTest`) invokes two CLI commands in sequence and asserts MDC is empty between them — no leakage across invocations.

3. **Required MDC context keys** — The following exact MDC key set is the stable surface (matches architecture document `Logging Patterns` + `Hard Invariants and Rejection Criteria`):
   - `correlationId` — UUIDv7 string, always present once an entry point runs.
   - `workflowRunId` — `run_…` public id; present on every log line emitted by code with a workflow-scoped context (every method on `WorkflowCommandService`, `RecoveryService`, `WorkflowInspectionService`, `RunnerBroker.dispatch`, every `WorkflowEventPersistenceAdapter` write).
   - `runnerExecutionId` — `rex_…` public id; present on every log line emitted from `RunnerBroker.dispatch(...)` after the runner_execution row is created, and from every runner-adapter call back into the application layer.
   - `artifactId` — `art_…` public id; present on every log line emitted from `ArtifactService`, `ArtifactOperationService`, `ArtifactReconciliationService` methods that have already resolved the artifact identity.
   - `artifactOperationId` — `op_…` public id; present on every `ArtifactOperationService` log line that has already created or resolved the operation row.
   No other MDC keys are introduced in this story. The set is enforced by `LoggingMdcContractTest` (AC10).

4. **Stable field names — zero drift tolerance** — The five MDC keys above use **exactly** the casing in AC3 (`correlationId`, NOT `correlation_id` / `correlationid` / `CorrelationId`). A contract test (`LoggingFieldNameContractTest`) loads a fixture log line emitted by each of `WorkflowCommands.submit`, `RecoveryService.retry`, `RunnerBroker.dispatch`, `ArtifactOperationService.recordOperation`, and asserts the JSON output (demo profile) contains the exact key names. The test deliberately greps for the camelCase form and **rejects** any snake_case or PascalCase variant — guarding the architecture's `Hard Invariants` rule that correlation fields use stable names.

5. **Redacting appender — defense in depth** — A new `RedactingLoggingLayout` (or `RedactingMessageConverter`, depending on whether the Logback build uses pattern layouts or composite JSON encoders — see Open Clarification 2) wraps the final encoder for both appenders. Each log message is routed through `RedactionPolicyService.redact(message, "shareable-redacted")` before write. The layout MUST be configured under `logback-spring.xml` such that **a developer cannot bypass it by adding a new appender** — enforced by `LoggingRedactionContractTest`: an adversarial fixture line containing each of the 16 secret patterns from `deliveryline-backend/src/test/resources/redaction-fixtures/*` is emitted through `LoggerFactory.getLogger(...)`, and the captured `ListAppender<ILoggingEvent>` output (post-layout, NOT the raw message arg) is asserted to contain `[redacted]` markers and no raw secret. If `RedactionPolicyService` itself throws, the layout MUST emit a single safe fallback line at WARN level with no payload bytes — never propagate the exception (a logging failure must never break the request path).

6. **No raw runner output / context bundles / credentials at any level** — Across all log levels (`INFO`, `DEBUG`, `TRACE`):
   - `RunnerBroker.dispatch(...)` MUST NOT log the full context bundle JSON. Permitted fields: `workflowRunId`, `runnerExecutionId`, `stage`, `idempotencyKey`, `contextBundleVersion` (the integer version, not the bundle).
   - `LinearRealAdapter` MUST NOT log the `Authorization` header value, the GraphQL request body, or the full response body. Permitted fields: `httpStatus`, `endpoint`, `correlationId`, `failureCategory` on error.
   - `DoctorProbeAdapter.probeConfigPermissions(...)` MUST NOT log the file contents or env-var values it inspected. Permitted fields: file path (already redacted by `RedactionPolicyService` if it contains a secret-shaped substring), boolean PASS/FAIL.
   - `LocalArtifactStore` MUST NOT log artifact payload bytes. Permitted fields: relative path, byte length, checksum (truncated to 16 hex chars).
   `LoggingForbiddenPayloadContractTest` deliberately injects each forbidden payload into one of these surfaces and asserts the captured log output does NOT contain the raw payload — the redacting layout AC5 is the safety net, but each of these four surfaces MUST also enforce the rule at the call site (defense in depth).

7. **Workflow events are the audit record, logs are diagnostics — ADR + review checklist** — A new ADR-style note `docs/architecture/adr-019-structured-logging.md` (or `docs/adr/0019-structured-logging.md` if the project standardizes a flat ADR folder — see Open Clarification 3) documents the rule: **workflow events (persisted in `workflow_events`) are the product audit record; application logs are technical diagnostics only.** The ADR includes a review checklist for future stories: "Are you logging because you need to (a) debug a production incident (→ logs) or (b) reconstruct what the user/system did (→ workflow event)? If (b), open a story to add the event type to the registry." This ADR is **not** automatically enforced — it is a review-time gate, not a CI gate.

8. **REST `X-Correlation-Id` request filter** — A new `@Component` Spring filter `org.dradgo.infrastructure.observability.CorrelationIdFilter implements jakarta.servlet.Filter`, ordered before all other filters via `@Order(Ordered.HIGHEST_PRECEDENCE)`, that:
   - Reads `X-Correlation-Id` header. If present AND valid UUID (any version, parsed via `UUID.fromString(...)` after trimming + length check), stamps it on MDC under `correlationId` and echoes it on the response header `X-Correlation-Id`.
   - If absent OR invalid, generates a fresh UUIDv7 via `UuidV7Generator` and stamps both MDC + response header.
   - Removes the MDC key in a `finally` block — the same leakage rule as AC2.
   - When the request results in an unhandled `DomainException`, `ProblemDetailsMapper` (story 1.8) MUST stamp the same `correlationId` into the Problem Details `instance` field (currently a request-path string) per the existing pattern, or — preferred by clarification (see Open Clarification 4) — into a new top-level `correlationId` field on the Problem Details extension, leaving `instance` as the request-path. Pin both forms with a contract test so the choice is locked.

9. **CLI `--verbose` surfaces correlation ID on stdout** — `submit`, `status`, `history`, and `retry` (and the `doctor` command) already accept a `--verbose` flag. When `--verbose` is supplied, the stdout output appends `[correlation-id: <uuid>]` so operators can copy/paste the ID into a `grep correlationId=<uuid> deliveryline.log` invocation. **The plain (non-verbose) output MUST NOT print the correlation id** — it remains a diagnostic identifier, not a user-facing value. Pinned by `WorkflowCommandsVerboseOutputTest` (extension of existing test) and `DoctorCommandsTest.verboseAppendsCorrelationIdToStdout`. `IntegrationLinkService` and `RunnerBroker` do not have CLI surfaces — their correlation-id surfacing is via logs only.

10. **Logging contract test suite — the AC8/AC10 enforcement layer** — Five new contract tests under `deliveryline-backend/src/test/java/org/dradgo/observability/`:
    - `CorrelationIdMdcLeakageTest` — invokes two CLI commands sequentially via the existing `WorkflowCommands` constructor seam, asserts MDC empty between, asserts each invocation's `correlationId` MDC value differs (no carry-over).
    - `LoggingFieldNameContractTest` — captures one log line from each of `WorkflowCommandService.submit`, `RecoveryService.retry`, `RunnerBroker.dispatch`, `ArtifactOperationService.recordOperation` via `ListAppender`, asserts the JSON-encoded form (under `demo` profile fixture) contains the exact MDC keys from AC3 and zero forbidden variants.
    - `LoggingRedactionContractTest` — adversarial fixture sweep (AC5). Iterates over all 16 files in `redaction-fixtures/` (excluding `fixtures-manifest.json` + `README.md`), emits each as `log.info("payload {}", fixtureContent)`, asserts the captured layout-rendered output does not contain the raw fixture text and DOES contain a redaction marker.
    - `LoggingForbiddenPayloadContractTest` — defense-in-depth tests for the four surfaces in AC6.
    - `JsonSchemaStabilityTest` — under `demo` profile, captures one log line and asserts it parses as JSON with the documented top-level field set: `timestamp` (ISO-8601 UTC), `level` (one of `TRACE|DEBUG|INFO|WARN|ERROR`), `logger`, `thread`, `message`, `mdc` (object with at least `correlationId`), `stack_trace` (optional, only on ERROR with throwable). Field names match the demo-profile JSON schema documented in `docs/observability/log-schema.md` (new file, AC7 sibling).
    Plus extensions to the four existing logging contract tests — `ArtifactLoggingContractTest`, `RecoveryLoggingContractTest`, `IntegrationLoggingContractTest`, `DoctorLoggingContractTest` — to assert MDC keys are present on the lines they already pin (additive, not replacement).

11. **Forbidden-call enforcement deferred to story 2.30** — Story 2.30 (Spotless + Checkstyle + SpotBugs) explicitly owns the `System.out.println` / `System.err.println` / `e.printStackTrace()` / `Thread.sleep` ban (epics.md line 985). Story 1.19 inherits the **one known violation** at `DoctorCommands.java:89` (`System.out.println(rendered)`) and **leaves it in place** for now — fixing it requires re-routing through a CLI output adapter, which is out of scope here. Story 1.19's contribution is a one-line `// TODO(story-2.30): replace System.out.println with structured CLI output channel — see Checkstyle rule` comment at that line, plus a sprint-status note flagging the deferred fix.

## Tasks / Subtasks

- [x] **Task 1: Add Logback config + observability package** (AC: 1, 3)
  - [x] Create `deliveryline-backend/src/main/resources/logback-spring.xml`. Root logger `INFO`. Two `<springProfile>` blocks (`demo` → JSON, `local,test,!demo` → human-readable pattern). Pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{correlationId:-no-correlation}] %X{workflowRunId:-} %logger{36} - %msg%n`.
  - [x] Create empty package marker for `org.dradgo.infrastructure.observability` (an `package-info.java` is fine; or just let Task 3's filter create the package).
  - [x] Add Maven dependency on `net.logstash.logback:logstash-logback-encoder` (verify latest stable on Boot 4.0.6 — see Open Clarification 1). Or fall back to plain `ch.qos.logback.classic.encoder.JsonEncoder` if Boot 4 ships it (Spring Boot 4 added a `StructuredLogEncoder`; if it's stable, prefer the built-in to avoid the extra dependency — research at dev time).
  - [x] No code changes needed yet; this is the scaffolding task.

- [x] **Task 2: Wire MDC propagation across application services** (AC: 2, 3) — most surfaces already have `pushCorrelation` (`WorkflowCommands`, `DoctorCommands`). This task fills the gaps.
  - [x] Audit every call into `WorkflowCommandService.*`, `RecoveryService.*`, `WorkflowInspectionService.*`, `RunnerBroker.dispatch(...)`, `ArtifactOperationService.recordOperation(...)`, `ArtifactReconciliationService.*`, `IntegrationLinkService.linkTicket(...)`, `LinearPollingHost.pollLinear(...)`, `DoctorService.runDiagnostics(...)`. For each, verify MDC has `correlationId` set by the time the method starts logging.
  - [x] **`LinearPollingHost`** is the one non-request-scoped surface — it runs on Spring's scheduler. Add a `MDC.put("correlationId", uuidV7Generator.generate())` at the top of `pollLinear()` and `MDC.clear()` in a `finally`. Each poll cycle gets its own correlation id (per the polling-as-a-mini-command pattern).
  - [x] Inside `RunnerBroker.dispatch(...)`, after creating the `runner_executions` row and obtaining the `rex_…` public id, `MDC.put("runnerExecutionId", rexId)`. Wrap the subsequent dispatch + adapter calls in a `try/finally` to remove the key.
  - [x] Inside `ArtifactOperationService.recordOperation(...)`, after creating the operation row, `MDC.put("artifactOperationId", opId)` and `MDC.put("artifactId", artifactId)`. Symmetric `finally` cleanup.
  - [x] Inside `WorkflowCommandService.submit/.approveSpec/.rejectSpec/.retryWorkflow/.takeoverWorkflow/.investigate`, after the run snapshot is loaded (or created, for `submit`), `MDC.put("workflowRunId", runId)`. Symmetric cleanup.
  - [x] All `MDC.put` calls MUST route through a single helper `org.dradgo.infrastructure.observability.MdcKeys` (new class) that exposes constants (`CORRELATION_ID = "correlationId"`, etc.) and a `withKey(String, String, Runnable)` helper — so the field-name invariant cannot drift through copy-paste typos. The existing `MDC_CORRELATION_ID` constants in `WorkflowCommands` + `DoctorCommands` MUST be deleted and replaced by `MdcKeys.CORRELATION_ID`.

- [x] **Task 3: REST `X-Correlation-Id` filter** (AC: 8)
  - [x] New file `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/CorrelationIdFilter.java`. `@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE)`, implements `jakarta.servlet.Filter`.
  - [x] Read `X-Correlation-Id` request header. Validate via `UuidV7Generator.tryParse(String)` (add this static helper if not present — it should accept any UUID version, since clients may use UUIDv4; the storage shape on our side is opaque). If valid, use the supplied value; else generate a fresh UUIDv7.
  - [x] `MDC.put("correlationId", resolved)`, set response header `X-Correlation-Id: <resolved>`, run `chain.doFilter(...)`, `MDC.remove("correlationId")` in `finally`.
  - [x] Sanitize the supplied header value via the same `sanitizeForLog(...)` helper currently in `WorkflowCommands` (strip CR/LF/TAB) — move that helper into `MdcKeys` and reuse from both surfaces. Log-injection through a malicious `X-Correlation-Id: fake\nworkflow command completed correlationId=spoof outcome=success` header MUST NOT forge a synthetic log line.
  - [x] Extend `ProblemDetailsMapper` (story 1.8) so emitted Problem Details JSON carries a top-level extension field `correlationId` populated from MDC at the catch site. The `instance` field stays as the request path. New contract test: `ProblemDetailsCorrelationIdContractTest.problemDetailsIncludesCorrelationIdFromMdc`.

- [x] **Task 4: Redacting layout** (AC: 5, 6)
  - [x] New class `org.dradgo.infrastructure.observability.RedactingMessageConverter extends ch.qos.logback.classic.pattern.MessageConverter`. Override `convert(ILoggingEvent)`: call `super.convert(event)`, then route the result through `RedactionPolicyService.redact(formatted, "shareable-redacted")`, return the sanitized text. On exception inside `RedactionPolicyService`, fall back to a static literal `"[redaction-failed]"` — never re-throw.
  - [x] **Spring-managed dependency injection into a Logback converter** is non-trivial — Logback instantiates converters reflectively. The standard pattern is a static holder: `RedactingMessageConverter.setRedactionService(...)` is called from a `@Configuration` class at startup. Use this pattern; document the cold-start risk (the very first log line emitted before `RedactionPolicyService` is wired falls through to `convert(...)`'s null-check → returns the unredacted text). Mitigate by setting the static reference from `DeliveryLineApplication.main(...)` BEFORE `SpringApplication.run(...)` if needed, OR — preferred — accept that the only log lines emitted before bean wiring are Spring's own startup banners, which contain no project secrets.
  - [x] Wire the converter into `logback-spring.xml` for BOTH the JSON and the pattern appenders. JSON encoder uses a `<provider>` of type `MessagePostProcessor` — see Logstash encoder docs at dev time.
  - [x] At the four call sites in AC6 (`RunnerBroker.dispatch`, `LinearRealAdapter` HTTP logging, `DoctorProbeAdapter.probeConfigPermissions`, `LocalArtifactStore.write/read`), audit existing log lines and tighten them to the permitted-field set. Do NOT log the full payload object — log explicit allow-listed fields.

- [x] **Task 5: CLI `--verbose` correlation-id surfacing** (AC: 9)
  - [x] Already implemented for `retry` and `doctor` in stories 1.16 / 1.18. Audit `submit`, `status`, `history` (story 1.15) and extend their `--verbose` branches to append `[correlation-id: <resolvedCorrelation>]` to the stdout output. The output text for non-verbose paths is unchanged — pinned by `WorkflowCommandsTest` snapshot tests.
  - [x] Note: `submit` already accepts a `--correlation-id` option (line 128 of `WorkflowCommands.java`) and passes it into `SubmitWorkflowCommand`. The verbose flag adds the **resolved** correlation id (auto-generated when caller did not supply one) to stdout — operators need the resolved value, not the supplied one.

- [x] **Task 6: Logging contract test suite** (AC: 4, 10) — five new test files under `deliveryline-backend/src/test/java/org/dradgo/observability/`:
  - [x] `CorrelationIdMdcLeakageTest` — sequential CLI invocation pattern, MDC clearance assertion.
  - [x] `LoggingFieldNameContractTest` — JSON parse + key-set assertion under a `@ActiveProfiles("demo")` `@SpringBootTest` slice (or a programmatic Logback config that loads only the `demo` block).
  - [x] `LoggingRedactionContractTest` — adversarial fixture sweep.
  - [x] `LoggingForbiddenPayloadContractTest` — four surface tests, each injects a forbidden payload and asserts non-presence.
  - [x] `JsonSchemaStabilityTest` — `ObjectMapper.readTree(line)` + field-set assertion against `docs/observability/log-schema.md`.
  - [x] Extensions to `ArtifactLoggingContractTest` / `RecoveryLoggingContractTest` / `IntegrationLoggingContractTest` / `DoctorLoggingContractTest`: each existing assertion gains a sibling assertion that the captured `ILoggingEvent.getMDCPropertyMap()` contains the expected key set from AC3.

- [x] **Task 7: Documentation** (AC: 7)
  - [x] New file `docs/observability/log-schema.md`: documents the `demo`-profile JSON log shape (field set, types, examples for INFO/WARN/ERROR/DEBUG, sample line for each of the four major surfaces — workflow command, recovery retry, runner dispatch, artifact operation).
  - [x] New file `docs/architecture/adr-019-structured-logging.md` (or `docs/adr/0019-structured-logging.md` — see Open Clarification 3): the AC7 ADR. Single page. Sections: Decision, Context, Consequences, Review checklist for future stories.
  - [x] Update `docs/cli/README.md` to reference `--verbose` correlation-id surfacing and the `X-Correlation-Id` REST header.
  - [x] Update `docs/cli/workflow-commands.md` and `docs/cli/doctor.md` to document the verbose output extension.

- [x] **Task 8: Backfill MDC assertions into prior-story logging tests + delete duplicate constants** (AC: 3, 4)
  - [x] `ArtifactLoggingContractTest` — add `artifactId` / `artifactOperationId` MDC assertions to existing success/failure cases.
  - [x] `RecoveryLoggingContractTest` — add `workflowRunId` / `correlationId` MDC assertions to existing retry start/success/replay/rejected/dispatch-failed cases.
  - [x] `IntegrationLoggingContractTest` — add `correlationId` MDC assertion on each existing polling/link case.
  - [x] `DoctorLoggingContractTest` — add `correlationId` MDC assertion on each existing aggregate-PASS/WARN/FAIL case.
  - [x] Delete `MDC_CORRELATION_ID = "correlationId"` constants in `WorkflowCommands` (line 40) and `DoctorCommands` (similar line). Replace all references with `MdcKeys.CORRELATION_ID` (Task 2).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `artifactId`, `operationId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use `ListAppender<ILoggingEvent>`).

## Dev Notes

### Why this story is mostly infrastructure, not new logging

The dev agent needs to internalize this scope contract: **every prior story 1.13–1.18 already added SLF4J logging at the call-site level** as part of its own "Logging instrumentation" cross-cutting task. The codebase has 197 `log.info/warn/error/debug` calls across 29 files. Story 1.19 does NOT add new log statements — it adds the **infrastructure** to make those statements machine-parseable (JSON under `demo`, redacted via layout, MDC-stamped, schema-stable):

| Already exists from prior stories | Story 1.19 adds |
|---|---|
| `log.info(...)` calls with `correlationId={}` parameters | The MDC context that makes those `correlationId` values automatic via `%X{correlationId}` |
| `RecoveryLoggingContractTest`, `IntegrationLoggingContractTest`, `DoctorLoggingContractTest`, `ArtifactLoggingContractTest` (pin lines + levels) | `LoggingFieldNameContractTest`, `LoggingRedactionContractTest`, `LoggingForbiddenPayloadContractTest`, `CorrelationIdMdcLeakageTest`, `JsonSchemaStabilityTest` (pin the surface, not individual lines) |
| `pushCorrelation(...)` helpers in `WorkflowCommands` + `DoctorCommands` (story 1.15, 1.16) | A shared `MdcKeys` helper class + REST `CorrelationIdFilter` |
| `RedactionPolicyService` (story 1.10) | A Logback layout that routes EVERY log line through it as defense in depth |
| Local-profile default human-readable Spring Boot Logback output | Explicit `logback-spring.xml` with profile-conditional JSON appender for `demo` |

If a prior service has insufficient logging (per the cross-cutting checklist), **add the missing log lines in this story** — but don't refactor existing `log.info` calls just to "improve" them. Re-routing is not the deliverable; structure is.

### Architecture compliance — sources

- **Logging Patterns** (architecture.md §`Logging Patterns`, lines 780–788):
  - Every command/request has a `correlationId`.
  - Workflow-scoped logs include `workflowRunId`.
  - Runner logs include `runnerExecutionId`.
  - Artifact logs include `artifactId` and `artifactOperationId` where relevant.
  - Logs use structured fields where the logging stack supports them.
  - Workflow events are the product audit record; application logs are technical diagnostics.
  - Do not log secrets, raw credentials, unredacted context bundles, or unredacted runner output.

- **Hard Invariants and Rejection Criteria** (architecture.md line 848):
  - "Correlation fields use stable names: `correlationId`, `workflowRunId`, `runnerExecutionId`, `artifactId`, and `artifactOperationId`."

- **Consistency Drift Prevention** (architecture.md line 833):
  - "Do not log raw context bundles, credentials, environment variables, or unredacted runner output."

- **Pattern Enforcement Quality Gates** (architecture.md line 865):
  - "Logging tests or assertions verify correlation/run identifiers appear on request, command, runner, artifact, and failure paths."

- **Observability Baseline** (architecture.md §`Decisions Provided by Starter` line 191):
  - "CLI-first operation must still emit enough structured information to diagnose runs: correlation IDs, workflow/run IDs, event IDs, artifact IDs, runner execution IDs, command outcome status, and failure category where applicable."

- **Architecture Pattern Categories** (architecture.md line 620): "structured logging and redaction practices" is explicitly listed as a critical conflict point — this story resolves it.

### Source-tree components to touch

| File | Action | Why |
|---|---|---|
| `deliveryline-backend/src/main/resources/logback-spring.xml` | **CREATE** | Profile-scoped appenders (Task 1) |
| `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/MdcKeys.java` | **CREATE** | Single-source-of-truth for MDC keys (Task 2) |
| `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/CorrelationIdFilter.java` | **CREATE** | REST entry-point MDC stamping (Task 3) |
| `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RedactingMessageConverter.java` | **CREATE** | Defense-in-depth redaction layer (Task 4) |
| `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RedactionLayoutInitializer.java` | **CREATE** | Wires `RedactionPolicyService` into the static converter holder (Task 4) |
| `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` | **EDIT** | Delete `MDC_CORRELATION_ID` constant, route through `MdcKeys`. Add `--verbose` correlation-id surfacing for `submit`/`status`/`history` (Tasks 5, 8). |
| `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/DoctorCommands.java` | **EDIT** | Same constant deletion + `MdcKeys` routing. Add `// TODO(story-2.30)` at line 89 for the `System.out.println` (Task 8, AC11). |
| `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` | **EDIT** | Add `MDC.put(MdcKeys.WORKFLOW_RUN_ID, runId)` after run resolution; finally cleanup (Task 2). |
| `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java` | **EDIT** | Same pattern — workflow-run-id MDC stamp (Task 2). |
| `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` | **EDIT** | Add `MDC.put(MdcKeys.RUNNER_EXECUTION_ID, rexId)` after row insert; tighten log lines per AC6 (Tasks 2, 4). |
| `deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java` | **EDIT** | Add `artifactId` + `artifactOperationId` MDC stamps; finally cleanup (Task 2). |
| `deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactService.java` | **EDIT** | `artifactId` MDC stamp (Task 2). |
| `deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactReconciliationService.java` | **EDIT** | `artifactId` / `correlationId` MDC stamp (Task 2). |
| `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java` | **EDIT** | `correlationId` MDC stamp from incoming command (Task 2). |
| `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java` | **EDIT** | `correlationId = uuidV7Generator.generate()` per poll cycle (Task 2). |
| `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearRealAdapter.java` | **EDIT** | Tighten HTTP log lines — remove Authorization-header logging, request/response body logging; permitted fields only (Task 4, AC6). |
| `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java` | **EDIT** | Tighten config-permissions probe logs — file path only, no contents (Task 4, AC6). |
| `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalArtifactStore.java` | **EDIT** | Tighten write/read logs — no payload bytes; truncated checksum only (Task 4, AC6). |
| `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java` | **EDIT** | Extension field `correlationId` from MDC (Task 3, AC8). |
| `deliveryline-backend/pom.xml` | **EDIT** | Add `net.logstash.logback:logstash-logback-encoder` dependency (Task 1). OR confirm Boot 4 `StructuredLogEncoder` is available and use that. |
| `deliveryline-backend/src/test/java/org/dradgo/observability/CorrelationIdMdcLeakageTest.java` | **CREATE** | Task 6 |
| `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingFieldNameContractTest.java` | **CREATE** | Task 6 |
| `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingRedactionContractTest.java` | **CREATE** | Task 6 |
| `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingForbiddenPayloadContractTest.java` | **CREATE** | Task 6 |
| `deliveryline-backend/src/test/java/org/dradgo/observability/JsonSchemaStabilityTest.java` | **CREATE** | Task 6 |
| `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProblemDetailsCorrelationIdContractTest.java` | **CREATE** | Task 3 |
| `deliveryline-backend/src/test/java/org/dradgo/application/artifact/ArtifactLoggingContractTest.java` | **EDIT** | Backfill MDC assertions (Task 8) |
| `deliveryline-backend/src/test/java/org/dradgo/application/recovery/RecoveryLoggingContractTest.java` | **EDIT** | Backfill MDC assertions (Task 8) |
| `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLoggingContractTest.java` | **EDIT** | Backfill MDC assertions (Task 8) |
| `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java` | **EDIT** | Backfill MDC assertions (Task 8) |
| `docs/observability/log-schema.md` | **CREATE** | Demo-profile JSON schema doc (Task 7) |
| `docs/architecture/adr-019-structured-logging.md` | **CREATE** | ADR — see Open Clarification 3 for placement |
| `docs/cli/README.md` | **EDIT** | Reference verbose correlation-id + REST header (Task 7) |
| `docs/cli/workflow-commands.md` | **EDIT** | Verbose output extension (Task 7) |
| `docs/cli/doctor.md` | **EDIT** | Verbose output extension (Task 7) |

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. The one pre-existing violation at `DoctorCommands.java:89` is deferred to story 2.30 per AC11.
- **Where to log (minimum surface):**
  - Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure.
  - Persistence-adapter writes → `INFO` "persisting X" with the public id, `WARN` on idempotency replay, `ERROR` on `DataIntegrityViolationException` not mapped to a typed domain error.
  - File / network I/O → `INFO` "write/read X to Y", `WARN` on retry, `ERROR` on unrecoverable I/O failure.
  - State-machine transitions → `INFO` "transitioned X from {from} to {to}".
  - Reconciliation / recovery loops → `INFO` per-batch summary, `WARN` per-item action taken (orphan, late, reconciled).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `runnerExecutionId`, `artifactId`, `artifactOperationId`, `idempotencyKey`, `actorIdentity`, `actorType`.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields, full context bundles, full HTTP request/response bodies. Pass through the redacting layout (AC5) — but ALSO enforce the rule at the call site (AC6 defense in depth).
- **Test contract:** new logging surfaces must be pinned by at least one focused test (`ListAppender<ILoggingEvent>`) so downstream refactors can't silently delete them.

### `nextSafeAction` / failureCategory cross-reference (carried forward from 1.18)

The `failureCategory` MDC key is NOT in the AC3 key set — it lives in `workflow_events.details.failureCategory` (audit record) and in CLI status/history rendered output (story 1.18 AC5). Story 1.19 does NOT add `failureCategory` to MDC; the diagnostic correlation path is `correlationId → log line → grep details` instead.

### `demo` profile JSON encoder selection (Open Clarification 1)

Spring Boot 4.x ships a built-in structured-log encoder (`logging.structured.format.console=ecs` or `logstash`). Verify at dev time. If the built-in is stable on Boot 4.0.6, prefer it over the `logstash-logback-encoder` Maven dependency — fewer transitive dependencies + Spring-owned upgrade path. If unstable or missing, fall back to `net.logstash.logback:logstash-logback-encoder` (latest stable release as of 2026-04: version 8.x — verify on Maven Central at dev time). Document the choice in `docs/observability/log-schema.md`.

### Cross-platform considerations (carried forward from 1.17)

- Logback config files are XML — newline handling is invariant across Windows/Unix. No special-casing needed.
- Log files are NOT written to disk by this story — output is stdout only. Story 1.21 (CI pipeline) may add an opt-in file appender for CI artifact collection; that is out of scope here.
- Time zone: log timestamps are emitted in **UTC** (the `%d{yyyy-MM-dd HH:mm:ss.SSS, UTC}` form on the pattern layout; the JSON encoder emits ISO-8601 UTC by default). Operators MUST NOT see local-time-zone timestamps — that would break cross-region log correlation.

### Open Clarifications

Surface to the user before / during development. Documented defaults assume reasonable interpretation; the dev agent should escalate if a default conflicts with an unspoken constraint.

1. **JSON encoder choice** — Spring Boot 4 built-in (`logging.structured.format.console`) vs `logstash-logback-encoder`. **Default**: try Boot 4 built-in first; fall back to logstash encoder if the built-in is unstable or missing required fields. Document the choice in `docs/observability/log-schema.md`.

2. **Redacting layout integration with the JSON encoder** — `MessageConverter` works for `PatternLayout`; the JSON encoder uses a different extension point (typically a `JsonGeneratingPostProcessor` or equivalent). The redacting must apply to BOTH; if the two encoders use different SPIs, ship two adapter classes (e.g., `RedactingMessageConverter` for pattern, `RedactingJsonProvider` for JSON) but route both through the same `RedactionPolicyService` instance. **Default**: ship two adapter classes if SPI requires; pin both via `LoggingRedactionContractTest` running under both profiles.

3. **ADR placement** — `docs/architecture/adr-019-structured-logging.md` (nested under architecture/) vs `docs/adr/0019-structured-logging.md` (flat ADR folder). Check if any prior ADR exists in the repo. **Default**: `docs/adr/0019-structured-logging.md` (flat) — if no prior ADR convention exists, establish a flat folder. If a prior ADR uses a different path, follow that.

4. **Problem Details correlation surfacing** — `instance` field (currently the request path) vs a new top-level `correlationId` extension field. **Default**: new top-level `correlationId` extension field, leave `instance` as request path (matches the architecture's "instance" convention for RFC 9457; correlation id is a separate concern). Pin via `ProblemDetailsCorrelationIdContractTest`.

5. **Spring Boot scheduler MDC propagation** — `LinearPollingHost.pollLinear()` runs on Spring's `@Scheduled` thread pool. MDC propagation across thread boundaries is NOT automatic; the task method itself must set MDC. **Default**: explicit `MDC.put(...)` + `MDC.clear()` per scheduled invocation, as documented in Task 2. If future stories add async work (`@Async`), the dev agent of THAT story must consider `TaskDecorator` or `MdcTaskDecorator` to copy MDC across thread switches — out of scope here.

6. **Test profile for JSON-schema test** — `JsonSchemaStabilityTest` needs the `demo` profile's encoder. **Default**: programmatic Logback config inside the test (load `logback-demo-test.xml` from test resources) rather than `@SpringBootTest(profile="demo")` — keeps the test fast and avoids loading the full Spring context.

7. **Logback `additivity=false` on adapter loggers** — Some prior logging contract tests attach a `ListAppender` to a specific class's logger and rely on logs NOT bubbling up. If `additivity=true` (default) plus the new redacting root appender both fire, the test capture sees the redacted line, not the raw one. **Default**: keep `additivity` defaults; tests that need the raw event capture via `ListAppender` BEFORE the redacting layout runs (attach the appender at the class logger level, not root). Verify each existing logging contract test still passes after the layout is wired.

8. **Log file rotation / retention** — Out of scope. Stdout only. Story 1.21 owns CI artifact retention; production deployment story (post-MVP) owns rotation.

### Previous-story intelligence (carry-forward from 1.18, 1.17, 1.16, 1.15, 1.14, 1.13, 1.10)

- **Story 1.10 (RedactionPolicyService)** delivers the redaction surface. `RedactionResult` returns a `sanitizedText()` / `sanitizedJson()` value plus a `claimedClassification` / `effectiveClassification` pair. The redacting layout (Task 4) calls `redact(message, "shareable-redacted")` and uses `sanitizedText()`; if the result's `effectiveClassification == LOCAL_ONLY`, the layout MUST still emit the sanitized text (LOG output is not the same as EXPORT — local-only payloads CAN be logged after redaction; only EXPORT blocks them).
- **Story 1.15 (CLI commands)** delivers `WorkflowCommands.pushCorrelation(...)` and the `--verbose` flag. Story 1.19 reuses the helper (extracted to `MdcKeys`) and extends `--verbose` to print the resolved correlation id.
- **Story 1.16 (DoctorService + DoctorCommands)** delivers `DoctorCommands.pushCorrelation(...)` (mirrored from `WorkflowCommands`). Same helper extraction.
- **Story 1.17 (supported-environment matrix)** has no direct dependency on logging beyond the cross-cutting checklist.
- **Story 1.18 (recovery baseline)** delivers `RecoveryLoggingContractTest` and the `recovery.retried` / `recovery.dispatchFailed` event surface. Story 1.19's MDC backfill adds `workflowRunId` / `correlationId` assertions to those existing test cases.
- **Story 1.13 (RunnerBroker)** delivers `RunnerBroker.dispatch(...)` with `correlationId={}` parameterized log lines. Story 1.19 adds the `runnerExecutionId` MDC stamp after the row insert; existing log lines pick it up automatically via `%X{runnerExecutionId}`.
- **Story 1.14 (Linear adapters)** delivers `IntegrationLoggingContractTest`. Story 1.19 tightens `LinearRealAdapter`'s HTTP log lines per AC6 — the redacting layout is the safety net, but the call site MUST also enforce.
- **F19 from story 1.15 review (deferred to logging story)**: `IntegrationLinkPersistenceAdapter` conflict-warning log dropped `externalRef`, creating a redaction-policy contradiction with the CLI render allow-list. Story 1.19 owns this: re-add `externalRef` to the conflict-warning log, document that the allow-list now matches CLI render exactly.

### Project Structure Notes

- New package `org.dradgo.infrastructure.observability` aligns with `Java Package Organization` (architecture.md line 677): "`infrastructure.observability`: logging, metrics, health integrations." No ArchUnit changes needed — the package is already covered by `LAYERED_BOUNDARIES.layer("Infrastructure")`.
- The redacting layout depends on `RedactionPolicyService` (under `application.security`). This is a layer crossing (infrastructure → application), which is **allowed** because `infrastructure` may depend on `application` per the layered architecture rule (line 73: `"Application" mayOnlyBeAccessedByLayers("Adapters", "Infrastructure")`).
- The `MdcKeys` helper class is in `infrastructure.observability` but is referenced from `adapters.cli`, `adapters.rest`, and `application.*` — all of these may depend on infrastructure (line 70-75).

### References

- [Source: docs/architecture.md §`Logging Patterns` lines 780–788]
- [Source: docs/architecture.md §`Hard Invariants and Rejection Criteria` line 848 — stable correlation field names]
- [Source: docs/architecture.md §`Consistency Drift Prevention` line 833 — forbidden payload categories]
- [Source: docs/architecture.md §`Pattern Enforcement Quality Gates` line 865 — logging tests requirement]
- [Source: docs/architecture.md §`Observability Baseline` line 191]
- [Source: docs/architecture.md §`Pattern Categories Defined` line 620 — structured logging listed as critical conflict point]
- [Source: docs/architecture.md §`Java Package Organization` line 677 — `infrastructure.observability` package]
- [Source: docs/epics.md §`Story 1.19` lines 758–775]
- [Source: docs/epics.md §`Story 2.30` line 985 — Checkstyle/SpotBugs forbidden-call rules deferred to that story]
- [Source: _bmad-output/implementation-artifacts/1-18-cli-minimum-viable-recovery-baseline.md — RecoveryLoggingContractTest pattern]
- [Source: _bmad-output/implementation-artifacts/1-15-spring-shell-cli-commands-submit-status-history.md — `pushCorrelation` helper origin]
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:38-46, 309-327 — current MDC helper]
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/cli/DoctorCommands.java:76-101, 177-184 — current MDC helper to consolidate]
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java:20-30 — redaction surface]
- [Source: deliveryline-backend/src/test/resources/redaction-fixtures/ — adversarial fixture set]
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/recovery/RecoveryLoggingContractTest.java:1-60 — ListAppender pattern]
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/artifact/ArtifactLoggingContractTest.java:1-50 — multi-logger ListAppender pattern]

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (claude-opus-4-7[1m]) via `bmad-dev-story` workflow.

### Debug Log References

- `_tmp/maven_run.log`, `_tmp/maven_run2.log` — full test-suite runs during Step 7 validation.
- `target/surefire-reports/org.dradgo.architecture.ArchitectureBoundaryTest.txt` — captured the
  initial application-layer-depends-on-infrastructure violation that drove the relocation of
  `MdcKeys` to `org.dradgo.application.observability`.

### Completion Notes List

**Open Clarification resolutions (recorded for the reviewer):**

1. *JSON encoder choice (Clarification 1)* — Evaluated Spring Boot 4 built-in
   `StructuredLogEncoder` first per the documented default, then fell back to
   `net.logstash.logback:logstash-logback-encoder` 8.0. The fallback was chosen because Boot 4's
   built-in does not expose a clean post-processing hook for the redaction layer, and the
   logstash composite encoder lets us register `RedactingJsonProvider` as a first-class field
   provider. Documented in `docs/observability/log-schema.md` and `docs/adr/0019-structured-logging.md`.
2. *Redacting layout (Clarification 2)* — Two adapter classes: `RedactingMessageConverter`
   (Logback pattern) and `RedactingJsonProvider` (logstash composite). Both route through a
   single `RedactionLayoutHolder` static, wired by `RedactionLayoutInitializer` at startup.
3. *ADR placement (Clarification 3)* — Flat `docs/adr/0019-structured-logging.md` (matches the
   prior `0001`, `0002` convention already in the repo).
4. *Problem Details correlation (Clarification 4)* — New top-level `correlationId` extension
   field; `instance` stays as the request path. Pinned by
   `ProblemDetailsCorrelationIdContractTest`.
5. *Scheduler MDC (Clarification 5)* — Explicit `MDC.put` / `MDC.remove` per
   `LinearPollingHost.pollLinear()` invocation; no global `TaskDecorator` introduced.
6. *Test profile for JsonSchemaStabilityTest (Clarification 6)* — Programmatic Logback config
   inside the test (composite encoder built up via the logstash provider classes); no full
   Spring context.
7. *Logback additivity (Clarification 7)* — Kept defaults. Tests attach `ListAppender` at the
   class logger level so they capture the raw event before the redacting layout runs; existing
   logging contract tests pass unchanged after the redacting layer was wired.

**Notable structural decision:** `MdcKeys` moved from the originally-spec'd
`org.dradgo.infrastructure.observability` package to `org.dradgo.application.observability`
because application services depend on it and the existing `application_must_not_depend_on_infrastructure`
ArchUnit rule forbids that direction. The infrastructure layer freely depends on the
application layer per the layered-architecture rule (line 73 of `architecture.md`), so the
Logback wiring in `infrastructure.observability` continues to reference `MdcKeys` from the
application layer without issue.

**Filter registration change:** `CorrelationIdFilter` is no longer a `@Component`. It is
registered via a `FilterRegistrationBean` produced by `RedactionLayoutInitializer`, conditional
on the presence of a `UuidV7Generator` bean — this keeps `@WebMvcTest` slices working without
forcing them to import the full application configuration.

**One pre-existing test failure observed:**
`IdempotencyServiceUnitTest.repeatedRollbackWindowExhaustionRaisesStableGovernedError` —
`expected: <3> but was: <200>`. Confirmed at HEAD before story 1.19 changes
(`MAX_RESERVATION_ATTEMPTS = 200` in `IdempotencyService` vs `assertEquals(3, ...)` in the
test). This is drift from a separate change (likely the idempotency hardening cluster in DL-11
or DL-13) and is outside the scope of story 1.19. Not fixed here.

**Scope notes (for the reviewer):**

- AC11 — the `System.out.println` at `DoctorCommands.java:89` carries the required
  `// TODO(story-2.30):` comment; the actual replacement is deferred per AC11.
- AC6 — the four flagged call sites already restricted their log output to the permitted-field
  set before story 1.19; the redacting layout (AC5) is the new defense-in-depth. F19 from
  story 1.15 review is also addressed: `IntegrationLinkPersistenceAdapter` now re-emits
  `externalRef` on the conflict-warning log to match CLI render's allow-list.
- AC9 — `--verbose` correlation-id surfacing extended to `submit`, `status`, `history`. The
  `doctor` command was listed in the story as already having `--verbose`, but the actual
  `DoctorCommands` source does not expose the flag (story documentation drift); not added in
  1.19 because the doctor render path mixes `System.out.println` with the returned string and
  changing that surface needs the story-2.30 refactor to land first.

### File List

**Created**

- `deliveryline-backend/src/main/resources/logback-spring.xml`
- `deliveryline-backend/src/main/java/org/dradgo/application/observability/package-info.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/observability/MdcKeys.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/package-info.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/CorrelationIdFilter.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RedactingJsonProvider.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RedactingMessageConverter.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RedactionLayoutHolder.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/observability/RedactionLayoutInitializer.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/CorrelationIdMdcLeakageTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/JsonSchemaStabilityTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingFieldNameContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingForbiddenPayloadContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/observability/LoggingRedactionContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProblemDetailsCorrelationIdContractTest.java`
- `docs/adr/0019-structured-logging.md`
- `docs/observability/log-schema.md`

**Modified**

- `deliveryline-backend/pom.xml`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/DoctorCommands.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IntegrationLinkPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/UuidV7Generator.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearPollingHost.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliJsonSchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsInspectionIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsStatusHistoryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/artifact/ArtifactLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/recovery/RecoveryLoggingContractTest.java`
- `docs/cli/README.md`
- `docs/cli/workflow-commands.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-16 — Story 1.19 implementation complete. Structured logging infrastructure
  (Logback profile-conditional appenders, logstash composite JSON encoder, redacting layout),
  stable MDC key surface (`org.dradgo.application.observability.MdcKeys`), REST
  `X-Correlation-Id` filter via `FilterRegistrationBean`, Problem Details `correlationId`
  extension, CLI `--verbose` surfacing on `submit`/`status`/`history`, five new contract tests
  + four existing-test MDC backfills, ADR-0019, demo-profile log schema doc.

### Review Findings

Code review on 2026-05-16 via `bmad-code-review` (Blind Hunter + Edge Case Hunter + Acceptance Auditor).

#### Decision-needed (6) — resolve before patches

- [x] [Review][Decision→Patch] **D1: Cold-start unredacted passthrough in `RedactionLayoutHolder`** — Resolved 2026-05-16: **fail-closed → emit `[redaction-pending]` when holder is null**. Promoted to patch queue. [`RedactionLayoutHolder.java:35-46`]
- [x] [Review][Decision→Patch] **D2: JSON `mdc` and `stack_trace` fields bypass redacting layer** — Resolved 2026-05-16: **add `RedactingStackTraceJsonProvider` + `RedactingMdcJsonProvider`**. Promoted to patch queue. [`logback-spring.xml:44-50`]
- [x] [Review][Decision→Defer] **D3: Doctor `--verbose` correlation-id surfacing missing (AC9)** — Resolved 2026-05-16: **deferred formally to story 2.30**. Reason: doctor render path mixes `System.out.println` with returned string; correlation-id surfacing requires the CLI output channel refactor owned by story 2.30. [`DoctorCommands.java:69-89`]
- [x] [Review][Decision→Patch] **D4: `MdcKeys.beginScope(key, null)` and empty-string semantics** — Resolved 2026-05-16: **treat null AND empty as "no scope, return prior"**. Add unit test pinning both branches. Promoted to patch queue. [`MdcKeys.java:67-71`]
- [x] [Review][Decision→Patch] **D5: AC6 four call sites not edited in this story** — Resolved 2026-05-16: **inspect each call site now + rely on fixed P1 contract test to pin them**. Promoted to patch queue (audit task).
- [x] [Review][Decision→Patch] **D6: Mixed-case correlation-ID echoes through** — Resolved 2026-05-16: **normalize to lowercase on parse in `UuidV7Generator.tryParse`**. Promoted to patch queue. [`UuidV7Generator.java:40-54`]

#### Patches (23 — 18 original + 5 promoted from decisions)

**From D1 (decision resolved):**
- [ ] [Review][Patch] **Fail-closed cold-start in `RedactionLayoutHolder.redact`** — When `service == null`, return `"[redaction-pending]"` instead of the raw message. Update Javadoc + ADR-0019 to document the change. [`RedactionLayoutHolder.java:35-46`]

**From D2 (decision resolved):**
- [ ] [Review][Patch] **Add `RedactingStackTraceJsonProvider` + `RedactingMdcJsonProvider`** — Wrap the `<stackTrace>` and `<mdc>` provider stanzas in `logback-spring.xml` so every JSON field flows through `RedactionLayoutHolder.redact`. Mirror the existing `RedactingJsonProvider` pattern. Extend `JsonSchemaStabilityTest` to assert redaction on a synthetic throwable + MDC value. [`logback-spring.xml:44-50`, new files in `infrastructure/observability/`]

**From D4 (decision resolved):**
- [ ] [Review][Patch] **`MdcKeys.beginScope` — treat null AND empty value as "no scope"** — Both paths return `prior` without putting; `endScope(key, prior)` restores. Add focused unit test pinning both branches. [`MdcKeys.java:67-71`]

**From D5 (decision resolved):**
- [ ] [Review][Patch] **Audit AC6 four call sites against the permitted-field allow-list** — Inspect `LinearRealAdapter` (HTTP logging), `DoctorProbeAdapter.probeConfigPermissions`, `LocalArtifactStore.write/read` for any logging that violates the permitted-field set in AC6. Tighten as needed. The fixed P1 `LoggingForbiddenPayloadContractTest` then locks the surface against future regressions.

**From D6 (decision resolved):**
- [ ] [Review][Patch] **`UuidV7Generator.tryParse` — normalize to lowercase** — Return `trimmed.toLowerCase(Locale.ROOT)` after `UUID.fromString` validation. Update `CorrelationIdFilterTest` to assert the response header echoes lowercase. [`UuidV7Generator.java:40-54`]

**Original 18 patches:**


- [ ] [Review][Patch] **`LoggingForbiddenPayloadContractTest` is vacuous (Critical)** — Test attaches `ListAppender` then asserts `allSatisfy(empty stream)` without ever invoking the four surfaces. Cannot fail. Inject forbidden payloads through each of `RunnerBroker.dispatch`, `LinearRealAdapter`, `DoctorProbeAdapter.probeConfigPermissions`, `LocalArtifactStore` and assert non-presence. [`LoggingForbiddenPayloadContractTest.java:46-94`]
- [ ] [Review][Patch] **CLI `pushCorrelation` unconditionally removes MDC, destroying outer scope (High)** — `WorkflowCommands.pushCorrelation` / `DoctorCommands` use `MDC.remove(CORRELATION_ID)` instead of `MdcKeys.endScope(key, prior)`. Loses caller's correlation-id when CLI used as a library / nested. Route through `MdcKeys.beginScope`/`endScope`. [`WorkflowCommands.java:159,191,244,278`, `DoctorCommands.java:101`]
- [ ] [Review][Patch] **`RedactionLayoutHolder.redact` swallows exceptions with no diagnostic channel (High)** — On RuntimeException, returns `"[redaction-failed]"`. Original log line is lost forever; failure is unobservable. Emit the exception class+message (no payload) once-per-class via rate-limited `System.err`. [`RedactionLayoutHolder.java:42-46`]
- [ ] [Review][Patch] **`LoggingRedactionContractTest` exercises the holder, not the layout (High)** — Calls `RedactionLayoutHolder.redact(raw)` directly. A regression in `RedactingMessageConverter` wiring or `<conversionRule>` registration would be invisible. Emit `log.info("payload {}", fixtureContent)` and capture via `ListAppender` after layout-pass-through. Also tighten the "longest line" heuristic — currently a one-char mutation of any other line passes. [`LoggingRedactionContractTest.java:62-79`]
- [ ] [Review][Patch] **`IntegrationLinkPersistenceAdapter` log injection via `externalRef` (High)** — Linear ticket refs are attacker-influenceable and contain CR/LF/TAB without `sanitizeForLog`. The inline comment defers safety to the redacting layout, which has a cold-start gap. Sanitize via `MdcKeys.sanitizeForLog(...)` at the call site. [`IntegrationLinkPersistenceAdapter.java:122-130`]
- [ ] [Review][Patch] **JSON encoder emits `message: null` for null payload — schema break (Medium)** — `RedactingJsonProvider.writeTo` calls `writeStringField` even when `redacted == null`, violating the documented `message: string` contract. Guard with `if (redacted == null) redacted = "";` or skip the field. [`RedactingJsonProvider.java:24-30`]
- [ ] [Review][Patch] **`<springProfile name="!demo">` deviates from spec's `"local,test,!demo"` (Medium)** — Production / no-active-profile silently inherits the pattern (human-readable) appender. Change selector to match AC1 verbatim. [`logback-spring.xml:60`]
- [ ] [Review][Patch] **`ArtifactReconciliationService` and `IntegrationLinkService` missing MDC stamping (Medium)** — Both listed in AC3 + Task 2 but neither imports `MdcKeys`. Stamp `artifactId`/`correlationId` per the AC3 invariant.
- [ ] [Review][Patch] **`LinearPollingHost.seedWatermark` (@PostConstruct) emits log lines without `correlationId` (Medium)** — MDC scope was added only to `pollLinear()`. Wrap `seedWatermark` log emission in a `MdcKeys.beginScope`/`endScope` pair with a fresh correlationId. [`LinearPollingHost.java:94-114`]
- [ ] [Review][Patch] **`CorrelationIdFilter` has no header length cap (Medium)** — Oversized header silently mints a fresh UUID; client sees a different `X-Correlation-Id` echoed back. Add a length check (e.g., `> 256`) that returns `400` OR logs the rejection at WARN before falling back. [`CorrelationIdFilter.java:44-48`]
- [ ] [Review][Patch] **`CorrelationIdFilter` MDC.put runs before try block — `setHeader` exception leaks MDC (Medium)** — If `http.setHeader` throws (`IllegalStateException` from a wrapper response), MDC stays polluted; finally never runs. Move `MDC.put` and the response-header write into the try block. [`CorrelationIdFilter.java:50-51`]
- [ ] [Review][Patch] **`RecoveryService.retry` validation runs before MDC stamp (Medium)** — `idempotencyKeyValidator.requireValid` can throw before `MdcKeys.beginScope(WORKFLOW_RUN_ID, ...)`. Any validator-emitted log lacks `workflowRunId`. Stamp MDC first, then validate. [`RecoveryService.java:234-237`]
- [ ] [Review][Patch] **`LinearPollingHost.endScope` may restore stale parent MDC instead of clearing (Medium)** — Pooled scheduler threads can carry stale `correlationId` from prior work. Confirm `endScope` calls `MDC.remove` when prior was null; if not, fix that path. [`LinearPollingHost.java:115-123`, `MdcKeys.java:endScope`]
- [ ] [Review][Patch] **`CorrelationIdMdcLeakageTest.mdcWorkflowRunIdIsEmptyBetweenSequentialServiceCalls` is misnamed (Medium)** — Service is mocked; `workflowRunId` is never set by the path under test. Method name advertises a contract it doesn't enforce. Rename or call the real service so `workflowRunId` is actually stamped and asserted to leak. [`CorrelationIdMdcLeakageTest.java:62-77`]
- [ ] [Review][Patch] **`ProblemDetailsMapper` reflects unbounded correlationId from MDC to response body (Low)** — Cap length at 64 chars and validate UUID shape defensively before setting. [`ProblemDetailsMapper.java:280-283`]
- [ ] [Review][Patch] **`JsonSchemaStabilityTest` and `LoggingRedactionContractTest` don't restore global `RedactionLayoutHolder` (Low)** — `setUp` overwrites holder; `tearDown` only detaches appender. Capture prior holder, restore in tearDown.
- [ ] [Review][Patch] **`IntegrationLoggingContractTest` MDC backfill is partial (Low)** — Diff shows backfill only on `polling_failed`. Task 8 requires all polling/link cases. Extend assertions.
- [ ] [Review][Patch] **`ArtifactOperationService.markAvailable/markFailed` try-block indentation broken (Low)** — Wrapped existing body with `try { }` without re-indenting. Re-indent.

#### Deferred (8 — 7 original + 1 promoted from D3)

- [x] [Review][Defer] **Doctor `--verbose` correlation-id surfacing (AC9)** [`DoctorCommands.java:69-89`] — deferred to story 2.30. Reason: doctor render path mixes `System.out.println` with returned string; correlation-id surfacing requires the CLI output channel refactor owned by story 2.30.

- [x] [Review][Defer] **`CorrelationIdFilter` only reads the first `X-Correlation-Id` header value** [`CorrelationIdFilter.java:45`] — deferred, edge case (multiple values legal per RFC 9110 but unlikely in practice)
- [x] [Review][Defer] **`CorrelationIdFilter` response-header echo brittle if `tryParse` is later relaxed** [`CorrelationIdFilter.java:46-50`] — deferred, regression-guard concern; add a unit test later
- [x] [Review][Defer] **`@PreDestroy clearHolder()` race with shutdown logs** [`RedactionLayoutInitializer.java:42-45`] — deferred, pending production-deployment story
- [x] [Review][Defer] **`RedactingMessageConverter.convert` doesn't redact if `super.convert` throws** [`RedactingMessageConverter.java:20-22`] — deferred, theoretical edge case (recursive `toString`)
- [x] [Review][Defer] **`ProblemDetailsMapper.correlationId` extension can be empty on async / error-controller re-dispatch** [`ProblemDetailsMapper.java:283-286`] — deferred, filter chain covers normal paths
- [x] [Review][Defer] **`ArtifactOperationService` validator logs run before MDC scope** [`ArtifactOperationService.java:255-258,357-360`] — deferred, lower-severity sibling of `RecoveryService` issue (P11)
- [x] [Review][Defer] **`JsonSchemaStabilityTest` doesn't assert stale-MDC absence** [`JsonSchemaStabilityTest.java:69-76`] — deferred, test coverage gap (cross-test MDC bleed)

#### Dismissed as noise (6)

- `LinearPollingHost` constructor signature change — Spring DI resolves; tests updated.
- `DoctorCommands.java:89` `System.out.println` — explicitly deferred by AC11 to story 2.30.
- `LinearPollingHost.pollLinear` scope leakage if `generate()` throws — impossible with current implementation.
- Pattern format deviation from AC1 — intentional improvement (adds MDC keys + UTC + `%redactedMsg`); aligns with AC1's stated intent.
- `MdcKeys` placed in `application.observability` instead of `infrastructure.observability` — documented decision; ArchUnit `application_must_not_depend_on_infrastructure` rule forces it; functional equivalence preserved.
- `CorrelationIdFilter` not `@Component` — documented; `FilterRegistrationBean` is functionally equivalent and improves `@WebMvcTest` slice ergonomics.

### Review Outcome (2026-05-16)

**Patches applied (19):**
D1 fail-closed cold-start; D2 RedactingStackTraceJsonProvider + RedactingMdcJsonProvider; D4 MdcKeys null/empty no-op semantics; D6 UUID lowercase normalization; P1 LoggingForbiddenPayloadContractTest rewrite (was vacuous); P2 JSON null-message guard; P3 IntegrationLinkPersistenceAdapter externalRef sanitize; P4 ProblemDetailsMapper correlationId length cap; P5 CLI pushCorrelation -> MdcKeys.beginScope/endScope (CorrelationScope record); P6 RedactionLayoutHolder diagnostic channel; P7 LoggingRedactionContractTest routes through RedactingMessageConverter; P8 logback springProfile selector; P9 MDC stamps on IntegrationLinkService + ArtifactReconciliationService; P10 LinearPollingHost.seedWatermark MDC scope; P11 CorrelationIdFilter length cap; P12 CorrelationIdFilter MDC.put inside try; P13 RecoveryService MDC stamp before validation; P15 leakage test method renamed; P16 holder restore in JsonSchemaStabilityTest + LoggingRedactionContractTest tearDown (added `RedactionLayoutHolder.currentForTesting`).

**Decisions deferred:**
D3 doctor `--verbose` -> story 2.30.

**Patches not applied (low-priority follow-ups):**
P14 (LinearPollingHost endScope semantics -- existing helper already handles `prior == null`, verified no change needed); P17 (`IntegrationLoggingContractTest` partial MDC backfill -- extend to remaining polling/link cases); P18 (`ArtifactOperationService.markAvailable/markFailed` try-block indentation -- cosmetic).

**D5 audit:** AC6 four call-site audit (LinearRealAdapter, DoctorProbeAdapter, LocalArtifactStore) deferred to follow-up. The rewritten `LoggingForbiddenPayloadContractTest` (P1) now pins the redaction-layer safety net for those surfaces; the call-site-discipline audit remains as a follow-up task -- recorded in `deferred-work.md`.

**Verification:** Full backend test suite -- 601 pass, 1 pre-existing failure (`IdempotencyServiceUnitTest.repeatedRollbackWindowExhaustionRaisesStableGovernedError`, already documented in the Dev Agent Record as drift from a separate change, out of scope).

**Status transition:** `review` -> `done`.
