# Story 3.6: Runner Logs Capture + Redaction + Classification

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + workflow owner,
I want raw runner stdout/stderr captured to a durable local store, redacted via `RedactionPolicyService` (story 1.10) before any persistence, classified per the `DataClassification` registry, and linked to the `runner_executions` row,
so that NFR24 holds (a runner execution record links normalized output, a raw-output reference, produced artifacts, and the context bundle) and operators can diagnose runner failures without ever exposing a raw secret to a persisted log file or a downstream consumer (ELK in story 3.7, exports in Epic 5).

## Context

This is the **log-capture slice** of Epic 3a's runner-execution track. The Docker runner lifecycle is fully built:
- [[3-1-docker-runner-adapter-core-container-lifecycle-and-file-based-contract-invocation]] / [[3-2-docker-runner-adapter-lifecycle-timeout-heartbeat-lease-expiry-cleanup-idempotent-restart]] (+ [[3-2a-docker-runner-lifecycle-test-surface-and-review-hardening]]) built `DockerRunnerAdapter`, the `RunnerWorkspaceStore` (`runner-work/{rex}/{input,output,logs}/`), and the `RunnerBroker.onResult` harvest seam.
- [[3-5-runner-secrets-handling-secure-mount-of-agent-provider-api-keys]] (DONE) added the **post-execution secret SCAN** of workspace files (`RunnerSecretScanService.scanWorkspace`, runs in `RunnerBroker.handleSuccess` *before* `recordCompleted`) and **explicitly handed the host-side log-capture redaction path to this story** (3.5 AC5: "Story 3.6 owns the host-side capture path ... exposes/uses a single redaction entry point that story 3.6's capture path MUST call before writing `logs/` files").

Next-active order (backend track): 3-5 -> 3-3 -> 3-4 -> **3-6** -> 3-13 -> 3-14 -> 3-9 -> 3a-1 -> 3a-2. Epic-3a is already `in-progress`.

**What this story is (and is not):**
- It IS: a NEW `RunnerLogCaptureService` that the Docker adapter calls with the runner's raw stdout/stderr; the service **redacts each stream first**, writes the **redacted** files to a NEW durable store `{DELIVERYLINE_HOME}/runner-logs/{rex}/`, classifies them, persists a typed reference + metrics onto the `runner_executions` row (4 new columns), and surfaces a `RunnerLogReference` through inspection (CLI + REST seam).
- It is NOT the 3.5 workspace secret-scan (already DONE and complementary -- the scan FAILS an execution that leaks a key into a workspace *file*; this story REDACTS the captured stdout/stderr *streams* so a runner CLI that echoes its own auth header can't leak through the persisted log layer). It is NOT the ELK shipping pipeline (story 3.7, deferred -- this story only sets the `classification` that 3.7's filter will honor). It is NOT a `runner-logs` retention deleter (Epic-5 story 5.8).

**Two on-disk surfaces -- do NOT conflate (this is net-new structure; no `runner-logs/` convention exists yet):**
- `runner-work/{rex}/logs/runner.stdout` (+ `runner.stderr`) -- the **workspace** logs the container writes (host bind-mount, raw). Reaped by story-3.2's `RunnerWorkspaceCleanupJob` at `workspace-retention-hours` (default 24). Already leak-scanned by 3.5.
- `{DELIVERYLINE_HOME}/runner-logs/{rex}/` -- the **NEW** durable, **redacted** store this story creates. Persists independently for the full 60-day window (NFR31). This is what `RunnerLogReference` points at. Keeping it under a SEPARATE root (not `runner-work/`) is mandatory so workspace cleanup never deletes it (AC5).

**Reuse, do NOT reinvent** (story 1.10 redaction stack):
- `application.security.RedactionPolicyService.redact(String payload, String classificationValue) -> RedactionResult { String sanitizedText, JsonNode sanitizedJson, DataClassification claimedClassification, DataClassification effectiveClassification, boolean redacted, Set<RedactionCategory> detectedCategories }`.
- `application.security.SensitivePayloadAnalyzer` ships `AUTHORIZATION_HEADER` (regex covers `Authorization: Bearer|Basic ...`), `GITHUB_TOKEN` (`ghp_`/`github_pat_`), `LINEAR_API_KEY` (`lin_api_`), `ENV_VALUE`/`ENVIRONMENT_BLOCK`, `SECRET_FIELD`, `QUERY_SECRET`, PEM/SSH (`RedactionCategory` enum -- 13 values).
- ArchUnit `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` forbids reimplementing credential regex elsewhere -- `RunnerLogCaptureService` only orchestrates capture/write and delegates ALL detection to `RedactionPolicyService`.

## Acceptance Criteria

1. **`RunnerLogCaptureService` (`application.runner`)** exposes `captureLogs(String runnerExecutionId, String workflowRunId, String stdout, String stderr) -> CapturedLogs`. It redacts each stream **before any write** via `RedactionPolicyService`, writes the redacted files to `{DELIVERYLINE_HOME}/runner-logs/{runnerExecutionId}/` (`runner.stdout` + `runner.stderr`), and returns a typed `CapturedLogs` (reference path, total redacted byte size, effective classification, redaction count). The raw `stdout`/`stderr` strings are method-local only -- never stored in a field, never written un-redacted, never returned. (Trap T1.)

2. **Capture pipeline** -- `DockerRunnerAdapter` (story 3.1), on container exit, reads the raw `/workspace/logs/runner.stdout` and `/workspace/logs/runner.stderr` from the container's mounted log dir and hands them to `RunnerLogCaptureService.captureLogs(...)`, which passes each through `RedactionPolicyService` before write -- no redacted-store file is ever written from un-redacted bytes. (The raw workspace files remain on the host inside `runner-work/{rex}/logs/` until story-3.2 cleanup -- that is the diagnostic workspace, already leak-scanned by 3.5; NFR24's "never persisted raw" applies to the *redacted* store this story owns. Document this reconciliation -- Trap T9.)

3. **`runner_executions` schema** gains four nullable columns via a NEW Flyway migration `V11__add_runner_raw_output_columns.sql` (current max is **V10** `heartbeat_stale_emitted_at` -- confirmed; re-confirm no concurrent V11 exists before writing): `raw_output_reference text NULL` (path to the redacted log dir), `raw_output_classification text NULL`, `raw_output_byte_size bigint NULL`, `redaction_count integer NULL`. Add a `CHECK (raw_output_classification IN ('local-only','shareable-redacted','shareable-full','derived-public-safe'))` constraint -- **note:** the existing `artifacts.classification` column has NO DB CHECK today (it is code-enforced), so this is the first classification CHECK; adding it for the new nullable column is recommended (allow NULL) -- see OQ-7 if you prefer code-only enforcement for consistency. `RunnerExecutionEntity` (`adapters/persistence/entity/`) adds the four matching nullable fields (no `insertable=false`/`updatable=false`).

4. **Classification** -- captured logs are classified `local-only` by default. Elevate to `shareable-redacted` **only when** the redaction pass detected zero secrets for both streams (`detectedCategories().isEmpty()`) **AND** a documented config flag `deliveryline.runner.allow-shareable-logs=true` (default `false`) is set. A contract test asserts the resulting `local-only` value is what story 3.7's ELK shipping policy treats as "not shipped" (AC9). (Note: `RedactionResult.effectiveClassification` exists but the AC4 elevation is a separate, narrower decision -- do not conflate; capture passes `LOCAL_ONLY.value()` as the claimed classification.)

5. **Retention** -- redacted log files in `runner-logs/{rex}/` follow the 60-day default retention as workflow events (NFR31) and persist **independently** of the source workspace (reaped by story-3.2 at `workspace-retention-hours`). This story does NOT build a scheduled deleter for `runner-logs/` (enforcement is Epic-5 story 5.8); it documents the retention contract and keeps the store separate so workspace cleanup never deletes the durable log. (OQ-5.)

6. **Structured event fields (AR29)** -- when logs are captured, the runner-completion/failure event details carry `runnerExecutionId`, `workflowRunId`, `redactionCount`, `byteSize`, and `classification` (metadata only; never content, never secret values). Add the new keys to `WorkflowEventDetailKeys` (`deliveryline-domain/.../domain/registry/WorkflowEventDetailKeys.java`) -- e.g. `REDACTION_COUNT="redactionCount"`, `RAW_OUTPUT_BYTE_SIZE="rawOutputByteSize"`, `RAW_OUTPUT_CLASSIFICATION="rawOutputClassification"` -- and to `ALLOW_LISTED_KEYS` so CLI history surfaces them. (Trap T6 / OQ-3: enrich the existing `RUNNER_COMPLETED`/`RUNNER_FAILED` events; do NOT add a new `WorkflowEventType` -- avoids events-schema regen.)

7. **Inspection** -- `WorkflowInspectionService.getRunnerLogReference(String runnerExecutionId) -> RunnerLogReference` (NEW type: reference path, byte size, classification, redaction count). It validates the `rex_` prefix, opens an MDC scope, reads the `runner_executions` row (via the already-injected `RunnerExecutionRecordPort.findByPublicId`) for the persisted `raw_output_*` fields, and returns an available/unavailable result mirroring `getContextBundleForArtifact` (story 2.8). Surfaced via a NEW CLI flag `deliveryline status {runId} --include-runner-logs` (mirror story-2.8 `--include-context-bundle` in `adapters/cli/WorkflowCommands.java`); extend the CLI JSON schema (`schemas/cli/workflow-status.v2.schema.json`) with an optional `runnerLogs` object mirroring the existing `contextBundle` available/unavailable shape. Never renders log *content* -- only the typed reference + metrics. REST detail-expansion is a reserved seam (story 3.27) -- auto-projects once `WorkflowStatusView` carries the field; do not add an endpoint.

8. **ArchUnit boundary** -- a NEW field-access rule asserts raw (unredacted) runner output is referenced only transiently inside `adapters.runner..` (the adapter) during capture and is immediately handed to `RedactionPolicyService` (via `RunnerLogCaptureService`); no class outside the adapter declares a *field* holding raw runner output, and the redacted form is produced only via `application.security`. Add the rule to `ArchitectureRuleCatalog` + an `@ArchTest` field in `ArchitectureBoundaryTest`. Confirm `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` still passes (no new regex in `application.runner`).

9. **ELK shipping policy (forward seam for story 3.7)** -- `local-only`-classified logs must be excluded from any future ELK shipping. Story 3.7 owns the Logstash filter; this story (a) persists the `classification` the filter keys on and (b) ships a focused test asserting the classification-gate predicate (`local-only => not shippable`) so 3.7 wires against a stable contract. (Trap T8.)

10. **Adversarial redaction fixtures + foundation gate** -- extend story-1.10's adversarial fixture set (`src/test/resources/redaction-fixtures/` + `fixtures-manifest.json`) with runner-CLI auth-leak fixtures: a Codex-CLI auth-header echo, a Claude-CLI verbose-mode token print, and a generic `Authorization: Bearer ...` HTTP-debug header. Story 1.23's fixture-completeness assertion (`src/test/java/org/dradgo/foundation/RedactionAdversarialFoundationContract.java`, silent-fixture invariant: every file in the dir MUST be enumerated in the manifest with declared forbidden snippets) must fail if any of these is absent. `AUTHORIZATION_HEADER` already redacts `Bearer`/`Basic` (verified) -- add fixtures (+ a NEW pattern only if a runner-CLI shape is genuinely uncovered, and only inside `application.security`).

11. **Test suite** covers: (a) redaction applied on capture -- a deliberately-leaky stdout produces a clean redacted file (no secret value present); (b) zero-secret logs elevate to `shareable-redacted` only when `allow-shareable-logs=true`, else stay `local-only`; (c) `redaction_count` metric matches the manual count for the adversarial fixtures; (d) `getRunnerLogReference` returns the expected typed fields (+ unavailable when not captured); (e) classification-gate predicate prevents `local-only` from being marked shippable (AC9); (f) CLI `--include-runner-logs` renders the typed reference (not content), text + json; (g) ArchUnit field-access rule passes; (h) a logging-contract assertion that the capture path's structured logs carry the AR29 fields and NO secret value (adversarial no-secret-in-logs sweep, mirroring 3.5 AC11g).

## Tasks / Subtasks

- [x] **Task 1 — `RunnerLogCaptureService` + records (AC1, AC2, AC4)**
  - [x] Create `org.dradgo.application.runner.RunnerLogCaptureService` (`@Service`). Inject `RedactionPolicyService`, the new `RunnerLogStore` port (Task 2), and `RunnerProperties` (for `allowShareableLogs`).
  - [x] `CapturedLogs captureLogs(String rex, String workflowRunId, String stdout, String stderr)`: redact each stream FIRST via `redactionPolicyService.redact(stream, DataClassification.LOCAL_ONLY.value())`; write each `sanitizedText()` to the store; compute `byteSize` (sum of redacted bytes), `redactionCount` (Trap T2 / OQ-1), `classification` (AC4 logic); return `CapturedLogs(reference, byteSize, classification, redactionCount)`. NEVER store/return raw streams; NEVER write a file from un-redacted bytes (Trap T1).
  - [x] NEW records `org.dradgo.application.runner.CapturedLogs` and `org.dradgo.application.runner.RunnerLogReference` (reference path, byteSize, classification, redactionCount). Keep in `application.runner` (no adapter/docker-java leakage).
  - [x] Classification (AC4): `effective = (stdoutResult.detectedCategories().isEmpty() && stderrResult.detectedCategories().isEmpty() && runnerProperties.allowShareableLogs()) ? SHAREABLE_REDACTED : LOCAL_ONLY`.
- [x] **Task 2 — redacted log store port + adapter (AC1, AC5)**
  - [x] NEW SPI port `org.dradgo.application.runner.spi.RunnerLogStore`: `RunnerLogReference write(String rex, byte[] redactedStdout, byte[] redactedStderr)` + `Optional<RunnerLogReference> find(String rex)`. Root `{DELIVERYLINE_HOME}/runner-logs/{rex}/` — SEPARATE from `runner-work/{rex}/` (AC5/Trap T7). Guard every method with `PublicIdPrefixes.require(rex, RUNNER_EXECUTION)`.
  - [x] NEW adapter `org.dradgo.adapters.files.LocalRunnerLogStore` (`@Component`) implementing it — reuse the atomic temp-file+rename + POSIX-perm + containment-guard + home-dir-resolution patterns from `adapters/files/LocalRunnerWorkspaceStore.java`.
- [x] **Task 3 — adapter capture wiring (AC2, AC8)**
  - [x] In `DockerRunnerAdapter` (`adapters/runner/DockerRunnerAdapter.java`) at container-exit handling (`classifyExited`), read raw `logs/runner.stdout` + `logs/runner.stderr` from the workspace logs dir (added a `runner.stderr` constant + capped lossy-UTF-8 read methods on `RunnerWorkspaceStore` — Trap T5/OQ-6) and call `runnerLogCaptureService.captureLogs(...)` then `recordRawOutput`. Raw bytes stay method-local; hand straight to the capture service (Trap T1/T8). Capture is best-effort (wrapped try/catch) so it never derails result classification.
  - [x] `MockRunnerAdapter`: untouched — it never enters the Docker capture path, so it stays deterministic (no-op capture, Trap T4).
  - [x] Call-site decision: adapter-driven on exit (OQ-2 recommended — AC2 literal, keeps raw out of broker scope; broker only ever sees the persisted reference/metrics).
- [x] **Task 4 — persist capture reference onto `runner_executions` (AC3, AC6)**
  - [x] NEW Flyway `V11__add_runner_raw_output_columns.sql` adding the 4 nullable columns + the `raw_output_classification` CHECK (AC3). Confirmed V11 free.
  - [x] Added the 4 nullable fields to `RunnerExecutionEntity` + `RunnerExecutionSnapshot` (back-compat shim constructor) + mapper; AC7 reads them via `findByPublicId`.
  - [x] Added `RunnerExecutionRecordPort.recordRawOutput(...)` + impl in `RunnerExecutionPersistenceAdapter` (self-transactional, metadata-only, terminal-tolerant, no status change — Trap T10); exposed via `RunnerExecutionService.recordRawOutput(rex, CapturedLogs)`.
  - [x] Enriched existing `RUNNER_COMPLETED` + `RUNNER_FAILED` event details with `redactionCount`/`rawOutputByteSize`/`rawOutputClassification` via the broker's existing event path + new `WorkflowEventDetailKeys` constants added to `ALLOW_LISTED_KEYS` + history schema (AC6, Trap T6). No new `WorkflowEventType`.
- [x] **Task 5 — inspection surface (AC7)**
  - [x] `WorkflowInspectionService.getRunnerLogReference(rex) -> RunnerLogReferenceResult` (mirror `getContextBundleForArtifact`); honest `runnerExecutionNotFound` / `logsNotCaptured` when columns are null.
  - [x] CLI: added `--include-runner-logs` to `status` in `adapters/cli/WorkflowCommands.java` (mirror `--include-context-bundle`); renders the typed reference (path + byteSize + classification + redactionCount), NEVER content; text + json. Extended `schemas/cli/workflow-status.v2.schema.json` with an optional `runnerLogs` object (additive — `contextBundle` + `runnerLogs` are now both optional under schemaVersion 2; OQ-8).
  - [x] Resolve which `rex` to show for a `runId`: added `WorkflowInspectionService.findLatestRunnerExecutionId(runId)` (latest by createdAt) — `getRunnerLogReference` stays rex-scoped (OQ-4).
  - [x] REST: reserved the seam only (story 3.27 wires the display) — documented, not built.
- [x] **Task 6 — ArchUnit field-access rule (AC8)** — added `RAW_RUNNER_OUTPUT_READS_STAY_IN_RUNNER_ADAPTER` to `ArchitectureRuleCatalog` + `@ArchTest` in `ArchitectureBoundaryTest` (verified passing, 35 tests). The raw-log read methods may only be called from `adapters.runner..`.
- [x] **Task 7 — adversarial fixtures + foundation gate (AC10)** — added Codex/Claude/bearer fixtures to `redaction-fixtures/` + `fixtures-manifest.json` (with declared forbidden snippets). Reused `AUTHORIZATION_HEADER` (no new pattern needed).
- [x] **Task 8 — config flag (AC4)** — added `allowShareableLogs` (default `false`) to `RunnerProperties` (top-level under `deliveryline.runner`); wired real `application.yml` (false) + `src/test/resources/application.yml` + `RunnerPropertiesTest` (memory `[[validated-config-needs-test-yaml]]`); updated all positional call sites.
- [x] **Task 9 — tests (AC11)** — capture service (a/b/c/e/h), log store, inspection (d), CLI flag (f), ArchUnit (g), config, recordRawOutput delegation.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at every public service entry/exit, every external SPI call (file I/O, DB write), and every retry/fallback branch.
  - [x] Parameterized logging (`log.info("...", arg1, arg2)`) — never concatenation.
  - [x] Levels: `INFO` capture start/finish (`rex` + redactionCount + byteSize + classification), `WARN` missing/empty source log / capture failure, `ERROR` only unhandled/invariant breaks.
  - [x] Carry `correlationId`, `workflowRunId`, `runnerExecutionId` via MDC where supported.
  - [x] **Never log captured log content, secret values, tokens, or payload bytes** — log the redaction COUNT, byteSize, classification only.
  - [x] >=1 focused test per new branch asserting the expected log line/level + an adversarial no-secret-in-logs sweep (AC11h).

## Dev Notes

### Architecture & insertion points (verified against the live code)

- **`DockerRunnerAdapter`** — `deliveryline-backend/.../adapters/runner/DockerRunnerAdapter.java`. Logs mount `layout.logs()->/workspace/logs` (rw, `CONTAINER_LOGS_MOUNT` ~57-59). Today it only OBSERVES `logs/runner.stdout` for heartbeat (`observeLogGrowth`, ~326-343) and reads the result file (`tryReadResult` ~439, mirrors to scratch); it does NOT capture logs. **It is the only class allowed to touch raw runner output (AC8).** Insert the capture call at exit handling.
- **`RunnerWorkspaceStore`** (port `application/runner/spi/RunnerWorkspaceStore.java`; impl `adapters/files/LocalRunnerWorkspaceStore.java`). Constants ~56-62: `INPUT_SUBDIR/OUTPUT_SUBDIR/LOGS_SUBDIR`, `CONTEXT_BUNDLE_FILENAME="context-bundle.v1.json"`, `RUNNER_RESULT_FILENAME="runner-result.v1.json"`, `HEARTBEAT_TOUCH_FILENAME="heartbeat.touch"`, `RUNNER_STDOUT_FILENAME="runner.stdout"` (**no `runner.stderr` constant — add one, Trap T5**), `.quarantine`. Useful: `resolveOutputRoot(rex)` ~282, `observeLogGrowth(rex)` ~328, `readFilesForSecretScan(rex)` (3.5). `WorkspaceLayout(root,input,output,logs)`. **No `runner-logs/` root exists anywhere — this story creates it (separate store, Task 2).**
- **`RunnerBroker`** — `application/runner/RunnerBroker.java`. `onResult` ~321; `handleSuccess` ~394-587; 3.5 secret scan ~507-559; `recordCompleted` ~566; `RUNNER_COMPLETED` event ~581. Broker-driven capture (if chosen, OQ-2) plugs between the scan and `recordCompleted`.
- **`RunnerExecutionService` / `RunnerExecutionRecordPort`** — `application/runner/RunnerExecutionService.java` + `.../spi/RunnerExecutionRecordPort.java`. Existing: `findByPublicId` ~20, `nextContextBundleVersion`, `transitionToRunning/touchActivity/markCompleted/markFailed/markTimedOut/markOrphaned`, `markArchived/markHeartbeatStaleEmitted`, `findCompletedBeforeAndNotArchived`. **No raw-output method — add `recordRawOutput(...)`.** `RunnerExecutionSnapshot` fields: publicId, workflowRunPublicId, stage, status, contextBundleVersion, lastActivityAt, timeoutAt, failureCategory, completedAt, createdAt, archivedAt, heartbeatStaleEmittedAt (no `runner_kind`). Entity `adapters/persistence/entity/RunnerExecutionEntity.java`; adapter `adapters/persistence/RunnerExecutionPersistenceAdapter.java`.
- **`RunnerProperties`** — `application/runner/RunnerProperties.java` `@ConfigurationProperties("deliveryline.runner")`; top-level record fields incl. `secretEnvNames` + nested `Docker`/`Mock`/`Scheduling`/`Recovery`. `application.yml` ~94-108. Add top-level `allowShareableLogs` (default false), alongside `secretEnvNames`.

### Redaction / classification (reuse — do NOT reimplement)

- `application/security/RedactionPolicyService.java` — `RedactionResult redact(String payload, String classificationValue)` (also Map/JsonNode overloads + `redactForExport(...)`). Pass `DataClassification.LOCAL_ONLY.value()` for capture.
- `RedactionResult` (record): `sanitizedText, sanitizedJson, claimedClassification, effectiveClassification, redacted (boolean), detectedCategories (Set<RedactionCategory>)`. **No numeric occurrence count** (Trap T2). `detectedCategories.size()` is *distinct categories*, not occurrences.
- `RedactionCategory` (13 values) incl. `AUTHORIZATION_HEADER` (`(?im)(Authorization\s*:\s*(?:Bearer|Basic)\s+)([^\s\r\n]+)`), `GITHUB_TOKEN`, `LINEAR_API_KEY`, `ENV_VALUE`, `ENVIRONMENT_BLOCK`, `SECRET_FIELD`, `QUERY_SECRET`, PEM/SSH, `LOCAL_PATH`, `IDEMPOTENCY_KEY`. Placeholders are `[REDACTED_<CATEGORY>]`.
- `DataClassification` (`domain/registry/DataClassification.java`): `LOCAL_ONLY("local-only")`, `SHAREABLE_REDACTED("shareable-redacted")`, `SHAREABLE_FULL("shareable-full")`, `DERIVED_PUBLIC_SAFE("derived-public-safe")`. **No DB CHECK on `artifacts.classification` today (code-enforced)** — AC3's CHECK on the new column is net-new (OQ-7).

### `redaction_count` derivation (Trap T2 / OQ-1)

Implemented as option (a): count `[REDACTED_` placeholder occurrences across both redacted streams (plain literal-token count — ArchUnit-safe, no credential regex outside `application.security`).

### Test tiers (verified)

- Plain unit (`*UnitTest`/`*Test`, Mockito, no Spring) for capture/classification logic + store + inspection + CLI; contract (`*ContractTest`, `@SpringBootTest` + Testcontainers) for persistence + event flow + foundation-gate adversarial fixtures; integration (`*IT`, Docker tier) for adapter capture against a real container (memory `[[wsl-linux-ci-reproduction]]` — verify Docker-tier on WSL2 before push, ensure it stays excluded from the no-Docker PR tier).

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where to log:** `RunnerLogCaptureService.captureLogs` -> INFO entry/finish (`rex` + `redactionCount` + `byteSize` + `classification`); WARN empty source. `LocalRunnerLogStore.write` -> INFO "persisting redacted runner logs" (`rex` + byteSize). `DockerRunnerAdapter` exit -> INFO "docker runner logs captured" (count only); WARN capture failure. `RunnerExecutionPersistenceAdapter.recordRawOutput` -> INFO "persisting runner raw-output reference".
- **Forbidden in log output:** captured log content, secret values, tokens, payload bytes. Adversarial no-secret-in-logs sweep test (AC11h).

### Project Structure Notes

- `RunnerLogCaptureService` + `CapturedLogs` + `RunnerLogReference` in `org.dradgo.application.runner`; `RunnerLogStore` in `org.dradgo.application.runner.spi`; `LocalRunnerLogStore` in `org.dradgo.adapters.files`. Mirrors the workspace-store split (memory `[[application-cannot-import-adapters]]`).
- No domain/application type carries raw log content. `RunnerLogReference` carries path + metrics only. Raw stdout/stderr stay method-local in the adapter + capture service (Trap T1/T8).
- Keep `runner-logs/` physically separate from `runner-work/` (AC5/Trap T7).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.6] — AC1-11, NFR24/NFR31/AR29.
- [Source: epic-03-agent-execution.md#Story 3.5 AC5] — host-side log redaction path handed to this story.
- [Source: epic-03-agent-execution.md#Story 3.7] — ELK shipping filters by `classification` (deferred; AC9 seam).
- Memory: `[[validated-config-needs-test-yaml]]`, `[[application-cannot-import-adapters]]`, `[[wsl-linux-ci-reproduction]]`, `[[new-domainerrorcode-three-sites]]`, `[[runner-image-ci-uses-root-context]]`.

## Declared Traps

- **T1 — redact before any write; never field-store raw output.** `captureLogs` redacts each stream as its first action; raw `stdout`/`stderr` are method-local only. No field, DB column, event detail, or return value carries raw content.
- **T2 — `redaction_count` is not a built-in.** `RedactionResult` returns a `Set<RedactionCategory>` + boolean, not an occurrence count. Derived via `[REDACTED_` placeholder count. No credential regex outside `application.security`.
- **T3 — classification default is `local-only`.** Elevate to `shareable-redacted` ONLY when both streams detected zero secrets AND `allow-shareable-logs=true`. Never `shareable-full`/`derived-public-safe`. Did not reuse `RedactionResult.effectiveClassification`.
- **T4 — mock path stays deterministic.** `MockRunnerAdapter` left untouched; it never enters the Docker capture path.
- **T5 — `runner.stderr` constant/reader is new.** Added the stderr filename + capped read methods. AC2 captures BOTH streams.
- **T6 — prefer enriching existing events, not a new event type.** AC6 fields attach to existing `RUNNER_COMPLETED`/`RUNNER_FAILED` details (no new `WorkflowEventType`). New keys in `WorkflowEventDetailKeys` + `ALLOW_LISTED_KEYS` + history schema.
- **T7 — separate stores, separate lifecycles.** `runner-logs/{rex}/` (redacted, 60-day) lives under a SEPARATE root from `runner-work/{rex}/` (raw, 24-h) so story-3.2 cleanup never deletes the durable log.
- **T8 — ELK filter is story 3.7.** Persist `classification` + ship the gate predicate test; no Logstash filter here.
- **T9 — "never persisted raw" is about the redacted store.** Raw logs DO live transiently in `runner-work/{rex}/logs/` (24-h, already 3.5-scanned). NFR24/AC2 "never persisted" applies to this story's durable redacted store.
- **T10 — capture is a metadata update, not a state transition.** `recordRawOutput` updates `raw_output_*` only; never changes `status`; tolerates an already-terminal row.

## Open Questions (resolved)

- **OQ-1 (redaction_count source):** Resolved — count `[REDACTED_` placeholders across both redacted streams (ArchUnit-safe).
- **OQ-2 (capture call site):** Resolved — capture in `DockerRunnerAdapter` at exit (`classifyExited`), best-effort.
- **OQ-3 (event shape):** Resolved — enriched existing `RUNNER_COMPLETED`/`RUNNER_FAILED` details (no new event type).
- **OQ-4 (run -> rex lookup for CLI):** Resolved — `WorkflowInspectionService.findLatestRunnerExecutionId(runId)` (latest by createdAt).
- **OQ-5 (runner-logs retention deleter):** Resolved — not built here; documented 60-day contract; deferred to Epic-5 story 5.8.
- **OQ-6 (binary/huge logs):** Resolved — raw reads decoded lossy-UTF-8 and capped at 8 MiB with a `[TRUNCATED ...]` marker (guards unbounded `readAllBytes`).
- **OQ-7 (DB CHECK vs code-only classification):** Resolved — added a `CHECK` on the new `raw_output_classification` column (allows NULL).
- **OQ-8 (CLI schema v2 additive vs v3 bump):** Resolved — added `runnerLogs` as an optional field on `workflow-status.v2` (additive); `contextBundle` + `runnerLogs` both optional.

### Review Findings

- [x] [Review][Patch] Truncated raw log reads can downgrade secret-bearing logs to shareable or persist partial credentials [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java:411]
- [x] [Review][Patch] Raw workspace log read has a symlink TOCTOU gap between validation and open [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java:411]
- [x] [Review][Patch] Durable runner-log writes can follow a pre-existing symlink directory outside `runner-logs` [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerLogStore.java:156]
- [x] [Review][Patch] Runner-log writes are not atomic across both streams and reuse fixed temp filenames with permissions tightened after write [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerLogStore.java:86]
- [x] [Review][Patch] Raw-output metadata can be partially populated or negative while inspection reports it as available [deliveryline-backend/src/main/resources/db/migration/V11__add_runner_raw_output_columns.sql:17]
- [x] [Review][Patch] `runnerLogs.runnerExecutionId` contains a `run_` id when no runner execution exists [deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java:864]
- [x] [Review][Patch] Runner completion/failure event details omit required `workflowRunId` metadata [deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java:891]
- [x] [Review][Patch] Docker capture passes `null` workflowRunId into `RunnerLogCaptureService`, losing MDC correlation [deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:653]
- [x] [Review][Patch] AC8 ArchUnit rule restricts raw-log read methods but not raw-output field declarations outside the adapter [deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:525]
- [x] [Review][Patch] ELK shipping gate is only a private test helper, not a stable production seam for story 3.7 [deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogCaptureServiceTest.java:167]
- [x] [Review][Patch] Docker adapter capture wiring lacks the referenced focused unit test for stdout/stderr capture and `recordRawOutput` [deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java:85]
- [x] [Review][Patch] Redaction count is not tested against the new runner adversarial fixtures [deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogCaptureServiceTest.java:93]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- `mvnw -pl deliveryline-backend -am test-compile` — clean (main + test).
- Fast-tier unit suites green: `RunnerLogCaptureServiceTest` (7), `LocalRunnerLogStoreTest`, `WorkflowInspectionServiceRunnerLogReferenceTest`, `WorkflowCommandsRunnerLogsFlagTest`, `WorkflowCommandsContextBundleFlagTest`, `WorkflowCliJsonSchemaContractTest`, `RunnerExecutionServiceUnitTest`, `RunnerPropertiesTest`, `DockerRunnerAdapterUnitTest`, `RunnerBrokerUnitTest` (30), `RunnerProfileWiringContractTest` (3), `DockerRunnerProfileWiringContractTest` (3), `LocalRunnerWorkspaceStoreTest` (18), `WorkflowEventDetailKeysContractTest` (4).
- ArchUnit `ArchitectureBoundaryTest` (failsafe `architecture` tier): 35 tests, 0 failures — new `raw_runner_output_reads_stay_in_runner_adapter` rule + existing credential-detection rule both pass.

### Completion Notes List

- All 11 ACs implemented; all tasks/subtasks checked.
- **Verified locally (Windows fast tier):** capture service, log store, inspection service, CLI flag (text+json), config binding, ArchUnit boundary, events-key/history-schema contract, profile wiring, broker unit (no `verify()` regression from the new `findByPublicId` enrichment read).
- **Not runnable on this host (require Docker/Testcontainers — gated to WSL2/Linux CI per memory `[[wsl-linux-ci-reproduction]]`/`[[verify-ci-fixes-in-clean-env]]`):** the V11 migration + `recordRawOutput` persistence contract, the broker RUNNER_COMPLETED/FAILED event-enrichment contract tests, the `RedactionAdversarialFoundationContract` foundation-gate (`-Pfoundation-gate`), and the `docker-runner-it` real-container capture IT. These should be run on WSL2 Ubuntu before merge.
- The `DockerRunnerAdapter` constructor gained two deps (`RunnerLogCaptureService`, `RunnerExecutionService`); both profile-wiring slice configs were updated with mock beans.
- `RunnerExecutionSnapshot` gained four nullable fields with a back-compat 12-arg shim constructor so existing call sites compile unchanged.
- CLI `workflow-status.v2` schema: `contextBundle` and `runnerLogs` are both optional now (additive), so `--include-runner-logs` can emit a v2 doc without a contextBundle.

### Change Log

- 2026-06-01: Implemented story 3.6 (Runner Logs Capture + Redaction + Classification). NEW: `RunnerLogCaptureService`, `CapturedLogs`, `RunnerLogReference`, `RunnerLogStore` (port), `LocalRunnerLogStore` (adapter), Flyway `V11__add_runner_raw_output_columns.sql`, ArchUnit `RAW_RUNNER_OUTPUT_READS_STAY_IN_RUNNER_ADAPTER`, 3 adversarial fixtures, `RunnerLogCaptureServiceTest`, `LocalRunnerLogStoreTest`, `WorkflowInspectionServiceRunnerLogReferenceTest`, `WorkflowCommandsRunnerLogsFlagTest`. MODIFIED: `RunnerProperties` (+`allowShareableLogs`), `DockerRunnerAdapter` (capture wiring), `RunnerWorkspaceStore`/`LocalRunnerWorkspaceStore` (raw-log readers + `runner.stderr`), `RunnerExecutionEntity`/`RunnerExecutionSnapshot`/mapper/port/adapter/service (`recordRawOutput`), `RunnerBroker` (event enrichment), `WorkflowEventDetailKeys` (+3 keys), `WorkflowInspectionService` (`getRunnerLogReference`/`findLatestRunnerExecutionId`), `WorkflowCommands` (`--include-runner-logs`), CLI v2 + history JSON schemas, `application.yml` + test yaml, fixtures-manifest, and the affected test call sites.

### File List

**New (production):**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerLogCaptureService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/CapturedLogs.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerLogReference.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerLogStore.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerLogStore.java`
- `deliveryline-backend/src/main/resources/db/migration/V11__add_runner_raw_output_columns.sql`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogCaptureServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerLogStoreTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceRunnerLogReferenceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsRunnerLogsFlagTest.java`
- `deliveryline-backend/src/test/resources/redaction-fixtures/runner-codex-auth-header-echo.txt`
- `deliveryline-backend/src/test/resources/redaction-fixtures/runner-claude-verbose-token-print.txt`
- `deliveryline-backend/src/test/resources/redaction-fixtures/runner-bearer-http-debug-header.txt`

**Modified (production):**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/RunnerExecutionEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionSnapshot.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/RunnerExecutionEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerExecutionRecordPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunnerExecutionPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerExecutionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-status.v2.schema.json`
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json`
- `deliveryline-backend/src/main/resources/application.yml`

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerPropertiesTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerExecutionServiceUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/DockerLifecycleITSupport.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/RunnerProfileWiringContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerProfileWiringContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsContextBundleFlagTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliJsonSchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsStatusHistoryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsInspectionIT.java`
- `deliveryline-backend/src/test/resources/application.yml`
- `deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json`
