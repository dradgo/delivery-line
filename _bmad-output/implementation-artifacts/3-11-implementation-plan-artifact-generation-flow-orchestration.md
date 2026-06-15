# Story 3.11: Implementation-Plan Artifact Generation Flow (Orchestration)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager (downstream consumer) and Developer (next reviewer),
I want the workflow orchestration wired end-to-end for the **plan stage**: spec-approved → repository workspace prepared (story 3.9) → EXECUTION runner dispatched against the full implementation-stage context bundle (story 3.10) → implementation-plan artifact ingested → workflow auto-advanced to `WaitingForReview`,
so that FR14 (developer access to approved spec + workflow context) and FR15 (developer reviews implementation output — plan side) are wired through the runner broker, repository workspace, artifact operations, and the state-transition service — **promoting the dormant `RunnerStage.EXECUTION` seam (built by 3.9 / 3.10 / 3a-2) into a live production flow.**

## Context & Central Reconciliation (READ FIRST)

**This story is the EXECUTION-stage analog of story 3a-1 (`dispatchSpecGeneration`).** 3a-1 built the INVESTIGATION (spec) orchestration; 3.11 mirrors it almost verbatim for the implementation-plan stage. **Read `3a-1-spec-stage-orchestration-dispatch-spec-generation.md` first** — its `WorkflowOrchestrationService`, the broker↔orchestration lazy-`Supplier` cycle break, the stage→artifact-type guard, the `auto-dispatch` master switch, the idempotency-key + in-flight-guard discipline, and the test shape are ALL reused. This story adds the *plan-stage twins* of those spec-stage methods plus the *one new trigger* (spec-approval).

**THE STRUCTURAL FACTS THAT SHAPE THIS STORY (each verified against current code):**

1. **The dormant seam is already built — 3.11 only adds the live caller.** Every downstream piece exists and is unit/IT-tested:
   - `ContextBundleService.create(...)` is sub-stage-aware (`ExecutionSubStage`) + repo-aware for EXECUTION (story 3.10).
   - `RunnerBroker.dispatch(...)` resolves `repositoryRef`, clones+summarizes, derives the `ExecutionSubStage`, and threads repoRef/ticketRef onto the dispatch request **for EXECUTION** (story 3.10, `RunnerBroker.java:~405-444`).
   - `captureAndPush` runs in `handleSuccess` (story 3.9/3.10, `RunnerBroker.java:~861`).
   - The stage→artifact-type guard + `RUNNER_ARTIFACT_TYPE_MISMATCH` exist, with `EXECUTION → {implementationPlan, prOutput}` already mapped (`RunnerBroker.java:1021-1027`).
   - The transition table already admits `WaitingForSpecApproval → Executing`, `Executing → WaitingForReview`, and `Executing → Failed` (with the runner failure-category guard) — **no transition-table change is needed** (contrast 3a-1, which had to ADD `INBOX→INVESTIGATING` and `INVESTIGATING→FAILED`). See `WorkflowTransitionTable.java:61-75`.
   - **What is missing is the live EXECUTION originator.** `WorkflowOrchestrationService` only dispatches INVESTIGATION today (`dispatchSpecGeneration`/`retrySpecGeneration`); `RunnerBroker.onResult` only delegates a SUCCESS transition for `row.stage() == INVESTIGATION` (`RunnerBroker.java:1006-1012`). Production `EXECUTION` is reachable only via `RecoveryService` retry, never originated.

2. **`markAvailable` STILL has no production caller — this is the headline tension (OQ-1).** Epic AC2 says the plan payload is persisted via `LocalArtifactStore` then `markAvailable(...)` is called, and AC3 fires the `WaitingForReview` transition once the artifact is `available`. **But `ArtifactOperationService.markAvailable` (`:284`) has ZERO production callers system-wide** ([[markavailable-has-no-production-caller]]; confirmed by grep — only the method definition + the persistence adapter exist) — it needs checksum + storageRef plumbing that no runner-ingest path wires. **Story 3a-1 hit the identical wall and resolved it by firing `onSpecStageSucceeded` on successful artifact INGEST (`recordOperation`), NOT on a true `available` artifact, and flagging the gap for a dedicated follow-up.** 3.11 mirrors that precedent (see Decision D1): fire `onPlanStageSucceeded` on successful EXECUTION ingest; the implementation-plan artifact stays `pending`; the `Executing → WaitingForReview` transition fires anyway. Do **NOT** take on `markAvailable` checksum/storageRef plumbing in this story — it is large, cross-cuts the Epic-2/3 artifact-review surface, and is explicitly out of scope (same boundary 3a-1 / 3.10 drew).

3. **The trigger is spec-approval, not submit.** 3a-1 hooked `dispatchSpecGeneration` into the submit path; 3.11 hooks `dispatchPlanGeneration` into `ApprovalService.approveSpec` — **after** its existing `WaitingForSpecApproval → Executing` transition (`ApprovalService.java:235-239`). `workflowOrchestrationService` is **already a constructor dependency of `ApprovalService`** (`:77,91,116`) — `rejectSpec` already calls `workflowOrchestrationService.retrySpecGeneration(...)` at `:452` after its transition. 3.11 adds the symmetric `approveSpec → dispatchPlanGeneration(...)` call. No new injection.

**THE GATING DISCIPLINE (mirrors 3a-1):**

- Add a `plan-stage.auto-dispatch` master switch (twin of `spec-stage.auto-dispatch`): **ON** in production `application.yml`, **OFF** in the shared test `application.yml` — so the ~815-test fast tier stays deterministic and the full approve→plan→`WaitingForReview` loop is exercised only by the dedicated `@SpringBootTest` integration test that opts in via `@TestPropertySource`. When OFF, `approveSpec` behaves byte-identically to today (the plan dispatch is a no-op).
- The mock runner (`happy-implementation-plan` scenario, already configured at `RunnerProperties.Mock.defaults()` `RunnerStage.EXECUTION → "happy-implementation-plan"`) makes the entire approve→plan→`WaitingForReview` loop testable WITHOUT Docker, a real repo, or secrets. Real end-to-end (Docker + clone + agent keys) is a runtime/profile concern (`runners.docker`), not a code dependency.

**Scope boundary — do NOT build:**

- **Story 3.12 (pr-output dispatch on plan approval)** — its trigger is the developer's technical approval of the plan (story 3.20, deferred), so there is no production originator for the pr-output sub-stage yet. 3.11 wires the plan sub-stage only. (The broker's `deriveExecutionSubStage` already returns `IMPLEMENTATION_PLAN` when no approved plan exists, so the spec-approval dispatch correctly composes a plan bundle.)
- **`markAvailable` plumbing** (OQ-1 / Decision D1) — out of scope; fire on ingest.
- **Story 3.15 PR-linkage / `IntegrationLinkService.linkGitHubPr`** — that is the pr-output flow (3.12), deferred.
- **`RUNNER_ARTIFACT_TYPE_MISMATCH` three-sites** — already done by 3a-1; reuse.

## Acceptance Criteria

> Criteria are the epic's verbatim ACs (`epic-03-agent-execution.md` §"Story 3.11", lines 216–234) with **binding clarifications** added inline in **bold parentheticals** where the epic wording predates the live code or references not-yet-built upstream pieces. Note that 3.12 (PR/output) and 3.20/3.21 (technical approve/reject) are deferred, so the pr-output sub-stage and plan-rejection-loop retry are dormant — 3.11 delivers the plan-stage path + a directly-tested seam.

1. **Given** `WorkflowOrchestrationService.dispatchPlanGeneration(workflowRunId)` in `application.workflow`, **When** invoked from the `WaitingForSpecApproval → Executing` transition triggered by spec approval (story 2.9 / `ApprovalService.approveSpec`), **Then** it: (a) confirms the run is in `Executing` (the approval already transitioned it — **do NOT re-transition**, mirror `retrySpecGeneration`'s no-transition discipline, Trap T8), (b) builds a deterministic idempotency key (see Decision D2), (c) calls `RunnerBroker.dispatch(workflowRunId, RunnerStage.EXECUTION, idempotencyKey, systemActor(correlationId))` — which (already, story 3.10) resolves the repo, calls `RepositoryWorkspaceService.prepareWorkspace(...)` + `summarize`, derives `ExecutionSubStage.IMPLEMENTATION_PLAN`, calls `ContextBundleService.create(...)` with the sub-stage + repo summary + branch, records the pending `runner_executions` row, and dispatches the runner kind resolved by `kindForStage`/sub-stage (Decision D3, AC1d `deliveryline.runner.plan-stage.kind`). **(AC1's separate `RepositoryWorkspaceService` + `ContextBundleService` calls in the epic are ALREADY composed inside `RunnerBroker.dispatch` for EXECUTION per story 3.10 — orchestration calls the broker ONCE; it does NOT call those services directly, preserving the ArchUnit boundary.)**

2. **Given** the runner produces a valid `runner-result.v1.json` carrying an `artifactReferences[]` entry with `artifactType=implementationPlan`, **Then** the existing `RunnerBroker.handleSuccess(...)` path ingests it via `ArtifactOperationService.recordOperation(...)` + appends events (unchanged ingestion — **no new artifact code**). **(Epic wording "`markAvailable(...)` is called after the plan payload is persisted" is NOT yet satisfiable — `markAvailable` has no production caller system-wide; the implementation-plan artifact remains `pending`. Per Decision D1 the auto-advance fires on successful INGEST, exactly as story 3a-1 does for the spec artifact. This is the dormant-`markAvailable` carryover [[markavailable-has-no-production-caller]], NOT a regression introduced here.)**

3. **Given** the implementation-plan artifact is ingested for a run whose terminal runner execution was `RunnerStage.EXECUTION` (plan sub-stage), **Then** an automatic state transition fires: `WorkflowTransitionService.transition(workflowRunId, WorkflowState.WAITING_FOR_REVIEW, actor=system, reason="implementation_plan_ready", idempotencyKey, details)`. **This is the central new behavior** — `handleSuccess` ingests the EXECUTION artifact today but drives NO success transition (only INVESTIGATION is delegated, `RunnerBroker.java:1006`). The auto-advance MUST be owned by `WorkflowOrchestrationService` (new `onPlanStageSucceeded(...)`, the EXECUTION twin of `onSpecStageSucceeded`), delegated from the broker via the SAME lazy `Supplier<WorkflowOrchestrationService>` already in place — to satisfy AC9. **Idempotent:** a duplicate/late result whose run already reached (or progressed beyond) `WaitingForReview` surfaces `ILLEGAL_TRANSITION`, swallowed via the benign-replay-vs-anomaly classification pattern from `handleSpecReadyIllegalTransition` (mirror it; the "ready-or-beyond" set for the plan stage is `{WAITING_FOR_REVIEW, COMPLETED}` plus any post-review states reachable for the run).

4. **Given** runner failures (timeout, crash, contract violation, non-zero exit — story 3.1/3.2) OR repository-workspace failures (clone failed, push rejected — story 3.9 AC7) at the EXECUTION stage, **When** detected, **Then** the run transitions to `Failed` with the correct `failure_category` from the registry, preserving the failed `runner_executions` row + redacted logs (story 3.6) + workspace (story 3.1 retention). **(The broker ALREADY drives EXECUTION failures via `driveWorkflowFailed(...)` → `WorkflowTransitionService`, and the table already admits `Executing → Failed` with the runner failure-category guard. This story must CONFIRM plan-stage failures land at `Failed` — including the 3.9 git-failure categories surfaced through the broker's existing `GitCommandException`/`RuntimeException` reservation-FAILED handling — and reconcile the AC9 ownership tension exactly as 3a-1 did, OQ-2: keep the broker's failure-drive, scope the new ArchUnit rule to SUCCESS auto-advance.)**

5. **Given** retry, **Then** `WorkflowOrchestrationService.retryPlanGeneration(workflowRunId)` re-prepares the workspace (idempotent branch reuse, story 3.9 AC3 — handled inside `RunnerBroker.dispatch` for EXECUTION) and re-dispatches with a fresh `runnerExecutionId` + fresh context-bundle version (capturing any clarifications incorporated since the failure), preserving the prior failure event + `runner_executions` row for audit. **(The plan-rejection / technical-feedback loop driver (stories 3.20/3.21) is DEFERRED, so there is no `planRejectionLoopCount` field today (only `specRejectionLoopCount` exists on `WorkflowRunSnapshot`). For 3.11, `retryPlanGeneration` is reachable from the CLI/recovery `retry` baseline (story 1.18) only; the idempotency-key discriminator must therefore be derived from durable run state, not a not-yet-existing loop counter — see Decision D2. Like `retrySpecGeneration`, it re-dispatches ONLY and never re-transitions — the run is already `Executing` (or the recovery path transitioned it).)**

6. **Given** idempotency, **Then** repeated `dispatchPlanGeneration` calls for the same run while an EXECUTION-stage execution is already `pending`/`running` are no-ops returning the existing `runnerExecutionId` — never doubly-dispatch. **(Mirror `inFlightSpecDispatch` → a new `inFlightPlanDispatch` scanning `findByWorkflowRunPublicIdAndStatusIn(runId, ACTIVE_STATUSES)` for a `stage() == RunnerStage.EXECUTION` row, returning a `RunnerDispatchResult.Replayed`. The broker's key+fingerprint idempotency does NOT dedupe a same-key re-dispatch once the bundle version advances, so the explicit in-flight guard in orchestration is required — same finding as 3a-1 AC6/OQ-4.)**

7. **Given** correlation propagation (story 1.19), **Then** the `correlationId` originating from the spec-approval REST/CLI command flows through dispatch → runner events → artifact events → state transition. **(`RunnerBroker.handleSuccess` no longer mints a random correlationId — 3a-1's Trap T6 fixed that. 3.11 only needs to thread the approveSpec command's correlationId into `dispatchPlanGeneration(runId, correlationId)` via `systemActor(correlationId)`, identical to the spec path.)**

8. **Given** the artifact-variant discriminator (story 1.6 / party-mode finding #2), **Then** if the runner produces a result with `artifactType` other than `implementationPlan` (or `prOutput`) at the EXECUTION stage, the orchestration/broker rejects it with `RUNNER_ARTIFACT_TYPE_MISMATCH` and the run transitions to `Failed`. **(ALREADY implemented — `allowedArtifactTypesForStage(EXECUTION) = {IMPLEMENTATION_PLAN, PR_OUTPUT}` + `handleArtifactTypeMismatch` route to `Failed` via the contract-violation path, `RunnerBroker.java:1021-1069`. Epic AC8 is plan-specific (`implementationPlan`); since the plan-vs-pr distinction is the SEMANTIC `ExecutionSubStage` (not a wire stage), a `prOutput` emitted at the plan dispatch is NOT a wire-level type mismatch — confirm this is acceptable for 3.11 and defer strict sub-stage type-pinning to 3.12, OQ-3.)**

9. **Given** the ArchUnit boundary (story 1.5 / 3a-1 AC9), **Then** `WorkflowOrchestrationService` is the single path that auto-advances workflow state on a runner SUCCESS outcome. Extend the existing `ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS` rule (3a-1) so only `WorkflowOrchestrationService`/`RunnerBroker` may invoke `onPlanStageSucceeded` too (rename/generalize the rule to cover both success callbacks, or add a sibling rule). The pre-existing `ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE` rule stays green (orchestration routes through `WorkflowTransitionService`). The broker's failure-drive remains the documented exception (OQ-2, mirror 3a-1).

10. **Given** runner-image selection, **Then** the plan-stage runner kind is configurable via `application.yml` `deliveryline.runner.plan-stage.kind` (`codex` | `claude`), wired into the dispatch path. **(Requires a NEW config group on `RunnerProperties` (mirror the `SpecStage` record: `kind` + `autoDispatch`) and extending `kindForStage` / adding a sub-stage-aware resolver. CAUTION: `RunnerStage.EXECUTION` covers BOTH plan and pr-output sub-stages, and `kindForStage(RunnerStage)` currently returns `docker.defaultKind()` for EXECUTION — see Decision D3 / OQ-4 for resolving per-sub-stage kind without fanning a new `RunnerStage` value. The key MUST be added to BOTH `src/main/resources/application.yml` AND `src/test/resources/application.yml` — [[validated-config-needs-test-yaml]], Trap T4.)**

11. **Given** the test suite, **Then** integration tests cover: end-to-end happy path (approve spec → plan generated via mock `happy-implementation-plan` scenario → state at `WaitingForReview`), each runner failure mode → `Failed` with correct `failure_category`, a workspace/git-push failure → `Failed` with the git failure category, retry preserves prior events + creates a new `runnerExecutionId`, idempotent re-dispatch is a no-op, `RUNNER_ARTIFACT_TYPE_MISMATCH` on a type-mismatched output, and `correlationId` propagation across the full event chain. **(Mirror `SpecStageOrchestrationIT`; name the new test `*IT` so Failsafe runs it and the no-Docker Surefire fast tier excludes it — [[springboot-testcontainers-test-must-be-IT]]. Drive the async result deterministically — invoke `pollActiveExecutions()`/`onResult(...)` directly, do NOT sleep on the 5s poller, Trap T7. Plus a `WorkflowOrchestrationServiceTest` unit slice for `dispatchPlanGeneration` key shape / in-flight no-op / `onPlanStageSucceeded` transition + idempotent-replay classification.)**

**Logging instrumentation** (cross-cutting; see task below) — `INFO` on `dispatchPlanGeneration`/`retryPlanGeneration` entry + dispatch decision + the `implementation_plan_ready` transition; `WARN` on in-flight no-op / retry / benign-replay-or-anomaly classification; carry `correlationId`, `workflowRunId`, `runnerExecutionId`, `stage`, `subStage`; never log payloads/secrets/host paths.

## Tasks / Subtasks

- [x] **Task 1 — Plan-stage orchestration methods on `WorkflowOrchestrationService`** (AC: #1, #5, #6, #9)
  - [x] Add `dispatchPlanGeneration(String workflowRunId)` + `dispatchPlanGeneration(String workflowRunId, String correlationId)` (overload pair, mirror `dispatchSpecGeneration`). Gate on a new `planAutoDispatchEnabled()` (Task 4). Confirm the run is `Executing` (the approval already transitioned it) — **do NOT transition** (no `ensureInvestigating`-style hop; the EXECUTION dispatch presupposes `Executing`). Build the key (Decision D2), guard via `inFlightPlanDispatch` (AC6), then `runnerBroker.dispatch(workflowRunId, RunnerStage.EXECUTION, key, systemActor(correlationId))`.
  - [x] Add `retryPlanGeneration(String workflowRunId[, String correlationId])` — re-dispatch ONLY, never re-transition (T8); same in-flight guard; fresh key discriminator so the broker mints a new `runnerExecutionId` + bundle version.
  - [x] Add `inFlightPlanDispatch(String workflowRunId)` — twin of `inFlightSpecDispatch`, scanning `ACTIVE_STATUSES` for a `stage() == RunnerStage.EXECUTION` row → `RunnerDispatchResult.Replayed`.
  - [x] Reuse `requireRun`, `systemActor`, `systemTransitionActor`, `logDispatchOutcome`, the MDC scope helpers — all already present.

- [x] **Task 2 — Wire the EXECUTION success transition (the central gap)** (AC: #2, #3, #9)
  - [x] Add `onPlanStageSucceeded(String workflowRunId, String runnerExecutionId, String correlationId)` — EXECUTION twin of `onSpecStageSucceeded`: `transition(runId, WAITING_FOR_REVIEW, system, "implementation_plan_ready", "plan-ready:"+runnerExecutionId, Map.of("runnerExecutionId", rex))`. Swallow `ILLEGAL_TRANSITION` via a `handlePlanReadyIllegalTransition` modeled on `handleSpecReadyIllegalTransition` (benign-replay set = states at/beyond `WaitingForReview`; never rethrow — the call site shares the poller's per-item tx with `recordCompleted`).
  - [x] In `RunnerBroker.onResult` (the block at `:1000-1012`), add an EXECUTION branch alongside the INVESTIGATION one: when `row.stage() == RunnerStage.EXECUTION` (and the derived sub-stage is the plan sub-stage — see OQ-3) and ingestion succeeded + execution `COMPLETED`, call `orchestration.onPlanStageSucceeded(...)` via the SAME `workflowOrchestrationServiceSupplier.get()` lazy supplier (no new wiring; the cycle is already broken). Anchor it AFTER `captureAndPush` so a push failure routes to `Failed` instead of advancing to `WaitingForReview`.
  - [x] Confirm (assertion in test) that the transition fires on successful INGEST (Decision D1), since `markAvailable` stays unwired — document the carryover in a code comment referencing [[markavailable-has-no-production-caller]].

- [x] **Task 3 — Spec-approval trigger wiring** (AC: #1) — depends on the approveSpec transaction boundary
  - [x] In `ApprovalService.approveSpec(...)`, after the existing `WaitingForSpecApproval → Executing` transition (`:235-239`), call `workflowOrchestrationService.dispatchPlanGeneration(workflowRunId, correlationId)` — symmetric to `rejectSpec`'s `retrySpecGeneration` call at `:452`. `workflowOrchestrationService` is ALREADY injected (`:77,91,116`); no new dependency. Honor approveSpec's `@Transactional(propagation = MANDATORY)` (`:139`) — the dispatch runs in the outer `WorkflowCommandService.approveSpec` transaction; a dispatch failure rolls the approval back (all-or-nothing; confirm desired + test the rollback, Trap T3).
  - [x] Thread the originating `correlationId` (from the approveSpec command / MDC) into the dispatch (AC7).

- [x] **Task 4 — `plan-stage` configuration** (AC: #10) — **validated-config test-yaml trap**
  - [x] Add a `PlanStage` record to `RunnerProperties` mirroring `SpecStage` — `RunnerKind kind` (default `codex`) + `boolean autoDispatch` (Spring binds missing → `false`; `defaults()` → `true`). Add the field to the record header + compact ctor null-coalescing + `defaults()` (mind the two-arg `defaults()` + the all-args ctor used in `RunnerPropertiesTest`).
  - [x] Add `planAutoDispatchEnabled()` accessor used by Task 1's gate.
  - [x] Resolve the plan-stage kind: extend `kindForStage`/add a sub-stage-aware resolver so the EXECUTION+IMPLEMENTATION_PLAN dispatch honors `plan-stage.kind` (Decision D3 / OQ-4). Keep the broker's secret-scan + dispatch comparing against the SAME injected key (the 3.5 / 3a-1 coupling lesson).
  - [x] Add `deliveryline.runner.plan-stage.kind: codex` + `deliveryline.runner.plan-stage.auto-dispatch: true` to `src/main/resources/application.yml`, and `plan-stage.kind: codex` + `plan-stage.auto-dispatch: false` to `src/test/resources/application.yml` ([[validated-config-needs-test-yaml]] — test yaml shadows, not merges).

- [x] **Task 5 — ArchUnit boundary** (AC: #9)
  - [x] Generalize/extend the 3a-1 rule `ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS` to also cover `onPlanStageSucceeded` (only `WorkflowOrchestrationService`/`RunnerBroker` may invoke it). Update the `@ArchTest` registration in `ArchitectureBoundaryTest`. Keep the broker's failure-drive as the documented exception (OQ-2).

- [x] **Task 6 — Tests** (AC: #11)
  - [x] `ImplementationPlanOrchestrationIT` (`@SpringBootTest` + Testcontainers Postgres, profiles `test` + `linear-mock` + the plan auto-dispatch opt-in via `@TestPropertySource`, mock runner `happy-implementation-plan`): approve spec → plan generated → state at `WaitingForReview`; each failure mode → `Failed`; a git-push/workspace failure → `Failed` with git category; retry → new `runnerExecutionId` + preserved audit; idempotent re-dispatch no-op; `RUNNER_ARTIFACT_TYPE_MISMATCH`; correlationId propagation. Drive async via `pollActiveExecutions()`/`onResult(...)` directly (T7). Name `*IT` (T11).
  - [x] Extend `WorkflowOrchestrationServiceTest` (unit): `dispatchPlanGeneration` key shape + no-transition + EXECUTION in-flight no-op; `onPlanStageSucceeded` fires the `WAITING_FOR_REVIEW`/`implementation_plan_ready` transition; `handlePlanReadyIllegalTransition` benign-replay (INFO) vs anomaly (WARN) classification.
  - [x] Extend `RunnerBrokerUnitTest` for the EXECUTION success delegation branch (plan sub-stage → `onPlanStageSucceeded` invoked; non-plan / failure paths not).
  - [x] Extend `RunnerPropertiesTest` for the `PlanStage` binding + `kindForStage`/sub-stage resolver. Update any all-args `new RunnerProperties(...)` / `SpecStage`-arity test ctors for the new `PlanStage` field.
  - [x] Ensure the precondition holds: the run must be in `WaitingForSpecApproval` with an approval-eligible spec artifact for `approveSpec` to reach the `Executing` transition (the IT seeds this via the mock spec path or directly).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J + MDC at: `dispatchPlanGeneration`/`retryPlanGeneration` entry + dispatch outcome (reuse `logDispatchOutcome`); the `implementation_plan_ready` transition (INFO); the in-flight no-op (WARN); `handlePlanReadyIllegalTransition` benign-replay (INFO) vs anomaly (WARN); the broker EXECUTION success-delegation decision.
  - [x] Parameterized logging only (`log.info("...", a, b)`), never concatenation.
  - [x] Levels: `INFO` normal lifecycle (dispatch start/finish, plan-ready transition), `WARN` recoverable (in-flight replay, retry, benign-replay/anomaly, runner/git failure → Failed), `ERROR` only for unhandled failures. `DEBUG` hot-path detail.
  - [x] Context keys on every line: `correlationId`, `workflowRunId`, `runnerExecutionId`, `stage`, plus `subStage` where known. Use `MdcKeys.beginScope/endScope` consistent with the spec methods.
  - [x] NEVER log payload bytes, secrets/tokens, raw PII, host absolute paths, or plan/feedback bodies.
  - [x] Pin the new surfaces with at least one focused list-appender / `OutputCaptureExtension` assertion (dispatch, plan-ready transition, type-mismatch) — mirror `RunnerLoggingContractTest` / `WorkflowOrchestrationServiceTest` style.

### Review Findings

> Code review 2026-06-09 (3-layer adversarial: Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 11 ACs implemented; T1/T2/T3/T4/T11/D1/D2/D3/AC9 conformance verified. No must-fix bugs. 1 decision-needed, 0 patch, 5 deferred, 11 dismissed as noise/inherited-from-spec-stage-twin/verified-correct.

1. **`decision-needed`** (RESOLVED 2026-06-09 → dismissed):

- [x] [Review][Decision][Resolved: dismissed] approveSpec ↔ plan-dispatch transaction coupling — Story 3.11 adds `dispatchPlanGeneration(...)` inside `approveSpec`'s MANDATORY transaction (T3, all-or-nothing), so a dispatch-time failure rolls back the human spec approval (row + event + transition). **Alex confirmed atomicity is the intended contract (T3).** Narrow rollback window in 3.11 (dispatch is DB-only work; repo seam dormant, no synchronous git clone). Caveat for the repo-seam-live story: once a network git clone enters the dispatch path, re-evaluate whether a transient clone failure should discard a completed approval. [`ApprovalService.java:~252`]

2. **`patch`** (unchecked): none.

3. **`defer`** (checked, pre-existing/forward-looking):

- [x] [Review][Defer] In-flight plan guard does not distinguish EXECUTION sub-stages — `inFlightPlanDispatch` returns the first `stage()==EXECUTION` active row regardless of sub-stage; once story 3.12 adds pr-output dispatch, an in-flight pr-output execution would make a legitimate plan dispatch a silent no-op (and vice versa). Not triggerable in 3.11 (PR_OUTPUT path has no production originator). [`WorkflowOrchestrationService.java:~428`] — deferred to story 3.12.
- [x] [Review][Defer] `dispatchPlanInternal` has no `EXECUTING` precondition guard — unlike `dispatchSpecGeneration`'s `ensureInvestigating`, it reads the run only for logging then dispatches an EXECUTION runner unconditionally (T1 forbids transitioning). Safe today (only caller is `approveSpec`, which just transitioned to Executing; `retryPlanGeneration` has no production caller). [`WorkflowOrchestrationService.java:~266`] — deferred until `retryPlanGeneration` gets a recovery/CLI caller (OQ-5).
- [x] [Review][Defer] `PLAN_READY_OR_BEYOND` omits `EXECUTING` (asymmetric with `SPEC_READY_OR_BEYOND`) — minimal `{WaitingForReview, Completed}` set; a late/duplicate plan-ready replay arriving while the run is legitimately back in `EXECUTING` would log WARN-anomaly instead of INFO-replay. Currently correct (no path returns plan-stage to Executing until the 3.20 reject loop). [`WorkflowOrchestrationService.java:~194`] — revisit with story 3.20.
- [x] [Review][Defer] PR_OUTPUT sub-stage success is an absorbing state — the broker `else` branch ingests + completes the execution but drives no transition, leaving the run in `EXECUTING` with no auto-advance/failure/timeout. Documented 3.12 deferral; unreachable in 3.11's live scope (plan approval, story 3.20, is deferred so `deriveExecutionSubStage` always returns IMPLEMENTATION_PLAN in prod). [`RunnerBroker.java:~1031`] — resolved by story 3.12.
- [x] [Review][Defer] No plan-stage-specific failure / git-push-failure / correlationId IT — AC4/AC11 plan-stage failure→`Failed` and correlationId propagation are verified by reuse of pre-existing broker tests + the unit tier, not by a new plan-flavored test (spec-sanctioned scoping, mirrors `SpecStageOrchestrationIT`). [`ImplementationPlanOrchestrationIT.java`] — optional coverage enhancement.

#### Dismissed (verified, by-design, or false premise)

- Re-harvest-forever hazard from unguarded `deriveExecutionSubStage` / non-ILLEGAL_TRANSITION rethrow (Blind B10 / Edge E5) — **false premise**: `RunnerBroker` is non-`@Transactional`, `recordCompleted` commits independently, `pollActiveExecutions` has a per-item `catch (Exception)` (line 1922) and terminal rows leave `ACTIVE_STATUSES`; worst case strands the run in EXECUTING (logged, recoverable via story 1.18), same as the spec-stage twin.
- Push-failure routing claim unverifiable (Blind B11) — **verified correct**: EXECUTION delegation is anchored after `captureAndPush` returns at `RunnerBroker.java:969`.
- `kindForStage` exhaustive switch dropped its default (Blind B3) — intentional over a closed 2-value enum; a new stage should explicitly extend it.
- `onPlanStageSucceeded` ungated (Blind B4 / Auditor) — intentional mirror of `onSpecStageSucceeded`; caller restriction enforced by ArchUnit `ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_PLAN_RUNNER_SUCCESS` (AC9).
- Idempotency key per-`runnerExecutionId` + best-effort ILLEGAL_TRANSITION classification (Blind B5/B6) — inherited spec-stage pattern; dedupe carried by the transition table + benign-replay classification, documented.
- `PlanStage` primitive-boolean default footgun (Blind B8 / Edge E6) — mirrors existing `SpecStage.autoDispatch`; both `application.yml` set it explicitly; documented.
- correlationId `normalizeOptional` vs raw on the two legs (Blind B9) — different sources (approveSpec command vs runner row); not a real inconsistency.
- `retryPlanGeneration` gated on auto-dispatch + no production caller (Auditor A2) and narrow D2 replay window (A4) — acceptable per spec (directly-tested seam).

## Dev Notes

### THE references that matter most

| Concern | File to mirror | Why |
|---|---|---|
| **The direct sibling story** (spec-stage orchestration) | `3a-1-spec-stage-orchestration-dispatch-spec-generation.md` — its Tasks 1–7, Traps T1–T10, OQ-1…OQ-5 | 3.11 is the EXECUTION analog; copy its discipline almost verbatim, substituting EXECUTION/plan for INVESTIGATION/spec and `WaitingForReview` for `WaitingForSpecApproval`. |
| **The methods to twin** | `WorkflowOrchestrationService.dispatchSpecGeneration` / `retrySpecGeneration` / `onSpecStageSucceeded` / `handleSpecReadyIllegalTransition` / `inFlightSpecDispatch` (`WorkflowOrchestrationService.java:109-376`) | exact templates for `dispatchPlanGeneration` / `retryPlanGeneration` / `onPlanStageSucceeded` / `handlePlanReadyIllegalTransition` / `inFlightPlanDispatch`. |
| **The broker success-delegation hook** | `RunnerBroker.onResult` INVESTIGATION branch (`RunnerBroker.java:1000-1012`) + lazy `workflowOrchestrationServiceSupplier` | add the EXECUTION branch right beside it; the cycle break + supplier already exist. |
| **The trigger** | `ApprovalService.approveSpec` `→ Executing` transition (`:235-239`) + the symmetric `rejectSpec → retrySpecGeneration` call (`:452`) | add `dispatchPlanGeneration` after the `→ Executing` transition; orchestration is already injected. |
| **The EXECUTION dispatch composer (already built)** | `RunnerBroker.dispatch` EXECUTION repo-context block (`~:405-444`), `deriveExecutionSubStage`, `RepositoryWorkspaceService.prepareWorkspace`/`captureAndPush` (`~:861`), `ContextBundleService.create(...)` 10-arg (story 3.10) | orchestration calls `dispatch(...)` ONCE; all of this fires inside it for EXECUTION — no orchestration-side repo/bundle calls. |
| **Stage→type guard (already built)** | `RunnerBroker.allowedArtifactTypesForStage` + `handleArtifactTypeMismatch` (`:1021-1069`), `RUNNER_ARTIFACT_TYPE_MISMATCH` (3a-1 three-sites) | AC8 is done; reuse. |
| **Transition table (no change)** | `WorkflowTransitionTable.java:61-75` | `WaitingForSpecApproval→Executing`, `Executing→WaitingForReview`, `Executing→Failed` all present. |
| **Config twin** | `RunnerProperties.SpecStage` (`:253-262`) + `kindForStage` (`:112-118`) + `autoDispatchEnabled()` (`WorkflowOrchestrationService.java:104`) | template for `PlanStage` + the plan auto-dispatch switch. |
| **The test shape** | `SpecStageOrchestrationIT`, `WorkflowOrchestrationServiceTest`, `RunnerBrokerUnitTest`, `RunnerPropertiesTest` | mirror for the plan path. |

### Decisions (made by this story; rationale)

- **D1 — Fire `onPlanStageSucceeded` on successful artifact INGEST, NOT on a true `available` artifact.** `ArtifactOperationService.markAvailable` has no production caller anywhere ([[markavailable-has-no-production-caller]]); wiring it needs checksum/storageRef plumbing that cross-cuts the Epic-2/3 artifact-review surface — explicitly out of scope (same boundary 3a-1 and 3.10 drew). The implementation-plan artifact stays `pending`; the `Executing → WaitingForReview` auto-advance fires on ingest. This is a faithful mirror of 3a-1's spec path. **Flag for architect ratification + recommend a dedicated `markAvailable`-wiring follow-up story** (it blocks a true `available`-artifact developer-review e2e for both spec and plan).
- **D2 — Idempotency key derived from durable run state, not a not-yet-existing loop counter.** The spec path keys on `specRejectionLoopCount`; there is no `planRejectionLoopCount` (stories 3.20/3.21 deferred). Recommend `plan-dispatch:<workflowRunId>:<attempt>` where `attempt` = the count of prior EXECUTION `runner_executions` for the run (or the next `contextBundleVersion` for `(run, EXECUTION)` the broker already assigns via `nextContextBundleVersion`). Deterministic + replay-safe: the initial spec-approval dispatch is `attempt=0`; a CLI/recovery retry advances it so the broker mints a fresh `runnerExecutionId` + bundle version. Confirm the chosen counter source before coding (OQ-2 below).
- **D3 — Plan-stage kind resolution.** `RunnerStage.EXECUTION` is shared by plan + pr-output, but `kindForStage(RunnerStage)` cannot distinguish them. Recommend: add `plan-stage.kind` config + resolve the kind from the derived `ExecutionSubStage` at the dispatch site (the broker already derives it), with `pr-output` kind deferred to 3.12 (defaults to `plan-stage.kind` / `docker.defaultKind()` until then). Do NOT add a `RunnerStage` enum value for the sub-stages (it would fan out to parsers/timeout maps/SQL/contracts — the 3.10 D1 lesson). OQ-4.
- **D4 — No transition-table change, no new DomainErrorCode, no Flyway, no schema patch.** All required edges + the type-mismatch code + the EXECUTION bundle schema already exist (3a-1 / 3.10). 3.11 is pure orchestration wiring + config. (Confirm before adding any migration — max Flyway stays V11.)
- **D5 — Keep the broker as failure-drive owner (OQ-2 resolution carried from 3a-1).** Scope the new ArchUnit success-auto-advance rule to `onPlanStageSucceeded` too; document the broker's `driveWorkflowFailed` as the pre-existing exception. Don't refactor the failure path into orchestration in this story.

### Open Questions (each carries a recommendation — proceed unless the architect objects)

- **OQ-1 (headline) — `markAvailable` dormancy.** Epic AC2/AC3 presuppose an `available` plan artifact; `markAvailable` has no production caller. **Recommend D1** (fire on ingest, plan stays `pending`, flag a follow-up). Confirm the architect accepts the `pending`-artifact `WaitingForReview` semantics for the plan stage (it matches the spec stage's `pending` spec at `WaitingForSpecApproval`).
- **OQ-2 — Idempotency-key discriminator (D2).** `attempt` = prior-EXECUTION-execution count vs next `contextBundleVersion`. **Recommend** the `contextBundleVersion`-derived key since the broker already mints it per `(run, stage)` and it is monotonic + persisted — avoids a separate count query. Confirm the broker exposes/returns it pre-dispatch, else use the execution count.
- **OQ-3 — `prOutput` emitted at the plan dispatch.** A `prOutput` at the EXECUTION+plan dispatch is not a wire-stage type mismatch (both are EXECUTION types). **Recommend** accepting it for 3.11 (no strict sub-stage type-pinning) and adding sub-stage→type pinning in 3.12 when the pr-output flow exists. The mock plan scenario emits `implementationPlan`, so this is an edge only a misbehaving real runner hits.
- **OQ-4 — Per-sub-stage runner kind (D3).** Resolve plan kind from `ExecutionSubStage` at the dispatch site. **Recommend** `plan-stage.kind` now; `implementation-stage.kind` (pr-output) lands with 3.12. Until then EXECUTION+pr-output falls back to `plan-stage.kind`/`docker.defaultKind()`.
- **OQ-5 — Recovery-retry reach.** Should story-1.18 CLI/UI `retry` reach `retryPlanGeneration` for a `Failed` EXECUTION-stage run now? **Recommend** wiring the method + a thin recovery hook (mirror 3a-1 OQ-3's rejectSpec-only scope decision), leaving general `Failed`-run plan retry as a follow-up if `RecoveryService` doesn't already cover the `Executing`-re-entry case. Note `RecoveryService.java:418` can already re-dispatch a `failedStage` of EXECUTION (3.10 fact) — confirm it routes through `retryPlanGeneration` semantics.

### Traps (wiring hazards — each maps to a memory or a verified code fact)

- **T1 — Do NOT re-transition in `dispatchPlanGeneration`.** Unlike `dispatchSpecGeneration` (which ensures `Investigating`), the plan dispatch presupposes `Executing` (approveSpec already transitioned). An `Executing → Executing` hop would be illegal / a duplicate event. Mirror `retrySpecGeneration`'s no-transition discipline.
- **T2 — The broker↔orchestration cycle is already broken** via the lazy `Supplier<WorkflowOrchestrationService>` ([[broker-orchestration-lazy-supplier]]). Reuse `workflowOrchestrationServiceSupplier.get()`; do NOT add a new eager injection (it throws `BeanCurrentlyInCreation`).
- **T3 — `approveSpec` is `@Transactional(MANDATORY)`** (`ApprovalService.java:139`). `dispatchPlanGeneration` invoked from it runs in the outer `WorkflowCommandService.approveSpec` tx; a dispatch failure rolls back the approval + transition (all-or-nothing). Decide that's desired (recommended: yes) and test the rollback.
- **T4 — Validated-config shadowing** ([[validated-config-needs-test-yaml]]). `deliveryline.runner.plan-stage.*` must be in BOTH `src/main` and `src/test` `application.yml` or the whole `@SpringBootTest` tier fails at startup.
- **T5 — `RunnerProperties` is a record with multiple test ctors** ([[two-public-constructors-need-autowired]] / [[docker-adapter-ctor-dep-fans-out]] analog). Adding the `PlanStage` field changes the all-args ctor — update `defaults()`, the compact ctor, and every `new RunnerProperties(...)` test site (`RunnerPropertiesTest` + any others).
- **T6 — Don't sleep on the 5s poller in tests** (Trap from 3a-1 T7). Invoke `pollActiveExecutions()`/`onResult(...)` directly.
- **T7 — `*IT` naming for the `@SpringBootTest`+Testcontainers test** ([[springboot-testcontainers-test-must-be-IT]]) — a `*Test` name leaks into the no-Docker Surefire fast tier and reds CI.
- **T8 — Don't re-emit runner-lifecycle events from orchestration** (3a-1 T10). The broker owns `runner.*` events; orchestration owns only the `WORKFLOW_STATE_CHANGED` transition (appended by `WorkflowTransitionService` itself — don't double-append).
- **T9 — Run gates via PowerShell** ([[rtk-hook-only-matches-bash]]); verify Docker-backed tiers in a clean env / WSL2 ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]). The IT + foundation gate are Docker-backed.
- **T10 — Checkstyle line-anchored suppressions** ([[checkstyle-suppressions-line-anchored]]) — if edits to `WorkflowCommandService`/`ApprovalService` shift a suppressed `Thread.sleep`/forbidden-call line, re-anchor the `lines="N"` in `config/checkstyle/suppressions.xml`.
- **T11 — `plan-stage.auto-dispatch=false` in test yaml is load-bearing.** Without it the ~815-test fast tier becomes non-deterministic (every approveSpec would fire a real EXECUTION dispatch). The IT opts in via `@TestPropertySource`. Mirror the `spec-stage.auto-dispatch` convention exactly.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. ADR `0019-structured-logging` governs format.
- **Surface:** `WorkflowOrchestrationService.dispatchPlanGeneration`/`retryPlanGeneration`/`onPlanStageSucceeded` + the broker's EXECUTION success-delegation branch + the approveSpec dispatch trigger. `INFO` lifecycle (dispatch start/finish, `implementation_plan_ready` transition), `WARN` recoverable (in-flight replay, retry, benign-replay/anomaly, runner/git failure → Failed), `ERROR` only for unhandled failure. `DEBUG` hot-path.
- **Required context keys:** `correlationId`, `workflowRunId`, `runnerExecutionId`, `stage`, `subStage` — via MDC where the broker/orchestration already scope `WORKFLOW_RUN_ID`/`RUNNER_EXECUTION_ID`.
- **Forbidden:** payload bytes, secrets/tokens, raw PII, host absolute paths, plan/feedback/clarification bodies. Route uncertain content through the redaction path before logging.
- **Test contract:** new logging surfaces pinned by at least one focused test (list-appender or `OutputCaptureExtension`).

### Project Structure Notes

- Backend module is **`deliveryline-backend/`**. Base package `org.dradgo`. Java 21, Spring Boot 4.0.6.
- New orchestration methods → extend `org.dradgo.application.workflow.WorkflowOrchestrationService` (do NOT create a parallel service). Application code must not import `org.dradgo.adapters..` ([[application-cannot-import-adapters]]) — reach the runner subsystem only through the `RunnerBroker` application bean (already the pattern).
- Trigger edit → `org.dradgo.application.approval.ApprovalService.approveSpec`.
- Broker edit → `org.dradgo.application.runner.RunnerBroker.onResult` (EXECUTION success branch only).
- Config → `org.dradgo.application.runner.RunnerProperties` (`PlanStage` record + resolver) + BOTH `application.yml` files.
- **No DB migration** (no new columns; `runner_executions` + `artifacts` + `workflow_events` already model everything — max Flyway stays V11). **No new DomainErrorCode / no three-sites** (reuse `RUNNER_ARTIFACT_TYPE_MISMATCH`). **No transition-table change.** **No schema patch.** Confirm before adding any of these.

### Verification commands (PowerShell — memory [[rtk-hook-only-matches-bash]])

- Focused unit: `mvnw -pl deliveryline-backend test -Dtest=WorkflowOrchestrationServiceTest,RunnerBrokerUnitTest,RunnerPropertiesTest,RunnerLoggingContractTest`
- Plan-stage IT (Docker/Testcontainers): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=ImplementationPlanOrchestrationIT` (run via the `verify` lifecycle if the `@{argLine}`/jacoco issue bites — see 3.9/3a-1 note).
- Foundation gate (Docker up — ArchUnit boundaries incl. the AC9 rule, transition cross-product/failure-category contracts): `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false`.
- Static + full fast tier: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check` then `mvnw -pl deliveryline-backend test` (expect the no-dispatch baseline to hold with `plan-stage.auto-dispatch=false`).
- WSL2 Linux smoke of the Docker-backed IT + foundation gate ([[wsl-linux-ci-reproduction]] / [[verify-ci-fixes-in-clean-env]]).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.11] — ACs 1–11 (lines 216–234); FR14/FR15; the deferred-3b sequencing note (line 8); downstream 3.12 pr-output (line 236) + 3.17/3.20/3.22 dev-review (deferred).
- The direct sibling: [Source: _bmad-output/implementation-artifacts/3a-1-spec-stage-orchestration-dispatch-spec-generation.md] — `WorkflowOrchestrationService`, ADR 0004, the central-gap pattern, cycle break, auto-dispatch switch, in-flight guard, `RUNNER_ARTIFACT_TYPE_MISMATCH` three-sites, `markAvailable` follow-up flag.
- Bundle/repo seam (built dormant): [Source: _bmad-output/implementation-artifacts/3-10-full-context-bundle-generation-for-implementation-stage.md] — EXECUTION `create(...)` + broker EXECUTION repo-context gating + `deriveExecutionSubStage`; [Source: _bmad-output/implementation-artifacts/3-9-repository-workspace-service-git-clone-branch-management-commit-push.md] — `prepareWorkspace`/`captureAndPush`/deterministic branch + git failure categories.
- ADR: [Source: docs/adr/0004-spec-stage-orchestration.md] — §Decision-1 is 3a-1; 3.11 is its plan-stage analog (the ADR explicitly references "story 3.11's plan-stage orchestration").
- Code anchors (verified): `WorkflowOrchestrationService.java:104,109-160,163-211,225-327,361-376`; `RunnerBroker.java:405-444,861,1000-1012,1021-1069`; `ApprovalService.java:77,91,116,139-140,235-239,452`; `WorkflowTransitionTable.java:61-75,135-150`; `RunnerProperties.java:44,112-118,253-262`; `WorkflowRunSnapshot.java:22` (`specRejectionLoopCount` only — no plan equivalent); `ArtifactOperationService.java:284` (`markAvailable`, no production caller).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context)

### Debug Log References

- Initial `RunnerBrokerUnitTest` EXECUTION delegation tests failed at the contract-validation gate (`handleFailedValidation`) because they reused `specResultPayload(...)` (a `contentReference`-shaped spec result). The runner-result schema is artifact-type-specific: `implementationPlan` requires `steps` + `contextReferences`, `prOutput` requires `branch`/`commitSha`/`prReference`/`diffReference` (no `contentReference`). Fixed by adding dedicated `implementationPlanResultPayload()` / `prOutputResultPayload()` helpers mirroring the canonical valid fixtures.
- `ArchitectureBoundaryTest` is `@Tag("architecture")` and runs in a dedicated Failsafe execution (`**/architecture/**/*Test.java`), NOT the Surefire fast tier; `-Dtest=ZzzNone` / `-Dtest=ArchitectureBoundaryTest` both report "Tests run: 0". The new AC9 plan rule is verified via `failsafe:integration-test -Dit.test=**/architecture/**/*Test` (ArchitectureBoundaryTest 41 rules, 0 failures).

### Completion Notes List

Story 3.11 promotes the dormant `RunnerStage.EXECUTION` seam (built by 3.9/3.10/3a-2) into a live plan-stage flow — the EXECUTION analog of 3a-1. Pure orchestration wiring + config; **no** transition-table / DomainErrorCode / Flyway / schema change (Decision D4 held — max Flyway stays V11).

- **Task 1/2 — `WorkflowOrchestrationService`** (the spec methods' twins): `dispatchPlanGeneration` (+ correlationId overload), `retryPlanGeneration` (+ overload), shared `dispatchPlanInternal` (gated on `planAutoDispatchEnabled()`, **never transitions** — Trap T1), `inFlightPlanDispatch` (AC6), `onPlanStageSucceeded` (`Executing → WaitingForReview`, reason `implementation_plan_ready`), `handlePlanReadyIllegalTransition` (benign-replay INFO vs anomaly WARN, best-effort, never rethrows), `planDispatchKey` (`plan-dispatch:<run>:<nextExecutionBundleVersion>` — Decision D2/OQ-2, derived from `recordPort.nextContextBundleVersion(run, EXECUTION)`). `PLAN_READY_OR_BEYOND = {WAITING_FOR_REVIEW, COMPLETED}`.
- **Task 2 broker hook** — `RunnerBroker.onResult` gains an `else if (row.stage() == EXECUTION)` branch beside the INVESTIGATION one, anchored AFTER `captureAndPush` (a push failure routes to Failed, not WaitingForReview). It derives the sub-stage via `contextBundleService.deriveExecutionSubStage(...)` and delegates `onPlanStageSucceeded` ONLY for `IMPLEMENTATION_PLAN` (pr-output deferred to 3.12, OQ-3); reuses the existing lazy `Supplier<WorkflowOrchestrationService>` (no new wiring, cycle already broken — Trap T2). Decision D1: fires on successful INGEST; the plan artifact stays `pending` (`markAvailable` unwired — [[markavailable-has-no-production-caller]], OQ-1).
- **Task 3 — trigger** — `ApprovalService.approveSpec` calls `dispatchPlanGeneration(runId, correlationId)` AFTER the `WaitingForSpecApproval → Executing` transition (symmetric to `rejectSpec → retrySpecGeneration`), inside the existing `MANDATORY` tx (all-or-nothing rollback, Trap T3). No new injection. No-op when `plan-stage.auto-dispatch=false`.
- **Task 4 — config** — `RunnerProperties.PlanStage` record (`kind` + `autoDispatch`, twin of `SpecStage`); `planAutoDispatchEnabled()`; `kindForStage(EXECUTION)` now returns `planStage.kind()` (Decision D3/OQ-4 — also serves pr-output until 3.12; keeps the broker secret-scan + dispatch comparing against the SAME key). `plan-stage.kind/auto-dispatch` added to BOTH `application.yml` files (true in main, **false** in test — Trap T4/T11). Updated `defaults()` + compact ctor + all 9 `new RunnerProperties(...)` call sites.
- **Task 5 — ArchUnit** — sibling rule `ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_PLAN_RUNNER_SUCCESS` (only `WorkflowOrchestrationService`/`RunnerBroker` may call `onPlanStageSucceeded`), registered in `ArchitectureBoundaryTest`. Broker failure-drive remains the documented OQ-2 exception (D5).
- **Task 6 — tests** — `ImplementationPlanOrchestrationIT` (`@SpringBootTest` + Testcontainers, `plan-stage.auto-dispatch=true` via `@TestPropertySource`, mock `happy-implementation-plan`): seeds an approval-eligible spec artifact directly (mock spec stays `pending` — markAvailable unwired), approve→plan→`WaitingForReview` happy path, in-flight idempotent no-op, retry fresh execution. Failure-mode/mismatch/correlationId covered deterministically by the unit tier (mirrors `SpecStageOrchestrationIT`'s scoping). Extended `WorkflowOrchestrationServiceTest` (+6 plan tests), `RunnerBrokerUnitTest` (+2 EXECUTION delegation), `RunnerPropertiesTest` (PlanStage binding + kindForStage). Drive async via `pollActiveExecutions()` (Trap T6); named `*IT` (Trap T7).
- **Logging** — INFO on dispatch entry/outcome + the `implementation_plan_ready` transition; WARN on in-flight no-op / benign-replay-or-anomaly classification; MDC `workflowRunId`/`runnerExecutionId`; never logs payloads/secrets/host paths. Pinned via ListAppender assertions in `WorkflowOrchestrationServiceTest`.

**Verification (PowerShell — [[rtk-hook-only-matches-bash]]):** focused unit 78/0; full fast Surefire 835/0/11skip (no-dispatch baseline byte-identical with `plan-stage.auto-dispatch=false`); spotless:apply (5 files) + checkstyle:check 0 violations; `-Pfoundation-gate verify` 30/0/1skip; ArchUnit Failsafe 47/0 (ArchitectureBoundaryTest 41 incl. the new AC9 plan rule); `ImplementationPlanOrchestrationIT` 3/0 (real Docker/Testcontainers Postgres). Recommend code-review with a different LLM + WSL2/Linux clean-env confirm of the Docker tiers ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

**Carryover flagged (OQ-1/D1):** `markAvailable` still has no production caller — the auto-advance fires on ingest and the plan artifact stays `pending` for BOTH spec and plan stages. Recommend a dedicated `markAvailable`-wiring follow-up story (blocks a true `available`-artifact developer-review e2e).

### File List

**Production (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java` — plan-stage methods (Tasks 1/2)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` — EXECUTION success-delegation branch (Task 2)
- `deliveryline-backend/src/main/java/org/dradgo/application/approval/ApprovalService.java` — dispatchPlanGeneration trigger (Task 3)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java` — PlanStage record + planAutoDispatchEnabled() + kindForStage(EXECUTION) (Task 4)
- `deliveryline-backend/src/main/resources/application.yml` — plan-stage config (Task 4)

**Tests / config:**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/ImplementationPlanOrchestrationIT.java` — NEW (Task 6)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowOrchestrationServiceTest.java` — plan-stage unit tests + service() PlanStage arg
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java` — EXECUTION delegation tests + ctor arg
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerPropertiesTest.java` — PlanStage binding + kindForStage coverage + ctor args
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` — AC9 plan rule (Task 5)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` — register AC9 plan rule (Task 5)
- `deliveryline-backend/src/test/resources/application.yml` — plan-stage config (Trap T4)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLogCaptureServiceTest.java` — ctor arg
- `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java` — ctor arg
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java` — ctor arg
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/DockerLifecycleITSupport.java` — ctor arg
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java` — ctor arg

## Change Log

| Date | Change |
|---|---|
| 2026-06-09 | bmad-dev-story: implemented all 11 ACs + Logging. Plan-stage orchestration (`dispatchPlanGeneration`/`retryPlanGeneration`/`onPlanStageSucceeded` + in-flight guard + benign-replay classification) as the EXECUTION twin of 3a-1; `ApprovalService.approveSpec` trigger; `PlanStage` config + `kindForStage(EXECUTION)`; AC9 ArchUnit plan rule; `ImplementationPlanOrchestrationIT` + unit coverage. No transition-table/DomainErrorCode/Flyway/schema change. Status `ready-for-dev → in-progress → review`. |
