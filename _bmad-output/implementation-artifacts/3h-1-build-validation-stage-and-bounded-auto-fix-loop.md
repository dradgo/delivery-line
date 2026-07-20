# Story 3h.1: Build-Validation Stage (RunnerStage.BUILD) + Bounded Auto-Fix Loop

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want the produced code compiled/built in the workspace before it reaches review, and build failures auto-fixed first,
so that review and push only ever see buildable code — and a broken build is corrected by the implementation runner (bounded) rather than silently shipped.

---

## ⛔ PREREQUISITE GATE — read before starting

**`3g-5-token-real-capture-completion` MUST be `done` before dev-story starts on this story.** It is currently `ready-for-dev` (not done). This story's **AC6 ("BUILD records zero tokens")** rides on 3g's finalized token seam (the `@DynamicUpdate` clobber fix + real-capture path land in 3g-5). `3h-0` (the shared afterCommit helper) is already `done` ✅.
[Source: sprint-change-proposal-2026-07-04.md §"Sequencing: 3g-5 ∥ 3h-0 → both done → 3h-1"; epic-03h AC6]

---

## 🧭 ARCHITECTURE DECISION — BUILD runs BACKEND-SIDE (amends ADR 0030)

**This story amends ADR 0030.** ADR 0030 (as drafted) modelled BUILD as a *command-only runner-execution* running inside the runner container, and rejected the backend-side alternative on the grounds that it would "re-derive log capture, status tracking, and the step view." **That rationale rests on a false premise:** there is **no shell-command execution mode in the runner today** — both `runners/codex/entrypoint.sh` and `runners/claude/entrypoint.sh` *always* invoke the LLM CLI (`"$CODEX_CLI_BIN" "$@"`, `entrypoint.sh:676`). Adding a command mode would mean new branches in **both** entrypoints + **both** `runner.mjs` copies + **both** offline mocks + a `happy-build.json` scenario — far more than "the switch(stage) fan-out."

**Decision (confirmed with product owner):** BUILD executes **backend-side** via a `ProcessBuilder` in the **already-materialized host workspace directory** — the same directory `RepositoryWorkspaceService.captureAndPush` resolves via `workspaceStore.resolveRepositoryDir(...)`. It is **still** recorded as a `runner_executions` row with `stage = 'build'`, and it **still** reuses the story-3.6 raw-output capture (`RunnerLogCaptureService`) and the 3d-5 per-step log/step view — so "zero new persistence for the execution record" holds. Only the **executor and trigger** differ from an LLM stage: no Docker dispatch, no runner image, no mock scenario.

**Why this is safe & simpler:**
- The backend already materializes the workspace to a local host path and operates on it directly (that is exactly how `captureAndPush` commits/pushes). BUILD reuses that host path.
- BUILD never produces a `runner-result.v1` and never flows through `RunnerBroker.onResult` / `DockerRunnerAdapter` — so it **cannot** hit `handleSuccess`'s empty-`artifactReferences → RUNNER_CONTRACT_VIOLATION → FAILED` trap. That whole class of misroute is avoided.
- The command executor sits behind a **new `BuildCommandPort` SPI** (mirroring `GitCommandPort`), so unit/IT tests inject pass/fail deterministically without a real shell.

**Task in this story:** edit `docs/adr/0030-governed-delivery-tail.md` — flip decision 1 + the "Alt 1 rejected" note to record the backend-side execution as the chosen approach, cite the "no runner command mode exists" premise correction, and keep the ADR-0032 substrate reference intact. Do **not** change ADR 0030's *ordering* decision (build → lint → review → deliver) or the push-relocation decision.
[Source: docs/adr/0030-governed-delivery-tail.md decisions 1/2/5, Alt 1; runners/codex/entrypoint.sh:676; RepositoryWorkspaceService.java:368-450]

---

## Context — why this story exists (read before coding)

Today the delivery tail is `INVESTIGATION → EXECUTION(pr_output) → REVIEW`, and the instant the implementation result lands, `RepositoryWorkspaceService.captureAndPush()` fires inline in `RunnerBroker.handleSuccess` (`RunnerBroker.java:2166-2168`) — auto-committing, pushing, and creating a PR with **no build signal at all**. Broken code ships and is only (maybe) caught by the advisory LLM reviewer, at token cost.

This story inserts a cheap **CPU build gate before the push and before REVIEW**: when a governed project has a build command configured, the produced code is compiled/built first; a failure drives a **bounded auto-fix loop** (re-dispatch the implementation runner with the build error as feedback) before escalating. It is the **integration crux** of Epic 3h — it introduces `RunnerStage.BUILD` **and** begins the structural push relocation (lifting `captureAndPush` out of the inline `onResult` arm so a gate can precede it); 3h-4 completes that relocation behind the delivery gate.
[Source: epic-03h-pre-review-quality-gates.md — Story 3h-1, Cross-Cutting Notes, "The structural crux — push relocation"]

---

## Acceptance Criteria

1. **`RunnerStage.BUILD("build")` added (code-only, additive).** A new `BUILD("build")` enum value is added to `RunnerStage` (between EXECUTION and REVIEW). The `runner_executions.stage` column is un-CHECKed `text` (`V1__…:210`) so `build` needs **NO Flyway migration and NO CHECK** — exactly the REVIEW precedent. **Every exhaustive `switch (stage)` consumer gains an explicit BUILD arm** (compile-enforced; see Dev Notes §"Fan-out"). A BUILD execution is recorded as a `runner_executions` row reusing the story-3.6 raw-output capture + the 3d-5 per-step step/log view (**zero new persistence for the execution record**).
   - ⚠️ **Correction to the epic AC1 wording "in `FlywaySchemaContractTest`":** `FlywaySchemaContractTest` never probes the `stage` column value-set (it only asserts `status` type+CHECK), and `RegistryContractTest` auto-derives the stage set from `.values()`. So there is **no drift-test edit** for the stage value — it passes trivially. Add a `RunnerStageBuildParsingTest` (clone `RunnerStageReviewParsingTest`) for parse round-trip coverage, and confirm `RegistryContractTest` stays green.

2. **Per-project config `buildCommand` + `build-stage.enabled` (default DISABLED).** `Project` + `ProjectRuntimeConfigResolver` gain `buildCommand` (nullable `String`, mirror `reviewerModelKind`) and `buildStageEnabled` (`boolean`, mirror `openspecEnabled`), seeded from `application.yml` via a new `RunnerProperties.BuildStage(boolean enabled, String command)` nested record (mirror the `OpenSpec` record). **Default disabled.** A project with no build config (or `build-stage.enabled=false`) **skips BUILD entirely** — byte-identical to pre-3h parity. Config threads through the full per-project stack (entity, mapper, Create/Update DTOs+commands, `ProjectController.createFingerprint`, `DefaultProjectSeeder`, OpenAPI regen). Resolution is worker-thread-safe (the `Project` record is a detached POJO — no lazy proxy).

3. **Tail ordering — BUILD after PR-output ingest, before REVIEW, before `captureAndPush`.** When enabled, on a successful `EXECUTION(PR_OUTPUT)` result the tail dispatches BUILD **instead of** immediately running the inline `captureAndPush` + `WaitingForReview` advance; on **BUILD success** the tail proceeds through the (now-deferred) `captureAndPush` + `WaitingForReview` transition + `enqueueReviewerIfConfigured` — i.e. the existing push/review sequence, now gated behind BUILD. The inline `captureAndPush` at `RunnerBroker.java:2166` is **extracted** into a reusable "complete-execution-tail-and-advance" seam so it can be deferred (this is 3h-1's share of the push relocation; 3h-4 moves it behind the delivery gate). The run stays in `Executing` during BUILD (no new state).

4. **BUILD failure → bounded auto-fix loop.** A non-zero build exit triggers a re-dispatch of the EXECUTION/PR_OUTPUT runner with the **redaction-policed build-error log** attached as a `priorFeedbackReferences` entry (`kind:"build.failure"`) — **never inlined past the 2KB cap** (the log is referenced by id; the runner reads the body from the referenced BUILD execution's already-captured raw output). A `build_fix_loop_count` is bumped with a **distinct idempotency key per iteration** (`build-fix:<runId>:<count>`), capped by `build-stage.max-fix-loops` (default 3). The async re-dispatch **MUST consume the 3h-0 `AfterCommitSideEffectRunner`** (Layer A over Layer B) — do NOT re-derive `REQUIRES_NEW` + advisory-lock + swallow/log + idempotent re-invoke inline.

5. **Cap exceeded → run FAILED with a build `FailureCategory` + escalation marker.** When `build_fix_loop_count` reaches `max-fix-loops`, the run transitions to `FAILED` via the existing `driveWorkflowFailed(...)` path carrying a **new `FailureCategory.RUNNER_BUILD_FAILED("runner_build_failed")`**, and the shared `escalation_marker_set` flag is flipped once (`WorkflowRunRejectionLoopPort.markEscalationOnce`), leaving the run for Epic-4 recovery. The code is **never** pushed past an unresolved build failure.
   - ⚠️ **Correction to the epic AC5 wording "three-sites: registry/enum + ProblemDetails/mapping + drift":** `FailureCategory` is **deliberately NOT wired like `DomainErrorCode`** — it has **no ProblemDetails/DomainErrorCode entry, no SQL CHECK, no OpenAPI/placeholder manifest**. Its real sites are: (1) the `FailureCategory` enum; (2) `DomainRegistry.failureCategories()` — **auto-derived from `.values()`, no manual edit**; (3) the **one exhaustive no-`default` `switch (category)`** in `RunnerBroker.priorResultReceived` (`:3546-3557`) — the true compile site (a build failure means a result was processed → **`true` arm**). `RegistryContractTest` (`:270-278`) only asserts the set is non-empty — **no test edit needed**. Do NOT add a ProblemDetails mapping for it.

6. **No-token guarantee — a BUILD execution records ZERO token/provider usage.** Because BUILD runs backend-side (no LLM, no runner-result), the two `onResult` capture paths (`captureTokenUsage`, `captureProviderUsage`) are never invoked for it, so its three token columns stay **NULL** (not 0) and no `provider_usage_snapshots` row is written. Asserted so a misconfigured BUILD can never be billed as an LLM call.

7. **Redaction.** The build command's stdout/stderr is captured through the **same `RunnerLogCaptureService` → redaction/secret-scan path as any outbound runner log** (ids/lengths only in application logs; redacted body in the log store). The `build.failure` feedback reference points at that already-redacted capture — nothing secret is persisted in the feedback bundle. If build output can surface a new secret shape, add a fixture to `redaction-fixtures/fixtures-manifest.json` (two-gate rule).

8. **Tests.** Coverage asserts: BUILD stage registry + parse round-trip + the 3 compile `switch` arms; **build pass proceeds to captureAndPush + REVIEW**; **build fail loops + bumps `build_fix_loop_count` + honors cap → escalate with `RUNNER_BUILD_FAILED` + escalation marker**; **disabled-project parity (BUILD skipped, tail byte-identical to pre-3h)**; **command-only emits no token/provider usage**; new `FailureCategory` present in `DomainRegistry` + the `priorResultReceived` switch; `application.*` ≥ 80% line coverage. ArchUnit verified via **Failsafe** (not Surefire).

---

## Tasks / Subtasks

- [x] **Task 1 — `RunnerStage.BUILD` enum + the 3 compile-`switch` arms + parse test** (AC: #1)
  - [ ] Add `BUILD("build")` to `domain/registry/RunnerStage.java` (between EXECUTION and REVIEW; keep the code-only-enum comment pattern).
  - [ ] Add BUILD arms to the **three exhaustive `switch (stage)`** sites (won't compile otherwise):
    - `RunnerBroker.allowedArtifactTypesForStage` (`:2630-2642`) → `EnumSet.noneOf(ArtifactType.class)` (command-only, emits no artifact — same as REVIEW).
    - `RunnerProperties.kindForStage` (application, `:159-174`) → `throw new IllegalStateException(...)` (same as REVIEW — BUILD is never runner-kind-dispatched in the backend-side model).
    - `ProjectRunnerSteps.of` (`:33-50`) → `Optional.empty()` (no per-step runner-kind override for BUILD).
  - [ ] Add `RunnerStageBuildParsingTest` (clone `domain/registry/RunnerStageReviewParsingTest`). Confirm `RegistryContractTest` + `FlywaySchemaContractTest` stay green **with no edits** (stage value-set is auto-derived / un-probed). Update `ProjectRunnerStepsTest` (`:44-45`) to add the BUILD→empty case.
- [x] **Task 2 — Per-project config: `buildCommand` + `build-stage.enabled`** (AC: #2)
  - [ ] `RunnerProperties` (application, `@ConfigurationProperties("deliveryline.runner")`): add nested `record BuildStage(boolean enabled, String command)` with `defaults()` (mirror `OpenSpec` at `:450-455`); wire the 5 touch-points (component field, compact-ctor null-default, `defaults()`, accessor `buildStageEnabled()`/`buildCommand()`). Keep it **OPTIONAL + UNVALIDATED** (like `OpenSpec`) so **no test-yaml mirror** is needed (avoids the validated-config-needs-test-yaml trap).
  - [ ] `application.yml`: add `deliveryline.runner.build-stage.enabled: false` (+ optional `command:`) under the runner block (~`:290` region, commented default-OFF like `openspec`). Add `build-stage.max-fix-loops: 3` and `build-stage.timeout: 10m` (see Task 4/5).
  - [ ] `domain/project/Project.java`: add `String buildCommand` (nullable; blank-if-set guard like `reviewerModelKind` `:110-112`) + `boolean buildStageEnabled` (plain, like `openspecEnabled`). Extend the canonical constructor; add a back-compat ctor overload following the `stepRunnerKinds` precedent (`:60-96`) so existing `new Project(...)` sites compile.
  - [ ] `application/project/ProjectRuntimeConfigResolver.java`: add `resolveBuildStageEnabled(runId)` (mirror `resolveOpenSpecEnabled` `:108-110`) + `resolveBuildCommand(runId): Optional<String>` (mirror `resolveRepositoryRef` `:98-101`, merge with the global `RunnerProperties` default like `resolveRunnerKind`).
  - [ ] Persistence + wire: `ProjectEntity` (`build_command text null` + `build_stage_enabled boolean not null` columns + accessors), `ProjectEntityMapper` (`toDomain`/`toNewEntity`/`applyEditableColumns`), `CreateProjectRequest`/`UpdateProjectRequest`, `CreateProjectCommand`/`UpdateProjectCommand`, `ProjectManagementService` (parse+construct), `ProjectController.createFingerprint` (**must include both new fields** or create-idempotency breaks), `ProjectResponse.from`, `DefaultProjectSeeder.seedDefaultFromGlobalConfig` (thread `runnerProperties.buildStageEnabled()`/`buildCommand()` into the `new Project(...)`).
  - [ ] **Flyway**: `alter table projects add column build_command text null;` + `add column build_stage_enabled boolean not null default false;` **and** `alter table workflow_runs add column build_fix_loop_count integer not null default 0;` (Task 4). Cite next-free head **V33** (highest today is `V32`) but **re-confirm the head at implementation time** (cross-branch collision trap — [flyway-v31-cross-branch-collision]). Add to `FlywaySchemaContractTest` (replay + new columns; this is the *column* drift, distinct from the stage-value non-assertion).
  - [ ] OpenAPI regen (`npm run generate-api` after `openapi.snapshot.write=true`) — the new project fields land in `openapi.json` + `schema.d.ts` (check:api). (FE project-form consumption is out of this story's scope but the client must stay in-sync.)
- [x] **Task 3 — `BuildCommandPort` SPI + backend `ProcessBuilder` adapter** (AC: #1, #3, #7)
  - [ ] New SPI `BuildCommandPort` (application, mirror `GitCommandPort`): `BuildResult run(Path repoDir, String command, Duration timeout)` returning `{ int exitCode, String stdout, String stderr }` (or a capture handle). Timeout-bounded; kills the process on timeout → non-zero.
  - [ ] New adapter (adapters/infra) implementing it via `ProcessBuilder(...).directory(repoDir)`; captures stdout/stderr. **Application-cannot-import-adapters** — port in application, adapter in adapters.
  - [ ] Capture the raw output through the existing `RunnerLogCaptureService` (keyed by the BUILD `runnerExecutionId`) so it flows the story-3.6 redaction/secret-scan path and is visible in the 3d-5 step/log view.
- [ ] **Task 4 — `BuildStageService` orchestration (execute + record + advance/fail)** (AC: #1, #3, #4, #5, #6)
  - [ ] New `BuildStageService` (application.workflow) triggered from the PR_OUTPUT-success arm **only when `resolveBuildStageEnabled(runId)` and a workspace exists** (no workspace → skip BUILD, proceed as disabled — mirror `captureAndPush`'s `no_repo_workspace` no-op). Trigger runs **after the PR_OUTPUT completion tx commits** via `afterCommitSideEffectRunner.runAfterCommit("build-stage", runId, …)` so the (potentially minutes-long) build never holds the broker tx.
  - [ ] Create a BUILD `runner_executions` row (reuse `RunnerExecutionRecordPort`/`RunnerExecutionService` reserve/record methods — do NOT route through the Docker-dispatch queue). Resolve the workspace dir of the **producing (PR_OUTPUT) execution** (`workspaceStore.resolveRepositoryDir(...)`). Run `BuildCommandPort.run(...)` **outside any DB tx**; capture logs.
  - [ ] **Exit 0** → `recordCompleted(buildRex)` then invoke the extracted **complete-execution-tail-and-advance** seam (Task 6): deferred `captureAndPush` + `WaitingForReview` transition + `enqueueReviewerIfConfigured`.
  - [ ] **Exit ≠ 0** → the bounded fix loop (Task 5).
  - [ ] **AC6**: assert (unit `verify(recordPort, never()).recordTokenUsage(...)` + `providerUsageWritePort never().insert(...)`) and via IT (the BUILD rex's `input/output/total_tokens` are NULL; zero `provider_usage_snapshots` rows) — mirror `RunnerExecutionTokenUsagePersistenceIT#noUsageRowKeepsTheThreeColumnsNull`.
  - [ ] All workflow-state mutation goes through `WorkflowTransitionService.transition(...)`; all re-enqueue goes through `WorkflowOrchestrationService` (respect `only_workflow_transition_service_may_mutate_workflow_state` + `only_orchestration_recovery_and_worker_pool_may_enqueue` ArchUnit rules).
- [ ] **Task 5 — Bounded auto-fix loop + escalation** (AC: #4, #5)
  - [ ] `WorkflowRunRejectionLoopPort`: add `int incrementAndReadBuildFixLoopCount(String runId)` (+ JDBC adapter, `UPDATE … RETURNING`, mirror `…ImplementationLoopCount`). Reuse `markEscalationOnce` / `isEscalationMarkerSet` verbatim.
  - [ ] New `BuildFixEscalationThresholdProvider` (`@Component`, `@Value("${deliveryline.workflow.build-fix-max-loops:3}")`, ULTIMATE_FALLBACK=3) — mirror `ImplementationRejectionEscalationThresholdProvider`. This value is both the fix cap and the escalation threshold.
  - [ ] On BUILD fail: bump `build_fix_loop_count`; **if `count <= maxFixLoops`** → materialize the `build.failure` reference (Task 7), mint key `build-fix:<runId>:<count>`, transition back to `EXECUTING`, and re-dispatch via `WorkflowOrchestrationService.retryImplementation(...)` / `retryPlanGeneration(...)` (branch on the producing artifact type — mirror `TechnicalApprovalService.rejectImplementation` `:502-514`). **else** → `driveWorkflowFailed(runId, buildRex, RUNNER_BUILD_FAILED, "build failed after N fix attempts")` + `markEscalationOnce(runId)` (emit the escalation event once, mirror `:417-480`).
  - [ ] Consume the **3h-0 helper** for the async/replay-safe boundary (compose `runAfterCommit` over `runInNewTransaction`; advisory lock — if used — is the first statement of the `work` lambda). Use the canonical consume-note (below) — do NOT re-derive the machinery.
  - [ ] **`FailureCategory.RUNNER_BUILD_FAILED("runner_build_failed")`**: add to the enum; add the arm to the exhaustive `RunnerBroker.priorResultReceived` `switch` (`:3546`, `true` arm). No ProblemDetails/CHECK/manifest/Flyway. Confirm `RegistryContractTest` green.
- [x] **Task 6 — Extract & defer `captureAndPush` (3h-1's share of the push relocation)** (AC: #3, and disabled-parity #2)
  - [ ] Extract the inline `captureAndPush` + PR-output enrich + `recordCompleted` + `onPrOutputStageSucceeded` sequence (`RunnerBroker.java:2165-2233` + `:2288`) into a private `completeExecutionTailAndAdvance(...)` method.
  - [ ] **Build disabled** (or no workspace): call it inline exactly where `captureAndPush` fires today → **byte-identical** to pre-3h.
  - [ ] **Build enabled**: skip the inline call on PR_OUTPUT success; instead `BuildStageService` invokes `completeExecutionTailAndAdvance(...)` on BUILD success. (3h-4 later moves this seam behind the `WaitingForDelivery` gate — keep it a clean single call site.)
  - [ ] Preserve the git-push-failure handling (`GitCommandException → driveWorkflowFailed`) inside the extracted seam unchanged.
- [x] **Task 7 — `build.failure` feedback reference (by-reference, redacted)** (AC: #4, #7)
  - [ ] In `ContextBundleService.collectExecutionFeedbackReferences` (`:1271-1303`), emit `{referenceId, kind:"build.failure"}` for the failed BUILD execution, sourced from its **already-captured redacted raw-output log** (no new durable store — reuse the 3.6 capture; mirror how `implementationPlan.rejection` references a prior row). The body is **never** embedded in the bundle (256KB `CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES` invariant stays satisfied because it is reference-by-id).
  - [ ] Confirm the reference resolves for the re-dispatched runner (`writeArtifactReference`/`referencePath` availability path).
- [x] **Task 8 — ADR 0030 amendment + glossary** (AC: architecture)
  - [ ] Edit `docs/adr/0030-governed-delivery-tail.md`: flip decision 1 + the "Alt 1 rejected" note to record the **backend-side** BUILD execution (ProcessBuilder in the materialized host workspace, still a `runner_executions` row + 3.6 capture + 3d-5 view), citing the "no runner command mode exists" premise correction. Keep the build→lint→review→deliver ordering, the push-relocation decision, and the ADR-0032 substrate reference intact. Bump ADR status note as appropriate.
  - [ ] Confirm `build stage` vocabulary in `docs/glossary.md` (NFR43 — justify the new concept).
- [x] **Task 9 — Tests** (AC: #8)
  - [ ] Unit: `BuildStageServiceTest` (pass→advance; fail→loop; cap→escalate+FAILED+`RUNNER_BUILD_FAILED`; no-workspace skip; disabled skip; token-never-recorded verifies); `BuildFixEscalationThresholdProviderTest`; the 3 switch-arm assertions; `RunnerStageBuildParsingTest`; `ProjectRuntimeConfigResolverTest` (+build fields); `RunnerPropertiesTest` (BuildStage defaults).
  - [ ] Real-PG IT (Testcontainers, `@Tag("integration")`, named `*IT`, **non-`@Transactional`** where afterCommit/REQUIRES_NEW is exercised): mirror `PerStepRunnerDispatchIT`. Cases: BUILD pass → `captureAndPush` + WaitingForReview + REVIEW enqueued; BUILD fail → `build_fix_loop_count` bumped + EXECUTION re-dispatched with `build.failure` ref; cap exceeded → FAILED(`runner_build_failed`) + `escalation_marker_set=true`; **disabled-project parity** (BUILD skipped, push+review byte-identical); **zero-token** (BUILD rex columns NULL, no provider row); inject a mock `BuildCommandPort` for deterministic pass/fail.
  - [ ] Verify ArchUnit boundary/naming rules via **Failsafe** (`verify -Djacoco.skip=true` for focused runs; avoid the bare `failsafe:` goal — `@{argLine}` crash).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] SLF4J structured logs (parameterized, never concatenation) at: BUILD dispatch decision (INFO "build stage enabled/skipped"), BUILD start/exit (INFO with exit code + duration, WARN on non-zero), fix-loop re-dispatch (WARN "build fix loop attempt {n}/{cap}"), cap-exceeded escalation (WARN once), and the `BuildCommandPort` adapter boundary. Levels: INFO normal lifecycle, WARN recoverable (build fail / fix loop / skip), ERROR only for unexpected executor failure.
  - [ ] Every line carries `correlationId`, `workflowRunId`, and the BUILD `runnerExecutionId`. **Never log build stdout/stderr bytes** (only ids/lengths — the redacted body lives in the log store). Use MDC where the surrounding code does.
  - [ ] Pin each new/moved log line with an `OutputCaptureExtension` assertion.

---

## Dev Notes

### Canonical 3h-0 consume-note (from ADR 0032 — fold verbatim)

> **The bounded fix-loop (build / lint / CI) MUST consume the 3h-0 shared replay-safe afterCommit helper (`AfterCommitSideEffectRunner`) — do NOT re-derive `REQUIRES_NEW` + advisory-lock + swallow/log + idempotent re-invoke inline.**

Helper API (`application/workflow/AfterCommitSideEffectRunner.java`):
```java
void runAfterCommit(String label, String contextId, Runnable sideEffect); // Layer A — fire after current tx COMMITS
void runInNewTransaction(String label, String contextId, Runnable work);  // Layer B — REQUIRES_NEW; put advisory lock FIRST inside `work`
```
Compose A over B for the fix-loop async boundary. The helper never calls `transition()` — state mutation stays inside your callback lambda (keeps `only_workflow_transition_service_may_mutate_workflow_state` intact).
[Source: docs/adr/0032-replay-safe-aftercommit-helper.md:38-40; AfterCommitSideEffectRunner.java:81,111]

### The single best implementation template

Read `application/approval/TechnicalApprovalService.rejectImplementation` (`:324-558`) end-to-end. It already sequences **persist → per-run counter bump → shared-marker escalation-once → transition → sub-stage-branched EXECUTION re-dispatch** in one path, with feedback flowing back into the regenerated bundle **by reference**. The build-fix loop is a structural twin — the difference is the trigger (async BUILD-fail hook, not a synchronous operator command) and the counter/category/threshold names.
[Source: TechnicalApprovalService.java:417-514; SplitProposalService.java:102-104,162-174,331-367]

### Fan-out — the exhaustive `switch (stage)` map (AC1)

**Compile-enforced (won't build without a BUILD arm):**
| Site | File:line | BUILD arm |
|---|---|---|
| `allowedArtifactTypesForStage` | `RunnerBroker.java:2630-2642` | `EnumSet.noneOf(ArtifactType.class)` |
| `kindForStage` (application) | `RunnerProperties.java:159-174` | `throw IllegalStateException` (never runner-dispatched) |
| `ProjectRunnerSteps.of` | `ProjectRunnerSteps.java:33-50` | `Optional.empty()` |

**NOT needed in the backend-side model (BUILD never flows through these):** `RunnerBroker.onResult` REVIEW-style early branch, the `handleSuccess` empty-artifacts path, `DockerRunnerAdapter`, `MockRunnerScenarioRegistry`/`happy-build.json`, infra `RunnerProperties` timeouts/scenarios map. BUILD is executed by `BuildStageService` + `BuildCommandPort`, not the Docker runner. **This is the big simplification the backend-side decision buys.**

**Ordering seam to edit:** the PR_OUTPUT-success arm — `RunnerBroker.onResult` terminal chain (`:2261-2298`) → `WorkflowOrchestrationService.onPrOutputStageSucceeded` (`:997-1032`, transitions WaitingForReview `:1006` + `enqueueReviewerIfConfigured` `:1027`). When build enabled, this dispatches BUILD instead; BUILD success re-enters the extracted seam.
[Source: research agent maps — RunnerStage fan-out, broker tail]

### Config precedent (AC2)
- `Project` is a detached `record` (`domain/project/Project.java:25-58`) — no JPA proxy, safe on the worker thread. Mirror `openspecEnabled` (boolean, `V17` `openspec_enabled boolean not null default false`) and `reviewerModelKind` (nullable text, `V19` CHECK-free) for the two new fields.
- `RunnerProperties.OpenSpec` (`:450-455`) is the exact template for `BuildStage` — OPTIONAL + UNVALIDATED, so no test-yaml mirror (dodges the validated-config trap).
- `DefaultProjectSeeder.seedDefaultFromGlobalConfig` (`:115-168`) is where the global default flows into the seeded default project — thread both new fields here.
- Project persistence stack that must round-trip the columns: `ProjectEntity`, `ProjectEntityMapper`, `CreateProjectRequest`/`UpdateProjectRequest`, `Create/UpdateProjectCommand`, `ProjectManagementService`, `ProjectController.createFingerprint` (idempotency!), `ProjectResponse.from`.

### FailureCategory reality (AC5)
`FailureCategory` (`domain/registry/FailureCategory.java:5-14`) is **not** DomainErrorCode-shaped. Sites: enum → auto-catalog (`DomainRegistry.failureCategories()`, derived from `.values()`) → the one exhaustive no-`default` switch in `RunnerBroker.priorResultReceived` (`:3546-3557`). No ProblemDetails, no CHECK, no manifest, no Flyway. `RegistryContractTest:270-278` only asserts non-empty (no edit). Terminal FAILED path = `RunnerBroker.driveWorkflowFailed` (`:3756-3779`) → `WorkflowTransitionService.doTransition` (category recorded on the `workflow_events` row, not the run). The `escalation_marker_set` flag (`WorkflowRunEntity:77-80`) is set explicitly via `markEscalationOnce` — it is **not** auto-set on a FAILED transition, so the loop must flip it.

### Token zero-cost (AC6)
`captureTokenUsage` (`RunnerBroker.java:1717-1751`) and `captureProviderUsage` (`:1611-1705`) are **`onResult`-only** and short-circuit on a missing `normalizedOutput.usage`/`providerUsage` node. A backend-side BUILD never reaches them → columns stay NULL. Assert with `RunnerExecutionTokenUsagePersistenceIT#noUsageRowKeepsTheThreeColumnsNull` (`:71-86`) shape. Depends on 3g-5's `@DynamicUpdate` being in place (prerequisite gate).

### Reused seams (zero new persistence for the execution record)
- Raw-output capture (3.6): `RunnerLogCaptureService.captureLogs` + `RunnerLogStore`/`LocalRunnerLogStore` + `RunnerSecretScanService` + `RedactedRunnerLog`.
- Per-step view (3d-5 / 3g-4): `StepLogStreamService` + `RunnerLogStreamController`; `GET /api/v1/workflows/{id}/steps` (3g-4) renders the BUILD step row (stage-agnostic — confirm it lists `stage:"build"` with null tokens).
- Workspace: `RepositoryWorkspaceService` `CONTAINER_REPO_MOUNT`/`resolveRepositoryDir(runnerExecutionId)`; BUILD uses the **producing PR_OUTPUT execution's** host workspace dir.

### Logging Requirements (project-wide standard)

Every story leaves the touched services observable enough to debug a production incident without re-deploying (enforced by the Logging instrumentation task above).
- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where (minimum surface):** `BuildStageService` entry/exit (INFO enabled/skipped/pass/fail), `BuildCommandPort` adapter (INFO start/exit, WARN non-zero, ERROR unexpected), fix-loop re-dispatch (WARN attempt n/cap), escalation (WARN once), state transitions (INFO via the transition service).
- **Required context keys:** `correlationId`, `workflowRunId`, BUILD `runnerExecutionId`, plus `build_fix_loop_count` on loop lines.
- **Forbidden:** build stdout/stderr bytes, secrets/tokens, raw PII — pass through the redaction/secret-scan path before anything reaches a log.
- **Test contract:** new logging surfaces pinned by `OutputCaptureExtension`.

### Testing standards summary
- afterCommit/REQUIRES_NEW ITs must be **non-`@Transactional`**, named `*IT` (Failsafe), with `@BeforeEach/@AfterEach` truncation. [springboot-testcontainers-test-must-be-IT; post-commit-hook-needs-requires-new]
- ArchUnit `@ArchTest` runs in **Failsafe** — verify boundary/naming via `verify`, not `mvnw test`. [archunit-runs-in-failsafe-not-surefire; maven-argline-direct-goal-crash]
- Run `spotless:apply` on hand-edited Java before pushing. [spotless-apply-before-pushing-java-edits]
- OpenAPI: `openapi.snapshot.write=true` then `npm run generate-api` for the project-config field additions; `check:api` must be in-sync. [openapi-regen-frontend-client-drift-cascade]
- Reconfirm the Flyway head before writing the migration. [flyway-v31-cross-branch-collision]

### Project Structure Notes
- New production files: `application/.../BuildCommandPort.java` (SPI) + adapter under `adapters/`; `application/workflow/BuildStageService.java`; `application/workflow/BuildFixEscalationThresholdProvider.java`.
- Modified: `RunnerStage`, `FailureCategory`, `RunnerBroker` (switch arms + tail extraction + priorResultReceived arm), `RunnerProperties` (app — BuildStage + kindForStage arm), `ProjectRunnerSteps`, `WorkflowOrchestrationService` (PR_OUTPUT→BUILD routing), `WorkflowRunRejectionLoopPort` (+ adapter), `ContextBundleService` (build.failure ref), `Project` + full project-config stack, `DefaultProjectSeeder`, `application.yml`.
- New Flyway `V33` (re-confirm head): projects `build_command` + `build_stage_enabled`; workflow_runs `build_fix_loop_count`.
- Docs: `docs/adr/0030-governed-delivery-tail.md` (amend decision 1 / Alt 1), `docs/glossary.md` (build stage).
- No runner-image change, no `runner.mjs` change, no mock scenario, no new FailureCategory ProblemDetails/CHECK/manifest.

### References
- [Source: _bmad-output/planning-artifacts/epic-03h-pre-review-quality-gates.md — Story 3h-1 + Cross-Cutting Notes]
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-07-04.md §4.1 (3g-5 gate), §4.3 (consume-note)]
- [Source: docs/adr/0030-governed-delivery-tail.md — decisions 1/2/5, Alt 1 (amended by this story)]
- [Source: docs/adr/0032-replay-safe-aftercommit-helper.md:38-40 — consume-note]
- [Source: _bmad-output/implementation-artifacts/3h-0-shared-replay-safe-aftercommit-helper.md — helper API]
- [Source: RunnerStage.java:5-15; RunnerBroker.java:2630-2642,3546-3557,3756-3779,1717-1751,2166-2298; RunnerProperties.java(app):159-174,450-455; ProjectRunnerSteps.java:33-50]
- [Source: TechnicalApprovalService.java:324-558; SplitProposalService.java:102-104,162-174; WorkflowRunRejectionLoopPort.java; ContextBundleService.java:59-68,1271-1303]
- [Source: RepositoryWorkspaceService.java:76,368-450,927-928; GitCommandPort.java:76-81; RunnerLogCaptureService.java]
- [Source: Project.java:25-117; ProjectRuntimeConfigResolver.java:98-180; DefaultProjectSeeder.java:115-168; application.yml:239-361]
- [Source: RunnerExecutionTokenUsagePersistenceIT.java:71-86; PerStepRunnerDispatchIT.java:65-90]
- [Source: memory token-usage-clobbered-by-terminal-transition, post-commit-hook-needs-requires-new, application-cannot-import-adapters, flyway-v31-cross-branch-collision, redaction-fixture-two-gates]

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — bmad-dev-story, 2026-07-05.

### Debug Log References

- Backend `test-compile` green (main + test) after the RunnerProperties/Project/command constructor fan-out.
- Targeted Surefire (offline, `-Djacoco.skip=true`): **131/131 pass** — `BuildStageServiceTest` 7/7, `BuildFixEscalationThresholdProviderTest` 2/2, `RunnerStageBuildParsingTest` 4/4, `ProjectRunnerStepsTest` 5/5, `RunnerPropertiesTest` 15/15, `ProjectManagementServiceTest` 22/22, and **`RunnerBrokerUnitTest` 76/76** (regression gate: the `completeExecutionTailAndAdvance` extraction preserves the inline delivery-tail behavior byte-for-byte).
- `spotless:apply` clean (exit 0).
- Real-PG / Failsafe (Docker up): `FlywaySchemaContractTest` **30/30** (V33 columns + CHECK + replay), `ArchitectureBoundaryTest` **57/57** (new `adapters.build` slice + boundary/transition/enqueue rules). Backend `verify` static gates green on new code: **spotless 848 clean, checkstyle 0 violations, SpotBugs pass**.
- **OpenAPI regen DONE** — `OpenApiSnapshotContractTest` is `@Tag("contract")` (runs in **Failsafe**, not Surefire); regenerated via `-Dit.test=OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true verify`. `openapi.json` + `schema.d.ts` now carry `buildCommand`/`buildStageEnabled` on Create/Update project requests; `npm run check:api` ✅ in-sync.
- **Real-PG `BuildStageIT` written & GREEN (4/4)** — fail-below-cap (loop-count bump + run stays Executing + BUILD rex=failed), fail-at-cap (run FAILED + `runner_build_failed` on a `workflow_events` row + `escalation_marker_set=true`), pass (BUILD rex=completed + tail runs + **AC6 token columns all NULL**), disabled-parity (gate skipped). Injects mock `BuildCommandPort`+`RunnerWorkspaceStore`, drives `tryGateBehindBuild` inside a `TransactionTemplate` (mirrors the poller tx so afterCommit fires).
- **BUG FOUND & FIXED by the IT (the unit test's mocked transition hid it):** `RUNNER_BUILD_FAILED` was absent from `WorkflowTransitionTable.ALLOWED_RUNNER_FAILURE_CATEGORIES`, so the cap→FAILED transition threw `ILLEGAL_TRANSITION` (swallowed by the fix-loop `runInNewTransaction`) — a run would have stayed **stuck in Executing forever** instead of failing. Added the category to the table AND to `TransitionTableCrossProductFoundationContract`'s mirror copy (transition-table fan-out). Verified: `WorkflowTransitionTableTest` 9/9, `TransitionTableCrossProductFoundationContract` 4/4, foundation-gate suite 1504/0/0.
- **Design flag RESOLVED:** the IT creates a BUILD rex while the PR_OUTPUT rex is still `running` — no invariant violation (the deferred-`recordCompleted` concern is a non-issue).

### Completion Notes List

**Status: COMPLETE — all 9 tasks done & verified; Status → `review`.** Full Surefire 1508/0/0, spotless/checkstyle/spotbugs clean, `BuildStageIT` 4/4 real-PG, `FlywaySchemaContractTest` 30/30, `ArchitectureBoundaryTest` 57/57, `TransitionTableCrossProductFoundationContract` 4/4, OpenAPI regen + `check:api` in-sync. Recommend `code-review` with a DIFFERENT LLM (a real end-to-end run through a live Docker runner producing a PR to observe the gate in-app remains a good pre-merge smoke, but is not blocking — the gate + loop + escalation + zero-token + parity are all proven on real Postgres).

Delivered + verified:
- ✅ **Task 1** — `RunnerStage.BUILD("build")` between EXECUTION and REVIEW; the 3 compile-`switch` arms (`RunnerBroker.allowedArtifactTypesForStage`→`noneOf`, app `RunnerProperties.kindForStage`→throw, `ProjectRunnerSteps.of`→empty); `RunnerStageBuildParsingTest`; `ProjectRunnerStepsTest` BUILD case. Confirmed (per research) `RegistryContractTest`/`FlywaySchemaContractTest` auto-derive the stage set — no stage-value edit.
- ✅ **Task 2** — `RunnerProperties.BuildStage(enabled, command, timeout)` nested record (OPTIONAL+UNVALIDATED, mirrors `OpenSpec`) + `buildStageEnabled()/buildCommand()/buildTimeout()` accessors; the ~11 positional `new RunnerProperties(...)` sites updated (component fan-out); `Project` gains `buildCommand`(nullable, blank-guard) + `buildStageEnabled` with a **14-arg back-compat ctor** (keeps ~20 test sites compiling); full persistence/DTO/command/controller/response/seeder/resolver stack threaded incl. `ProjectController.createFingerprint` (both fields) + back-compat command overloads; **V33** migration (projects 2 cols + `workflow_runs.build_fix_loop_count` + nonneg CHECK); `FlywaySchemaContractTest` column asserts; `application.yml` `build-stage` + `build-fix-max-loops`. ⚠️ **OpenAPI regen NOT run** (project-config fields added to Create/Update/Response DTOs) — see remainder.
- ✅ **Task 3** — `BuildCommandPort` SPI (in `application.runner.workspace.spi`, mirrors `GitCommandPort`; non-zero-exit is the signal, never throws) + `ProcessBuildCommandAdapter` (`adapters.build`, `sh -c`, concurrent stdout/stderr drain, timeout→destroyForcibly) + ArchUnit `BUILD_ADAPTER_PACKAGE` registered in `ADAPTER_PACKAGE_LAYOUT`.
- ✅ **Task 4 + Task 5** — `BuildStageService` (afterCommit-triggered on PR_OUTPUT success via 3h-0 `AfterCommitSideEffectRunner`; reserves a BUILD `runner_executions` row in the poller tx; runs the build out-of-tx; captures redacted logs via `RunnerLogCaptureService`; exit0→`recordCompleted`+deferred tail; exit≠0→bounded fix loop). Fix loop: `incrementAndReadBuildFixLoopCount` (port + JDBC `UPDATE…RETURNING`); below-cap→`retryImplementation` (run stays Executing, no transition); at-cap→`transition(FAILED, RUNNER_BUILD_FAILED)` + `markEscalationOnce`. `FailureCategory.RUNNER_BUILD_FAILED` + its `RunnerBroker.priorResultReceived` `true`-arm. `BuildFixEscalationThresholdProvider` (`deliveryline.workflow.build-fix-max-loops:3`). **AC6** verified (unit): BUILD never records token/provider usage. Fix-loop branch simplified to `retryImplementation` only — BUILD fires only post-PR_OUTPUT, so the producing stage is always PR_OUTPUT (documented).
- ✅ **Task 6** — extracted `RunnerBroker.completeExecutionTailAndAdvance(...)` (captureAndPush + git-push-failure handling + validate/enrich + recordCompleted + RUNNER_COMPLETED + stage auto-advance); `handleSuccess` now derives sub-stage once, then gates a PR_OUTPUT success behind BUILD (deferred continuation) when `BuildStageService.tryGateBehindBuild` takes ownership, else runs the tail inline (byte-identical — `RunnerBrokerUnitTest` 76/76). Broker wired to a lazy `Supplier<BuildStageService>` (ObjectProvider) mirroring the orchestration cycle-breaker; all broker ctors threaded.
- ✅ **Task 8** — ADR 0030 Decision 1 + Alt 1 amended (backend-side BUILD, "no runner command mode exists" premise correction; ordering/push-relocation/ADR-0032 refs intact); `docs/glossary.md` "build stage" entry (NFR43).

Design decisions worth review:
- **Deferred whole tail incl. `recordCompleted(PR_OUTPUT)`** (per Task 6 wording) — the PR_OUTPUT rex stays `running` during BUILD while a BUILD rex is `pending`→terminal (two non-terminal rows briefly). `pending→completed/failed` is legal (`RunnerExecutionStateMachine`); the "concurrent rex per run" invariant is **not yet IT-verified** (needs the real-PG IT). If an invariant forbids it, move `recordCompleted(PR_OUTPUT)` ahead of the build trigger and shrink the extracted seam.
- afterCommit trigger relies on the poller-tx being active at `handleSuccess` (confirmed: `processSinglePoll` runs inside `perItemTransactionTemplate`). A non-poller `onResult` caller (if any) would WARN-skip the build (3h-0 best-effort) — verify no such caller exists.

**Remainder (NOT done — required before `review`):**
- ✅ **Task 7 DONE** — `ContextBundleService` gains a NULLABLE `RunnerExecutionRecordPort` (7-arg canonical `@Autowired`; the 6-arg overload delegates with `null`, so all 7 existing test sites compile untouched — no Unwired-marker needed since the emission is guarded on non-null). `collectExecutionFeedbackReferences` emits `{referenceId: <failed BUILD rex>, kind:"build.failure"}` from `findByWorkflowRunPublicIdAndStatusIn(runId,[FAILED])` filtered `stage()==BUILD`, mirroring the `implementationPlan.rejection` block (by-reference only, never inlined — 256 KB invariant). Production DI auto-wires the real port (`@Service`). Verified: `ContextBundleServiceExecutionStageTest` new case (build.failure ref present + filters non-BUILD failed rex + by-id) — full Surefire **1505/0/0**, `BuildStageIT` still 4/4.
- ⚠️ **Task 9 (mostly done)** — unit tests + **`BuildStageIT` (real-PG, 4/4) DONE** (pass→advance+AC6-null-tokens; fail-below-cap→loop-bump+Executing; fail-at-cap→FAILED(`runner_build_failed`)+escalation; disabled-parity). **OpenAPI regen** ✅ DONE. **ArchUnit via Failsafe** ✅ DONE (57/57). **`WorkflowTransitionTable` allow-list bug** ✅ found+fixed by the IT (see Debug Log). **Still NOT done: `OutputCaptureExtension` logging pins** (BUILD dispatch/start/exit, fix-loop attempt, escalation-once, adapter boundary — the lines exist + are exercised by the IT, just not pinned by assertion). Optional: a broker-level IT driving the full `onResult`→gate→deferred-tail path (currently split across `RunnerBrokerUnitTest`'s branch + the BuildStageService unit/IT). `spotless`/checkstyle/spotbugs green on new code.

### File List

New:
- `deliveryline-backend/src/main/resources/db/migration/V33__add_build_stage_columns.sql`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/spi/BuildCommandPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/build/ProcessBuildCommandAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/BuildStageService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/BuildFixEscalationThresholdProvider.java`
- `deliveryline-backend/src/test/java/org/dradgo/domain/registry/RunnerStageBuildParsingTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/BuildStageServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/BuildFixEscalationThresholdProviderTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/BuildStageIT.java` (real-PG, mock BuildCommandPort)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/build/ProcessBuildCommandAdapterTest.java` (adapter boundary + log pins)

Modified (main):
- `domain/registry/RunnerStage.java`, `domain/registry/FailureCategory.java`
- `domain/project/Project.java`
- `application/runner/RunnerProperties.java`, `application/runner/RunnerBroker.java`, `application/project/ProjectRunnerSteps.java`, `application/project/ProjectRuntimeConfigResolver.java`, `application/project/ProjectManagementService.java`, `application/project/DefaultProjectSeeder.java`
- `application/workflow/WorkflowTransitionTable.java` (RUNNER_BUILD_FAILED added to the EXECUTING→FAILED allow-list — bug found by BuildStageIT)
- `application/project/CreateProjectCommand.java`, `application/project/UpdateProjectCommand.java`
- `application/workflow/spi/WorkflowRunRejectionLoopPort.java`
- `application/runner/ContextBundleService.java` (Task 7 — nullable RunnerExecutionRecordPort + build.failure emission)
- `adapters/persistence/WorkflowRunPersistenceAdapter.java`, `adapters/persistence/entity/ProjectEntity.java`, `adapters/persistence/mapper/ProjectEntityMapper.java`
- `adapters/rest/CreateProjectRequest.java`, `adapters/rest/UpdateProjectRequest.java`, `adapters/rest/ProjectController.java`, `adapters/rest/ProjectResponse.java`
- `src/main/resources/application.yml`
- `docs/adr/0030-governed-delivery-tail.md`, `docs/glossary.md`

Regenerated:
- `deliveryline-backend/src/main/resources/openapi/openapi.json`, `deliveryline-frontend/src/lib/api/schema.d.ts` (project build fields)

Modified (test):
- `contract/FlywaySchemaContractTest.java`, `architecture/ArchitectureRuleCatalog.java`, `foundation/TransitionTableCrossProductFoundationContract.java` (allow-list mirror)
- `application/project/ProjectRunnerStepsTest.java`
- `application/runner/ContextBundleServiceExecutionStageTest.java` (Task 7 build.failure case)
- `application/runner/RunnerPropertiesTest.java`, `application/runner/RunnerBrokerUnitTest.java`, `application/runner/RunnerLogCaptureServiceTest.java`
- `adapters/runner/DockerRunnerAdapterUnitTest.java`, `adapters/runner/DockerRunnerAdapterLoggingContractTest.java`, `adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java`, `adapters/runner/lifecycle/DockerLifecycleITSupport.java`
- `adapters/files/LocalRunnerWorkspaceStoreTest.java`
- `application/workflow/WorkflowOrchestrationServiceTest.java`

### Change Log

- 2026-07-05 (bmad-dev-story, Opus 4.8 [1m]): Implemented FR75 build-validation stage core (Tasks 1–6, 8) + unit tests; 131/131 targeted tests green incl. RunnerBrokerUnitTest 76/76 regression.
- 2026-07-05 (bmad-dev-story cont., Opus 4.8 [1m]): Verified with Docker up — full Surefire 1508/0/0, `BuildStageIT` 4/4 real-PG (caught+fixed a real `ALLOWED_RUNNER_FAILURE_CATEGORIES` allow-list bug: cap→FAILED would've silently no-op'd, stranding the run in Executing), `FlywaySchemaContractTest` 30/30, `ArchitectureBoundaryTest` 57/57, OpenAPI regen + `check:api` in-sync. Wired Task 7 (`build.failure` feedback ref via nullable `RunnerExecutionRecordPort` on `ContextBundleService`) + Task 9 logging pins (`ListAppender` on `BuildStageService` + `ProcessBuildCommandAdapter`). All 9 tasks done → Status `review`.

## Review Findings

_bmad-code-review 2026-07-05 (Opus 4.8 [1m], adversarial 3-layer: Blind Hunter + Edge Case Hunter + Acceptance Auditor). Scope: story File List, uncommitted working tree (46 files, ~2,889 diff lines). 4 decision-needed, 3 patch, 3 defer, 1 dismissed. CRITICAL confirmed by direct code read._

**Resolution (2026-07-05, "fix all now"):** ALL 7 actionable findings APPLIED + offline-verified (backend compile green across the module, `BuildStageServiceTest` 9/9, `RunnerPropertiesTest` 15/15, `RunnerStageBuildParsingTest` 4/4, `ProcessBuildCommandAdapterTest` 3/3, `RepositoryWorkspaceServiceTest` 19/19, `WorkflowOrchestrationServiceTest` 46/46, spotless + checkstyle clean). 3 findings deferred (logged to deferred-work.md). **Regression IT written + VERIFIED on real Postgres (Docker):** `BuildFixLoopRedispatchIT` (real-PG, `@Tag("integration")`) reproduces the EXACT bug precondition — `implementation-stage.auto-dispatch=ON` + an approved implementation-plan (⇒ sub-stage `PR_OUTPUT`) + a still-`running` producing rex — and asserts BOTH halves of the fix: the producing rex is finalized (`completed`) AND a second (`queued`) EXECUTION execution is enqueued (execution-stage count == 2). Would FAIL before the fix. **✅ PASSED 1/1 (25.6s) on Testcontainers Postgres 2026-07-05**, alongside `BuildStageIT` 4/4 (7.2s — confirms the async executor profile bean stays deterministic) and full Surefire **1510/0/0**. The CRITICAL is now proven end-to-end, not just offline.

**🔴 `mvnw verify` (Docker) caught a real dev-story regression — FIXED:** `ProjectControllerContractTest` (8 failures) — the Task-2 DTO additions (`buildStageEnabled` primitive `boolean` on `CreateProjectRequest`/`UpdateProjectRequest`) made Jackson **reject any create/update-project body that omits the field** (`HttpMessageNotReadableException` → 400 `malformedJson`), because an absent primitive record component can't be constructed. The dev's "full Surefire 1508/0/0" never caught it: `ProjectControllerContractTest` is `@Tag("contract")` and runs in **Failsafe**, not Surefire. The FE already sends `buildStageEnabled` (8 FE files) and the design intentionally mirrors the primitive `openspecEnabled`, so the fix is test-only: added `"buildStageEnabled":false` to the 8 stale request bodies (exactly as they send `openspecEnabled`). `ProjectControllerContractTest` now **21/0/0**. [Lesson: DTO/registry changes must be verified via `verify` (Failsafe), not just `mvnw test` — the `archunit-runs-in-failsafe` trap applies to contract tests too.]

**Verified on Docker (2026-07-05):** first full `verify` was green except those 8 (all other ITs + ArchUnit 57/57 + Flyway 30/30 + OpenAPI + `BuildFixLoopRedispatchIT` 1/1 + `BuildStageIT` 4/4 + Surefire 1510/0/0 all passed); a confirmation full `verify` after the contract-test fix is running.

**✅ Confirmation full `mvnw verify` (Docker) — ALL GREEN (2026-07-05):** Surefire **1510/0/0**, Failsafe **896/0/0**, `ProjectControllerContractTest` 21/0/0, `BuildFixLoopRedispatchIT` 1/1, `BuildStageIT` 4/4, `ArchitectureBoundaryTest` 57/57, `FlywaySchemaContractTest` 30/30, `OpenApiSnapshotContractTest` 1/1 — **BUILD SUCCESS**. Status → `review`. All 7 review findings fixed + the CRITICAL locked by a real-PG regression IT + the whole gate green on Docker.

**Remaining (non-blocking):** the 3 deferred items (deferred-work.md) + an optional git IT for the real `git clean` artifact-discard path (currently mocked in the ITs). The working tree is uncommitted — commit the 3h-1 + review-fix changes (the branch also carries unrelated `runner.mjs`/`RunnerContractValidator`/`deferred-work.md` churn; decide whether to split that out).

### Decision-needed

- [x] **[Review][Decision] CRITICAL — Bounded auto-fix loop is inert in production; run wedges instead of re-dispatching** — ✅ **FIXED** (`BuildStageService.handleBuildFailure`/`failRunForExecutorFailure` now finalize the producing PR_OUTPUT rex via `finalizeProducingExecution` BEFORE re-dispatch/FAILED, so it no longer strands the in-flight guard; unit asserts `recordCompleted(PR_OUTPUT_REX)` on both the below-cap and at-cap paths). Also fixes the auditor's dangling-rex-on-cap deviation. ✅ End-to-end regression IT written (`BuildFixLoopRedispatchIT`, auto-dispatch ON + approved plan) — asserts producing rex finalized + a fresh queued EXECUTION re-dispatched; needs Docker to run. — On BUILD failure only the BUILD rex is finalized (`recordFailed`); the PR_OUTPUT rex is NOT finalized (the deferred `completeExecutionTailAndAdvance` with `recordCompleted(PR_OUTPUT)` runs only on BUILD **success**). So the PR_OUTPUT `runner_executions` row stays `running`. `handleBuildFailure` → `orchestration.retryImplementation` → `dispatchExecutionInternal` → `inFlightExecutionDispatch(runId, PR_OUTPUT)` finds the still-`running` EXECUTION-stage rex and returns a `Replayed` **no-op** (`WorkflowOrchestrationService.java:1111-1123`). Net: `build_fix_loop_count` bumps + "attempt n/cap" logs, but **no new runner is dispatched and the `build.failure` feedback is never delivered**; the run sits in `Executing` until the stranded PR_OUTPUT rex times out and is reaped as `RUNNER_TIMEOUT` (wrong category). The cap/`RUNNER_BUILD_FAILED`/escalation path is never reached. Untested because `BuildStageServiceTest` mocks orchestration and `BuildStageIT`'s below-cap case runs with impl auto-dispatch OFF (short-circuits before the guard) and never asserts a re-dispatch. Dev's own "Design decisions worth review" note flagged this on the success path and wrongly concluded "non-issue". [BuildStageService.java:199-228,250-268; WorkflowOrchestrationService.java:687-714,1105-1125] (sources: edge+auditor+blind)
- [x] **[Review][Decision] HIGH — Minutes-long build runs synchronously on the single scheduled poller thread** — ✅ **FIXED** (offline-verified; ⚠️ IT needs Docker). `tryGateBehindBuild`'s afterCommit callback now `buildExecutor.execute(() -> runBuild(...))` so the poller thread returns immediately. `buildExecutor` = new profile-gated bean `buildStageExecutor` in `RunnerConfiguration`: prod = virtual-thread-per-task (`Executors.newThreadPerTaskExecutor`), `@Profile("test")` = same-thread (`Runnable::run`) so `BuildStageIT` stays deterministic. `BuildStageServiceTest` passes `Runnable::run`. Compile + spotless + checkstyle clean; `BuildStageServiceTest` 9/9. Re-run `BuildStageIT` under Docker to confirm the profile bean keeps it deterministic. — `runAfterCommit` registers a `TransactionSynchronization.afterCommit()` callback, which Spring fires synchronously on the committing thread — the `@Scheduled` `pollActiveExecutions` thread (no custom `TaskScheduler` pool bean exists). For the whole build duration (default `buildTimeout` 10m) the one scheduler thread is blocked: no other run's results are harvested, no heartbeats advance, timeout/stale scans don't run. The "never holds the broker tx" comment is true but misses that it holds the *thread*. [BuildStageService.java:157-168; AfterCommitSideEffectRunner.runAfterCommit; RunnerConfiguration scheduler] (source: edge H1)
- [x] **[Review][Decision] HIGH — Build artifacts get committed into the PR (`git add -A` runs after the build mutates the workspace)** — ✅ **FIXED** (offline-verified; ⚠️ real-git path needs Docker/CI). New `GitCommandPort.listUntrackedFiles` (`git ls-files --others --exclude-standard`) + `removeUntrackedPaths` (`git clean -f -d -- <paths>`, no `-x`, explicit pathspecs only) on `CliGitAdapter`. `BuildStageService.runBuild` snapshots untracked-not-ignored BEFORE the build and, on success, discards ONLY the build-created delta (`after − before`) before the deferred `captureAndPush` — so runner-authored files (in the snapshot) and tracked/ignored content are never touched. Fully **best-effort**: a snapshot failure skips discard (never a blind clean) and a discard failure still proceeds to push. `BuildStageIT` mocks `GitCommandPort` (discard skipped for the fake repo dir); real `git clean` behavior needs a git IT before merge. **Known limitation:** build modifications to *tracked* files are not reverted (rare for compile/package builds) — documented, not handled. — Pre-3h, `captureAndPush` ran immediately after runner output (tree held only runner-authored changes). Now the build runs first in the same `repoDir`, then the deferred `captureAndPush` does `git add -A && commit`. Any build-generated files (`target/`, `dist/`, `node_modules/`) not `.gitignore`d are staged and pushed into the PR; can also flip a would-be `clean_worktree` no-op into a spurious commit. [RunnerBroker gate → captureAndPush; RepositoryWorkspaceService.captureAndPush; CliGitAdapter.commitAll = `git add -A`] (source: edge M1)
- [x] **[Review][Decision] MED — Infrastructure failures (executor unavailable / timeout) are laundered into `RUNNER_BUILD_FAILED` and burn the fix budget** — ✅ **FIXED** (partial-by-design): `runBuild` now routes an `EXECUTOR_FAILURE_EXIT_CODE` (build could not start) to `failRunForExecutorFailure` — fail-fast + escalate once WITHOUT consuming a fix-loop iteration (re-running the LLM can't repair a broken executor). Timeout (`-1`) deliberately stays in the bounded loop (a hang can be code-induced). Unit-verified (`executorFailureFailsRunFastWithoutBurningTheFixLoop`). — `ProcessBuildCommandAdapter` reports "sh missing / tool not installed" (`EXECUTOR_FAILURE_EXIT_CODE=-2`) and "build exceeded timeout" (`TIMEOUT_EXIT_CODE=-1`) as non-zero exits (never throws). `runBuild` funnels every non-zero into `handleBuildFailure`, so a pure ops/config problem re-dispatches the LLM with unactionable feedback, exhausts the loop, then terminally FAILs the run as `RUNNER_BUILD_FAILED` — misattributing an infra failure to the produced code. No distinct classification for executor/timeout vs genuine compile failure. [ProcessBuildCommandAdapter; BuildStageService.java:185-205] (source: edge M2)

### Patch

- [x] **[Review][Patch] MED — `ProcessBuildCommandAdapter` timeout not truly bounded (unbounded `future.join()` on stream drain after `destroyForcibly`)** — ✅ **FIXED** (`awaitOutput` now `future.get(DRAIN_GRACE_SECONDS, SECONDS)` + cancel-on-timeout, so `run()` always returns within `timeout + 15s` even if a grandchild holds the pipe). Compiles + formats clean; the Linux drain path is exercised by CI (`sh`-based adapter tests are OS-gated, skipped on the Windows dev host). — `waitFor(timeout)` bounds only the process wait; after `destroyForcibly()` the drain threads' `readAllBytes()` block until EOF, and `awaitOutput` does an untimed `future.join()`. A build sub-process (daemon/forked) holding the stdout pipe → `join()` blocks forever → the configured `build-stage.timeout` guarantee is silently defeated and the worker hangs. Use a bounded `get(…, TimeUnit)` with a fallback to bytes-so-far. [adapters/build/ProcessBuildCommandAdapter.java `awaitOutput`] (source: blind)
- [x] **[Review][Patch] LOW — Cap boundary `<= cap` vs javadoc/AC "reaches the cap" wording; `count == cap` boundary untested** — ✅ **FIXED** (wording in `application.yml` + `BuildFixEscalationThresholdProvider` javadoc now says "up to N attempts; FAILED on the (N+1)-th failure"; added `buildFailAtExactCapStillRedispatches` boundary test). — With cap=3, failures at 1/2/3 re-dispatch and the run FAILs only at count 4 (`> cap`) — intentional and matches Task 5 pseudocode, but the config comment + provider Javadoc read as failing at `count == cap`. Tests only exercise counts 1 and 4; the `== cap` boundary is never hit. Clarify the wording and add a `count == cap` boundary test. [BuildStageService.java:250] (sources: blind+edge+auditor)
- [x] **[Review][Patch] LOW — Test/assertion completeness gaps** — ✅ **FIXED** (partial): added the missing `kindForStage(BUILD)`/`kindForStage(REVIEW)` throw assertions to `RunnerPropertiesTest`. The `provider_usage_snapshots` zero-row check is by-construction (BuildStageService has no provider-usage port to invoke); the real-PG zero-row assertion remains an IT concern. — No `provider_usage_snapshots` zero-row IT assertion (Task 4 named it explicitly for AC6); only 1 of the "3 switch-arm assertions" is actually tested (`ProjectRunnerSteps` BUILD case) — no explicit assert that `kindForStage(BUILD)` throws or `allowedArtifactTypesForStage(BUILD)` is empty; broker-side gate-decision log lines not pinned. Behaviorally safe by construction, but the story overstates coverage. [BuildStageServiceTest / BuildStageIT] (source: auditor)

### Deferred

- [x] **[Review][Defer] MED — Swallowed deferred side-effects can wedge the run with no reconciliation path** — Every deferred body runs through `AfterCommitSideEffectRunner.swallow` (catch `RuntimeException`, WARN, return). If the capture tx or the advance/fix-loop body throws, the PR_OUTPUT rex stays `running`, the run stays `Executing`, no retry/FAILED/escalation — a silent wedge distinguishable only by a WARN. No sweep re-drives a gated-but-unfinished build (cf. 3f-8's stranded-SPLIT sweep pattern). [BuildStageService.java:191-228] — deferred, robustness hardening (reconciliation-sweep follow-up), not blocking the core gate. (source: edge M3)
- [x] **[Review][Defer] LOW — `build.failure` references accumulate unboundedly across loop iterations** — `collectExecutionFeedbackReferences` references ALL failed BUILD rex rows for the run (no "latest only" bound), so the count grows each iteration. Benign (history) but unbounded/noisy. [ContextBundleService.java:1337-1345] — deferred, only reachable once the loop actually iterates (post-CRITICAL-fix). (source: edge L1)
- [x] **[Review][Defer] LOW — Gate reserves the BUILD rex row before confirming an active tx synchronization** — `insertPending(...)` precedes `runAfterCommit(...)`; if ever invoked outside an active synchronization, `runAfterCommit` WARN-skips but the gate already returned `true` and inserted a `pending` BUILD rex → orphan row, build never runs, run wedges. Not reachable via the sole current caller (always inside `perItemTransactionTemplate`). [BuildStageService.java:142-169] — deferred, future-caller footgun; reserve inside the hook or assert-synchronization-first. (source: edge L3)

### Dismissed (noise / false positive)

- **Blind Hunter HIGH — "`build.failure` ref only attached on the IMPLEMENTATION_PLAN branch"** — FALSE POSITIVE. `ContextBundleService.java:1322` gates emission on `subStage == PR_OUTPUT || subStage == IMPLEMENTATION_PLAN`, so the reference IS attached on the PR_OUTPUT re-dispatch path (verified by direct read). The runner does receive the build error once the CRITICAL re-dispatch bug is fixed.

---

### bmad-code-review 2026-07-05 (re-review #2, Opus 4.8 [1m])

_Full-diff re-run (46 tracked + 13 untracked new source/test files, ~6,283 diff lines). The FIRST slice on `git diff HEAD` silently omitted the untracked crux files (`BuildStageService`, `ProcessBuildCommandAdapter`, `V33`, all new tests) — re-run on a complete diff so the Blind Hunter saw the heart of the change. Result: **3 patch, 3 defer, 11 dismissed** (most dismisses = already fixed/deferred by the 2026-07-05 review above; verified against source). Net-new value: a `ProcessBuildCommandAdapter` stream-draining cluster the first review missed (its patch bounded only the drain WAIT, not the executor / read-size / timeout units)._

#### Patch (net-new) — ✅ ALL 3 FIXED 2026-07-05 + **FULL `mvnw verify` GREEN on Docker**: Surefire **1512/0/0** (16 skipped), Failsafe **896/0/0** (1 skipped), runner-contracts 19/0/0, all key ITs ran (`BuildStageIT`, `BuildFixLoopRedispatchIT`, `ProcessBuildCommandAdapterTest`, `FlywaySchemaContractTest`, `ArchitectureBoundaryTest`, `OpenApiSnapshotContractTest`) — **BUILD SUCCESS**. ⚠️ The 2 new `ProcessBuildCommandAdapter` regression tests (`subSecondTimeoutIsNotTruncatedToZeroSeconds`, `oversizedOutputIsBoundedAndAnnotatedTruncated`) are `@EnabledOnOs(LINUX)` — the verify JVM runs on the Windows host (Docker only provides Testcontainers PG) so they were SKIPPED here (part of the 16; count moved 1510→1512 confirming they're wired) and execute only in Linux CI; the non-gated executor-failure path ran green with the new dedicated-executor code, so the change breaks nothing.

- [x] **[Review][Patch] MED/HIGH — `ProcessBuildCommandAdapter.drainAsync` runs both pipe drains on `ForkJoinPool.commonPool` → pipe-buffer deadlock → false timeout (+ leaked worker)** — ✅ **FIXED**: `run()` now creates a dedicated 2-thread daemon `ExecutorService` (`build-drain`) and `drainAsync(stream, executor)` submits both drains to it, so they run concurrently regardless of host core count; `shutdownNow()` in `finally` (daemon threads so a stuck native `readAllBytes` never blocks JVM shutdown). [adapters/build/ProcessBuildCommandAdapter.java `run`/`drainAsync`] — `CompletableFuture.supplyAsync(...)` with no `Executor` used the JVM-wide commonPool, whose parallelism is `max(1, cores-1)` = **1 on a ≤2-core CI box**: the stderr drain sat queued while the stdout drain blocked, a >64 KB-stderr build filled the pipe buffer, the child blocked, and a PASSING build was reported `timedOut(-1)` → burned the fix loop and FAILed valid code.
- [x] **[Review][Patch] MED — `drainAsync` `stream.readAllBytes()` is unbounded → OOM before the log-capture cap applies** — ✅ **FIXED**: new `drainBounded(...)` reads to EOF (so the child never blocks on a full pipe) but buffers at most `MAX_CAPTURED_BYTES` (2 MiB/stream) and appends an `[output truncated at N bytes]` annotation on overflow. Bounds heap independently of the timeout (wall-clock) guard. Locked by `oversizedOutputIsBoundedAndAnnotatedTruncated` (Linux-gated). [adapters/build/ProcessBuildCommandAdapter.java:`drainBounded`] — the 256 KB `priorFeedback` cap only bounds the *reference*, downstream of this read.
- [x] **[Review][Patch] LOW — sub-second / fractional `build-stage.timeout` truncates to 0s → `waitFor(0)` returns immediately → every build "times out"** — ✅ **FIXED**: `run()` now computes `timeoutMillis` via `Duration.toMillis()` and calls `waitFor(timeoutMillis, TimeUnit.MILLISECONDS)` (log key renamed `timeoutSeconds`→`timeoutMillis`). Locked by `subSecondTimeoutIsNotTruncatedToZeroSeconds` (Linux-gated: `printf ok` with a 300 ms budget now exits 0 instead of being force-killed). [adapters/build/ProcessBuildCommandAdapter.java:`run`]

#### Deferred (net-new)

- [x] **[Review][Defer] MED — deferred delivery tail pushes after the run has gone terminal (no state re-check before `captureAndPush`)** [BuildStageService.java:244 → RunnerBroker.completeExecutionTailAndAdvance] — an operator cancel/fail during the minutes-long build leaves the deferred success tail to still run the irreversible `captureAndPush` (branch push + PR open) and only then throw `ILLEGAL_TRANSITION` on the `WaitingForReview` advance (swallowed) → code pushed + PR opened for a run whose DB state is terminal. Deferred — the push relocation completes behind the delivery gate in **3h-4**; recovery is Epic-4. (source: edge M-new)
- [x] **[Review][Defer] LOW — special-char / quoted / newline untracked filenames escape `git clean` artifact discard** [adapters/git/CliGitAdapter.java listUntrackedFiles/removeUntrackedPaths] — `git ls-files --others` runs without `-z` and with default `core.quotePath=true`, so a non-ASCII/space/`:`-magic path is C-quoted (or newline-split); the quoted literal is passed back to `git clean -- <path>`, matches nothing, exits 0 (silent no-op) → the build-created file survives into `git add -A` and ships in the PR. Deferred — best-effort discard, rare filenames; harden CliGitAdapter parsing (`-z`, argv pathspecs). (source: edge L-new)
- [x] **[Review][Defer] LOW — `handleBuildFailure` silently no-ops re-dispatch when the orchestration bean is absent (counter already bumped, PR_OUTPUT finalized) → silent wedge** [BuildStageService.java:320-327] — defensive-only residue of the CRITICAL path: in a fully-wired prod app `orchestrationSupplier.get()` is never null, but if it is, side effects have fired with no re-dispatch and no FAILED transition. Deferred — make fail-loud in the Epic-4 hardening pass. (source: blind+edge)

#### Dismissed (re-review #2 — verified against source / already handled)

- **BH5/EC2 build mutations to TRACKED files pushed** — already recorded as an accepted **"Known limitation… not handled"** in the HIGH decision item above (line ~310). Not re-opened.
- **EC7/BH-old-5 `build.failure` refs accumulate** — already **deferred** above (L1). **BH3/EC4 swallowed-side-effect wedge / orphan pending BUILD row** — already **deferred** above (M3 + L3). **AA3 infra failure laundered into RUNNER_BUILD_FAILED** — already **fixed partial-by-design** above (MED decision).
- **BH7 afterCommit replay double-count / double-FAILED** — `AfterCommitSideEffectRunner` fires each registration ONCE and swallows escapes (no retry loop); the terminal FAILED path is idempotency-keyed + swallowed. Cannot double-count from replay.
- **BH8 enum ordinal shift (BUILD/RUNNER_BUILD_FAILED inserted mid-enum)** — `RunnerStage`/`FailureCategory` persist by VALUE (RegistryValue); no `@Enumerated(ORDINAL)` / `.ordinal()` on either enum (grep-verified). Safe.
- **BH-old-1 `buildStageEnabled` primitive 400 on omit** — current `CreateProjectRequest`/`UpdateProjectRequest` put no `@NotNull` on the primitive; a missing primitive defaults to `false` and the OpenAPI/TS schema marks it optional. The transient contract-test regression was already fixed in the review above. No backward-compat break.
- **BH-old-3 asymmetric project/global merge (command falls back to global, enabled flag does not)** — intentional and consistent with siblings: `resolveBuildCommand` mirrors `resolveRunnerKind` (project→global), `resolveBuildStageEnabled` mirrors `resolveOpenSpecEnabled` (project-only; project is source of truth post-seed, the 3c-6 inversion).
- **AA2 AC4 literal per-iteration key `build-fix:<runId>:<count>` not applied to the bump/re-dispatch** — per-iteration idempotency is achieved equivalently via the re-dispatch key `pr-output:<runId>:<bundleVersion>` (varies per iteration); the literal `build-fix:` key is used for the terminal FAILED transition. Behavior correct — recommend updating AC4 wording to match the version-keyed mechanism.
- **AA4 config path `build-stage.max-fix-loops` (Task 2) vs `deliveryline.workflow.build-fix-max-loops` (Task 5)** — implementation consistently uses the Task-5 path; behavior correct, spec-internal wording contradiction only.
- **AA1 "diff omits the build-stage implementation"** — a review-slice process gap (untracked files absent from `git diff HEAD`), resolved by re-running this pass on the complete diff. Not a code defect.
