# Story 3.18: Workflow Batch Submission (CLI + REST)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager submitting a batch of related low-risk tickets at once,
I want `deliveryline submit-batch --tickets LIN-1,LIN-2,LIN-3` (or `--from-file tickets.txt`) and `POST /api/v1/workflows/batch` returning a batch ID + per-ticket queue/reject result with **best-effort semantics** (one rejected ticket does not fail the whole batch),
so that bulk submissions are first-class operations with clear per-ticket outcomes rather than an opaque all-or-nothing API.

## Context & Central Reconciliation (READ FIRST)

**This story is a thin ORCHESTRATION + adapter layer over already-built pieces.** It composes the story-1.15 single-submission flow (`WorkflowCommandService.submit`) once per ticket, persists a batch aggregate, and exposes the result through one new CLI command and one new REST endpoint. The queue it submits onto is **already active** (stories 3.17a + 3.17b are `done`): `WorkflowCommandService.submit` → `WorkflowOrchestrationService.dispatchSpecGeneration` → `RunnerExecutionQueue.enqueue` already happens today, returning a `RunnerDispatchResult.Queued`. **You do NOT touch the queue, the worker pool, or the dispatch path** — you fan a list of tickets through the existing single-submit method and aggregate the outcomes.

**This is an out-of-slice epic-3b pull** (epic-3b is `in-progress` but the active slice is elsewhere). Read `3-17b-runner-worker-pool-and-queue-activation.md` (the immediate predecessor, `done`) for the queue model and house discipline, and `3-16-linear-completion-sync-...md` for out-of-slice etiquette.

### HEADLINE RECONCILIATIONS (epic AC text drifts from live code — these bindings win)

1. **Flyway "V5" → really `V15`.** Epic AC4 says `V5__add_batch_submissions.sql`. The latest migration on disk is **`V14__add_queue_dispatch_carriage.sql`** (3.17b). The next free version is **`V15`**. One migration, `V15__add_batch_submissions.sql`, both creates `batch_submissions` AND adds the `runner_executions.batch_submission_id` column. [Source: deliveryline-backend/src/main/resources/db/migration/V14__add_queue_dispatch_carriage.sql]

2. **There is NO `LinearTicketRef` type.** Epic AC1 says `tickets: List<LinearTicketRef>`. In live code a ticket reference is a **plain `String`** — `SubmitWorkflowCommand.linearTicketReference` (`@NotBlank @Size(max=128)`). Do NOT invent a `LinearTicketRef` wrapper record ([[avoid wheel reinvention]]). `SubmitBatchCommand` carries **`List<String> linearTicketReferences`** (1–N, configurable max). [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/SubmitWorkflowCommand.java:9-15]

3. **`SubmitBatchCommand` is NOT a `WorkflowCommand` — keep it OUT of the sealed hierarchy.** A naive read of "batch-level idempotencyKey + fingerprint" tempts you to add `SubmitBatchCommand` to the `sealed interface WorkflowCommand permits …` list. **Do NOT.** Doing so reds `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS` (a hard-coded `Set` foundation gate) AND obligates a per-permit REST round-trip capture in that contract — both conceptually wrong: batch is an *orchestrator over* `SubmitWorkflowCommand`, not a single workflow command. The idempotency infra you reuse (`IdempotencyService.checkAndReserve`) takes a **fingerprint `String` + `commandType` `String`**, not a `WorkflowCommand` — so the batch service computes its OWN canonical fingerprint and reserves directly. This deliberately AVOIDS the [[epic3b-command-and-approval-wiring-fanout]] (no `permits`, no `WorkflowCommandFingerprintFactory` case, no symmetry-contract edit). [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/WorkflowCommand.java:5-13; deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java:70-79]

4. **Best-effort = the batch loop must NOT hold an ambient transaction (THE central trap).** `WorkflowCommandService.submit` is `@Transactional` (REQUIRED) — its create + integration-link + dispatch commit/roll-back atomically per ticket. If `WorkflowBatchSubmissionService.submitBatch` were `@Transactional`, every per-ticket `submit()` would JOIN the one batch tx, so a single ticket's rollback-marked `DomainException` poisons the whole batch (best-effort destroyed). **`submitBatch` must NOT be `@Transactional` at the method level.** Each per-ticket `submit()` then opens its own physical REQUIRED tx; one ticket's failure rolls back only that ticket and the loop catches it and continues. The batch-level idempotency reserve and the `batch_submissions` row writes run in their OWN independent transactions (mirror `WorkflowCommandService.checkAndReserveInIndependentTransaction` / `completeWhenTransactionFinishes`, which already use `REQUIRES_NEW`-style independent txs). Related: [[post-commit-hook-needs-requires-new]]. [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:130-133,195-199,405-425]

5. **Replay must faithfully reconstruct the FULL per-ticket result, including rejected tickets — persist per-ticket outcomes.** Epic AC9 ("same key + same fingerprint returns prior result without re-processing") + AC3 (`tickets: List<TicketBatchResult>`) require the replay to return the same per-ticket list, including REJECTED tickets — which have NO `runner_executions` row, so `runner_executions.batch_submission_id` alone cannot reconstruct them. **Decision D-PERSIST (recommended): add a child `batch_submission_items` table** (one row per ticket: `ticket_ref`, `run_id` nullable, `queue_result`, `rejection_code` nullable, `rejection_reason` nullable, `child_correlation_id`). The `runner_executions.batch_submission_id` FK is STILL added (epic AC4 + serves story 3.19's per-batch queue filtering), but the items table is the authoritative result-reconstruction source. See Decisions + OQ-1.

## Scope Boundary — what 3.18 BUILDS vs REUSES vs DEFERS

| Concern | 3.18 | Note |
|---|---|---|
| `WorkflowBatchSubmissionService.submitBatch(SubmitBatchCommand) → BatchSubmissionResult` in `application.workflow` | **BUILD** | epic AC1/AC10 |
| `SubmitBatchCommand` / `BatchSubmissionResult` / `TicketBatchResult` records (standalone, NOT `WorkflowCommand`) | **BUILD** | epic AC1/AC3 — Reconciliation 3 |
| Best-effort loop: per-ticket `WorkflowCommandService.submit(...)`, catch `DomainException`, record outcome, continue | **BUILD** | epic AC2 — Reconciliation 4 |
| `bat_` public-id prefix in `PublicIdPrefixes` + the drift-test sites | **BUILD** | epic AC3 — [[new-domainerrorcode-three-sites]]-style fan-out (different registry) |
| `V15__add_batch_submissions.sql` (+ `batch_submission_items` child + `runner_executions.batch_submission_id` FK) | **BUILD** | epic AC4 — Reconciliation 1/5 |
| Batch-level idempotency (own fingerprint = sorted ticket list + actor; `checkAndReserve` directly) + replay | **BUILD** | epic AC9 — Reconciliation 3 |
| Per-ticket derived idempotency keys + child correlation IDs `{batchCorrelationId}/{ticketRef}` | **BUILD** | epic AC8 |
| Queue-full mid-batch → stop enqueueing, mark remaining `rejected`/`RUNNER_QUEUE_FULL`, return partial | **BUILD** | epic AC7 |
| CLI `deliveryline submit-batch` (Spring Shell `@Command` on `WorkflowCommands`): `--tickets` / `--from-file` / `--idempotency-key` / `--exit-on-any-rejection`; tabular output | **BUILD (net-new parsing + table)** | epic AC5 |
| REST `POST /api/v1/workflows/batch` + request/response DTOs + OpenAPI snapshot | **BUILD** | epic AC6 |
| Persistence vertical slice for `batch_submissions` (+items): SPI port, entity, mapper, adapter, repository | **BUILD** | standard pattern |
| ArchUnit: batch service stays in `application.workflow`, no adapter/persistence deps; new controller pinned | **BUILD** | epic AC10 |
| `RunnerExecutionQueue.enqueue` / worker pool / `RunnerDispatchResult.Queued` / dispatch path | **REUSE UNCHANGED** | 3.17a/3.17b — do NOT touch |
| `WorkflowCommandService.submit` / `submitInternal` / integration-link / event append | **REUSE (compose)** | story 1.15 — never duplicate |
| `IdempotencyService.checkAndReserve` / `complete` | **REUSE** | story 1.9 |
| Per-batch queue-status filtering (`?batchId=`), Prometheus/Grafana batch panels | **DEFER** | story 3.19 (uses the `batch_submission_id` FK this story adds) |
| New `WorkflowEventType` / new `DomainErrorCode` (see OQ-2) | **AVOID if possible** | reuse `RUNNER_QUEUE_FULL` / `IDEMPOTENCY_KEY_CONFLICT` / `INVALID_COMMAND_PAYLOAD` |

## Acceptance Criteria

> From `epic-03-agent-execution.md` §"Story 3.18" (lines 359–379), with **binding clarifications** in **bold parentheticals**.

1. **Given** `WorkflowBatchSubmissionService.submitBatch(command: SubmitBatchCommand) → BatchSubmissionResult`, **Then** `SubmitBatchCommand` carries: `linearTicketReferences: List<String>` (1–`max`, default max 100 configurable via `deliveryline.workflow.batch-max-tickets`), `actorIdentity`, `actorType`, `idempotencyKey` (batch-level), and `correlationId` (optional). **(Empty list or size > max raises `INVALID_COMMAND_PAYLOAD` — no new error code. `SubmitBatchCommand` is a standalone record, NOT a `WorkflowCommand` — Reconciliation 3.)**

2. **Given** **best-effort semantics**, **Then** the service iterates each ticket, for each calling the existing `WorkflowCommandService.submit(SubmitWorkflowCommand)` (which already enqueues via 3.17), and records per-ticket `TicketBatchResult{ticketRef, runId?, queueResult: 'queued'|'rejected', rejectionReason?, rejectionCode?}`; a single rejection (caught `DomainException`) does NOT abort processing of remaining tickets. **(`submitBatch` is NOT `@Transactional` so each ticket is an isolated tx — Reconciliation 4. A successful submit returns `SubmitWorkflowResult` → `queueResult='queued'`, `runId=workflowRunId`. A `DomainException` → `queueResult='rejected'`, `rejectionCode=errorCode.value()`, `rejectionReason=message`.)**

3. **Given** the result, **Then** `BatchSubmissionResult` includes: `batchId` (`bat_` prefix — added to `PublicIdPrefixes` + the `FlywaySchemaContractTest` drift sites), `submittedAt`, `actorIdentity`, `total: int`, `queuedCount: int`, `rejectedCount: int`, `tickets: List<TicketBatchResult>`.

4. **Given** persistence, **Then** `V15__add_batch_submissions.sql` adds a `batch_submissions` table (`id bigserial`, `public_id` `bat_`-format CHECK, `actor_identity`, `actor_type`, `idempotency_key` UNIQUE, `total/queued/rejected` counts, `created_at`, `archived_at`) **and** `runner_executions.batch_submission_id text null` (nullable, traces a queued execution back to its batch). **(Plus the `batch_submission_items` child table from Decision D-PERSIST so replay reconstructs rejected tickets — Reconciliation 5. Mirror the V8 create-table style + V14 alter style.)**

5. **Given** CLI `deliveryline submit-batch --tickets LIN-1,LIN-2,LIN-3 [--from-file tickets.txt] [--idempotency-key K] [--actor-identity I --actor-type T] [--correlation-id C] [--exit-on-any-rejection]`, **Then** parses tickets from the flag OR file (one per line, `#` comments + blank lines ignored), invokes `submitBatch`, prints a tabular result `Ticket | Run ID | Outcome | Reason`; default exit 0 if ≥1 queued, non-zero only when `--exit-on-any-rejection` is set AND `rejectedCount > 0`. **(Spring Shell — add a `@Command(name="submit-batch")` method to `WorkflowCommands`. `--tickets` and `--from-file` are mutually exclusive (require exactly one). Net-new: file parsing + the table renderer (no table helper exists). Exit-code-on-rejection mechanism is OQ-2.)**

6. **Given** REST `POST /api/v1/workflows/batch`, **Then** accepts `BatchSubmissionRequest` (camelCase JSON: `linearTicketReferences`, `actorIdentity`, `actorType`, `correlationId`) with a batch-level `Idempotency-Key` header, returns **200** with `BatchSubmissionResponse` JSON; OpenAPI documented + snapshot regenerated. **(Match the `submit-workflow` sibling's body-carried actor convention — OQ-3. REST ALWAYS returns 200 with the result regardless of per-ticket rejections; rejections are in the body, never a non-2xx.)**

7. **Given** queue capacity awareness from story 3.17, **When** `enqueue` raises `RUNNER_QUEUE_FULL` mid-batch, **Then** the service stops attempting further submits, marks all unprocessed tickets `rejected` with `rejectionCode=RUNNER_QUEUE_FULL`, and returns the partial result. **(Detect the `RUNNER_QUEUE_FULL` error code on the caught `DomainException` and break the loop — distinct from a per-ticket rejection that continues.)**

8. **Given** correlation propagation, **Then** the service generates one batch `correlationId` (or uses the supplied one) + per-ticket child IDs `{batchCorrelationId}/{ticketRef}`, threaded into each per-ticket `SubmitWorkflowCommand.correlationId`. **(Mint a fresh UUIDv7 via the existing generator when none supplied — mirror `CorrelationIdFilter`/CLI `pushCorrelation`.)**

9. **Given** idempotency at batch level, **Then** same key + same fingerprint (canonical hash of the SORTED ticket list + actor identity + actor type) returns the prior `BatchSubmissionResult` without re-processing; same key + different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`. **(Compute the fingerprint in the batch service; call `IdempotencyService.checkAndReserve(key, "SubmitBatchCommand", actorIdentity, fingerprint)`; on `REPLAY` load the prior result from `batch_submissions`+`batch_submission_items` via `result_ref` = the `bat_` id; on success `complete(key, batchId, COMPLETED)`, on failure `complete(key, null, FAILED)`. Per-ticket keys are derived deterministically as `{batchKey}:{ticketRef}` so a non-replay re-run stays per-ticket idempotent.)**

10. **Given** ArchUnit + scope, **Then** `WorkflowBatchSubmissionService` lives in `application.workflow`, depends only on `application.*` / `domain.*` / framework (no adapters/persistence), and **composes** `WorkflowCommandService.submit` — never duplicates the create/link/dispatch logic.

11. **Given** the test suite, **Then** covers: happy-path all-queued, mixed batch (some queued, some rejected), best-effort (one rejection does not abort), idempotent replay (same key+fingerprint → identical result, zero re-submits), idempotency conflict, queue-full mid-batch → partial result, correlation IDs propagate (`{batch}/{ticket}`), CLI tabular output + `--from-file` parsing + exit codes, REST 200 + OpenAPI snapshot conformance, `bat_` drift test, ArchUnit boundary.

## Tasks / Subtasks

- [x] **Task 1 — `bat_` public-id prefix + drift-test sites** (AC: #3)
  - [x] Add `BATCH_SUBMISSION("batchSubmission", "bat_", "ck_batch_submissions_public_id_format")` to `domain/id/PublicIdPrefixes.java` (mirror existing entries; the static prefix-collision block validates it on class init).
  - [x] Add `batch_submissions` → `bat_` to `FlywaySchemaContractTest.EXPECTED_PUBLIC_ID_PREFIX` AND add `batch_submissions` to its `CORE_TABLES` list (the story-1.4-equivalent drift test asserts the CHECK constraint exists per table). [Source: deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java:29-53,97-122]

- [x] **Task 2 — `V15` migration** (AC: #4)
  - [x] `V15__add_batch_submissions.sql`: `create table batch_submissions` (id bigserial PK, public_id text not null with `uq_batch_submissions_public_id` + `ck_batch_submissions_public_id_format` CHECK `^bat_[A-Za-z0-9_-]{4,64}$`, actor_identity, actor_type, idempotency_key text not null with `uq_batch_submissions_idempotency_key`, total/queued_count/rejected_count integer, created_at timestamptz default now(), archived_at). Mirror the V8 clarifications create-table template.
  - [x] In the same migration: `create table batch_submission_items` (id bigserial PK, batch_submission_id bigint FK → batch_submissions(id), ticket_ref, run_id text null, queue_result text CHECK in ('queued','rejected'), rejection_code text null, rejection_reason text null, child_correlation_id text null, position integer) — Decision D-PERSIST.
  - [x] In the same migration: `alter table runner_executions add column batch_submission_id text null;` (mirror V14 alter style — nullable, no backfill). [Source: V8__add_clarifications.sql; V14__add_queue_dispatch_carriage.sql]

- [x] **Task 3 — Application command/result records** (AC: #1, #3)
  - [x] `application/workflow/commands/SubmitBatchCommand` (standalone record, NOT `WorkflowCommand`): `List<String> linearTicketReferences`, `actorIdentity`, `actorType`, `idempotencyKey`, `correlationId`. Bean Validation: `@NotEmpty` + `@Size(max=…)` list, element `@NotBlank @Size(max=128)`, actor/key as in `SubmitWorkflowCommand`.
  - [x] `application/workflow/BatchSubmissionResult` (record: batchId, submittedAt, actorIdentity, total, queuedCount, rejectedCount, `List<TicketBatchResult> tickets`) + `TicketBatchResult` (ticketRef, runId nullable, queueResult enum/string, rejectionReason nullable, rejectionCode nullable).

- [x] **Task 4 — `WorkflowBatchSubmissionService`** (AC: #1, #2, #7, #8, #9, #10)
  - [x] New `application/workflow/WorkflowBatchSubmissionService` — `@Service`, **NOT `@Transactional` at the `submitBatch` level** (Reconciliation 4). Inject `WorkflowCommandService`, `IdempotencyService`, the new `BatchSubmissionWritePort`/`ReadPort`, the UUIDv7 generator, and `@ConfigurationProperties` max.
  - [x] Compute the canonical batch fingerprint (SHA-256 over sorted-distinct ticket list + actorIdentity + actorType.value()); mirror `WorkflowCommandFingerprintFactory`'s digest/append idiom but as a small private helper (do NOT route through the sealed factory).
  - [x] Reserve via `idempotencyService.checkAndReserve(key, "SubmitBatchCommand", actorIdentity, fingerprint)` in an independent tx; on `REPLAY` load + return the prior `BatchSubmissionResult` from the ports.
  - [x] Mint batch correlationId (supplied or fresh UUIDv7); loop tickets, building a `SubmitWorkflowCommand` per ticket with derived key `{batchKey}:{ticketRef}` + child correlation `{batchCorrelationId}/{ticketRef}`; call `workflowCommandService.submit(...)` in a try/catch. Success → `queued`; `DomainException` → `rejected` with code+message; if the code is `RUNNER_QUEUE_FULL` → mark remaining unprocessed tickets `rejected/RUNNER_QUEUE_FULL` and break (AC7).
  - [x] Persist the `batch_submissions` row + `batch_submission_items` (own tx); `idempotencyService.complete(key, batchId, COMPLETED)` on success / `complete(key, null, FAILED)` on unexpected failure.

- [x] **Task 5 — Persistence vertical slice** (AC: #4)
  - [x] `application/workflow/spi/BatchSubmissionWritePort` (insert batch + items, returns snapshot) + `BatchSubmissionReadPort` (load by `bat_` id for replay). Records project-owned in the SPI package.
  - [x] `adapters/persistence/entity/BatchSubmissionEntity` (+ `BatchSubmissionItemEntity`), `mapper/BatchSubmissionEntityMapper`, `BatchSubmissionWritePersistenceAdapter` (`@Component`, `@Transactional(REQUIRED)`; map `uq_batch_submissions_idempotency_key` `DataIntegrityViolationException` → `IDEMPOTENCY_KEY_CONFLICT` defense-in-depth, mirror `ApprovalWritePersistenceAdapter`), `repository/BatchSubmissionRepository` (`archived_at is null` filters). [Source: ApprovalWritePersistenceAdapter.java:69-176]

- [x] **Task 6 — REST endpoint + DTOs + OpenAPI** (AC: #6)
  - [x] `adapters/rest/BatchSubmissionRequest` (record; `@NotEmpty List<@NotBlank @Size(max=128) String> linearTicketReferences`, `@NotBlank @Size(max=128) actorIdentity`, `@NotNull ActorType actorType`, `@Size(max=128) correlationId`; `@JsonIgnoreProperties(ignoreUnknown=false)`) + `BatchSubmissionResponse` (record + static `from(BatchSubmissionResult)`, `@Schema(requiredMode=…)` annotations).
  - [x] `WorkflowController` `@PostMapping("/batch")` (`consumes`/`produces` JSON, `@Operation(operationId="submitBatch")`, `@ApiResponses` incl. 400/409/503), `@RequestHeader("Idempotency-Key")` (batch-level), `@Valid @RequestBody`, returns 200. Build `SubmitBatchCommand`, call the service, map to response.
  - [x] Regenerate the OpenAPI snapshot (`-Dopenapi.snapshot.write=true`) and commit; `OpenApiSnapshotContractTest` must stay byte-exact + the AC9/TRAP-1 assertion (existing POSTs survive) holds. [Source: OpenApiSnapshotContractTest.java:71-131]

- [x] **Task 7 — CLI `submit-batch`** (AC: #5)
  - [x] Add `@Command(name="submit-batch")` to `adapters/cli/WorkflowCommands` (auto-registered via `@CommandGroup`). `@Option`s: `--tickets` (CSV), `--from-file`, `--idempotency-key`, `--actor-identity`, `--actor-type`, `--correlation-id`, `--exit-on-any-rejection` (default false). Require exactly one of `--tickets`/`--from-file` (else `INVALID_COMMAND_PAYLOAD`).
  - [x] Net-new file parser: read lines, trim, drop blanks + `#`-comments (extend the `parseCsvOption` idiom from `DoctorCommands`). Net-new table renderer: `Ticket | Run ID | Outcome | Reason` (StringBuilder; no table helper exists — see WorkflowCommandOutputs for the rendering precedent). Print via the existing command-output mechanism.
  - [x] Resolve/generate idempotency key (mirror `resolveIdempotencyKey` — non-interactive requires explicit key) + push correlation scope. Exit-code: default returns normally (0); on `--exit-on-any-rejection` && `rejectedCount>0`, drive a non-zero exit (OQ-2 — recommend printing the table first, then throwing a CLI-band exception mapped by `WorkflowCliExitStatusExceptionMapper`). [Source: WorkflowCommands.java:156-211,887-895,1194-1205; DoctorCommands.java:146-158]

- [x] **Task 8 — ArchUnit** (AC: #10)
  - [x] Add `BATCH_SUBMISSION_SERVICE_LIVES_IN_APPLICATION_WORKFLOW`-style rule to `ArchitectureRuleCatalog` constraining `WorkflowBatchSubmissionService` to `application.workflow` + `application.*`/`domain.*`/framework deps only (mirror the `APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL` rule). Register in `ArchitectureBoundaryTest` (bump the rule count). The new controller is auto-covered by the existing `REST_CONTROLLERS_*` rules. Verify via Failsafe ([[archunit-runs-in-failsafe-not-surefire]]). [Source: ArchitectureRuleCatalog.java:581-600,256-280]

- [x] **Task 9 — Tests** (AC: #11)
  - [x] Unit (mock `WorkflowCommandService`/`IdempotencyService`/ports): all-queued, mixed, best-effort-continues-after-rejection, queue-full-mid-batch partial, replay returns identical result with zero re-submits, idempotency conflict, fingerprint is order-insensitive (sorted), correlation derivation.
  - [x] CLI unit (mock service, capture output): table rendering, `--from-file` parse (comments/blanks), `--tickets`/`--from-file` mutual exclusion, exit codes (default 0 with rejections; non-zero under `--exit-on-any-rejection`).
  - [x] REST contract (`@SpringBootTest` RANDOM_PORT + real `HttpClient`, the house pattern — NOT MockMvc): 200 body shape, camelCase, correlation echo, idempotent replay over HTTP.
  - [x] `*IT` (Testcontainers Postgres, `@Tag("integration")`): batch persists + items reconstruct on replay; `V15` applies; `batch_submission_id` written on queued rows. [[springboot-testcontainers-test-must-be-IT]]
  - [x] Drift: `FlywaySchemaContractTest` green for `bat_`; OpenAPI snapshot byte-exact; foundation gate green.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J + MDC on `WorkflowBatchSubmissionService`: `INFO` batch start (total, batchCorrelationId) + finish (queuedCount/rejectedCount), `INFO` per-ticket outcome, `WARN` on `RUNNER_QUEUE_FULL` mid-batch truncation + on idempotent replay, `ERROR` only on unexpected failure. Carry `correlationId` (batch), `idempotencyKey`, `actorIdentity`, `batchId`, and per-ticket `workflowRunId`/`ticketRef`. Parameterized logging only; never log full ticket payloads/secrets. Pin new log lines with a list-appender/`OutputCaptureExtension` test.

## Dev Notes

### THE references that matter most

| Concern | File to mirror / reuse | Why |
|---|---|---|
| **Single-submit to compose (do NOT duplicate)** | `application/workflow/WorkflowCommandService.submit` (`:130-133`) → `submitInternal` (`:195-270`) | AC2/AC10 — already enqueues via 3.17. |
| **Submit command shape** | `application/workflow/commands/SubmitWorkflowCommand.java` | per-ticket command you build in the loop. |
| **Idempotent reserve/replay pattern** | `WorkflowCommandService.executeIdempotent` (`:405-425`), `IdempotencyService.checkAndReserve` (`:34-59`) / `complete` | AC9 — reserve with your OWN fingerprint string. |
| **Fingerprint digest idiom (copy idiom, NOT the sealed factory)** | `WorkflowCommandFingerprintFactory` (`:21-103`) | AC9 — sorted-ticket-list canonical hash. |
| **Sealed permits / symmetry gate to AVOID editing** | `commands/WorkflowCommand.java:5-13`, `foundation/CommandModelSymmetryFoundationContract.java:70-79` | Reconciliation 3 — keep batch OUT. |
| **Public-id prefix + drift test** | `domain/id/PublicIdPrefixes.java:12-22,117-119`, `contract/FlywaySchemaContractTest.java:29-53,97-122` | AC3 — `bat_`. |
| **Create-table migration template** | `db/migration/V8__add_clarifications.sql` | AC4 batch_submissions. |
| **Alter-table migration template** | `db/migration/V14__add_queue_dispatch_carriage.sql` | AC4 `batch_submission_id` column. |
| **Persistence vertical slice** | `application/approval/spi/ApprovalWritePort.java`; `adapters/persistence/{entity/ApprovalEntity,mapper/ApprovalEntityMapper,ApprovalWritePersistenceAdapter,repository/ApprovalRepository}.java` | AC4 — incl. unique-constraint → `IDEMPOTENCY_KEY_CONFLICT` mapping. |
| **REST submit endpoint + DTOs** | `adapters/rest/WorkflowController.java:347-363`, `SubmitWorkflowRequest.java`, `SubmitWorkflowResponse.java` | AC6 — body actor convention (OQ-3). |
| **Error mapping** | `adapters/rest/ProblemDetailsCatalog.java` (RUNNER_QUEUE_FULL=503, IDEMPOTENCY_KEY_CONFLICT=409), `ProblemDetailsMapper.java` | AC6/AC7 — reuse existing codes. |
| **OpenAPI snapshot** | `src/main/resources/openapi/openapi.json`, `OpenApiSnapshotContractTest.java` | AC6 — regen `-Dopenapi.snapshot.write=true`. |
| **CLI command + output + exit mapper** | `adapters/cli/WorkflowCommands.java:156-211`, `WorkflowCommandOutputs.java`, `WorkflowCliExitStatusExceptionMapper.java`, `DoctorCommands.java:146-158` (CSV parse) | AC5. |
| **ArchUnit service-placement rule** | `architecture/ArchitectureRuleCatalog.java:581-600` (ApprovalService), `:256-280` (controllers) | AC10. |
| **Predecessor (queue model + house discipline)** | `3-17b-runner-worker-pool-and-queue-activation.md` | the active queue this submits onto. |

### Decisions (made by this story; rationale)

- **D-CMD — `SubmitBatchCommand` is standalone, not a `WorkflowCommand`.** Batch orchestrates over single commands; joining the sealed set reds the foundation symmetry gate and is conceptually wrong. Compute the fingerprint locally + reserve via `IdempotencyService` directly. (Reconciliation 3.)
- **D-TX — `submitBatch` is NOT `@Transactional`; per-ticket `submit()` is the unit of atomicity.** This is what MAKES best-effort work. Batch-row + idempotency writes run in their own independent txs. (Reconciliation 4.)
- **D-PERSIST — add a `batch_submission_items` child table.** Faithful replay (AC9/AC3) must return rejected tickets, which have no `runner_executions` row. The `runner_executions.batch_submission_id` FK is still added (AC4 + story 3.19 queue filtering) but is not the result source. (Reconciliation 5.) Fallback if scope must shrink: a single `result_json` snapshot column on `batch_submissions` (less queryable).
- **D-IDEMPKEY — per-ticket key = `{batchKey}:{ticketRef}` (deterministic).** Keeps per-ticket replay correct if the batch reserve succeeded but processing was interrupted before the batch-level `complete`.
- **D-FP — batch fingerprint over the SORTED, distinct ticket list + actor.** Order-insensitivity matches the user's mental model ("same tickets, same batch"); pin with an order-shuffled unit test.
- **D-NOEVENT / D-NOCODE — no new `WorkflowEventType`, no new `DomainErrorCode` (except possibly OQ-2).** Per-ticket `submit()` already appends its own `workflow.stateChanged`; reuse `RUNNER_QUEUE_FULL`/`IDEMPOTENCY_KEY_CONFLICT`/`INVALID_COMMAND_PAYLOAD`. Avoids [[new-workfloweventtype-fixture-sites]] and [[new-domainerrorcode-three-sites]].

### Open Questions (each carries a recommendation — proceed unless the architect objects)

- **OQ-1 — Result persistence shape.** Child `batch_submission_items` table (recommended, normalized + queryable + feeds story 3.19) vs a `result_json` snapshot column on `batch_submissions` (simpler replay, opaque). **Recommend the child table.**
- **OQ-2 — CLI non-zero exit on rejection.** The exit-status mechanism (`WorkflowCliExitStatusExceptionMapper`) is exception-driven, but `submitBatch` returns normally on best-effort. **Recommend:** print the table first, then under `--exit-on-any-rejection && rejectedCount>0` throw a CLI-layer exception mapped to a client-error band — preferably WITHOUT a new `DomainErrorCode` (reuse an existing client-band code, or use Spring Shell's native exit-code API if available in the pinned version). Confirm whether a dedicated `BATCH_PARTIAL_REJECTION` code is wanted (it would trigger the [[new-domainerrorcode-three-sites]] fan-out and is CLI-only, since REST always returns 200).
- **OQ-3 — REST actor convention.** Match the `submit-workflow` sibling (actorIdentity/actorType/correlationId in the BODY; story 1.15 was never migrated to headers and is the natural twin) vs the newer story-2.13 header convention (`X-Actor-Identity` + `LocalActorIdentityResolver`). **Recommend body-symmetry with `submit-workflow`** for CLI/REST parity and least surprise.
- **OQ-4 — Configurable max tickets default.** Epic says "1–100, configurable max". **Recommend** `deliveryline.workflow.batch-max-tickets` default 100; remember the validated-config test-yaml mirror ([[validated-config-needs-test-yaml]]) if it's a `@Validated @ConfigurationProperties` field.

### Traps (wiring hazards — each maps to a memory or a verified code fact)

- **T-TX (the silent breaker) — do NOT annotate `submitBatch` `@Transactional`.** It would collapse best-effort into all-or-nothing (one ticket's rollback-marked tx poisons the batch). The whole story's value depends on this. (Reconciliation 4.)
- **T-PERMITS — do NOT add `SubmitBatchCommand` to `WorkflowCommand`'s `permits`.** Reds `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS` + obligates a REST round-trip capture. (Reconciliation 3, [[epic3b-command-and-approval-wiring-fanout]].)
- **T-MIGRATION — next Flyway version is `V15`, not `V5`.** V14 is the latest on disk. [[runner-contracts-schema-stale-in-m2]]-style: editing schema + running backend-only tests can validate against a stale `.m2` — install or `-am`.
- **T-DRIFT — a new `bat_` prefix needs BOTH `PublicIdPrefixes` AND `FlywaySchemaContractTest` (map + CORE_TABLES).** Foundation-gate / contract test catches omissions.
- **T-OPENAPI — regenerate the snapshot via the package phase + `-Dopenapi.snapshot.write=true`** ([[maven-arglineation-goal-crash]]); the regen step needs cross-shell coordination ([[openapi-regen-platform-shim]]). The snapshot is byte-exact (`OpenApiSnapshotContractTest`).
- **T-ARCHUNIT — runs in Failsafe, not Surefire** ([[archunit-runs-in-failsafe-not-surefire]]). Verify the new rule via `failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- **T-IT-NAME — name Testcontainers tests `*IT` + `@Tag("integration")`** ([[springboot-testcontainers-test-must-be-IT]]); not `docker-runner-it` (no runner images pulled).
- **T-VALIDATED-CFG — mirror any new validated config key into `src/test/resources/application.yml`** ([[validated-config-needs-test-yaml]]).
- **T-RTK / verify — run gates via PowerShell** ([[rtk-hook-only-matches-bash]], [[maven-arglineation-goal-crash]]); reproduce CI/foundation tiers in a clean env / WSL2 ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).
- **T-CONTROLLER-THIN — the new controller may only depend on the application service surface** (no SPI/persistence/runner imports), per the existing `REST_CONTROLLERS_STAY_THIN…` rule.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback. No `System.out`/`printStackTrace()`. (CLI output goes through Spring Shell's return/terminal, not log statements.)
- **Surface:** `WorkflowBatchSubmissionService` (batch start/finish, per-ticket outcome, queue-full truncation, replay), the persistence adapter (batch insert, unique-conflict map), the controller entry. `INFO` lifecycle, `WARN` queue-full-truncation + idempotent replay, `ERROR` only unexpected failure.
- **Required keys:** `correlationId` (batch), `idempotencyKey`, `actorIdentity`, `batchId`, per-ticket `workflowRunId` + `ticketRef`. **Forbidden:** secrets, full payloads, PII.
- **Test contract:** new surfaces pinned by a focused list-appender / `OutputCaptureExtension` assertion.

### Project Structure Notes

- Backend `deliveryline-backend/`, base `org.dradgo`, Java 21, Spring Boot 4.0.6, Postgres + Flyway, Spring Shell CLI.
- New application: `application/workflow/WorkflowBatchSubmissionService`, `application/workflow/commands/SubmitBatchCommand`, `application/workflow/{BatchSubmissionResult,TicketBatchResult}`, `application/workflow/spi/{BatchSubmissionWritePort,BatchSubmissionReadPort}`.
- New adapters: `adapters/rest/{BatchSubmissionRequest,BatchSubmissionResponse}` + `WorkflowController` `@PostMapping("/batch")`; `adapters/cli/WorkflowCommands` `submit-batch`; `adapters/persistence/{entity/BatchSubmissionEntity,entity/BatchSubmissionItemEntity,mapper/BatchSubmissionEntityMapper,BatchSubmissionWritePersistenceAdapter,repository/BatchSubmissionRepository}`.
- New migration: `src/main/resources/db/migration/V15__add_batch_submissions.sql`.
- Registry: `domain/id/PublicIdPrefixes` (+`bat_`); test `contract/FlywaySchemaContractTest`, `architecture/ArchitectureRuleCatalog`+`ArchitectureBoundaryTest`, `src/main/resources/openapi/openapi.json`.
- **Reuse unchanged:** `RunnerExecutionQueue`, worker pool, `RunnerDispatchResult.Queued`, `WorkflowCommandService.submit`, `IdempotencyService`.

### Verification commands (PowerShell — [[rtk-hook-only-matches-bash]])

- Focused unit: `mvnw -pl deliveryline-backend test -Dtest=WorkflowBatchSubmissionServiceTest,WorkflowCommandsTest,FlywaySchemaContractTest`.
- ArchUnit (Failsafe — T-ARCHUNIT): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- Batch ITs (Testcontainers Postgres): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=*Batch*IT`.
- OpenAPI regen ([[openapi-regen-platform-shim]], [[maven-arglineation-goal-crash]]): `mvnw -pl deliveryline-backend package -Dopenapi.snapshot.write=true -Dtest=Zzz -DfailIfNoTests=false` then review + commit `openapi.json`.
- Foundation gate (Docker up): `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false`.
- Static + fast tier: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check` then `mvnw -pl deliveryline-backend test`.
- WSL2 Linux smoke of the foundation gate ([[wsl-linux-ci-reproduction]], [[verify-ci-fixes-in-clean-env]]).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.18] — ACs 1–11, lines 359–379; execution-order note line 365 ("must merge after the queue lands" — already true, 3.17a/b `done`). Adjacent: Story 3.17 (the queue, lines 344–357), Story 3.19 (queue inspection; AC9 line 397 filters by `batchId` using the `batch_submission_id` FK this story adds), Story 3.22 (takeover cancels queued executions).
- Predecessor: [Source: _bmad-output/implementation-artifacts/3-17b-runner-worker-pool-and-queue-activation.md] — the active queue model (enqueue, worker pool, `RunnerDispatchResult.Queued`).
- Single-submit composition: `application/workflow/WorkflowCommandService.java:130-133,195-270,405-425`; `application/workflow/commands/SubmitWorkflowCommand.java`.
- Idempotency: `application/idempotency/IdempotencyService.java:34-59`; `WorkflowCommandFingerprintFactory.java:21-103` (idiom only).
- Sealed gate to avoid: `application/workflow/commands/WorkflowCommand.java:5-13`; `foundation/CommandModelSymmetryFoundationContract.java:70-79`.
- Public-id + drift: `domain/id/PublicIdPrefixes.java:12-22,117-119`; `contract/FlywaySchemaContractTest.java:29-53,97-122`.
- Migrations: `db/migration/V8__add_clarifications.sql`; `db/migration/V14__add_queue_dispatch_carriage.sql`; `V1__create_workflow_core_tables.sql:206-234` (runner_executions).
- Persistence slice: `application/approval/spi/ApprovalWritePort.java`; `adapters/persistence/ApprovalWritePersistenceAdapter.java:69-176`; `adapters/persistence/entity/ApprovalEntity.java`; `mapper/ApprovalEntityMapper.java`; `repository/ApprovalRepository.java`.
- REST: `adapters/rest/WorkflowController.java:347-363`; `SubmitWorkflowRequest.java`; `SubmitWorkflowResponse.java`; `ProblemDetailsCatalog.java`; `OpenApiSnapshotContractTest.java:71-131`.
- CLI: `adapters/cli/WorkflowCommands.java:156-211,887-895,1194-1205`; `WorkflowCommandOutputs.java`; `WorkflowCliExitStatusExceptionMapper.java`; `DoctorCommands.java:146-158`.
- ArchUnit: `architecture/ArchitectureRuleCatalog.java:581-600,256-280`; `ArchitectureBoundaryTest.java`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-create-story 2026-06-15.

### Debug Log References

- Three real bugs caught + fixed by the Docker tier during dev:
  1. `BatchSubmissionEntityMapper` injected `ObjectMapper` directly → context boot failure (`No qualifying bean of type ObjectMapper`). This app does not expose a directly-injectable `ObjectMapper`; switched to `ObjectProvider<ObjectMapper>` with a `new ObjectMapper().findAndRegisterModules()` fallback (mirrors `WorkflowCommandOutputs`).
  2. `TicketBatchResult.isQueued()` boolean getter serialized as a `"queued"` JSON field → record deserialization on replay failed (`Failed to deserialize batch ticket outcomes`, `FAIL_ON_UNKNOWN_PROPERTIES`). Annotated `@JsonIgnore`.
  3. `result_json`/`submittedAt` replay byte-identity: first-call `created_at` was in-memory nanosecond precision but Postgres `timestamptz` round-trips at microseconds → `submittedAt` mismatch on replay. Truncated `@PrePersist` `created_at` to `ChronoUnit.MICROS`.
  4. A `TypeReference<>(){}` anonymous class in the mapper package violated the `persistence_mappers_must_be_location_qualified` ArchUnit rule (empty simple name ≠ `*Mapper`). Replaced with `readValue(json, TicketBatchResult[].class)` + `List.of(...)`.

### Completion Notes List

- **Architect decisions (this story):**
  - **OQ-1 (Alex):** per-ticket outcomes persisted as a `result_json` snapshot column on `batch_submissions` (NOT a child `batch_submission_items` table). Driver: this codebase's `FlywaySchemaContractTest` enforces that EVERY table is a "core table" (own `public_id` + prefix + `created_at`/`archived_at` + `uq`), so a child table would have needed a second public-id prefix + full core-table treatment — heavier than the simpler snapshot column, which fully satisfies AC9/AC3/AC11 replay. The `runner_executions.batch_submission_id` FK is still added (AC4 + story 3.19).
  - **OQ-2 / D-NOCODE:** CLI `--exit-on-any-rejection` signals a non-zero exit by throwing a CLI-band `DomainException(INVALID_COMMAND_PAYLOAD)` carrying the rendered table in its message. No new `DomainErrorCode` was added (avoids the three-sites fan-out + `openapi.json`/`schema.d.ts` enum drift); the per-ticket rejections are already itemized in the printed table.
  - **OQ-3:** REST uses the body-carried actor convention (`actorIdentity`/`actorType`/`correlationId` in the body, `Idempotency-Key` header) matching the `submit-workflow` sibling.
  - **OQ-4:** `deliveryline.workflow.batch-max-tickets` default 100, injected via `@Value` (NOT a validated `@ConfigurationProperties` — no test-yaml mirror needed).
  - **D-CONTROLLER (deviation from Task 6 literal text):** the REST endpoint lives on a NEW `WorkflowBatchController` (not `WorkflowController`). Adding the `WorkflowBatchSubmissionService` dependency to `WorkflowController` would have fanned out into ~10 `@WebMvcTest(controllers = WorkflowController.class)` slices. A dedicated controller is auto-covered by the existing `REST_CONTROLLERS_*` rules (Task 8 note + scope-table "new controller pinned"). AC6's actual contract (path `POST /api/v1/workflows/batch` + 200 behavior) is fully honored.
  - **D-IDEMPKEY (refinement):** per-ticket key is `hex(SHA-256(batchKey  ticketRef))` (64-char lowercase hex), NOT the literal `{batchKey}:{ticketRef}` the story sketched — the literal `:` form is rejected by `IdempotencyKeyValidator` (`:` outside the opaque charset + length can exceed 128). The hash is deterministic, so per-ticket replay correctness is preserved.
- **Reconciliations honored:** V15 (not V5); `List<String> linearTicketReferences` (no `LinearTicketRef`); `SubmitBatchCommand` is standalone (NOT in the `WorkflowCommand` sealed permits — `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS` untouched); `submitBatch` is NOT `@Transactional` (per-ticket `submit()` is the unit of atomicity → best-effort); faithful replay reconstructs rejected tickets from `result_json`. Queue-full mid-batch (`RUNNER_QUEUE_FULL`) truncates the remainder. No new `WorkflowEventType`/`DomainErrorCode`.
- **Verification (PowerShell + local Docker, all green):** focused units `WorkflowBatchSubmissionServiceTest` 10/0 + `WorkflowBatchCommandsTest` 9/0; full fast Surefire 976/0/11skip (no regressions across the `WorkflowCommands` 12-arg ctor change + 10 ctor call-site updates + new `@Component` beans booting in every `@SpringBootTest`); `FlywaySchemaContractTest` 15/0; `RegistryContractTest` 18/0 (SQL CHECK + manifest + `DomainRegistry` prefix alignment); `OpenApiSnapshotContractTest` 1/0 (snapshot regenerated, +179 lines: `submitBatch` op + both schemas); `ArchitectureBoundaryTest` 50/0 (new `BATCH_SUBMISSION_SERVICE_LIVES_IN_APPLICATION_WORKFLOW` rule); `WorkflowBatchEndpointContractTest` 3/0; `WorkflowBatchSubmissionIT` 1/0 (persist + `batch_submission_id` stamping on queued runs + idempotent replay); spotless + checkstyle clean. Frontend: `schema.d.ts` regenerated, `check:api` in sync, `tsc -b` clean, prettier clean.
- **Recommended before merge:** run `code-review` with a different LLM; reproduce the foundation-gate (`-Pfoundation-gate verify`) + Docker tiers in a clean WSL2/Linux env ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

### File List

**New (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/SubmitBatchCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/BatchSubmissionResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/TicketBatchResult.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowBatchSubmissionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/BatchSubmissionWritePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/BatchSubmissionReadPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/BatchSubmissionEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/BatchSubmissionEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/BatchSubmissionRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/BatchSubmissionPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/BatchSubmissionRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/BatchSubmissionResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowBatchController.java`
- `deliveryline-backend/src/main/resources/db/migration/V15__add_batch_submissions.sql`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java` (+`BATCH_SUBMISSION` / `bat_`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/RunnerExecutionRepository.java` (+`stampBatchSubmissionId` native UPDATE)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (+`submit-batch` command + `WorkflowBatchSubmissionService` ctor dep + helpers)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated — `submitBatch` + DTOs)

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowBatchSubmissionServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowBatchCommandsTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowBatchEndpointContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowBatchSubmissionIT.java`

**Modified (test/contract/registry):**
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` (CORE_TABLES + EXPECTED_PUBLIC_ID_PREFIX += `batch_submissions`)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (+ batch-service placement rule)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` (+ rule registration)
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+`batchSubmission`)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsTest.java`, `WorkflowCommandsSyncCompletionTest.java`, `WorkflowCommandsStatusHistoryTest.java`, `WorkflowCommandsContextBundleFlagTest.java`, `WorkflowCommandsRunnerLogsFlagTest.java`, `WorkflowCliCommandRegistrationIT.java`, `WorkflowCliJsonSchemaContractTest.java`, `foundation/CliRestEquivalenceContractTest.java` (12-arg ctor call-site updates)

**Modified (frontend):**
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated from openapi.json)

### Change Log

- 2026-06-15 — Story 3.18 implemented: batch submission orchestrator (`WorkflowBatchSubmissionService`) composing single-submit per ticket with best-effort semantics, batch-level idempotency + replay, `bat_` public-id, V15 `batch_submissions` (+`result_json` per OQ-1) + `runner_executions.batch_submission_id`, `POST /api/v1/workflows/batch` (new `WorkflowBatchController`), `deliveryline submit-batch` CLI, ArchUnit placement rule. Status ready-for-dev → review.

## Review Findings

> bmad-code-review 2026-06-15 — 3-layer adversarial (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the working-tree diff scoped to the story File List (35 files; parallel 3-31 frontend edits excluded). Each High verified against source before classifying (per the 3.11/3.12 false-premise discipline). Acceptance Auditor: all 11 ACs IMPLEMENTED (or sanctioned deviation); D-TX/T-PERMITS/T-MIGRATION/T-DRIFT/T-OPENAPI/D-NOCODE honored. Triage: 1 decision-needed, 2 patch, 4 defer, 11 dismissed. **All 1 decision-needed + 3 resulting patches APPLIED + verified GREEN** (see Resolution).

### Resolution (2026-06-15, Alex)

- **D1 → fix (dedupe distinct at entry, Alex option 1).** Added `normalizeTickets()` reducing `linearTicketReferences` to order-preserving distinct at `submitBatch` entry, so the loop, counts, per-ticket keys, and the (already-distinct) fingerprint all agree. Verified `WorkflowBatchSubmissionServiceTest` 10/0; runtime logs show a deduped batch reports `total=2`.
- **P1 → fixed.** `FIELD_SEPARATOR` literal NUL replaced with the ` ` escape (byte-level; behavior-identical NUL separator). File is now text — git no longer classifies it binary (382/0 line-count).
- **P2 → fixed.** Removed the 503 `@ApiResponse` + javadoc clause from `WorkflowBatchController` and the matching `503` block from `openapi.json` + frontend `schema.d.ts`. Verified BYTE-EXACT: `OpenApiSnapshotContractTest` 1/0 (Failsafe + Testcontainers); `openapi.json` valid JSON.
- Wider verify GREEN: `WorkflowBatchEndpointContractTest` 3/0, `WorkflowBatchCommandsTest` 9/0. Still recommended before merge: `-Pfoundation-gate verify` + WSL2/Linux clean-env Docker confirm ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).
- Follow-up nicety (not blocking): add an explicit duplicate-ticket regression test (`[LIN-1, LIN-1]` → `total=1`, one `submit()` call).

### Decision-needed

- [x] [Review][Decision] (RESOLVED → option 1, dedupe distinct; see Resolution) Duplicate ticket refs within one batch are inconsistently handled — `[WorkflowBatchSubmissionService.java:297-306 vs :147-193,278-284]` The fingerprint distincts the list (`new TreeSet<>(...)`, spec D-FP "sorted, distinct"), but the submit loop iterates the RAW list (`parseBatchTickets`/`SubmitBatchCommand` never dedupe) and `deriveTicketKey` is deterministic on `ticketRef` only. So `[LIN-1, LIN-1]` → the 2nd `submit()` reuses the 1st's per-ticket idempotency key → single-submit REPLAY → both outcomes recorded `queued` with the SAME `runId` (`queuedCount` over-counts one real run). Cross-batch: two requests differing only by duplicates hash to the same fingerprint, so reusing the batch idempotency key REPLAYs the prior result with the wrong `total`/`tickets` instead of raising `IDEMPOTENCY_KEY_CONFLICT`. Intended behavior is a product call: (a) dedupe input to distinct at service entry (recommended — consistent with D-FP), (b) reject duplicates as `INVALID_COMMAND_PAYLOAD`, or (c) make per-ticket keys position-bearing (allows true dupes). Source: blind+edge.

### Patch

- [x] [Review][Patch] (FIXED) Literal NUL byte (0x00) embedded in `FIELD_SEPARATOR` string literal — replaced with the `"\u0000"` escape (behavior-identical: still a NUL separator) `[WorkflowBatchSubmissionService.java:65]`. The raw NUL makes git classify the whole service file as **binary** — no diff, invisible to code review/grep/patch tooling, and corruptible by any whitespace-normalizing tool. (`append()` already uses `(byte) 0`; the escape also removes the latent risk that a later "fix" to a visible space silently changes only `deriveTicketKey`'s delimiter and breaks per-ticket idempotency.) Source: blind+edge.
- [x] [Review][Patch] (FIXED) Removed the unreachable `503 RUNNER_QUEUE_FULL` ApiResponse (contradicted AC6 "REST ALWAYS returns 200") `[WorkflowBatchController.java + openapi.json + schema.d.ts]`. The service catches `RUNNER_QUEUE_FULL` per-ticket on EVERY iteration including the first (`:149-158,178-184`) → records it rejected, returns 200. No code path ever surfaces 503. Drop the 503 `@ApiResponse` + the javadoc "(503)" clause and regenerate the OpenAPI snapshot (`-Dopenapi.snapshot.write=true`, cross-shell per [[openapi-regen-platform-shim]]). Source: blind+edge.

### Deferred

- [x] [Review][Defer] Batch-row insert failure AFTER per-ticket submits commit orphans the runs + burns the key `[WorkflowBatchSubmissionService.java:116-126,197-207,256-268]` — deferred, narrow trigger. Per-ticket `submit()` commit in their own txs; if `insert()` then throws (mapped `IDEMPOTENCY_KEY_CONFLICT` / `INTERNAL_ERROR`), `completeFailed` marks the idempotency record FAILED → a same-key retry hits `priorAttemptFailed` ("use a fresh key") forever, and the already-queued runs have no `batch_submissions` row / can never be replayed. Inherent to the best-effort + non-`@Transactional` design; runs still execute fine (only the aggregate + replay are lost). Needs partial-commit reconciliation design.
- [x] [Review][Defer] `stampBatchSubmissionId` relies implicitly on `submit()` having already committed its `runner_executions` row `[WorkflowBatchSubmissionService.java:227-239]` — deferred, latent. Works today because enqueue is synchronous inside `submit`'s tx; if dispatch becomes async/after-commit, the native UPDATE matches 0 rows and silently swallows (best-effort) → permanent loss of the batch trace link, indistinguishable from a real miss.
- [x] [Review][Defer] Test hardening for the central best-effort property + idempotency conflict — deferred, test enhancement. (a) `WorkflowBatchSubmissionServiceTest` mocks the tx manager so `independentTransactionTemplate` runs callbacks inline — the REQUIRES_NEW isolation that MAKES best-effort work is never exercised; the IT (`WorkflowBatchSubmissionIT`) is happy-path only (no test where one per-ticket `submit()` truly rolls back while later tickets commit). (b) AC11 "idempotency conflict" is asserted only by stubbing `checkAndReserve` to throw (vacuous); no end-to-end same-key/different-fingerprint conflict. (c) No test for CLI blank-only input (`--tickets ",,,"`) or unknown-JSON-field rejection.
- [x] [Review][Defer] CLI logs `resolvedCorrelation` but passes the RAW `correlationId` to the service `[WorkflowCommands.java:253-267]` — deferred, pre-existing house pattern. When `--correlation-id` is omitted, `pushCorrelation(null)` mints one id for the success log while the service independently generates a DIFFERENT batch/child correlation UUID → logged id ≠ stamped id. Identical to the `submit-workflow` sibling (`:193-204`); affects the whole CLI family, not 3.18-specific. AC8 (`{batch}/{ticket}` threading) is still satisfied.

### Dismissed (11, verified false-positive / sanctioned)

- `actor_type` UPPERCASE-vs-CHECK-lowercase mismatch — FALSE POSITIVE: `ActorType.value()` returns lowercase (`"human"`), matching the V15 CHECK. (blind, unconfirmed-blind)
- Null `reservation` falls through as RESERVED — FALSE POSITIVE: `IdempotencyService.checkAndReserve` never returns null (returns non-null or throws). (blind+edge)
- `FIELD_SEPARATOR` (NUL) vs `append()` `(byte) 0` divergence — both ARE NUL (consistent); moot once Patch P1 makes the separator the explicit `"\u0000"`. (blind)
- `--exit-on-any-rejection` reuses `INVALID_COMMAND_PAYLOAD` — sanctioned Decision D-NOCODE/OQ-2 (Alex): avoids the three-sites + openapi/schema enum fan-out; rejections itemized in the printed table.
- `@JsonIgnoreProperties(ignoreUnknown=false)` no-op — spec-prescribed (Task 6), consistent with sibling request DTOs.
- `@Size(max=1000)` list cap vs `batch-max-tickets:100` — intentional defense-in-depth (hard bean cap behind the configurable business cap), both → `INVALID_COMMAND_PAYLOAD`.
- Empty / all-blank CLI ticket input — handled: `parseBatchTickets` drops blanks → empty list caught by the command's `@NotEmpty` → `INVALID_COMMAND_PAYLOAD`.
- D-CONTROLLER (new `WorkflowBatchController` not `WorkflowController`) — sanctioned deviation; AC6 path/200 contract fully honored, auto-covered by `REST_CONTROLLERS_*`.
- D-IDEMPKEY hashed per-ticket key (not literal `{batchKey}:{ticketRef}`) — sanctioned; literal `:` form fails `IdempotencyKeyValidator`; deterministic so replay-correct.
- D-PERSIST `result_json` snapshot (not child `batch_submission_items` table) — sanctioned OQ-1 (Alex); replay reconstructs rejected tickets faithfully; `runner_executions.batch_submission_id` FK still added.
- `childCorrelationId` slash with a slash-bearing `ticketRef` — harmless ambiguity; AC8 only requires the `{batch}/{ticket}` thread.
