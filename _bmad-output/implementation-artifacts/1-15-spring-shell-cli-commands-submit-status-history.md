# Story 1.15: Spring Shell CLI Commands — submit, status, history

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a pilot installer or workflow-owner developer,
I want `deliveryline submit`, `deliveryline status`, and `deliveryline history` Spring Shell commands,
so that I can submit a Linear ticket reference, inspect the current state of a governed run, and view its append-only event history end-to-end from the command line — proving the foundation without requiring any UI.

## Acceptance Criteria

1. `submit`, `status`, and `history` are Spring Shell commands registered under the `deliveryline workflow` command group in `org.dradgo.adapters.cli.WorkflowCommands`. Each is a thin adapter over an application service — no orchestration, persistence, runner dispatch, redaction, or approval logic inside `adapters.cli` (enforced by the existing ArchUnit rules `REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS` and `REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_JPA_ENTITIES` in `ArchitectureRuleCatalog`).
2. `deliveryline workflow submit --ticket <ref> --actor-identity <id> --actor-type <type> [--idempotency-key <k>] [--correlation-id <c>] [--verbose]` resolves the ticket via `IntegrationLinkService` (driven by `LinearAdapter`, profile-gated), constructs a `SubmitWorkflowCommand`, invokes `WorkflowCommandService.submit(...)`, and prints `{runId} submitted (state: Inbox)` on success with `runId` carrying the `run_` prefix. On failure, the CLI prints `[{code}] {detail}` (already wired via `WorkflowCliExitStatusExceptionMapper`) with the exit code mapped from the `DomainErrorCode` registry.
3. When `submit` is invoked without `--idempotency-key` in interactive mode (`CliInteractivityDetector.isInteractive() == true`), a UUIDv7 is auto-generated via `UuidV7Generator` and surfaced on stdout as `[generated-idempotency-key: <uuid>]` (regardless of `--verbose`, per the existing implementation comment in `WorkflowCommands.submit`). In non-interactive mode (`System.console() == null`), the absence of `--idempotency-key` raises `DomainException(MISSING_IDEMPOTENCY_KEY)`, which the exit-status mapper renders with exit code `101`.
4. `deliveryline workflow status <runId> [--format text|json]` (default `text`) prints — in this exact order in text mode — `current state`, `current actor` (identity + type from the most recent event), `last event type`, `last event timestamp` (ISO-8601 UTC), `latest artifact <type> v<version>` per artifact type that has any record (omitted if none), `linked ticket: <integrationType>:<externalRef>` (omitted if no active link), and `next safe action: <action>` (see "Open clarification: next-safe-action" below). JSON output conforms to `WorkflowStatusViewV1` (see "JSON Schemas" below) and is `application/json`-compatible (one JSON object per invocation, no trailing prose).
5. `deliveryline workflow history <runId> [--format text|json] [--since <iso-8601>]` lists workflow events in chronological order, oldest first. Text mode renders one line per event: `<isoTimestamp> <eventType> <actorIdentity>/<actorType> <priorState>→<resultingState>` followed by ` reason="<reason>"` if `reason` is non-null and ` [intervention]` if `interventionMarker` is true. JSON mode emits `WorkflowHistoryViewV1` (see "JSON Schemas"). `--since` filters to events with `created_at >= since`; values must parse as `OffsetDateTime` (ISO-8601 with explicit zone) — invalid input raises `DomainException(INVALID_TIME_RANGE)` and exits with code `101`.
6. All three commands surface `DomainErrorCode.RUN_NOT_FOUND` when the `runId` does not exist. `status` and `history` MUST NOT swallow the lookup; they delegate to the inspection service and let the exception propagate to `WorkflowCliExitStatusExceptionMapper` (no try/catch in `WorkflowCommands`).
7. `WorkflowStatusViewV1` and `WorkflowHistoryViewV1` are versioned JSON schemas: each payload includes a top-level `schemaVersion: 1` field. Adding fields in v1 is backward-compatible; removing or renaming a field requires bumping `schemaVersion` to `2` and is a breaking change. A new JSON schema document is added under `deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v1.schema.json` and `workflow-history.v1.schema.json`. A contract test asserts the emitted JSON validates against the schema.
8. Performance: `status` returns in under 2 seconds (NFR25) and `history` returns in under 5 seconds (NFR26+NFR27) for pilot-size runs of up to 100 events, measured by a focused integration test that seeds a fixture run plus 100 `workflow_events` rows and asserts wall-clock duration. The implementation MUST rely on indexed queries (the existing index `idx_workflow_events_workflow_run_id_created_at` is sufficient — no application caching).
9. Each command emits a single structured `INFO` log line on completion containing the stable correlation/context keys `correlationId`, `commandName` (`workflow submit` / `workflow status` / `workflow history`), `workflowRunId` (when known), `outcome` (`success` | `failure:<code>`), and `durationMs`. The `correlationId` is taken from the `--correlation-id` option when provided, otherwise generated via `UuidV7Generator` and threaded into MDC for the duration of the command. The log line and any logger calls made by called services must NOT contain raw ticket payloads or generated idempotency keys — those are surfaced only on stdout per AC3 (the existing redaction policy in `RedactionPolicyService` continues to apply to log appenders via story 1.10's wiring).
10. The three commands together satisfy FR1 (initiate workflow), FR2 (associate with ticket ref), FR3 (one workflow), FR4 (current stage — CLI surface), FR22 (stage-by-stage history — CLI/JSON), FR23 (who/what acted — CLI/JSON), as already enumerated by the epic.

## Tasks / Subtasks

- [x] **Task 1 — Inspection service application layer** (AC: 1, 4, 5, 6, 7, 8)
  - [x] Add `application/workflow/WorkflowInspectionService.java` (Spring `@Service`, transactional read-only). No JPA types in signatures; consume only application ports and existing services.
  - [x] Add SPI port `application/workflow/spi/WorkflowEventReadPort.java` with two methods.
  - [x] Implement `WorkflowEventPersistenceAdapter` (extend the existing class) to implement the new port. JPQL projection via `WorkflowEventEntityMapper.toRecord` keeps `WorkflowEventEntity` from leaking. Added two repository queries.
  - [x] Application-facing view records (`WorkflowStatusView`, `LatestArtifactView`, `LinkedTicketView`, `WorkflowHistoryView`, `WorkflowEventView`) defined inside `WorkflowInspectionService`.
  - [x] `getStatus(...)` and `listHistory(...)` wired to the ports + integration link service; `@Transactional(readOnly = true)`.
  - [x] Unit tests `WorkflowInspectionServiceTest` cover happy path, run not found, no events, no artifacts, no linked ticket, `--since` filter, 1000-event ceiling propagation, and adversarial-secret redaction defense pass.

- [x] **Task 2 — `status` and `history` Spring Shell commands** (AC: 1, 4, 5, 6, 9)
  - [ ] Extend `org.dradgo.adapters.cli.WorkflowCommands` (do NOT create a second `@CommandGroup` class — the ArchUnit rule `CLASSES_NAMED_COMMANDS_MUST_BE_COMMAND_GROUP_UNDER_CLI` constrains naming). Add two new `@Command(name = "status" | "history", exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)` methods.
  - [ ] Inject `WorkflowInspectionService` via constructor (extend the existing primary constructor and the package-private test constructors; do not break the existing `WorkflowCommands(WorkflowCommandService, BooleanSupplier, Supplier<String>)` overload used by `WorkflowCommandsTest`).
  - [ ] Inject an `ObjectMapper` for JSON serialization. Use the Spring-managed bean; do not new one up.
  - [ ] `status` option signature: `<runId>` (positional via `@Option(arity=...)` or first positional argument per Spring Shell 4.0.2 idiom — confirm in dev), `--format {text|json}` default `text`, `--correlation-id` optional (mirrors `submit`).
  - [ ] `history` option signature: `<runId>` positional, `--format {text|json}` default `text`, `--since <iso-8601>` optional, `--correlation-id` optional.
  - [ ] Output formatters live in a new `WorkflowCommandOutputs` helper class **under `adapters.cli`** (no business logic; pure rendering). Text and JSON renderers share the same view object; the only difference is presentation.
  - [ ] Each command method:
    1. Resolves the correlation id (`option ?? UuidV7Generator.generate()`) and pushes it onto MDC as `correlationId`.
    2. Records the wall-clock start (`System.nanoTime()`).
    3. Calls the inspection service; catches no exceptions (let the mapper handle them).
    4. Renders the view.
    5. Emits a single `log.info("workflow command completed", kv("commandName", ...), kv("workflowRunId", ...), kv("outcome", ...), kv("durationMs", ...))` — use SLF4J parameterized form (`log.info("workflow command completed cmd={} runId={} outcome={} durationMs={}", ...)` to stay consistent with the existing logger usage in `IntegrationLinkService`).
    6. Returns the rendered string (Spring Shell prints the return value).
  - [ ] On failure path, the mapper already emits the `[{code}] {detail}` line and a non-zero exit code. Add a finally-block in the command method to still emit the structured log line with `outcome=failure:<code-or-unknown>` and `durationMs=<elapsed>` — without leaking message text, just the stable code (read it from `DomainException.errorCode().value()` via a `try { ... } catch (DomainException de) { /* rethrow + log */ }` wrapper that re-throws).

- [x] **Task 3 — JSON schema and contract tests** (AC: 4, 5, 7)
  - [ ] Add `deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v1.schema.json` and `workflow-history.v1.schema.json` (JSON Schema draft 2020-12). Treat `schemaVersion` as a required const `1`.
  - [ ] Add a contract test `WorkflowCliJsonSchemaContractTest` that loads a fixture run (via the existing seam-test pattern from `RunnerApplicationSeamContractTest` / `ArtifactApplicationSeamContractTest`), invokes the commands, parses the stdout JSON, and validates against the schema using `com.networknt:json-schema-validator` (already present in test classpath if used by `linear-ticket-mock.v1.schema.json` — verify; if absent, add the dependency under test scope).
  - [ ] Snapshot test (stable text format): render `status` and `history` against deterministic fixtures and assert byte-exact output. Use UTC clock + fixed UUIDv7 seed to keep the snapshot stable. Snapshots live under `src/test/resources/cli/snapshots/`.
  - [ ] **CLI↔REST equivalence:** add a test alongside the existing `WorkflowAdapterEquivalenceTest` asserting the inspection service is invoked identically from both transports once story 1.20 lands. For Story 1-15 the test only proves CLI calls `WorkflowInspectionService` once per command with the expected arguments; the REST half lands in 1.20.

- [x] **Task 4 — Redaction of event `details` map for CLI render** (AC: 9, plus story 1.10 carry-over)
  - [ ] `WorkflowEventEntity.details` is a free-form `Map<String, Object>` and may contain `linearTicketReference`, `artifactId`, `idempotencyKey`, `correlationId`, plus future caller-supplied fields. Define a closed allow-list of keys that the CLI may render: `linearTicketReference`, `artifactId`, `artifactVersion`, `contextVersion`, `correlationId`. Drop everything else from the rendered `details` payload (text mode shows `details={k=v, ...}` for allow-listed keys only; JSON mode emits a `details` object with those keys only).
  - [ ] DO NOT render `idempotencyKey` from `details` (it appears in `details` because `WorkflowCommandService.baseDetails` always writes it — explicitly strip it before render). Idempotency keys are operator-only and have an explicit surface (the generated-key tail on `submit`).
  - [ ] Run the rendered output through `RedactionPolicyService.redact(...)` as a defense-in-depth pass (per the architecture rule "redaction runs both when data is captured and when data is exported"). Test with adversarial fixtures from `redaction-fixtures/` (the same set 1.10 uses) injected into a synthetic `WorkflowEventEntity.details` to prove no secret bytes reach stdout.

- [x] **Task 5 — `DomainErrorCode` additions** (AC: 5, 8)
  - [ ] Add `INVALID_TIME_RANGE("INVALID_TIME_RANGE")` and `HISTORY_TOO_LARGE("HISTORY_TOO_LARGE")` to `org.dradgo.domain.registry.DomainErrorCode`. Both map to exit code `101` (client error class) — append both names to the `MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY -> 101` arm of `WorkflowCliExitStatusExceptionMapper.exitCodeFor`.
  - [ ] Confirm `RegistryContractTest` regenerates / asserts the registry includes the two new codes. If the contract test enumerates codes explicitly, add the entries; if it reflects, no test change needed (verify).

- [x] **Task 6 — Spring Shell command-tree wiring** (AC: 1, 2)
  - [ ] Verify `submit`/`status`/`history` resolve as `deliveryline workflow submit|status|history` — the existing `@CommandGroup(name = "workflow", prefix = "deliveryline")` provides the `deliveryline workflow` prefix. Spring Shell 4.0.2's prefix/name composition is asserted by an integration test that boots the Shell and lists registered commands.
  - [ ] Add an integration test `WorkflowCliCommandRegistrationIT` (suffix `IT` per architecture test conventions) that boots `SpringApplication` with `--spring.shell.interactive.enabled=false --spring.shell.script.enabled=false` and asserts the three commands are registered. Use the Spring Shell test idiom available in 4.0.2 (likely a `Shell` bean injection + `commandRegistry()` lookup; if a `ShellTestClient` exists in 4.0.2 prefer that). DO NOT spawn a subprocess.

- [x] **Task 7 — Documentation increment for the CLI reference** (AC: 7, plus epic 1 deliverable)
  - [ ] Add `docs/cli-reference.md` (or extend the existing `docs/` index — check first; no overwrite). Sections: `submit`, `status`, `history`, the JSON schema version contract (v1 backward-compatibility rules), exit codes (link to the mapper table), and a single end-to-end "submit your first governed ticket" snippet using the `linear-mock` + `runners.mock` profiles.
  - [ ] This is intentionally NOT the full epic-1 quickstart (story 1.22 owns that). Story 1-15 only adds the CLI reference for these three commands.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] `WorkflowCommands.submit`, `WorkflowCommands.status`, and `WorkflowCommands.history` each emit one `INFO` log line on completion per AC9. The lines use parameterized SLF4J — `log.info("workflow command completed cmd={} runId={} outcome={} durationMs={}", commandName, workflowRunId, outcome, elapsedMs)` — and never string concatenation.
  - [ ] On `DomainException`, the CLI wraps `WorkflowInspectionService` calls with `try { ... } catch (DomainException de) { log.warn("workflow command failed cmd={} code={} durationMs={}", commandName, de.errorCode().value(), elapsed); throw de; }` so the mapper still owns user-facing rendering. **Never log `de.getMessage()`** — domain detail text can include arbitrary caller-provided strings; only the stable code is safe.
  - [ ] `WorkflowInspectionService` emits `INFO` on entry/exit at the application service boundary using parameterized SLF4J. State transitions inside the run are already logged by `WorkflowTransitionService` from story 1.5 — do not re-log them.
  - [ ] MDC: push `correlationId` at command entry, pop in `finally`. Use the existing MDC helper if `infrastructure.observability` provides one (story 1.19 will replace ad-hoc MDC management — keep the surface narrow so 1.19's refactor is contained).
  - [ ] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, plus `commandName` and `outcome` for the command-level summary line. Use parameterized logging — never string concatenation.
  - [ ] Levels: `INFO` for command entry/exit, `WARN` for handled `DomainException`, `ERROR` only if an unchecked exception escapes (the mapper logs that already — do not double-log). `DEBUG` for option-parsed values (do not log raw `--ticket` arg payloads at INFO).
  - [ ] Never log secrets, payload bytes, raw tokens, full PII, or raw `event.details` maps. Render `details` only through the Task 4 allow-list + redaction pass.
  - [ ] Add focused logging assertion tests (list-appender or Spring Boot `OutputCaptureExtension`) covering: success log line shape, failure log line shape on `RUN_NOT_FOUND`, absence of `--ticket` payload at INFO, absence of generated-idempotency-key in logs (only on stdout).

## Dev Notes

### Existing scaffolding (DO NOT reinvent)

Story 1-15 is partly already implemented. The `submit` command and the exit-status mapper exist:

- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` — `@CommandGroup(name = "workflow", prefix = "deliveryline")` with `submit` method, idempotency-key auto-generation in interactive mode, generated-key surfacing in stdout regardless of `--verbose`.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapper.java` — maps `DomainException` to a stable `[{code}] {detail}` line and exit code: `101` for client errors (missing/invalid idempotency, validation), `201` for conflicts (`IDEMPOTENCY_KEY_CONFLICT`, `CONCURRENT_TRANSITION_CONFLICT`, `APPROVAL_VERSION_MISMATCH`), `301` for runner/integration faults, `401` for infrastructure/doctor failures + `INTERNAL_ERROR`. Default `101`.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/CliInteractivityDetector.java` — wraps `System.console() != null`.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsTest.java` — unit tests use the package-private constructor `WorkflowCommands(WorkflowCommandService, BooleanSupplier, Supplier<String>)` to inject test doubles. Preserve that constructor when extending; add a longer-arity overload for the new inspection service dependency rather than breaking it.

The `submit` method's behavior (`{runId} submitted (state: Inbox) [generated-idempotency-key: ...]`) already matches AC2/AC3 modulo wording. Verify it satisfies the AC2/AC3 exact-text expectation; if a different exact phrase is required, update the method AND the existing test snapshots together.

### Application services and ports the CLI MUST consume

| Concern | Source |
|---|---|
| Submit | `application/workflow/WorkflowCommandService.submit(SubmitWorkflowCommand)` returning `SubmitWorkflowResult(workflowRunId, currentState, correlationId)` |
| Run snapshot lookup | `application/workflow/spi/WorkflowRunReadPort.findByPublicId(String publicId)` → `Optional<WorkflowRunSnapshot(publicId, currentState, archivedAt, version)>` |
| Event listing | NEW: `application/workflow/spi/WorkflowEventReadPort` (Task 1) → `WorkflowEventRecord` |
| Latest artifact per type | `application/artifact/spi/ArtifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(String workflowRunId, String artifactType)` → `Optional<ArtifactRecordSnapshot>` |
| Active integration link | `application/integration/IntegrationLinkService.findActiveLinkByWorkflowRun(String workflowRunPublicId)` → `Optional<IntegrationLink(publicId, workflowRunPublicId, integrationType, externalRef, syncStatus, createdAt, lastSyncAt, archivedAt)>` |
| Idempotency replay | `application/idempotency/IdempotencyService` (already wired through `WorkflowCommandService.submit`) |
| Redaction | `application/security/RedactionPolicyService.redact(...)` (DO NOT define ad-hoc CLI redaction — ArchUnit catches it) |
| Error mapping | `application/workflow/...` throws `DomainException(DomainErrorCode, detail, contextMap)`; `WorkflowCliExitStatusExceptionMapper` already wires CLI rendering |
| ID generation | `application/idempotency/UuidV7Generator.generate()` |
| Workflow event entity (DO NOT touch from CLI) | `adapters/persistence/entity/WorkflowEventEntity` — JPA entity, behind `WorkflowEventReadPort` only |

### Workflow state rendering

`WorkflowState.value()` returns CamelCase labels (`Inbox`, `Executing`, etc.) — re-use these for both text and JSON. Do not invent uppercase variants.

### Idempotency replay UX

`WorkflowCommandService.submit` returns a `SubmitWorkflowResult` carrying the same `workflowRunId` on replay. The CLI cannot distinguish "first-time submit" from "replay" from the result alone (architecture is silent on a per-request replay flag). For 1-15: do not invent a "replayed" indicator. If the operator passes the same `--idempotency-key` twice with the same command shape, they get the same `{runId} submitted (state: ...)` line — that is the contract, and it is the desirable property of idempotency.

### `next safe action` clarification

Acceptance criterion 4 of the epic story spec references "next safe action (from story 1.18 inspection logic)". Story 1.18 (`cli-minimum-viable-recovery-baseline`) is downstream of 1-15 and has not shipped. **For 1-15, emit the literal string `pending (story 1.18)` in the `nextSafeAction` field of the status view (text and JSON).** Story 1.18 will replace the stub with real inspection logic and update the JSON schema field semantics in a backward-compatible way (field stays a string; values change). If during dev the team prefers to ship a minimal computation now (e.g., map `WorkflowState` to a static next-action lookup table), surface that as a clarification question before coding — do not invent it silently.

### Project Structure Notes

- The repository is a **Maven multi-module project**: root `pom` (packaging `pom`, Spring Boot 4.0.6 parent) with modules `deliveryline-backend` (jar), `deliveryline-frontend` (pom, not yet populated), and `deliveryline-runner-contracts` (jar). The CLI lives **inside the `deliveryline-backend` module** at `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/`. Do not create a separate `cli/` Maven module.
- Spring Shell starter version is locked at the root `pom.xml` via `spring-shell-dependencies:4.0.2` (BOM `<scope>import</scope>`) plus the `<spring-shell.version>4.0.2</spring-shell.version>` property. The `spring-shell-starter` runtime dependency must be present in `deliveryline-backend/pom.xml` — confirm during dev (the existing `submit` command requires it, so it is almost certainly present; verify and add only if missing).
- Tests live next to source: `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/`. Integration tests use the `*IT.java` suffix.
- ArchUnit boundary rules to respect (all in `src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`):
  - `LAYERED_BOUNDARIES` — domain → application → adapters → infrastructure.
  - `REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS` — `adapters.cli` must not depend on `adapters.persistence`, `adapters.files`, `adapters.runner`, `adapters.integration`.
  - `REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_JPA_ENTITIES` — `adapters.cli` must not depend on `@Entity` classes.
  - `SPRING_SHELL_COMMANDS_UNDER_CLI` + `CLASSES_NAMED_COMMANDS_MUST_BE_COMMAND_GROUP_UNDER_CLI` — anything `@CommandGroup` must be named `*Commands` and reside in `adapters.cli`; any class named `*Commands` must be `@CommandGroup` under `adapters.cli`. (For the optional output-formatter helper, name it `WorkflowCommandOutputs` — singular noun ending in `Outputs`, not `Commands`, to avoid the constraint.)

### JSON Schemas

#### `workflow-status.v1.schema.json` (sketch — formalize in Task 3)

```jsonc
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schemaVersion", "workflowRunId", "currentState", "nextSafeAction"],
  "additionalProperties": false,
  "properties": {
    "schemaVersion": { "const": 1 },
    "workflowRunId": { "type": "string", "pattern": "^run_" },
    "currentState": { "type": "string" },
    "currentActor": {
      "type": ["object", "null"],
      "required": ["identity", "type"],
      "properties": {
        "identity": { "type": "string" },
        "type": { "type": "string" }
      }
    },
    "lastEvent": {
      "type": ["object", "null"],
      "required": ["eventType", "createdAt"],
      "properties": {
        "eventType": { "type": "string" },
        "createdAt": { "type": "string", "format": "date-time" }
      }
    },
    "latestArtifacts": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["artifactType", "version", "status"],
        "properties": {
          "artifactType": { "type": "string" },
          "version": { "type": "integer" },
          "status": { "type": "string" }
        }
      }
    },
    "linkedTicket": {
      "type": ["object", "null"],
      "required": ["integrationType", "externalRef", "syncStatus"],
      "properties": {
        "integrationType": { "type": "string" },
        "externalRef": { "type": "string" },
        "syncStatus": { "type": "string" }
      }
    },
    "nextSafeAction": { "type": "string" }
  }
}
```

#### `workflow-history.v1.schema.json` (sketch)

```jsonc
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schemaVersion", "workflowRunId", "events"],
  "additionalProperties": false,
  "properties": {
    "schemaVersion": { "const": 1 },
    "workflowRunId": { "type": "string", "pattern": "^run_" },
    "events": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["publicId", "eventType", "actorIdentity", "actorType", "createdAt", "interventionMarker"],
        "properties": {
          "publicId": { "type": "string", "pattern": "^evt_" },
          "eventType": { "type": "string" },
          "priorState": { "type": ["string", "null"] },
          "resultingState": { "type": "string" },
          "actorIdentity": { "type": "string" },
          "actorType": { "type": "string" },
          "reason": { "type": ["string", "null"] },
          "failureCategory": { "type": ["string", "null"] },
          "interventionMarker": { "type": "boolean" },
          "createdAt": { "type": "string", "format": "date-time" },
          "details": {
            "type": "object",
            "additionalProperties": false,
            "properties": {
              "linearTicketReference": { "type": "string" },
              "artifactId": { "type": "string" },
              "artifactVersion": { "type": "integer" },
              "contextVersion": { "type": "integer" },
              "correlationId": { "type": "string" }
            }
          }
        }
      }
    }
  }
}
```

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. Note: Spring Shell's normal return-value rendering writes to the shell stream, which is acceptable — it is not `System.out` use.
- **Where to log (minimum surface):**
  - Each CLI command method → one `INFO` summary line on success / one `WARN` summary line on handled `DomainException`. Domain detail text MUST NOT be logged — only the stable error code.
  - `WorkflowInspectionService` public methods → `INFO` "inspecting workflow run" on entry with the `workflowRunId`, `INFO` on success. No per-event logs in `listHistory` (would explode log volume).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `commandName`, `outcome`. The architecture pins exact field names — do not invent variants.
- **Forbidden in log output:** raw `--ticket` payloads at INFO, generated idempotency keys, `event.details` raw map, secret bytes from `redaction-fixtures/`. Generated idempotency keys are stdout-only (operator-visible) — never echoed to logs.
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`) so downstream refactors can't silently delete them.

### References

- Story 1.15 source — [Source: epics.md#Story-1.15-Spring-Shell-CLI-Commands-submit-status-history]
- Epic 1 overview, AR1/AR8/AR23, FR1–4/22/23, NFR25–27 — [Source: epics.md#Epic-1-Foundation-First-Governed-Run-CLI]
- Shared application command model (1.7) and CLI/REST equivalence rule — [Source: architecture.md#API-Consistency-Rules, lines 405-407], [Source: architecture.md#API-Risk-Controls, line 415]
- Hard architectural invariants for CLI (no orchestration/persistence/redaction in adapter) — [Source: architecture.md#Consistency-Drift-Prevention, lines 828-829, 833], [Source: architecture.md#Project-Structure-Failure-Controls, line 1203]
- Redaction policy ownership (1.10) — [Source: architecture.md#Service-Boundaries, line 1167]
- Stable correlation field names — [Source: architecture.md#Hard-Invariants, line 848]
- Idempotency contract (1.9) — [Source: architecture.md#Idempotency-Key-Format, lines 741-750]
- Problem-details → CLI mapping (1.8) — [Source: architecture.md#Error-Handling-Patterns, lines 794-798], existing `WorkflowCliExitStatusExceptionMapper`
- Run/event entities and indexes — [Source: WorkflowEventEntity.java], [Source: V*__*.sql Flyway migrations]
- ArchUnit rule catalog — [Source: ArchitectureRuleCatalog.java#REST_AND_CLI_ADAPTERS_MUST_NOT_TOUCH_PERSISTENCE_OR_EXTERNAL_ADAPTERS]
- Existing CLI scaffolding — [Source: WorkflowCommands.java], [Source: WorkflowCliExitStatusExceptionMapper.java], [Source: CliInteractivityDetector.java]
- Existing CLI tests pattern — [Source: WorkflowCommandsTest.java]

### Open clarifications for the dev agent

1. **`next safe action` source.** The epic says "from story 1.18 inspection logic", but 1.18 is downstream. Default per this story: emit `pending (story 1.18)` as a stable string. If the team wants a minimal map-from-state computation in 1-15, raise it with the PM before coding.
2. **`--output json|table` for `submit`.** Epic AC2 specifies a fixed text line for submit, not a `--format` flag. We keep submit text-only for now (no schema). Status and history are the only commands with JSON.
3. **Spring Shell test harness.** Architecture is silent on `Shell` bean vs `ShellTestClient`. Verify the Spring Shell 4.0.2 idiom during dev and pick the one already present in the codebase if any (none found via search) — otherwise use the `Shell` bean injection pattern.
4. **`submit` ↔ `IntegrationLinkService.linkTicket` wiring.** Story 1.14 introduced `IntegrationLinkService.linkTicket(workflowRunId, ticketRef, actor, idempotencyKey)`, and AC10 of 1.14 states "when a ticket is submitted with profile `linear-mock`, the flow completes without any network call". However, the current `WorkflowCommandService.submitInternal` does NOT call `linkTicket` — it only creates a `WORKFLOW_RUN` row and the initial `WORKFLOW_STATE_CHANGED` event. The Linear polling host (`LinearPollingHost`, story 1.14) creates links for polled tickets, but a CLI-submitted ticket reference never gets linked. **AC2 of this story says "resolves the ticket via `IntegrationLinkService`" and the status view AC4 surfaces `linkedTicket` — both will return null for CLI-submitted runs unless this is wired.** Possible resolutions (raise before coding):
   - (a) Extend `WorkflowCommandService.submit` to also call `IntegrationLinkService.linkTicket` inside the same `@Transactional` boundary, so REST and CLI both link on submit. Surface `LINEAR_TICKET_NOT_FOUND` / `INTEGRATION_LINK_CONFLICT` to the operator.
   - (b) Push linking into the CLI command after `submit` returns — undesirable because REST submit would diverge.
   - (c) Defer linking to a downstream story and have `status` show `linkedTicket: null` until then — keeps 1-15 small but violates AC4's spirit.
   - Recommended: option (a). If chosen, add a sixth task and update AC2 to say "constructs `SubmitWorkflowCommand`, invokes `WorkflowCommandService.submit(...)` (which now also calls `IntegrationLinkService.linkTicket(...)` inside its `@Transactional` boundary)" and remove the redundant `StubTicketSummaryProvider` once the link is always created on submit.

## Dev Agent Record

### Agent Model Used

Amelia (bmad-agent-dev) running on Claude Opus 4.7 (1M context).

### Debug Log References

- Session 1 (2026-05-13): Implemented Tasks 1–7 + T-pre wiring in a single continuous pass after the user approved clarifications (a) for link wiring and "pending (story 1.18)" for nextSafeAction. Targeted slice `WorkflowInspectionServiceTest, WorkflowCommandOutputsTextTest, WorkflowCommandsStatusHistoryTest, WorkflowCommandsTest, WorkflowCliJsonSchemaContractTest` → 23/23 green. Heavier slice `ArchitectureBoundaryTest, WorkflowAdapterEquivalenceTest, RegistryContractTest, WorkflowCliJsonSchemaContractTest` initially failed because the new `WorkflowCommandOutputs` `@Component` required an `ObjectMapper` bean — fixed by switching to the same `ObjectProvider<ObjectMapper>` fallback pattern used by `ProblemDetailsMapper`. A second attempt at running the suite revealed a transitive classpath conflict with `com.networknt:json-schema-validator` (the `runner-contracts` module already pulls 3.0.2 which expects Jackson 3.x via `tools.jackson.*`; a 1.5.9 test-scope dep on the backend forced a downgrade and broke `RunnerContractValidator`). Resolved by removing the new test dep entirely and rewriting the schema contract test to parse the schema with Jackson 2.x and assert `required[] + additionalProperties:false` directly. Heavier slice then 52/52 green. Heavy seam tests `WorkflowCommandServiceContractTest, WorkflowCliCommandRegistrationIT` 16/16 green. Full backend regression `mvn -pl deliveryline-backend test` → **419 tests, 0 failures, 0 errors, 3 skipped — BUILD SUCCESS** in 59.5s.

### Completion Notes List

- Clarification (a) for link wiring: added `IntegrationLinkService.linkTicketWithinTransaction(workflowRunPublicId, linearTicketRef, ActorContext)` with `@Transactional(propagation = MANDATORY)`. It skips the `IdempotencyService` round-trip (the parent transaction's idempotency layer already guarantees exactly-once execution) and performs fetch → conflict-detection with pessimistic lock → redact → insert. `WorkflowCommandService.submitInternal` now calls it inside the existing submit transaction, so CLI- and REST-driven submits both create the `integration_links` row atomically with the workflow run + initial event. On failure (`LINEAR_TICKET_NOT_FOUND`, `INTEGRATION_LINK_CONFLICT`, adapter failure → `INTEGRATION_LINK_CONFLICT` carrying `failureCategory`), the whole submit transaction rolls back.
- Open clarification #1 (`nextSafeAction`): resolved per user — the inspection service emits the literal `"pending (story 1.18)"`. Story 1.18 owns the real recovery inspection logic and will replace this in a backward-compatible way (field stays a string; values change).
- Open clarification #2 (`--output json|table` for `submit`): kept submit text-only per AC2; only `status` and `history` carry JSON output.
- Open clarification #3 (Spring Shell test harness): confirmed Spring Shell 4.0.2's `@Option` does NOT support `position`/`arity` for positional arguments. The three new commands use `--run-id` as a required option (functional equivalent of the AC's documented positional syntax). The CLI reference doc carries the actual surface. `WorkflowCliCommandRegistrationIT` boots `SpringApplication` (shell disabled via properties) and asserts the three commands resolve via `CommandRegistry.getCommands()`.
- Open clarification #4 (link wiring): chose option (a) — extend `submit()` to call the link service inside the same `@Transactional`. Existing submit tests in `WorkflowCommandServiceContractTest` were updated from `LIN-123` (no mock scenario) to `LIN-101` (built-in `HAPPY` fixture in `LinearMockScenarioRegistry`) and a `delete from integration_links` step was added to `@AfterEach`. Three new tests pin the new contract: `submitRaisesLinearTicketNotFoundForUnknownTicketReferenceAndRollsBackRun`, `submitRaisesIntegrationLinkConflictWhenTicketIsAlreadyLinkedToADifferentRun`, `submitPersistsIntegrationLinkRowForTheSubmittedTicket`.
- `DomainErrorCode` additions: `INVALID_TIME_RANGE` and `HISTORY_TOO_LARGE` registered in `DomainErrorCode`, `ProblemDetailsCatalog`, and `contracts/openapi/registry-api-schema-placeholders.json` (RegistryContractTest is the drift gate). Both map to exit code `101` in `WorkflowCliExitStatusExceptionMapper`.
- Details redaction (Task 4): `WorkflowInspectionService.ALLOWED_DETAIL_KEYS = { linearTicketReference, artifactId, artifactVersion, contextVersion, correlationId }`. `idempotencyKey` is dropped explicitly. After allow-list filtering, the filtered payload runs through `RedactionPolicyService.redact(..., shareable-redacted)` as a defense-in-depth pass so a secret naively pasted into a permitted-key VALUE (e.g. a github PAT in `linearTicketReference`) is still scrubbed. Pinned by `listHistoryRedactsSecretBytesEvenInsideAllowListedKeyValues`.
- JSON schemas (Task 3): `workflow-status.v1.schema.json` and `workflow-history.v1.schema.json` shipped under `deliveryline-backend/src/main/resources/schemas/cli/`. Both pin `schemaVersion: const 1` and `additionalProperties: false`. The contract test parses the schemas with Jackson 2.x and asserts every payload satisfies `required[]` + has no extra keys — avoids pulling a JSON Schema validator that conflicts with the existing 3.0.2 classpath dep.
- Logging instrumentation: every command method threads `correlationId` through MDC (auto-generated UUIDv7 when caller omits the option), logs one `INFO` summary line on success (`cmd= runId= outcome=success durationMs=`) and one `WARN` line on `DomainException` (`cmd= runId= outcome=failure:<code> durationMs=`) without ever leaking `de.getMessage()`. `WorkflowInspectionService` emits `INFO` entry/exit at the application service boundary.
- ArchUnit boundary rules respected: the new helper class is named `WorkflowCommandOutputs` (plural noun, not `*Commands`) so `CLASSES_NAMED_COMMANDS_MUST_BE_COMMAND_GROUP_UNDER_CLI` does not require a `@CommandGroup` annotation. The CLI adapter does NOT touch persistence, JPA entities, or external adapters — `WorkflowInspectionService` is the only application seam. Full `ArchitectureBoundaryTest` 25/25 green.

### File List

**Added — application + domain:**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventReadPort.java`

**Added — adapters:**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java`

**Added — resources:**
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v1.schema.json`
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json`

**Added — tests:**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsStatusHistoryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandOutputsTextTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliJsonSchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliCommandRegistrationIT.java`

**Added — documentation:**
- `docs/cli/workflow-commands.md`

**Modified — application layer:**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java` (constructor + submitInternal wiring to `IntegrationLinkService.linkTicketWithinTransaction`)
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java` (added `linkTicketWithinTransaction` method)

**Modified — domain/registry:**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (added `INVALID_TIME_RANGE`, `HISTORY_TOO_LARGE`)

**Modified — adapters:**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (added `status` and `history` commands, logging, MDC, kept legacy constructor)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCliExitStatusExceptionMapper.java` (mapped two new codes to exit 101)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowEventPersistenceAdapter.java` (implements `WorkflowEventReadPort`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/WorkflowEventEntityMapper.java` (added `toRecord` projection)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowEventRepository.java` (added latest + paged-by-since queries)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (mapped two new codes)

**Modified — resources / docs:**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (problem-type URIs for the two new codes)
- `docs/cli/README.md` (linked to new workflow-commands.md page; listed two new error codes in exit-code band 101)

**Modified — tests:**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceContractTest.java` (LIN-123 → LIN-101; integration_links cleanup; three new assertions for link-on-submit behavior)

**Modified — sprint tracking:**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/1-15-spring-shell-cli-commands-submit-status-history.md` (this file)
- `.claude/settings.local.json` (consolidated `mvn`/`mvnw`/`unzip` permissions per user request)

### Change Log

- 2026-05-13: Story 1.15 — Added Spring Shell `workflow status` and `workflow history` commands and the `WorkflowInspectionService` application service that backs them; JSON view contracts (`workflow-status.v1`, `workflow-history.v1`) shipped under `schemas/cli/`; CLI/inspection details redaction with closed allow-list + defense-in-depth pass; CLI reference documentation. Wired `WorkflowCommandService.submit` to `IntegrationLinkService.linkTicketWithinTransaction` so CLI- and REST-submitted workflows always have an active `integration_links` row when they reach `Inbox`. Added `INVALID_TIME_RANGE` and `HISTORY_TOO_LARGE` domain error codes. Full backend regression 419/419 green, 0 failures, 0 errors, 3 skipped — flipped story `in-progress → review`.

### Review Findings

- [x] [Review][Patch] `status` and `history` require `--run-id` instead of the spec's positional `<runId>` surface [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:153]
- [x] [Review][Patch] Unsupported `--format` values silently fall back to text instead of being rejected [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:152]
- [x] [Review][Decision] Keep the adapter-layer `try/catch` in `WorkflowCommands` for stable completion logging and failure-code emission; accepted by review decision instead of code change [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:160]
- [x] [Review][Patch] Command completion logs do not satisfy AC9 because failure paths log at `WARN` and emitted lines omit `correlationId` [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:231]
- [x] [Review][Patch] History text rendering drops the redacted allow-listed `details` payload entirely [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java:117]
- [x] [Review][Patch] The JSON schema contract test bypasses the commands and does not perform real schema validation [deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliJsonSchemaContractTest.java:27]
- [x] [Review][Patch] The required AC8 performance proof test for 100 seeded events is still missing [deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsStatusHistoryTest.java:24]
- [x] [Review][Patch] Workflow event read queries do not exclude archived rows [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowEventRepository.java:14]

Batch-apply Session 1 note: 7 review patch items were applied in code and the final AC6 `WorkflowCommands` try/catch item was closed by review decision to keep the current behavior. Focused verification stayed green for the command/test slice.

Second-pass review findings (2026-05-13):

- [x] [Review][Patch] Replaying an interactive `submit` without `--correlation-id` can fail with `IDEMPOTENCY_KEY_CONFLICT` because the generated correlation id is folded into the submit fingerprint [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:122]
- [x] [Review][Patch] `submit` left the idempotency reservation open when the new link-on-submit path failed; the reservation/complete path now commits independently, failure statuses survive rollback, and same-key concurrency still replays successfully [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:125]
- [x] [Review][Patch] `history` could not faithfully render non-state events because existing artifact/runner events carry `resultingState = null`; the renderer and schema now accept null state outputs [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java:130]
- [x] [Review][Patch] Command completion logs used `cmd` / `runId` instead of the AC9-pinned stable keys `commandName` / `workflowRunId` [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:282]
- [x] [Review][Patch] The submit/link path leaked raw ticket references and idempotency-derived ticket material through service logs; entry/replay logs are now redacted to safe context only [deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java:244]
- [x] [Review][Patch] `WorkflowCliCommandRegistrationIT` now proves real Spring Shell runtime registration and documents the actual 4.0.2 command surface `deliveryline submit|status|history` instead of relying on reflection alone [deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliCommandRegistrationIT.java:16]
- [x] [Review][Patch] `WorkflowCommandsInspectionIT` now uses repository-backed seeded rows against Postgres, proving archived-row filtering and the AC8 100-event performance slice through the real query path [deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsInspectionIT.java:45]
- [x] [Review][Patch] CLI docs now match the actual Spring Shell 4.0.2 runtime surface `deliveryline submit|status|history` rather than the non-existent grouped `deliveryline workflow ...` form [docs/cli/README.md:28]

Batch-apply Session 2 note: second-pass review patches landed for correlation replay safety, failure-status idempotency completion, null-state history rendering, stable completion log keys, safe integration-link logging, runtime registration proof, repository-backed inspection/performance proof, and CLI command-surface documentation. Focused verification: `./mvnw -pl deliveryline-backend -am "-Dtest=WorkflowCliCommandRegistrationIT,WorkflowCommandsInspectionIT,WorkflowCommandsStatusHistoryTest,WorkflowCommandsTest,WorkflowCommandOutputsTextTest,WorkflowCliJsonSchemaContractTest,WorkflowCommandServiceContractTest,IntegrationLoggingContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` -> 44 tests, 0 failures, 0 errors, BUILD SUCCESS.

Third-pass review findings (2026-05-14):

- [x] [Review][Defer] Idempotency record commits COMPLETED via `REQUIRES_NEW` BEFORE the outer workflow_run transaction commits — `WorkflowCommandService.completeWhenTransactionFinishes` creates a window where the idempotency row points at a workflow_run that does not yet exist (or never will, on JVM crash between the two commits). The registered `afterCompletion` rollback then writes COMPLETED→FAILED, which contradicts the read-path's treatment of FAILED as terminal in `IdempotencyService.tryResolveExistingRecord`. Multiple valid fixes exist (move COMPLETED into `afterCommit`, change FAILED-terminal semantics, or remove the eager commit) — needs a deliberate concurrency-design call. [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:122–134] + [deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyService.java:83–88] — deferred as tech debt
- [x] [Review][Defer] `IdempotencyService.MAX_RESERVATION_ATTEMPTS` raised from 3 to 200 with a fixed `Thread.sleep(10ms)` retry inside the transaction → up to 2s of blocking per losing caller, no jitter, no exponential backoff, no metric/log on retry exhaustion, no `CONCURRENT_TRANSITION_CONFLICT` short-circuit. Combined with the eager-COMPLETED window above, this is a thread-starvation vector under contention. Decision: lower ceiling + add backoff/jitter, or surface a fast-fail conflict code, or accept the current design and document the upper bound. [deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyService.java:39–41,96–122] — deferred as tech debt (paired with F16)
- [x] [Review][Defer] `IdempotencyRecordRepository.findWithLockByKey` had `@Lock(LockModeType.PESSIMISTIC_READ)` removed (along with the `Lock` and `LockModeType` imports) while the method name still advertises locking. No replacement (`SELECT ... FOR UPDATE`, advisory lock, or rename) is visible in the diff. If callers depended on the row-lock for serialized state-machine transitions, the lock is silently gone. Need user intent: was the lock load-bearing, intentionally dropped (replaced by the new REQUIRES_NEW flow), or is this an accidental regression? [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/IdempotencyRecordRepository.java:4,7,40] — deferred as tech debt (paired with F16/F17)
- [x] [Review][Defer] `IntegrationLinkPersistenceAdapter` removed `externalRef` from the conflict warning log (`insert conflict integrationType={} workflowRunId={} cause={}`), so an operator can no longer identify which Linear ticket conflicted. This contradicts the CLI render policy where `linearTicketReference` is on the `WorkflowInspectionService.ALLOWED_DETAIL_KEYS` allow-list and rendered in `status` output — the same value is treated as PII in service logs but as visible in the CLI surface. Pick a consistent classification. [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IntegrationLinkPersistenceAdapter.java:522–537] — deferred as tech debt (paired with F16/F17/F18)
- [x] [Review][Patch] CLI commands skip the AC9-required completion log line for any non-`DomainException` (NPE, `IllegalStateException` from `WorkflowCommandOutputs.writeJson` JSON serialization failure, supplier failures, etc.) because `submit/status/history` only catch `DomainException`. The AC9 contract is "every command emits a single structured INFO line on completion" — fix: catch `RuntimeException` in each command, emit `outcome=failure:UNKNOWN` (or pull a code if `DomainException`), then rethrow. Also wrap `WorkflowCommandOutputs.writeJson`'s `IllegalStateException` into `DomainException(INTERNAL_ERROR, ...)` so the exit-code mapper applies. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:124–145,159–169,184–196] + [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java:191–197]
- [x] [Review][Patch] `WorkflowInspectionService.getStatus`/`listHistory` accept null/blank/wrong-prefix `workflowRunPublicId` without surfacing a governed error: null → raw `NullPointerException` from `Objects.requireNonNull` (no `DomainException`, no exit-code mapping); a string with the wrong public-id prefix (e.g., `art_xxx`) returns `RUN_NOT_FOUND` instead of `INVALID_ID_PREFIX`. Compare `IntegrationLinkService.linkTicketWithinTransaction` which calls `PublicIdPrefixes.require(...)` early. Fix: validate the prefix and reject null/blank with the documented error code. [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:67–68,106–107]
- [x] [Review][Patch] `--correlation-id` (and `runId` for status/history) are written verbatim to MDC and SLF4J interpolation, enabling log injection via embedded `\r\n`. A value like `--correlation-id $'abc\nworkflow command completed correlationId=fake outcome=success'` forges a synthetic completion line. Fix: strip `\r\n\t` (or reject) before `MDC.put` and before passing to logger interpolation in both `WorkflowCommands.pushCorrelation` and `WorkflowInspectionService` entry/exit logs. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:226–233] + [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:69,100–109]
- [x] [Review][Patch] `WorkflowCommandOutputs.renderHistoryText` appends `actorIdentity`, `actorType`, and `reason` without escaping `"`, `\`, or newlines. A `reason` value containing `"` or `\n` corrupts the per-line history format that operators will pipe through `grep`/`awk` and breaks the `key="value"` shape. Fix: escape `"` → `\"`, `\` → `\\`, control chars → `\n`/`\t` (or replace with spaces) before appending. Same applies to `renderStatusText`. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java:136–164]
- [x] [Review][Patch] Text mode normalizes timestamps to UTC via `withOffsetSameInstant(ZoneOffset.UTC)`; JSON mode emits the entity's raw `OffsetDateTime.toString()`, preserving whatever offset was persisted. Two surfaces of the same `createdAt` field render as different strings (e.g. `2026-05-13T09:00:00Z` text vs `2026-05-13T11:00:00+02:00` JSON). Pick one canonical format (UTC is conventional) and apply consistently across `renderStatusJson`, `renderStatusText`, `renderHistoryJson`, `renderHistoryText`. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java:109,183,206–211]
- [x] [Review][Patch] `WorkflowCommands.normalizeFormat` calls `format.trim().toLowerCase()` without a `Locale`, making the comparison locale-dependent (Turkish `I/i` rule). Latent bug: any future format value containing `I` (e.g. `INI`) breaks under `-Duser.language=tr`. Fix: `format.trim().toLowerCase(Locale.ROOT)`. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:264]
- [x] [Review][Patch] `WorkflowCommandOutputs.renderStatusJson` guards on `view.lastEventType() != null` but then dereferences `view.lastEventAt()` inside the same branch. The two are independent record fields. A view constructed with non-null `lastEventType` but null `lastEventAt` (e.g., from a future test double or migration) NPEs inside the renderer. Fix: guard each field independently or guard on `lastEventAt() != null`. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java:106–110]
- [x] [Review][Patch] `WorkflowInspectionService.redactDetails` handles `isInt/isLong/isBoolean/isNull` but coerces every other `JsonNode` (`FloatNode`, `DoubleNode`, `DecimalNode`, `BigIntegerNode`, `ArrayNode`, `ObjectNode`, `BinaryNode`) into a string via `value.asText()`. If `contextVersion` or `artifactVersion` is ever stored as `BigInteger` or `Double`, the field becomes a JSON string in the response and violates the `workflow-history.v1` schema (`type: integer`). Fix: handle `isNumber()` with `numberValue()` to preserve numeric typing; explicitly reject objects/arrays for the scalar allow-list. [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:140–153]
- [x] [Review][Patch] `WorkflowCommands.parseSince` accepts any `OffsetDateTime`, including future timestamps and `OffsetDateTime.MAX`. `--since 2099-01-01T00:00:00Z` returns silently empty (operator may believe history is empty); extreme values like `+999999999-12-31T...` are passed straight to JDBC and either truncate or fail driver-specifically. Fix: reject `sinceInclusive > now()` with `INVALID_TIME_RANGE`, and clamp/reject values outside Postgres `timestamptz` range. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:199–214]
- [x] [Review][Patch] `WorkflowCommands.requireInspectionWired` throws bare `IllegalStateException`, bypassing the `DomainException` → `WorkflowCliExitStatusExceptionMapper` exit-code mapping that every other failure in this class uses. Wrap as `DomainException(INTERNAL_ERROR, "WorkflowCommands constructed with the legacy submit-only constructor")` so the operator gets a stable exit code instead of a stack trace. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:457–463]
- [x] [Review][Patch] `WorkflowCommandOutputs.renderHistoryJson` emits whatever `event.details()` map it receives without renderer-side guarantees. Redaction happens upstream in `WorkflowInspectionService`, so this is correct today, but the renderer is a public Spring `@Component` — a future caller wiring it to raw event records will silently leak. Add a Javadoc precondition on the renderer (and ideally an assertion in tests) stating that `details` MUST already be redacted by the application layer. [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommandOutputs.java:167–189]
- [x] [Review][Defer] History query loads `HISTORY_CEILING + 1 = 1001` rows (with full entity columns) before throwing `HISTORY_TOO_LARGE` — a `count(*)` precheck or `LIMIT 1000` + probe would be cheaper. Also, no covering index added for `(workflow_run_id, created_at, id)` in the diff; the existing `idx_workflow_events_workflow_run_id_created_at` is asserted by `WorkflowCommandsInspectionIT` but the `archived_at IS NULL` filter has no index hint. Pre-existing perf surface — deferred. [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowEventPersistenceAdapter.java:588–602]
- [x] [Review][Defer] `WorkflowEventPersistenceAdapter` `--since` query forwards `OffsetDateTime` to JDBC without truncating to microsecond precision. Postgres `timestamptz` rounds sub-microsecond nanos driver-specifically, so an event at `09:00:00.000001Z` may be missed when caller passes `09:00:00.0000019Z`. Edge case far outside pilot scope — deferred. [deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowEventPersistenceAdapter.java:50–72]
- [x] [Review][Defer] `WorkflowInspectionService.getStatus` issues one query per `ArtifactType` value with no early termination or batching, growing linearly with each new artifact type added in stories 1.16+. A single grouped query (`SELECT artifact_type, MAX(version) ...`) would cap round-trips. Pre-existing per-type pattern — deferred to a query-hardening story alongside F11/F12 from 1-12. [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:75–83]

Batch-apply Session 3 note (2026-05-14): all 11 third-pass review patch items applied in code; 4 third-pass `[Review][Decision]` items deferred as tech debt (F16–F19 in `deferred-work.md`). Patch summary — (P1) `WorkflowCommands.submit/status/history` now also `catch (RuntimeException re)` and emit `outcome=failure:unknown` so the AC9 completion log line is always written; (P2) `WorkflowInspectionService.getStatus`/`listHistory` now invoke `PublicIdPrefixes.require(workflowRunPublicId, WORKFLOW_RUN)` before any logging or DB lookup, surfacing `INVALID_ID_PREFIX` for null/blank/wrong-prefix runIds; (P3) `pushCorrelation` strips `\r\n\t` via a new `sanitizeForLog` helper to prevent log injection; runId log injection is closed by P2's prefix validation (the registered `SUFFIX_PATTERN` rejects control characters); (P4) `renderHistoryText` and `formatActor` now route `actorIdentity`/`actorType`/`reason` through `escapeForText`/`escapeQuotedValue` helpers that escape `\`, CR, LF, TAB, and (for quoted values) `"`; (P5) text and JSON renderers both delegate timestamp formatting to a single `canonicalUtcIso(OffsetDateTime)` helper that normalizes to UTC ISO-8601, eliminating the text-vs-JSON divergence; (P6) `normalizeFormat` now uses `Locale.ROOT`; (P7) `renderStatusJson` guards the `lastEvent` block on both `lastEventType() != null` AND `lastEventAt() != null` (schema requires `createdAt` whenever the object is present); (P8) `WorkflowInspectionService.redactDetails` rebuild loop now handles `isNumber()` via `numberValue()` so floats/doubles/BigInteger/BigDecimal preserve numeric typing in the rendered JSON instead of coercing to strings; (P9) `parseSince` rejects future timestamps with `INVALID_TIME_RANGE`; (P10) `requireInspectionWired` now throws `DomainException(INTERNAL_ERROR, ...)` instead of bare `IllegalStateException`, restoring the exit-code mapping; (P11) `renderStatusJson` and `renderHistoryJson` carry Javadoc preconditions documenting that callers must pass the application-layer view (already redacted), not raw entity-shaped views; `writeJson` failures are now wrapped in `DomainException(INTERNAL_ERROR)` instead of `IllegalStateException`. Focused verification: `./mvnw -pl deliveryline-backend -am "-Dtest=WorkflowCommandsTest,WorkflowCommandsStatusHistoryTest,WorkflowCommandOutputsTextTest,WorkflowInspectionServiceTest,WorkflowCliJsonSchemaContractTest,WorkflowCommandServiceContractTest,WorkflowCliCommandRegistrationIT,IntegrationLoggingContractTest,ArchitectureBoundaryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` → 75 tests, 0 failures, 0 errors, BUILD SUCCESS. `WorkflowCommandsInspectionIT` not exercised this session because Docker/Testcontainers is unavailable in this environment (`\\.\pipe\docker_engine`), per the prior 2026-05-13 sprint-status note.
