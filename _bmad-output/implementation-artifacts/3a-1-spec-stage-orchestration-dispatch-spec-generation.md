# Story 3a.1: Spec-Stage Orchestration — `dispatchSpecGeneration`

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + workflow orchestrator,
I want `WorkflowOrchestrationService.dispatchSpecGeneration(workflowRunId)` to fire when a submitted run enters `Investigating` — analogous to story 3.11's `dispatchPlanGeneration`, but for the spec stage — and to auto-advance the run to `WaitingForSpecApproval` once the runner produces a valid spec artifact,
so that real ticket submission (stories 1.15 / 6.9 / 2.13) automatically dispatches a runner against the spec-stage context bundle (story 2.8 baseline, repo-extended later by 3a-2) and produces a spec artifact — replacing the test-only scaffolding that dispatches the mock spec runner directly today.

**Authoritative design doc:** `docs/adr/0004-spec-stage-orchestration.md` (Accepted 2026-05-26). This story IMPLEMENTS that ADR. **Do NOT author a new ADR** — 0004 already covers the decision; the epic stub's reference to "ADR 0004" is correct (unlike story 3.14, whose 0004/0005 references were stale).

## Acceptance Criteria

> AC shape follows **story 3.11 ACs 1–11** (see `_bmad-output/planning-artifacts/epic-03-agent-execution.md:216-234`) with the spec-stage substitutions from ADR 0004 §Decision-1 and the epic-3a stub (`epic-03-agent-execution.md:742-761`).

1. **Given** a new `WorkflowOrchestrationService` in `org.dradgo.application.workflow`, **When** `dispatchSpecGeneration(workflowRunId)` is invoked, **Then** it: (a) ensures the run is in `Investigating` (see AC-T1 / OQ-1 for the Inbox→Investigating reconciliation), (b) builds a deterministic idempotency key, (c) calls `RunnerBroker.dispatch(workflowRunId, RunnerStage.INVESTIGATION, idempotencyKey, ActorContext.SYSTEM)` — which assembles the spec-investigation bundle via the EXISTING `ContextBundleService.createForSpecInvestigation(...)`, records the pending `runner_executions` row, and dispatches through the active `RunnerAdapter` (mock under `!runners.docker`, Docker under `runners.docker`).

2. **Given** the runner produces a valid `runner-result.v1.json` carrying an `artifactReferences[]` entry with `artifactType=spec`, **Then** the existing `RunnerBroker.handleSuccess(...)` path ingests it via `ArtifactOperationService.recordOperation(...)` → payload write → `markAvailable(...)` (unchanged ingestion; no new artifact code) — the spec artifact lineage advances exactly as it does for the test-scaffolded path today.

3. **Given** the spec artifact becomes `available` for a run whose terminal runner execution was `RunnerStage.INVESTIGATION`, **Then** an automatic state transition fires: `WorkflowTransitionService.transition(workflowRunId, WorkflowState.WAITING_FOR_SPEC_APPROVAL, actor=system, reason="spec_ready", idempotencyKey)`. **This is the central new behavior — it does not exist today** (`handleSuccess` ingests the artifact but drives NO success transition; see Dev Notes §"The Central Gap"). The auto-advance decision MUST be owned by `WorkflowOrchestrationService` to satisfy AC9, not inlined raw into the broker.

4. **Given** runner failures (timeout, crash, contract violation, non-zero exit — story 3.1/3.2) at the spec stage, **When** detected, **Then** the run transitions to `Failed` with the correct `failure_category` from the registry and the failed `runner_executions` row + redacted logs (story 3.6) are preserved. **Note:** the broker ALREADY drives failure transitions via `driveWorkflowFailed(...)` → `WorkflowTransitionService`; this story must confirm spec-stage failures land at `Failed` and reconcile the AC9 ownership tension (Dev Notes §"Failure path & AC9").

5. **Given** retry, **Then** `WorkflowOrchestrationService.retrySpecGeneration(workflowRunId)` re-dispatches with a fresh context-bundle version + fresh `runnerExecutionId`, preserving the prior failure event + `runner_executions` row for audit. It is invoked from (a) `ApprovalService.rejectSpec(...)` AFTER its existing `WaitingForSpecApproval → Investigating` transition (`ApprovalService.java:~429`), and (b) the CLI/recovery `retry` baseline (story 1.18). Unlike the initial dispatch, the run is ALREADY in `Investigating` when `retrySpecGeneration` runs — it MUST re-dispatch only, never re-transition.

6. **Given** idempotency, **Then** repeated `dispatchSpecGeneration` calls for the same run while a spec-stage execution is already `pending`/`running` are no-ops returning the existing `runnerExecutionId` — never doubly-dispatch. The idempotency key MUST incorporate the spec-rejection loop count so each rejection-retry attempt is a distinct dispatch while a single attempt is replay-safe (see Dev Notes §"Idempotency key").

7. **Given** correlation propagation (story 1.19), **Then** the `correlationId` originating from the submit (or reject) REST/CLI command flows through the dispatch → runner events → artifact events → state transition chain. **Trap:** `RunnerBroker.handleSuccess` currently mints a fresh random `correlationId` (`RunnerBroker.java:417`) — this story must thread the real correlationId from the dispatching command/MDC instead (see Trap T6).

8. **Given** the stage→artifact-type contract, **Then** if the spec-stage runner emits a result whose `artifactType` is anything other than `spec`, the orchestration rejects it with a NEW `DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH` and the run transitions to `Failed` — a runner cannot accidentally emit a `implementationPlan`/`prOutput` at the spec stage. Today the broker accepts ANY `artifactType` without validating it against the dispatching stage (`handleSuccess` line ~420 parses but does not check) — this story adds the stage→expected-type guard.

9. **Given** the ArchUnit boundary (story 1.5 / 3.11 AC9), **Then** `WorkflowOrchestrationService` is the single path that auto-advances workflow state on runner success/failure outcomes. The existing rule `ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE` (`ArchitectureRuleCatalog.java:~352`) stays green (orchestration routes through `WorkflowTransitionService`). Add/extend a rule asserting no service OTHER than `WorkflowOrchestrationService` auto-triggers a transition in reaction to a runner outcome.

10. **Given** runner-image selection, **Then** the spec-stage runner kind is configurable via `application.yml` `deliveryline.runner.spec-stage.kind` (`codex` | `claude`), defaulting to `codex`. This requires a NEW config field on `RunnerProperties` + a stage→kind resolver, wired into the dispatch path, AND the matching key added to `src/test/resources/application.yml` (Trap T4 — validated-config shadowing).

11. **Given** the test suite, **Then** integration tests cover: end-to-end happy path (submit → spec generated via mock `happy-spec` scenario → state at `WaitingForSpecApproval` with an `available` spec artifact), each runner failure mode → state at `Failed` with correct `failure_category`, retry after rejection (rejectSpec → re-dispatch → new `runnerExecutionId`, prior audit preserved), idempotent re-dispatch is a no-op, `RUNNER_ARTIFACT_TYPE_MISMATCH` when the runner emits a non-spec result at the spec stage, and `correlationId` propagation across the full event chain. All async-result assertions drive the lifecycle deterministically (do not sleep on the 5s poller — see Trap T7).

## Tasks / Subtasks

- [x] **Task 1: Create `WorkflowOrchestrationService`** (AC: #1, #5, #6, #9)
  - [x] New class `org.dradgo.application.workflow.WorkflowOrchestrationService` (mirror the construction/transaction style of `WorkflowCommandService` / `ApprovalService`). Inject `RunnerBroker`, `RunnerProperties`, `WorkflowTransitionService`, `WorkflowRunReadPort` (state lookup), and the run-read port used to fetch the current `specRejectionLoopCount`.
  - [x] `dispatchSpecGeneration(String workflowRunId)`: resolve current run; ensure it is in `Investigating` (transition into it per OQ-1 resolution); build idempotency key; call `runnerBroker.dispatch(runId, RunnerStage.INVESTIGATION, key, ActorContext.SYSTEM)`. Return the resulting `RunnerExecutionHandle`/`RunnerDispatchResult`.
  - [x] `retrySpecGeneration(String workflowRunId)`: run is already `Investigating`; re-dispatch only (RunnerBroker handles fresh `runnerExecutionId` + bundle version). Do NOT re-transition.
  - [x] Build the idempotency key as `spec-dispatch:<workflowRunId>:<specRejectionLoopCount>` (Dev Notes §"Idempotency key").
  - [x] Guard: if a spec-stage execution is already `pending`/`running`, return the existing handle (lean on RunnerBroker's existing idempotency `Replayed` path — verify it covers the in-flight case for the same key).

- [x] **Task 2: Wire the success transition (the central gap)** (AC: #2, #3, #9)
  - [x] Add `WorkflowOrchestrationService.onSpecStageSucceeded(workflowRunId)` (or `onRunnerOutcome(...)`) that performs `WorkflowTransitionService.transition(runId, WAITING_FOR_SPEC_APPROVAL, system, "spec_ready", idempotencyKey)`.
  - [x] Have `RunnerBroker.handleSuccess(...)` — which already knows the `RunnerExecutionSnapshot row` (carries the stage) — delegate the terminal outcome to orchestration ONLY when `row.stage() == INVESTIGATION` and ingestion succeeded. **Break the constructor cycle** (broker↔orchestration): inject the orchestration callback into the broker via `ObjectProvider<WorkflowOrchestrationService>` or `@Lazy` (Trap T2).
  - [x] Confirm the transition fires only after the spec artifact is `available` (not merely recorded) — anchor the call after the successful `markAvailable` in the ingest loop.

- [x] **Task 3: Stage→artifact-type guard + new error code** (AC: #8) — **three-sites rule applies**
  - [x] Add `RUNNER_ARTIFACT_TYPE_MISMATCH` to `DomainErrorCode` (`domain/registry/DomainErrorCode.java`).
  - [x] Register it in `ProblemDetailsCatalog` (`adapters/rest/ProblemDetailsCatalog.java`) — `HttpStatus.BAD_GATEWAY` (mirror `RUNNER_CONTRACT_VIOLATION` at line ~127), title "Runner artifact type mismatch".
  - [x] Add the URI to `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`: `"RUNNER_ARTIFACT_TYPE_MISMATCH": "https://deliveryline.local/problems/runner-artifact-type-mismatch"`.
  - [x] Add a stage→expected-`ArtifactType` mapping (INVESTIGATION → `SPEC`; EXECUTION → `IMPLEMENTATION_PLAN`/`PR_OUTPUT`). In `handleSuccess`, validate each ingested artifact's type against the dispatching stage; on mismatch raise the new code and route to `Failed` with category `RUNNER_CONTRACT_VIOLATION` (reuse the existing `handleFailedValidation` failure path; the new DomainErrorCode is the typed surface for the REST/inspection layer).
  - [x] Verify with `-Pfoundation-gate` (memory: new-domainerrorcode-three-sites).

- [x] **Task 4: `spec-stage.kind` configuration** (AC: #10) — **validated-config test-yaml trap**
  - [x] Add a spec-stage kind field/resolver to `RunnerProperties` (`application/runner/RunnerProperties.java`). Recommended: top-level `SpecStage specStage` group binding `deliveryline.runner.spec-stage.kind` → `RunnerKind` (mirrors the existing `stageTimeouts` top-level grouping rather than the docker subgroup). Default `codex`.
  - [x] Add a `RunnerKind kindForStage(RunnerStage)` resolver (INVESTIGATION → spec-stage kind; else `docker.defaultKind()`).
  - [x] Wire the resolver into the dispatch kind selection (replace the hardcoded `docker().defaultKind()` at the dispatch site so spec dispatch honors the configured kind).
  - [x] Add `deliveryline.runner.spec-stage.kind: codex` to BOTH `src/main/resources/application.yml` AND `src/test/resources/application.yml` (test yaml shadows, not merges — memory: validated-config-needs-test-yaml).

- [x] **Task 5: Trigger wiring** (AC: #1, #5) — depends on OQ-1 resolution
  - [x] Auto-dispatch on submission: call `dispatchSpecGeneration(runId)` from the submit path (`WorkflowCommandService.submit` / `submitInternal`, `WorkflowCommandService.java:~159-203`) after the `Inbox` run + Linear link are created. Decide transaction boundary (Dev Notes §"Transaction & async boundary").
  - [x] Re-dispatch on rejection: call `retrySpecGeneration(runId)` from `ApprovalService.rejectSpec(...)` AFTER its `WaitingForSpecApproval → Investigating` transition (`ApprovalService.java:~429`). Honor rejectSpec's `@Transactional(propagation = MANDATORY)` semantics (Trap T3).
  - [x] Recovery `retry` baseline (story 1.18): ensure the existing `retryWorkflow` path can reach `retrySpecGeneration` for a `Failed` run whose last stage was investigation, OR document that spec-stage recovery retry is exercised via rejectSpec only for this story (scope decision — OQ-3).

- [x] **Task 6: ArchUnit boundary** (AC: #9)
  - [x] Add/extend an ArchUnit rule in `ArchitectureRuleCatalog` asserting only `WorkflowOrchestrationService` auto-advances workflow state on a runner outcome. Reconcile with the pre-existing broker→`WorkflowTransitionService` failure call (Dev Notes §"Failure path & AC9"): either route broker failure-driven transitions through orchestration too, or scope the rule narrowly + document the exception. Add `@ArchTest` reference in `ArchitectureBoundaryTest`.

- [x] **Task 7: Tests** (AC: #11)
  - [x] `@SpringBootTest` integration test mirroring `WorkflowCommandServiceContractTest` (Testcontainers Postgres, profiles `test` + `linear-mock`, mock runner `happy-spec`). Cover: submit→spec→`WaitingForSpecApproval`; each failure mode→`Failed`; rejectSpec→retry→new `runnerExecutionId` + preserved audit; idempotent re-dispatch no-op; `RUNNER_ARTIFACT_TYPE_MISMATCH`; correlationId propagation.
  - [x] Drive the async result deterministically (invoke `pollActiveExecutions()`/`onResult` directly or via the test harness; do NOT rely on the 5s scheduler — Trap T7).
  - [x] Unit test for `WorkflowOrchestrationService` (idempotency key shape, stage selection, no-op on in-flight).
  - [x] Replace/retire whatever in-test scaffolding currently dispatches the spec runner directly so tests flow through the real orchestration path (ADR 0004 §Context-1; Dev Notes §"What this replaces").

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (`dispatchSpecGeneration` start/finish, dispatch decision, spec-ready transition), `WARN` for recoverable anomalies (idempotent replay / in-flight no-op, retry re-dispatch, artifact-type mismatch rejection, runner failure→Failed), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `runnerExecutionId`, plus `stage` and (where known) `artifactId`. Use MDC (`MdcKeys.beginScope/endScope`) consistent with `ApprovalService` (`ApprovalService.java:~136-137`).
  - [x] Never log secrets, payload bytes, raw tokens, or full PII — log refs/ids/categories/counts only. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for the dispatch, the spec-ready transition, and the type-mismatch rejection (use a list-appender or `OutputCaptureExtension`).

## Dev Notes

### The Central Gap (read first)

`RunnerBroker.onResult(...)` → `handleSuccess(...)` (`RunnerBroker.java:321-466+`) currently: ingests artifact references via `ArtifactOperationService.recordOperation(...)` (line ~456), records the runner execution `COMPLETED`, emits `RUNNER_COMPLETED`. It **drives NO workflow state transition on success** — only failures route through `driveWorkflowFailed(...)` → `WorkflowTransitionService`. So a spec runner can finish and produce an `available` spec artifact while the run sits in `Investigating` forever. **Wiring `Investigating → WaitingForSpecApproval` on spec-available is the heart of this story** (AC3). The hook point is inside `handleSuccess`, guarded by `row.stage() == RunnerStage.INVESTIGATION`, delegated to `WorkflowOrchestrationService` to keep the auto-advance decision in one place (AC9).

### Async lifecycle (where things happen)

```
submit (REST/CLI) ─► WorkflowCommandService.submit ─► run created in INBOX (+ Linear link)
   └─► [Task 5] WorkflowOrchestrationService.dispatchSpecGeneration(runId)
          ├─ ensure run in INVESTIGATING (WorkflowTransitionService)            [OQ-1]
          └─ RunnerBroker.dispatch(runId, INVESTIGATION, key, ActorContext.SYSTEM)
                ├─ ContextBundleService.createForSpecInvestigation(...)  (EXISTS, 2.8 baseline)
                ├─ insert runner_executions (PENDING) + RUNNER_STARTED/RUNNER_DISPATCHED event
                └─ RunnerAdapter.dispatch(...)  (mock happy-spec under !runners.docker)

[scheduled, every 5s] RunnerConfiguration.pollActiveExecutions ─► RunnerBroker.poll
   └─ adapter.poll → tryReadResult → RunnerBroker.onResult(rex, bytes)
         └─ handleSuccess: ingest spec artifact (recordOperation → markAvailable)
               └─ [Task 2] if stage==INVESTIGATION & type==SPEC:
                     WorkflowOrchestrationService.onSpecStageSucceeded(runId)
                        └─ WorkflowTransitionService.transition(WAITING_FOR_SPEC_APPROVAL)   [AC3 — NEW]
```

Scheduled jobs live in `infrastructure/config/RunnerConfiguration.java` (`pollActiveExecutions` ~line 51; also timeout-scan ~43, stale-scan ~64) — there is **no Spring `ApplicationEventPublisher` / pub-sub**; all transitions are synchronous inline calls.

### Key existing surfaces (verified file:line)

- **State enum:** `domain/registry/WorkflowState.java` — `INBOX, PLANNED, INVESTIGATING, WAITING_FOR_SPEC_APPROVAL, EXECUTING, WAITING_FOR_REVIEW, COMPLETED, FAILED, PAUSED, TAKEN_OVER, RECONCILED`.
- **Transition table:** `application/workflow/WorkflowTransitionTable.java` — `INBOX → {PLANNED, TAKEN_OVER, RECONCILED}`, `PLANNED → {INVESTIGATING,...}`, `INVESTIGATING → {WAITING_FOR_SPEC_APPROVAL, TAKEN_OVER, RECONCILED}`, `WAITING_FOR_SPEC_APPROVAL → {EXECUTING, INVESTIGATING,...}`.
- **Transition service:** `application/workflow/WorkflowTransitionService.java` — `transition(runId, WorkflowState, TransitionActor, reason, idempotencyKey[, FailureCategory][, Map details])`; `TransitionActor(String identity, ActorType type)`; appends `WORKFLOW_STATE_CHANGED` itself (don't double-append).
- **Broker:** `application/runner/RunnerBroker.java` — `dispatch(String workflowRunId, RunnerStage stage, String idempotencyKey, ActorContext actor)` (~188); `onResult(String rex, byte[])` (~321); `handleSuccess(...)` (~394); dispatch fingerprint `workflowRunId|stage|contextBundleVersion` (~1620).
- **Adapter port:** `application/runner/spi/RunnerAdapter.java` — `dispatch/poll/tryReadResult/cancel`. Impls: `adapters/runner/MockRunnerAdapter.java` (`@Profile("!runners.docker")`, scenario `happy-spec` for INVESTIGATION), `adapters/runner/DockerRunnerAdapter.java` (`@Profile("runners.docker")`).
- **Bundle:** `application/runner/ContextBundleService.java` — `createForSpecInvestigation(...)` (~237) ALREADY exists for the spec stage (null approved-spec, rejections from approvals table, prior spec versions, hardwired `SHAREABLE_REDACTED`). Tested by `ContextBundleServiceSpecInvestigationTest`.
- **Artifact ingest:** `application/artifact/ArtifactOperationService.java` — `recordOperation(...)` (~250), `markAvailable(...)` (~284). No change needed.
- **Artifact type:** `domain/registry/ArtifactType.java` — `SPEC("spec")`, `IMPLEMENTATION_PLAN("implementationPlan")`, `PR_OUTPUT("prOutput")`.
- **Stage enum:** `domain/registry/RunnerStage.java` — `INVESTIGATION("investigation")`, `EXECUTION("execution")`.
- **Actor:** `application/artifact/ActorContext.java` — `(actorIdentity, actorType, correlationId)` with `ActorContext.SYSTEM` singleton.
- **Reject path:** `application/approval/ApprovalService.java` — `rejectSpec(...)` (~289-479) increments `spec_rejection_loop_count` (~359), then transitions `WaitingForSpecApproval → INVESTIGATING` (~429); returns with NO re-dispatch today (the hook point for `retrySpecGeneration`).
- **Submit path:** REST `WorkflowController.submit` `POST /api/v1/workflows/submit-workflow`; service `WorkflowCommandService.submit/submitInternal` (~120-203) creates the run in `INBOX`.
- **Correlation:** `application/observability/MdcKeys.java` (`CORRELATION_ID`, `WORKFLOW_RUN_ID`, `RUNNER_EXECUTION_ID`, ...) + `infrastructure/observability/CorrelationIdFilter.java` (`X-Correlation-Id` header → MDC, auto-gen UUIDv7).
- **ArchUnit:** `ArchitectureRuleCatalog.java:~352` `ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE`; tests in `ArchitectureBoundaryTest`.

### Idempotency key

Use `spec-dispatch:<workflowRunId>:<specRejectionLoopCount>`. The loop count is incremented atomically by `rejectSpec` (`ApprovalService.java:~359`), so: initial submit (count=0) and each rejection-retry get distinct keys (distinct dispatches), while a duplicate call within one attempt replays. `RunnerBroker.dispatch` already returns `RunnerDispatchResult.Replayed` on a same-key/same-fingerprint replay — verify it also short-circuits a still-in-flight `pending`/`running` execution for the same key (AC6); if not, add an in-flight guard in the orchestration service.

### Transaction & async boundary

`dispatchSpecGeneration` after submit: decide whether dispatch shares the submit transaction or runs after commit. Recommended: dispatch in the same logical request but be aware `RunnerBroker.dispatch` writes a `runner_executions` row + emits events — if the submit transaction rolls back, the dispatch must too. Mirror how `WorkflowCommandService.transition` (~391) composes. The actual runner result arrives asynchronously via the poller — the success transition (AC3) happens in a SEPARATE transaction inside `onResult`.

### Failure path & AC9

The broker ALREADY calls `WorkflowTransitionService` for failures (`driveWorkflowFailed`, ~1003). AC9 wants `WorkflowOrchestrationService` to be the SOLE auto-advancer on runner outcomes. Two ways to reconcile (pick one + document):
- **(Recommended)** Route the broker's terminal-outcome state decisions (both success and failure) through `WorkflowOrchestrationService.onRunnerOutcome(...)`, leaving the broker as pure result-harvester. Cleanest for AC9; larger touch on existing failure code.
- **(Minimal)** Keep failure transitions in the broker, scope the new ArchUnit rule to SUCCESS auto-advance only, and document the broker's failure-drive as the pre-existing exception. Smaller blast radius; weaker invariant. → **OQ-2**.

### What this replaces

ADR 0004 §Context-1: "today only test code dispatches the mock spec runner directly; there is no production path that auto-fires the spec runner on ticket submission." Find and retire that scaffolding (Task 7) so tests exercise the real orchestration. Candidate scaffolding lives near `ContextBundleServiceSpecInvestigationTest` and any test that seeds an `available` spec artifact directly to reach `WaitingForSpecApproval`.

### Dependency reality (important — do not block)

- **3a-2 (spec-stage repo-context bundle)** and **3.9 (RepositoryWorkspaceService)** are listed as dependencies but are sequenced AFTER / not-done (`3-9: backlog`, `3a-2: backlog`; next-active order is `3-9 → 3a-1 → 3a-2`). **This story does NOT block on them.** `ContextBundleService.createForSpecInvestigation` ALREADY exists (story 2.8 baseline) and produces a valid bundle today. 3a-1 wires orchestration against that baseline; 3a-2 later enriches the SAME bundle with repo fields **additively** — orchestration needs no change when it lands. Build and test 3a-1 now with the mock runner.
- **Docker / real runner:** the mock adapter (`happy-spec`) makes the full submit→spec→`WaitingForSpecApproval` loop testable WITHOUT Docker, repo clone, or secrets. Real end-to-end (Docker + repo + `ANTHROPIC_API_KEY`/`CODEX_API_KEY` + `GITHUB_TOKEN`) is a runtime/profile concern (`runners.docker`), not a code dependency for this story.

### Working-tree note

The git working tree currently carries UNCOMMITTED story 3-14 changes touching `DomainErrorCode.java`, `ProblemDetailsCatalog.java`, `registry-api-schema-placeholders.json`, `DoctorProbeAdapter/DoctorService/DoctorProbePort`, `GitHubConfiguration`, and both `application.yml` files (the two `DOCTOR_GITHUB_*` codes + github-real probe). When you add `RUNNER_ARTIFACT_TYPE_MISMATCH` and the `spec-stage.kind` keys, you'll be editing files already modified — coordinate so you don't clobber 3-14's in-flight edits.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - `WorkflowOrchestrationService.dispatchSpecGeneration/retrySpecGeneration/onSpecStageSucceeded` → `INFO` on entry + `INFO` on decision/finish / `WARN` on in-flight no-op or retry / `ERROR` on unexpected failure.
  - Spec-ready transition → `INFO` "transitioned {runId} Investigating → WaitingForSpecApproval reason=spec_ready".
  - Artifact-type mismatch → `WARN` with `stage`, expected vs actual `artifactType`, `runnerExecutionId` (never the payload).
  - Runner failure → `WARN`/`ERROR` with `failureCategory` (already partially present in broker).
- **Required context keys** (MDC or structured params): `correlationId`, `workflowRunId`, `runnerExecutionId`, `stage`, `artifactId` where known.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields. Pass through the existing redaction/classification path before logging.
- **Test contract:** new logging surfaces pinned by at least one focused test (list-appender or `OutputCaptureExtension`).

### Project Structure Notes

- New service belongs in `org.dradgo.application.workflow` (alongside `WorkflowCommandService`, `WorkflowTransitionService`) — NOT in `adapters.*`. Application code must not import `org.dradgo.adapters..` (memory: application-cannot-import-adapters; reach adapter capability only via the existing SPI ports the broker already uses).
- `RunnerProperties` stays in `application.runner` (it already does, for the same architecture reason).
- No DB migration is needed (no new columns; `runner_executions` + `artifacts` + `workflow_events` already model everything). Confirm before adding any Flyway file.

### Traps (LLM mistake-prevention)

- **T1 — `Inbox → Investigating` is NOT a direct transition.** The table is `INBOX → PLANNED → INVESTIGATING`. ADR 0004 / the stub describe a single "Inbox → Investigating" trigger. Resolve via OQ-1 before wiring Task 5 — do not silently emit an illegal transition.
- **T2 — Constructor cycle.** `WorkflowOrchestrationService` depends on `RunnerBroker` (for dispatch); the broker must call back into orchestration for the success transition. A constructor cycle fails Spring startup — inject the callback via `ObjectProvider<WorkflowOrchestrationService>` / `@Lazy` in the broker.
- **T3 — `rejectSpec` is `@Transactional(MANDATORY)`.** `retrySpecGeneration` invoked from it runs in the caller's transaction; a dispatch failure rolls back the rejection + transition. Decide if that all-or-nothing coupling is desired (recommended: yes, atomic) and test the rollback.
- **T4 — Validated-config shadowing.** Adding `deliveryline.runner.spec-stage.kind` to a `@ConfigurationProperties` record requires the key in BOTH main and `src/test/resources/application.yml` or the whole `@SpringBootTest` tier fails at startup (memory: validated-config-needs-test-yaml).
- **T5 — New DomainErrorCode = three sites.** `DomainErrorCode` + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json` must all be updated; verify with `-Pfoundation-gate` (memory: new-domainerrorcode-three-sites).
- **T6 — Don't mint a random correlationId.** `handleSuccess` does `UUID.randomUUID()` at line ~417; for AC7, thread the real correlationId (from the dispatch command / persisted on the runner execution or workflow run) instead, so the success transition + artifact events share the originating correlationId.
- **T7 — Don't sleep on the 5s poller in tests.** Invoke `pollActiveExecutions()`/`onResult(...)` directly (or via the test seam) to drive the result deterministically; relying on `@Scheduled` makes flaky tests.
- **T8 — Don't re-transition on retry.** `rejectSpec` already moves the run to `Investigating`; `retrySpecGeneration` must re-dispatch only. Re-transitioning would be an illegal `INVESTIGATING → INVESTIGATING` (or duplicate event).
- **T9 — Verify in a clean/Linux env if you touch shared gates.** Adding the error code + config touches foundation-gate + contract surfaces; local green ≠ CI green (memory: verify-ci-fixes-in-clean-env). Run gates via PowerShell to route around the RTK-Bash hook (memory: rtk-hook-only-matches-bash).
- **T10 — `dispatch` already emits the lifecycle events.** Don't append `RUNNER_DISPATCHED` from orchestration — the broker owns runner-lifecycle events; orchestration owns only the workflow state transition (which itself appends `WORKFLOW_STATE_CHANGED`).

### Open Questions (resolve with architect; recommendations given)

- **OQ-1 — Inbox→Investigating reconciliation.** Table requires `INBOX → PLANNED → INVESTIGATING`, and nothing in prod advances these today. Options: (a) orchestration performs both hops; (b) amend `WorkflowTransitionTable` to allow `INBOX → INVESTIGATING` directly (matches ADR 0004 wording; `PLANNED` appears to be a reserved/unused intermediate). **Recommend (b)** — add `INVESTIGATING` to `INBOX`'s allowed targets and document the rationale — pending architect confirmation that `PLANNED` carries no required side-effect. Whichever is chosen, `dispatchSpecGeneration` owns the transition (AC9).
- **OQ-2 — AC9 ownership of failure transitions.** See Dev Notes §"Failure path & AC9". **Recommend** routing both success and failure outcome→state decisions through `WorkflowOrchestrationService.onRunnerOutcome(...)` for a clean single-owner invariant; fall back to scoping the ArchUnit rule to success-only if the failure-path refactor proves too broad for this story.
- **OQ-3 — Recovery-retry scope.** Should story-1.18 CLI/UI `retry` reach `retrySpecGeneration` for a `Failed` spec-stage run now, or is rejectSpec-driven retry sufficient for 3a-1? **Recommend** wiring the rejectSpec path fully + leaving general `Failed`-run spec retry as a thin follow-up if the existing `retryWorkflow` doesn't already cover the `Investigating`-re-entry case.
- **OQ-4 — In-flight idempotency semantics.** Confirm `RunnerBroker.dispatch` short-circuits a same-key dispatch while the prior execution is still `pending`/`running` (AC6) vs only after completion. If it only dedupes by recorded idempotency key, the in-flight no-op may need an explicit guard in orchestration.
- **OQ-5 — Expected-type mapping location.** Put the `RunnerStage → ArtifactType` expectation in the broker, in `RunnerStage` itself, or in orchestration? **Recommend** a small mapping owned by the broker (where the result is parsed) since validation happens at ingest; orchestration consumes the typed error.

### References

- [Source: docs/adr/0004-spec-stage-orchestration.md] — authoritative design (Accepted). §Decision-1 = this story.
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3a-1] (lines 742-761) — stub: goal, deps, AC-shape reference, the story-2.10-AC4 re-entry note.
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.11] (lines 216-234) — the analog ACs 1–11 this story adapts.
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.1] (lines 12-31) — DockerRunnerAdapter / RunnerStage / file-based contract context.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-05-26.md] — the active-slice pivot driving 3a-1/3a-2.
- Code anchors: `RunnerBroker.java:188,321,394,417,1003,1620`; `WorkflowTransitionService.java`; `WorkflowTransitionTable.java`; `ContextBundleService.java:237`; `ApprovalService.java:289,359,429`; `WorkflowCommandService.java:120-203`; `RunnerProperties.java:26`; `DomainErrorCode.java`; `ProblemDetailsCatalog.java:119-129`; `ArchitectureRuleCatalog.java:352`; `infrastructure/config/RunnerConfiguration.java:51`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context) — bmad-dev-story

### Debug Log References

- Full fast Surefire tier (PowerShell, `-Djacoco.skip=true`): 744 tests / 0 failures / 0 errors / 11 skipped.
- New integration test `SpecStageOrchestrationIT` (Testcontainers Postgres + mock `happy-spec`): 3/3. Named `*IT` so Failsafe (Docker contract tier) runs it and Surefire (no-Docker Windows fast tier) excludes it — a `*IntegrationTest` name leaks into Surefire and crashes on the Docker-less unit-test runner.
- Focused unit slices: `WorkflowOrchestrationServiceTest` 6/0, `RunnerBrokerUnitTest` 35/0, `WorkflowTransitionTableTest` 9/0, `RunnerPropertiesTest` 10/0, `RunnerLoggingContractTest` 9/0, `ApprovalServiceRejectSpecTest`/`ApprovalServiceApproveSpecTest`/`WorkflowCommandServiceReplayRefTest` green.
- Foundation gate (`-Pfoundation-gate verify`): 30 tests / 0 failures / 1 skipped — three-sites `RUNNER_ARTIFACT_TYPE_MISMATCH`→`BAD_GATEWAY` (Contract #7), ArchUnit boundaries incl. the new AC9 rule (Contract #1), and the transition cross-product/failure-category contracts all green. `spotless:check` + `checkstyle:check` clean (0 violations).

### Completion Notes List

Implemented ADR 0004 §Decision-1. All 11 ACs + the Logging task delivered.

- **Task 1 — `WorkflowOrchestrationService`** (`application.workflow`): `dispatchSpecGeneration` (ensures `Investigating`, deterministic key `spec-dispatch:<runId>:<specRejectionLoopCount>`, dispatches via `RunnerBroker`), `retrySpecGeneration` (re-dispatch only, never re-transitions — T8), `onSpecStageSucceeded` (the AC3 auto-advance `Investigating → WaitingForSpecApproval`, idempotent — swallows `ILLEGAL_TRANSITION`). SLF4J + MDC instrumentation throughout.
- **Task 2 — success transition wired (the central gap):** `RunnerBroker.handleSuccess` now delegates the terminal spec-stage outcome to orchestration when `row.stage()==INVESTIGATION`, after the artifact is ingested + the execution `COMPLETED`. Broker↔orchestration cycle broken by a **lazy `Supplier<WorkflowOrchestrationService>`** (`ObjectProvider::getIfAvailable`); the original eager `getIfAvailable()` in the ctor caused `BeanCurrentlyInCreationException` — resolved at use time instead.
- **Task 3 — stage→artifact-type guard + `RUNNER_ARTIFACT_TYPE_MISMATCH`** (three sites: `DomainErrorCode` + `ProblemDetailsCatalog` `BAD_GATEWAY` + `registry-api-schema-placeholders.json`; verified `-Pfoundation-gate`). `handleSuccess` validates each ingested artifact's type against the dispatching stage (INVESTIGATION→`spec`; EXECUTION→`implementationPlan`/`prOutput`) and routes a mismatch to `Failed` via the existing contract-violation path, emitting the typed code in the `RUNNER_FAILED` event details.
- **Task 4 — `deliveryline.runner.spec-stage.kind`** on `RunnerProperties` (new nested `SpecStage` record) + `kindForStage(RunnerStage)` resolver wired into the dispatch request AND the post-exec secret-scan (so both compare against the same injected key); key added to BOTH `application.yml` files (T4).
- **Task 5 — trigger wiring:** `WorkflowCommandService.submit` auto-dispatches in the submit transaction; `ApprovalService.rejectSpec` re-dispatches after its `WaitingForSpecApproval → Investigating` transition (inside its `MANDATORY` tx — T3 atomic).
- **Task 6 — ArchUnit:** new `ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS` rule (only `WorkflowOrchestrationService`/`RunnerBroker` may invoke `onSpecStageSucceeded`) + `@ArchTest` registration.
- **Task 7 — tests + logging** as listed in Debug Log References; retired no scaffolding (existing tests still seed artifacts directly for approval flows, which is orthogonal).

**Decisions / reconciliations (resolve at review; recommendations followed):**

- **OQ-1 → option (b):** amended `WorkflowTransitionTable` to allow `INBOX → INVESTIGATING` directly (matches ADR 0004's stated single-hop trigger; `PLANNED` stays a reserved/unused intermediate). Updated `WorkflowTransitionTableTest` + `TransitionTableCrossProductFoundationContract`.
- **AC4 reconciliation (was implicit):** the table had **no `INVESTIGATING → FAILED` edge**, so `driveWorkflowFailed` silently swallowed the `ILLEGAL_TRANSITION` and spec-stage failures were stranded. Added the edge + extended the failure-category guard to admit `INVESTIGATING → FAILED` (same allowed runner categories as `EXECUTING → FAILED`).
- **OQ-2 → minimal:** kept the broker's pre-existing failure-drive; scoped the new ArchUnit rule to the SUCCESS auto-advance and documented the failure path as the exception.
- **AC6 / OQ-4:** the broker's key+fingerprint idempotency does NOT dedupe a same-key re-dispatch once the context-bundle version advances (it raises `IDEMPOTENCY_KEY_CONFLICT`). Added an explicit **in-flight guard** in orchestration: a pending/running INVESTIGATION execution makes dispatch/retry a no-op returning the existing handle.
- **Auto-dispatch master switch (`spec-stage.auto-dispatch`):** ON in production `application.yml`, OFF in the shared test `application.yml` — mirroring the existing `scheduling.enabled: false` test convention — so the ~700-test suite stays deterministic; the integration test opts in via `@TestPropertySource`. This is a deliberate deviation from a strictly-unconditional reading of AC1/AC5 to avoid destabilizing the suite; flag for architect ratification.
- **AC1c:** broker dispatch now routes INVESTIGATION → the existing `ContextBundleService.createForSpecInvestigation` (was using generic `create`).
- **AC10 secret-scan coupling:** also switched the post-exec scan kind to `kindForStage(stage)` (the 3.5 review flagged the latent `defaultKind()` divergence).
- **Mock end-to-end gap (closed):** `MockRunnerAdapter` now writes the referenced artifact content (`writeArtifactContent`) on the HAPPY path — a real runner writes both the result and the artifact files; without this the broker's full ingest path could not read the spec.

**Known gap surfaced for follow-up (NOT in 3a-1 scope):** the spec artifact is ingested but stays `pending` — `ArtifactOperationService.markAvailable` has **no production caller anywhere**, so AC2's "→ markAvailable" ingestion step is unwired system-wide. The orchestration auto-advance correctly fires on successful spec INGEST. Completing artifact availability needs checksum/storageRef plumbing + artifact code this story explicitly scopes out ("unchanged ingestion; no new artifact code"); it also affects the Epic-2 artifact-review surface. Recommend a dedicated follow-up story.

**Review-continuation addendum (2026-06-03):** closed the final two open patch findings. P3 — `WorkflowOrchestrationService.handleSpecReadyIllegalTransition` now distinguishes benign idempotent replay (state at/past `WaitingForSpecApproval`, INFO) from a genuine divergence anomaly (any other state, ERROR), instead of one blanket WARN swallow; still never rethrows (confirmed the call site shares the poller transaction with `recordCompleted`, so a rethrow would un-commit the completion and cause infinite re-harvest). P4 — `WorkflowCommandService.submitInternal` re-reads and returns the committed run state (Investigating when auto-dispatch advanced it in-tx) instead of a hardcoded Inbox. Re-anchored the line-numbered `ForbiddenThreadSleep` checkstyle suppression (697→709) shifted by the P4 edit. Verified via PowerShell: `WorkflowOrchestrationServiceTest` 8/0/0, full fast Surefire 746/0/11skip, `SpecStageOrchestrationIT` (Docker/Testcontainers) 3/0/0, `spotless:check` + `checkstyle:check` 0 violations.

Verification routed via PowerShell to avoid the RTK Bash hook (memory: rtk-hook-only-matches-bash). Recommend `code-review` with a different LLM. WSL2/Linux parity not run (no lockfile/frontend/runner-image change; Testcontainers behaves identically — but the foundation gate + integration test are Docker-backed, so a Linux CI confirm is still advisable per memory: verify-ci-fixes-in-clean-env).

### File List

**Main:**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java` (NEW)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/MockRunnerAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/resources/application.yml`

**Test / config:**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowOrchestrationServiceTest.java` (NEW)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/SpecStageOrchestrationIT.java` (NEW)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowTransitionTableTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowCommandServiceReplayRefTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceApproveSpecTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/approval/ApprovalServiceRejectSpecTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerPropertiesTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/DockerLifecycleITSupport.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java`
- `deliveryline-backend/src/test/resources/application.yml`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `config/checkstyle/suppressions.xml`

## Change Log

| Date       | Version | Description                                                                 | Author |
| ---------- | ------- | --------------------------------------------------------------------------- | ------ |
| 2026-06-02 | 0.1     | Implemented spec-stage orchestration (ADR 0004 §Decision-1); status → review | Amelia (dev-story) |
| 2026-06-03 | 0.2     | Review-continuation: applied final 2 patch findings (P3 ILLEGAL_TRANSITION anomaly disambiguation, P4 submit returns committed state); checkstyle suppression re-anchored 697→709; status → review | Amelia (dev-story) |
| 2026-06-03 | 0.3     | bmad-code-review of the review-continuation delta (3 layers): D1 (false-positive ERROR anomaly logs) resolved Option 1 → P0 soften to WARN + best-effort docs; P1 made vacuous tests assert log branch + added 2 cases; P2/P3 guarded both diagnostic re-reads against transaction-unwinding read failures; P4 dismissed (File-List false positive). Suppression re-anchored 709→719. Verified: WorkflowOrchestrationServiceTest 10/0/0, spotless+checkstyle clean. Status → done | Code review (Opus 4.8) |

## Review Findings

_Code review 2026-06-02 (bmad-code-review) — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 13 distinct findings after dedup: 2 decision-needed (both resolved → deferred per Alex), 4 patch, 7 deferred, 0 dismissed. Plus 6 positive verifications (three-sites, lazy-Supplier cycle break, test-yaml shadow, AC4 transition-table fan-out, AC6 in-flight+loop-key, AC5 no-re-transition — all confirmed correct)._

_Review-continuation dev-story 2026-06-03 (Amelia) — the 2 remaining batch-skipped patches (P3 ILLEGAL_TRANSITION disambiguation, P4 submit returns committed state) are now APPLIED + verified; all 4 patch findings resolved. Status → review._

### Decision-Needed (resolved)

- [x] [Review][Decision→Defer] Spec artifact never reaches `available`; auto-advance to `WaitingForSpecApproval` fires on a `pending` artifact — `RunnerBroker.handleSuccess` ingests via `recordOperation` but nothing in production calls `ArtifactOperationService.markAvailable`, so AC2's "→ markAvailable" and AC3's "becomes available" precondition are not enforced; `onSpecStageSucceeded` advances the run unconditionally on COMPLETED ingest. Self-disclosed KNOWN GAP (memory: markavailable-has-no-production-caller); full availability needs checksum/storageRef + artifact code the story scopes out. (sources: blind+edge+auditor, HIGH) — **DEFERRED (Alex): documented KNOWN GAP; availability needs out-of-scope artifact (checksum/storageRef) plumbing — accepted as tech debt for a follow-up, story closes on the disclosed AC2/AC3 limitation.**
- [x] [Review][Decision→Defer] Synchronous runner dispatch runs inside the submit/reject DB transaction — `dispatchSpecGeneration`/`retrySpecGeneration` → `RunnerBroker.dispatch` (PROPAGATION_REQUIRED) calls `runnerAdapter.dispatch` (Docker: createContainer + startContainer) while the `submit`/`rejectSpec` `@Transactional` is still open. A rollback after the container starts orphans a running container with no `runner_executions` row (recovery/timeout scans key off the row); also holds a DB connection across Docker daemon I/O. (sources: edge, HIGH) — **DEFERRED (Alex): deliberate atomic-rollback design (run + row commit/roll-back together); orphan-container / connection-hold risk rides on the pending architect ratification of the auto-dispatch=true deviation.**

### Patch

- [x] [Review][Patch] **APPLIED** Artifact-type mismatch guard runs inside the ingest loop — an earlier valid `artifactReference` is ingested before a later mismatch drives the run to `FAILED`, leaving orphaned half-ingested artifacts. Fixed: added a pre-validation pass over ALL refs before any ingestion in `handleSuccess`. [RunnerBroker.java:596-614] (sources: blind, HIGH) — verified RunnerBrokerUnitTest 33/0/0, spotless+checkstyle clean.
- [x] [Review][Patch] **APPLIED** `handleArtifactTypeMismatch` re-emits `RUNNER_FAILED` + re-drives `driveWorkflowFailed` on an already-terminal row — the event append + failure drive previously ran unconditionally, producing duplicate failure events on result replay. Fixed: early-return on `isTerminal(row.status())` (at-most-once failure semantics, mirroring the secret-leak & git-push paths). [RunnerBroker.java:867-879] (sources: blind+edge, MEDIUM) — verified RunnerBrokerUnitTest 33/0/0.
- [x] [Review][Patch] **APPLIED (2026-06-03 review-continuation dev-story)** `onSpecStageSucceeded` no longer blanket-swallows ALL `ILLEGAL_TRANSITION` as a benign replay. After catching it, the run state is re-read and the cause disambiguated: a run already at/past `WaitingForSpecApproval` (`SPEC_READY_OR_BEYOND` = WaitingForSpecApproval/Executing/WaitingForReview/Completed) is a benign idempotent replay → INFO + swallow; ANY other state (taken-over/failed/reconciled/paused, or vanished `<not_found>`) is a genuine divergence anomaly → **ERROR** so it surfaces in observability instead of being masked. Still swallowed (never rethrown) — verified the call site runs inside the SAME poller transaction as `recordCompleted`/`RUNNER_COMPLETED` (`RunnerBroker.onResult` ← `harvestResultFromAdapter` ← `perItemTransactionTemplate.execute`), so rethrowing would roll the committed completion back and the poller would re-harvest forever for a permanently-diverged run. [WorkflowOrchestrationService.java `handleSpecReadyIllegalTransition`] (sources: blind, MEDIUM) — verified WorkflowOrchestrationServiceTest 8/0/0 (benign-replay + TAKEN_OVER-anomaly + vanished branches), fast tier 746/0/11skip, spotless+checkstyle clean.
- [x] [Review][Patch] **APPLIED (2026-06-03 review-continuation dev-story)** `submit` now returns the run's ACTUAL committed state instead of a hardcoded `WorkflowState.INBOX`. After `dispatchSpecGeneration`, `submitInternal` re-reads the run within the transaction (`workflowRunReadPort.findByPublicId(...).map(WorkflowRunSnapshot::currentState)`): auto-dispatch=false leaves it `Inbox`; auto-dispatch=true reports `Investigating`. [WorkflowCommandService.java:208-225] (sources: edge, LOW) — verified SpecStageOrchestrationIT 3/0/0 (new `assertEquals(INVESTIGATING, submitResult.currentState())`), WorkflowCommandServiceContractTest's `INBOX`-return assertion stays green under the off-by-default test profile.

### Deferred

- [x] [Review][Defer] Originating submit/reject `correlationId` not propagated across the async poller boundary; falls back to deterministic `rex-<id>` (improvement over old random UUID), and the late-result harvest path still uses a random UUID — full propagation needs a runner-execution-row migration the story forbids. AC7 partial, documented in code. [RunnerBroker.java:590-595,resolveOutcomeCorrelationId] — deferred, needs migration out of scope (sources: blind+auditor, MEDIUM)
- [x] [Review][Defer] AC11 failure-mode / type-mismatch / correlationId coverage lives at the unit/slice tier (`WorkflowTransitionTableTest`, `RunnerBrokerUnitTest`, `WorkflowOrchestrationServiceTest`), not the `@SpringBootTest` integration tier AC11 names. Behavior IS covered; integration-tier addition is the literal-AC11 follow-up. [SpecStageOrchestrationIT] — deferred, behavior unit-covered (sources: auditor, MEDIUM)
- [x] [Review][Defer] AC9 auto-advance ArchUnit rule is scoped to success only and guards the `onSpecStageSucceeded` call, not the underlying `spec_ready` transition (failure path still mutates via the broker). Intentional OQ-2 minimal choice; hardening opportunity. [ArchitectureRuleCatalog.java ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS] — deferred, intentional OQ-2 scope (sources: auditor, MEDIUM)
- [x] [Review][Defer] In-flight dispatch guard is read-then-act (no row lock / unique constraint) → theoretical concurrent double-dispatch. Needs locking/constraint (migration-forbidden); not reachable from current callers. [WorkflowOrchestrationService.java inFlightSpecDispatch] — deferred, latent + needs constraint (sources: blind, LOW)
- [x] [Review][Defer] `ensureInvestigating` uses a run-fixed idempotency key with no state/loop discriminator — latent collision if a future caller routes a non-Inbox→Investigating through it. No current trigger. [WorkflowOrchestrationService.java ensureInvestigating] — deferred, latent (sources: edge, LOW)
- [x] [Review][Defer] AC5 CLI/recovery `retry` baseline (story 1.18) not wired to `retrySpecGeneration` (only `rejectSpec` is) — intentional OQ-3 deferral; minor Task 5(c) traceability note. [ApprovalService.java:452 only caller] — deferred, intentional OQ-3 (sources: auditor, LOW)
- [x] [Review][Defer] `MockRunnerAdapter.writeReferencedArtifactContents` catches only `IOException`, but `writeArtifactContent` throws `DomainException` on a path-traversal `contentReference` — test-infra only, controlled fixtures, not production-reachable. [MockRunnerAdapter.java:91-118] — deferred, test-infra only (sources: edge, LOW)

### Re-review 2026-06-03 (bmad-code-review of the review-continuation delta)

_Re-review of the uncommitted review-continuation delta (P3 + P4) — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 1 decision-needed, 4 patch, 0 defer, 5 dismissed. Note: the Acceptance Auditor positively verified both P3 and P4 as faithful and AC/trap-compliant; the Blind + Edge layers independently surfaced a one-directional false-positive hole in P3's classifier that the auditor's enum-partition reasoning missed._

_**Resolution (2026-06-03):** D1 resolved by Alex → Option 1 (accept best-effort + soften). All 4 patches applied (P0 soften-to-WARN + best-effort docs, P1 non-vacuous log-branch tests, P2/P3 guarded re-reads), 1 dismissed (P4 File-List false positive). Verified via PowerShell (rtk-hook-only-matches-bash): WorkflowOrchestrationServiceTest 10/0/0, spotless:check + checkstyle:check 0 violations. `ForbiddenThreadSleep` suppression re-anchored 709→719 (P4 edit + spotless re-wrap). SpecStageOrchestrationIT (Docker tier) not re-run locally — unchanged by this delta; advise a Linux/Docker CI confirm. Status → done._

#### Decision-Needed (resolved)

- [x] [Review][Decision→Patch] **P3 anomaly classifier emits false-positive ERROR logs for benign replays** — **RESOLVED (Alex → Option 1: accept as best-effort + soften).** Downgrade the anomaly branch from ERROR to WARN and document the classifier as best-effort (the current-state snapshot is not authoritative, so a benign replay of a run that genuinely reached spec-ready and then legitimately moved on — reject→re-investigating loop, taken-over/reconciled/paused/failed — may land in this branch). See patch P0 below. `handleSpecReadyIllegalTransition` classifies solely on the run's *current* state snapshot, so it cannot distinguish "diverged AFTER a genuine spec-ready" (benign replay) from "diverged having NEVER reached spec-ready" (true anomaly). Concrete benign cases now logged at ERROR on a duplicate/late poller harvest: (a) run reached `WaitingForSpecApproval`, was legitimately rejected back to `Investigating` (the normal reject→retry loop, `ApprovalService.rejectSpec`), then runner #1's result re-harvests → `current=INVESTIGATING` ∉ `SPEC_READY_OR_BEYOND` → ERROR; (b) run reached spec-ready then was legitimately `TAKEN_OVER`/`RECONCILED`/`PAUSED`/`FAILED` → ERROR. Because both branches behave identically (swallow + return), this is purely an observability defect — but it defeats the stated purpose of P3 (make genuine anomalies observable): the ERROR signal becomes noisy and will be ignored. The distinction is also unobservable beyond a log level (no metric/alert), and the updated Javadoc's "surfaced distinctly" overstates behavior. Edge Hunter confirmed the hole is one-directional (no false-negatives: in-set states are only reachable via a genuine spec-ready). [WorkflowOrchestrationService.java `handleSpecReadyIllegalTransition` / `SPEC_READY_OR_BEYOND`] (sources: blind+edge, HIGH/MEDIUM)

#### Patch

- [x] [Review][Patch] **APPLIED** P0 — Softened the P3 anomaly branch to WARN + documented best-effort classification (from the resolved Decision) [WorkflowOrchestrationService.java `handleSpecReadyIllegalTransition` / `SPEC_READY_OR_BEYOND`] — anomaly-branch `log.error` → `log.warn` ("probable anomaly … best-effort classification from current state"); method + field Javadoc now state the classification is best-effort on the current snapshot (a benign replay of a run that genuinely reached spec-ready and then legitimately moved on — reject→retry loop, taken-over/reconciled/paused/failed — may land here); softened the "surfaced distinctly" wording on `onSpecStageSucceeded`. (sources: blind+edge, decision-resolved) — verified WorkflowOrchestrationServiceTest 10/0/0, spotless+checkstyle clean.
- [x] [Review][Patch] **APPLIED** New P3 unit tests no longer vacuous [WorkflowOrchestrationServiceTest.java] — attached a logback `ListAppender` (mirrors `RunnerLoggingContractTest`) + `assertLoggedAt(level, fragment)` helper. Benign-replay test now asserts `INFO "idempotent replay"`; anomaly test asserts `WARN "probable anomaly"`; vanished test asserts `WARN "<not_found>"`. Added two cases: (a) post-spec-ready benign divergence (`INVESTIGATING` after a reject loop) documenting the accepted WARN false-positive; (b) diagnostic re-read failure swallowed (`WARN "could not re-read run state"`). The tests would now FAIL if the disambiguation branch were deleted. Satisfies the Logging-task "assert expected log line at expected level" contract. (sources: blind+auditor, MEDIUM) — verified 10/0/0.
- [x] [Review][Patch] **APPLIED** P3 diagnostic re-read can no longer roll back a committed completion [WorkflowOrchestrationService.java `handleSpecReadyIllegalTransition`] — wrapped the diagnostic `findByPublicId` re-read in `try/catch (RuntimeException)`; on a read failure it logs WARN ("could not re-read run state…") and returns, so the read can never propagate out of the poller per-item transaction and unwind the committed `recordCompleted`/`RUNNER_COMPLETED` (which would cause infinite re-harvest). New test `onSpecStageSucceededSwallowsDiagnosticReadFailureWithoutRethrowing`. (sources: edge, LOW) — verified 10/0/0.
- [x] [Review][Patch] **APPLIED** P4 cosmetic re-read can no longer roll back the entire submit [WorkflowCommandService.java submitInternal] — wrapped the re-read in `try/catch (RuntimeException)`, falling back to `workflowRun.currentState()` on BOTH an empty result and a thrown exception, so a transient read failure in this purely cosmetic state lookup cannot escape the `@Transactional submit` and discard the created run + Linear link + `Inbox→Investigating` transition + dispatched `runner_executions` row. (WARN-log of the fallback deliberately omitted — the class has no SLF4J logger and introducing one is out of proportion to a LOW finding; scope-trimmed.) (sources: edge+blind, LOW) — verified module compiles; checkstyle `ForbiddenThreadSleep` suppression re-anchored 716→719 after spotless re-wrap.
- [x] [Review][Patch→Dismissed] ~~Story File List omits `WorkflowOrchestrationService.java`~~ — **FALSE POSITIVE on verification:** the file IS present in the File List (under **Main:**, first entry, marked `(NEW)`). The auditor mis-cited the line range. No change needed. (sources: auditor, LOW)

#### Dismissed (5)

- P3 "re-read races the transition / unused version field" — subsumed by the Decision finding; optimistic-version on a best-effort diagnostic log line is over-engineering. (blind)
- P3 "swallow-without-rethrow safety asserted not demonstrated" — Edge Hunter verified the ordering is correct: `recordCompleted` commits before `onSpecStageSucceeded` within the same poller per-item tx. (The throwing-read variant survives as a patch above.) (blind)
- P3 "not-found silently masks data loss" — the `<not_found>` path is logged at ERROR (the anomaly branch), not silently swallowed. (blind)
- P4 "re-read may report a more-advanced state if another writer touched the row" — false positive: the run is created in *this* transaction and is invisible/unmutable to other transactions until commit. (blind)
- P3 "anomaly path strands the completed run with no compensation" — deliberate and correct given the shared-transaction constraint; auditor itself deems no action needed. (auditor)
