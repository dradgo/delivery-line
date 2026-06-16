# Story 3b.2: Two-Dispatch Execution Orchestration — Plan-Approval Re-Dispatches the PR Phase

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a workflow orchestrator,
I want approving the implementation plan to re-dispatch a second execution run (the PR phase), so the full walk is **approve spec → dispatch #1 (plan, read-only) → plan review → approve plan → dispatch #2 (PR, implement+push) → PR review → accept → `Completed`**,
so that the two-phase contract from 3b-1 actually reaches the `PR_OUTPUT` sub-stage and persists the `github_pr` link via `validateAndEnrichPrOutput`.

## Context & Resolved Open Question (read first — this changes what you build)

This is sub-project #1 "Story B" of the execution-stage `WaitingForReview` fix (twin of the spec-stage gate 3a-9 closed). 3b-1 (done, commit `95d3019`) made each execution dispatch carry the resolved `ExecutionSubStage` so dispatch #1 genuinely runs the read-only **plan** phase and dispatch #2 runs the **pr-output** phase. This story proves the **two-dispatch state walk end-to-end** — it is the headline pilot-readiness verification.

**The epic stub posed an open question: "does `acceptImplementation` on an `implementationPlan` already re-dispatch the PR phase, or must it be added?" — RESOLVED BY CODE TRACE: it ALREADY re-dispatches. Story 3.20 wired it.**

`TechnicalApprovalService.transitionAndDispatch` (`TechnicalApprovalService.java:566-607`) branches on artifact type:
- `PR_OUTPUT` accept → transition `WaitingForReview → COMPLETED`, **no** dispatch (`:573-584`).
- `IMPLEMENTATION_PLAN` accept → transition `WaitingForReview → EXECUTING` (`:590-596`) → **then** `workflowOrchestrationService.dispatchImplementation(runId, correlationId)` (`:604-605`), inside the same MANDATORY transaction (Trap T5: a dispatch failure rolls back the approval row + event + transition).

`dispatchImplementation` → `dispatchExecutionInternal(... ExecutionSubStage.PR_OUTPUT ...)` (`WorkflowOrchestrationService.java:541-551`). It is gated by `implementation-stage.auto-dispatch` (**`true` in production** `application.yml:224-226`; **OFF in the shared test profile** `test/resources/application.yml:90-92`). `ContextBundleService.deriveExecutionSubStage` (`:421-429`) returns `PR_OUTPUT` once an approved implementation-plan approval row exists — which the plan-accept just inserted — so dispatch #2 derives `PR_OUTPUT` and the broker composes the pr-output bundle.

**→ THE #1 DISASTER TO PREVENT: do NOT add a new re-dispatch trigger, event listener, post-commit hook, or transition hook. It exists. Adding one creates a DUPLICATE pr-output dispatch.** This is a **test-only (verification) story: ZERO production-code changes are expected.** If you believe production code must change, stop and re-read this section — the wiring is complete (3.20 re-dispatch + 3b-1 sub-stage tokens + prod `auto-dispatch: true`).

`[[post-commit-hook-needs-requires-new]]` is **N/A** here: the re-dispatch is a synchronous in-transaction call, NOT a `TransactionSynchronization.afterCommit` hook. (The only afterCommit hook on this path is the 3.16 Linear completion-sync, which fires on `COMPLETED`, not `EXECUTING`.) The epic's mention of that memory is a conditional ("applies *if* the re-dispatch fires from afterCommit") that does not hold — note it and move on.

## Acceptance Criteria

1. **Headline end-to-end orchestration IT (the deliverable).** A new Testcontainers `*IT` walks the full two-dispatch flow over the real wiring (real `approveSpec` + real `acceptImplementation` + mock runner, deterministic `pollActiveExecutions()` — never sleep, Trap T6):
   - Seed run at `WaitingForSpecApproval` + an approval-eligible `spec` artifact → real `approveSpec` transitions `→ Executing` and auto-dispatches the plan runner (sub-stage `IMPLEMENTATION_PLAN`).
   - Drive the queue (`dequeue → executeQueuedDispatch`) + poll → an `implementationPlan` artifact is ingested and the run auto-advances `Executing → WaitingForReview`. **Assert the plan phase did NOT push:** no `prOutput` artifact exists and **no active `github_pr` link** exists at this point.
   - Real `acceptImplementation` on the `implementationPlan` → transition `→ Executing` AND a **second** execution dispatch is enqueued (sub-stage now derives `PR_OUTPUT`). Assert exactly **2** executions and the run is `Executing`.
   - Drive the queue + poll → a `prOutput` artifact is ingested and the run auto-advances `Executing → WaitingForReview`.
   - With an active `github_pr` link present, real `acceptImplementation` on the `prOutput` → transition `→ Completed`. Assert final state `Completed`.
2. **Plan accept re-dispatches via the EXISTING wiring (verify, do not add).** The IT proves the re-dispatch is driven by the real `TechnicalApprovalService.acceptImplementation(IMPLEMENTATION_PLAN)` path — no new trigger is introduced anywhere. A unit-level assertion that `acceptImplementation` on an `implementationPlan` calls `dispatchImplementation` already exists (`TechnicalApprovalServiceAcceptImplementationTest.happyPathImplementationPlanTransitionsToExecutingAndDispatches`); reference it, do not duplicate it.
3. **Sub-stage routing is correct across the two dispatches.** Dispatch #1 carries `subStage = IMPLEMENTATION_PLAN` (no approved plan yet) and dispatch #2 carries `subStage = PR_OUTPUT` (approved plan now exists). Assert via the observable artifact types emitted (`implementationPlan` then `prOutput`) and that `validateAndEnrichPrOutput`'s sub-stage gate (`executionSubStage == PR_OUTPUT`, `RunnerBroker.java:~1515`) runs only on the second dispatch.
4. **Idempotency — duplicate accept / re-dispatch while the PR phase is in-flight is a no-op (mirror 3.11/3.12 AC6).** A second `acceptImplementation(implementationPlan)` with the same `idempotencyKey`, OR a direct second `dispatchImplementation`, while the pr-output execution is `queued`/`pending`/`running`, does NOT create a 3rd runner execution and does NOT illegally re-transition. (The sub-stage-aware in-flight guard `inFlightExecutionDispatch` at `WorkflowOrchestrationService.java:618` + the approval `idempotency_key` enforce this; `PrOutputOrchestrationIT.reDispatchImplementationWhileInFlightIsAnIdempotentNoOp` is the reusable pattern.)
5. **`github_pr` link gates and binds the `prOutput` accept.** The `prOutput` accept passes the AC6 PR-link gate (`assertPrLinkPresentAndMatches`, `TechnicalApprovalService.java:615-622`) against an active `github_pr` link, and the plan accept needs **no** link (an `implementationPlan` has no PR yet). The "enrich persists the link from a real push outcome" path is already covered deterministically by `RunnerBrokerUnitTest` + `RepositoryWorkspaceServiceIT` + `IntegrationLinkGitHubPrFoundationContract` — **do not duplicate it** in this IT (see Dev Notes "Design Decision DD2").
6. **Regression — investigation/spec path unchanged.** `SpecStageOrchestrationIT`, `ImplementationPlanOrchestrationIT`, and `PrOutputOrchestrationIT` stay green unchanged. No production source is modified, so no behavior shifts for the single-dispatch ITs or the recovery/legacy generic (`subStage == null`) path.
7. **No new production surface.** No new `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema, config property, REST endpoint, ArchUnit rule, or transition-table edge. Net change is a single new orchestration IT (plus, if a genuine wiring gap is discovered, the smallest possible fix — but none is expected; flag it loudly if you find one).

## Tasks / Subtasks

- [x] Task 1 — Confirm the wiring is complete before writing a line of production code (AC: #2, #7)
  - [x] Re-read `TechnicalApprovalService.transitionAndDispatch:566-607` and `WorkflowOrchestrationService.dispatchExecutionInternal:590-645`. Confirm the plan-accept → `dispatchImplementation(PR_OUTPUT)` call and the sub-stage-aware in-flight guard. **Write no production code in this story unless you find a real gap** (none is expected) — and if you do, document it as a deviation and keep the fix minimal. **→ Confirmed: no gap. `transitionAndDispatch` (`:566-607`) branches plan→Executing then `dispatchImplementation` in-tx; `dispatchExecutionInternal` (`:590-645`) carries the in-flight guard. ZERO production code changed.**
  - [x] Confirm production `auto-dispatch: true` for `plan-stage` and `implementation-stage` (`application.yml:215-226`) so the re-dispatch fires for real; the test profile keeps both OFF (`test/resources/application.yml:83-92`). **→ Confirmed; test profile both OFF (`test/resources/application.yml:78,85,92`), IT opts in per-test.**
- [x] Task 2 — Write the headline two-dispatch orchestration IT (AC: #1, #3) — `WaitingForReviewTwoDispatchOrchestrationIT` (name `*IT`, `@Tag` not required — it is a Testcontainers-Postgres IT like the siblings, not a `docker-runner-it`)
  - [x] Copy the harness skeleton from `PrOutputOrchestrationIT` / `ImplementationPlanOrchestrationIT`: `@Import(TestcontainersConfiguration.class)`, `@SpringBootTest`, `@ActiveProfiles({"test", "linear-mock"})`, the `@AfterEach` table cleanup, and the `insertRun` / `currentState` / `executionCount` / `executionStatus` / `drainQueue` helpers.
  - [x] `@TestPropertySource` MUST enable **both** master switches (shared profile keeps them OFF): `deliveryline.runner.plan-stage.auto-dispatch=true` and `deliveryline.runner.implementation-stage.auto-dispatch=true`. Leave `default-scenario.execution` at the shared default `happy-implementation-plan` (Trap T7) so dispatch #1 emits a plan.
  - [x] Seed `WaitingForSpecApproval` + an available spec via the `seedAvailableSpecArtifact` pattern (payload round-trips `ArtifactPayloadStore` so `isApprovalEligible` passes); real `commandService.approveSpec(...)` (`product_reviewer`) → assert `Executing`, 1 execution, status `queued`.
  - [x] `drainQueue()` + `runnerBroker.pollActiveExecutions()` → assert `WaitingForReview`, status `completed`, exactly 1 `implementationPlan` artifact, **0** `prOutput` artifacts, and no active `github_pr` link (plan phase pushes nothing). **→ asserted via `activeGitHubPrLinkCount(runId) == 0`.**
- [x] Task 3 — Drive the plan-accept → second dispatch and complete the walk (AC: #1, #3, #5)
  - [x] Make the ingested `implementationPlan` approval-eligible: mark it `available` in-test (the 3b-3 production wiring is **parallel and out of scope here** — `[[markavailable-has-no-production-caller]]`). Prefer `ArtifactOperationService.markAvailable(...)` against the ingested artifact's real payload checksum/storageRef; resolve its `artifactId` from the `artifacts` row. See Dev Notes "Design Decision DD1". **→ `markIngestedArtifactAvailable` drives the REAL `markAvailable` (pending op exists from ingest; writes own payload+ref since the pending row carries no storage_ref until markAvailable). Defensive: skips when already `available`.**
  - [x] `mockRunnerAdapter.pinScenarioForWorkflowRun(runId, "happy-pr-output")` **before** the plan accept (the pin is single-shot — consumed by the *next* dispatch — and dispatch #1 already ran, so it only affects dispatch #2). Autowire `MockRunnerAdapter` (bean active under `!runners.docker`).
  - [x] Real `acceptImplementation(implementationPlan)` (developer reviewer role; build an `AcceptImplementationCommand` with the current artifact + context-bundle versions). Assert: still `Executing`, **2** executions, latest `queued`. **→ versions read live via `currentVersions` (mirrors `ApprovalVersionBinder`).**
  - [x] `drainQueue()` + `pollActiveExecutions()` → assert `WaitingForReview`, exactly 1 `prOutput` artifact ingested.
  - [x] Ensure an active `github_pr` link exists (Design Decision DD2 — direct insert, since `IntegrationLinkService.linkGitHubPr` resolves the PR via the absent `github-mock`/`github-real` GitHubAdapter) and mark the ingested `prOutput` `available`. Real `acceptImplementation(prOutput)` → assert final state `Completed`.
- [x] Task 4 — Idempotency + regression assertions (AC: #4, #6)
  - [x] Add an idempotency case: while the pr-output execution is in-flight (after the plan accept enqueues dispatch #2, before draining), a duplicate `acceptImplementation(implementationPlan)` (same `idempotencyKey`) AND a direct `dispatchImplementation(runId, ...)` leave execution count at 2 and the run `Executing` (no 3rd execution, no illegal transition). Mirror `PrOutputOrchestrationIT.reDispatchImplementationWhileInFlightIsAnIdempotentNoOp`. **→ both arms asserted in `duplicatePlanAcceptOrPrDispatchWhileInFlightIsIdempotentNoOp`.**
  - [x] Regression: run `SpecStageOrchestrationIT`, `ImplementationPlanOrchestrationIT`, `PrOutputOrchestrationIT` — all green, unmodified (10/10). Confirm the `subStage == null` legacy/recovery path is untouched (zero production change).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] This is a verification/IT story with no new production branch, so **no new production log line is added**. Instead, pin the EXISTING observability the live incident needed: assert (logback list-appender, the `DockerRunnerAdapterUnitTest:247` pattern) that across the walk the two dispatches emit distinguishable sub-stage logs — `dispatchPlanGeneration entry ... subStage=implementationPlan` and `dispatchImplementation entry ... subStage=prOutput` (`WorkflowOrchestrationService.java:607-612`), and the plan-accept `acceptImplementation dispatchImplementation workflowRunId=...` line (`TechnicalApprovalService.java:600-602`). This proves the two-phase routing is observable in a prod incident (the exact thing that was opaque on `run_ae258…`).
  - [x] Use parameterized logging assertions only; never change production log levels. No new WARN/ERROR branch is introduced.
  - [x] No genuine production gap discovered — no code added.

## Dev Notes

### Why this story is test-only (the central reconciliation)

The two-phase **mechanism** is fully built across three landed stories; 3b-2 only **proves the composition**:

| Concern | Where it already lives | Status |
|---|---|---|
| Dispatch carries the precise sub-stage → runner stage token | 3b-1 `RunnerDispatchRequest.subStage` + `DockerRunnerAdapter.resolveRunnerStageToken` | done (commit `95d3019`) |
| Spec-approve → first execution dispatch (plan) | 3.11 `ApprovalService.approveSpec` → `dispatchPlanGeneration` | done |
| Plan-accept → second execution dispatch (pr-output) | **3.20** `TechnicalApprovalService.transitionAndDispatch:604-605` → `dispatchImplementation` | **done — this is the re-dispatch; DO NOT re-add** |
| Sub-stage derivation flips to `PR_OUTPUT` after plan approval | 1.13 `ContextBundleService.deriveExecutionSubStage:421-429` | done |
| `validateAndEnrichPrOutput` persists the `github_pr` link in the PR sub-stage | 3.12/3.15 `RunnerBroker` → `IntegrationLinkService.linkGitHubPr` | done |
| Sub-stage-aware in-flight idempotency guard | 3.12 `WorkflowOrchestrationService.inFlightExecutionDispatch:618` | done |
| Prod `auto-dispatch: true` for plan + implementation stages | `application.yml:215-226` | done |

The reason the live run `run_ae258aa42f524ba29db3c795732a21e6` failed was **purely** that the runner always ran `prOutput` (the coarse `execution` token) — fixed by 3b-1. With dispatch #1 now genuinely read-only-plan and dispatch #2 genuinely pr-output, the pre-existing orchestration is correct; nothing had ever exercised it end-to-end because no full-walk IT existed (only the half-ITs `ImplementationPlanOrchestrationIT` and `PrOutputOrchestrationIT`, each seeding mid-stream). 3b-2 closes that gap.

### Design Decision DD1 — making ingested artifacts approval-eligible in-test (3b-3 is parallel)

`acceptImplementation` gates on `artifactService.isApprovalEligible(artifactId)` (`TechnicalApprovalService.java:205-207`) = artifact `available` + payload bytes match the persisted checksum. **The mock execution path leaves `implementationPlan`/`prOutput` `pending`** (`markAvailable` has no production caller — `[[markavailable-has-no-production-caller]]`; this is exactly what the parallel story 3b-3 wires on ingest). Since 3b-2 and 3b-3 are sequenced parallel, **this IT must mark availability itself.** Two viable approaches:
- **(preferred)** call `ArtifactOperationService.markAvailable(artifactId, checksum, storageRef)` against the *ingested* artifact (resolve the row, read its stored payload, compute the SHA-256). This keeps the IT exercising the real ingested artifact.
- (fallback) mirror `ImplementationPlanOrchestrationIT.seedAvailableSpecArtifact`: write a payload to `ArtifactPayloadStore`, then update the artifact row to `available` with the matching checksum/storage_ref.

Do NOT take a dependency on 3b-3 landing first. If 3b-3 *has* landed by the time you implement, the ingested artifacts may already be `available` — assert defensively rather than assuming.

### Design Decision DD2 — the `github_pr` link for the `prOutput` accept (recommended: seed it)

The `prOutput` accept requires an active `github_pr` link (`assertPrLinkPresentAndMatches` → `findActiveGitHubPrLink`). In production that link is persisted by `validateAndEnrichPrOutput` → `linkGitHubPrBestEffort` → `IntegrationLinkService.linkGitHubPr` **only when `captureAndPush` returns a real push outcome**. But `RepositoryWorkspaceService` is `@Profile({"github-mock","github-real"})` and `captureAndPush` returns `Optional.empty()` for `no_repo_workspace`/`clean_worktree` (`RepositoryWorkspaceService.java:246-308`) — and **the mock runner writes synthetic artifact bytes to the scratch store, it does not dirty a real cloned repo.** So the natural enrich-persists-link path will NOT fire under the mock runner without standing up a real repo workspace and a runner that mutates it — disproportionate for this story.

**Recommendation:** in the IT, **seed an active `github_pr` link** (via `IntegrationLinkService.linkGitHubPr(runId, prRef, null, branchRef, commitSha, systemActor, idemKey)`, the same call the broker makes) before the `prOutput` accept — representing what enrichment persists in production. Assert the orchestration **state walk + two-dispatch sequencing + that the active link gates/binds the `prOutput` accept**. The "enrich actually persists the link from a real `RepositoryPushOutcome`" claim is already proven deterministically by `RunnerBrokerUnitTest` (enrichment shapes), `RepositoryWorkspaceServiceIT` (real push), and `IntegrationLinkGitHubPrFoundationContract` — AC5 explicitly forbids duplicating it. State this scope boundary in the IT's class javadoc (mirror the `PrOutputOrchestrationIT` javadoc style that already documents "no repo workspace wired here").

### The two-EXECUTION-outcomes-in-one-IT problem (load-bearing mechanism)

`MockRunnerAdapter.dispatch` resolves its scenario as `override ?? runnerProperties.mock().scenarioFor(request.stage())` (`MockRunnerAdapter.java:79-82`). The default is keyed by the **coarse** `RunnerStage` (`EXECUTION`) — a single static `default-scenario.execution` cannot yield `implementationPlan` then `prOutput`. The seam is `pinScenarioForWorkflowRun(workflowRunId, scenarioName)` (`:71-73`): a **single-shot** per-run override (`scenarioOverrides.remove` at dispatch). Plan: leave the default `happy-implementation-plan` for dispatch #1; `pin("happy-pr-output")` for dispatch #2 (the pin is consumed only by the second dispatch since the first already ran). Both fixtures exist: `runner-scenarios/happy-implementation-plan.json`, `runner-scenarios/happy-pr-output.json`.

### Exact touch points (verified against current source)

| File | Line(s) | Relevance |
|---|---|---|
| `application/approval/TechnicalApprovalService.java` | `154-257` (accept), `566-607` (transitionAndDispatch), `615-622` (PR-link gate) | the real accept path under test — **read, do not modify** |
| `application/workflow/WorkflowOrchestrationService.java` | `464-474` (dispatchPlanGeneration), `541-551` (dispatchImplementation), `590-645` (dispatchExecutionInternal + in-flight guard `:618`) | the dispatch entry points — **read, do not modify** |
| `application/runner/ContextBundleService.java` | `421-429` (deriveExecutionSubStage) | plan-approved → `PR_OUTPUT` |
| `application/runner/RunnerBroker.java` | `~1515` (PR-output sub-stage enrich gate), `validateAndEnrichPrOutput`/`linkGitHubPrBestEffort` | enrich runs only on dispatch #2 |
| `adapters/runner/MockRunnerAdapter.java` | `71-73` (pinScenarioForWorkflowRun), `79-82` (scenario resolution) | the per-phase scenario pin seam |
| `test/.../workflow/PrOutputOrchestrationIT.java` | whole file | **copy the harness** (drainQueue, cleanup, seed helpers) |
| `test/.../workflow/ImplementationPlanOrchestrationIT.java` | `seedAvailableSpecArtifact:234-262`, `approveSpec` flow | spec seed + approve pattern |
| `application/artifact/ArtifactOperationService.java` | `284` (markAvailable) | in-test availability (DD1) |
| `application/integration/IntegrationLinkService.java` | `370` (linkGitHubPr), `648` (findActiveGitHubPrLink) | seed/assert the `github_pr` link (DD2) |
| `src/main/resources/application.yml` | `208-226` | prod `auto-dispatch: true` (confirm, don't change) |
| `src/test/resources/application.yml` | `78-92` | test-profile flags OFF (override per-IT) |

### Scope boundary (do NOT do here)

- Do NOT add a re-dispatch trigger / event listener / post-commit hook (it exists — see Context). The single biggest failure mode for this story is creating a **duplicate** pr-output dispatch.
- Do NOT wire `markAvailable` into the production ingest loop — that is **3b-3** (parallel). Mark availability only inside this IT.
- Do NOT build the review UI, allowed-actions role wiring, or `prOutput`/`implementationPlan` renderers (3b-4/3b-5/3b-6).
- Do NOT change the runner image, `entrypoint.sh`, the dispatch envelope, or the adapter token mapping (3b-1, done).
- Do NOT tighten the artifact-type mismatch guard to per-sub-stage (3b-1 AC8 pinned it coarse).

### Architecture / boundaries

- The IT lives in `application/workflow` (the orchestration test package), beside its siblings; it autowires application services + `MockRunnerAdapter` (an adapter bean, allowed in a test) + `JdbcTemplate`. No `application → adapters` production import is added (`[[application-cannot-import-adapters]]`).
- `@SpringBootTest` + Testcontainers ⇒ name it `*IT` so Failsafe (Docker tier) runs it and Surefire excludes it (`[[springboot-testcontainers-test-must-be-IT]]`). It is a Postgres-Testcontainers IT like the siblings — NOT a `docker-runner-it` (no `@Tag` needed; do not gate it out of `verify`, `[[docker-it-needs-exact-docker-runner-it-tag]]`).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **This story:** no new production log surface (verification-only). The test pins the EXISTING two-phase dispatch logs (`WorkflowOrchestrationService` `dispatch* entry ... subStage={}` `:607-612`; `TechnicalApprovalService` `acceptImplementation dispatchImplementation ...` `:600-602`; the 3b-1 `DockerRunnerAdapter` token line is exercised only under the docker profile, out of scope here) via a logback list-appender so the plan-vs-PR routing stays observable — the exact signal that was missing on `run_ae258…`.
- **Required context keys** (already present on these lines): `workflowRunId`, `runnerExecutionId`, `correlationId`, `subStage`. **Forbidden:** provider key/secrets, payload bytes, raw refs.
- **Test contract:** the list-appender assertions are the pinning tests; do not assert on string concatenation or mutate levels.

### Testing standards

- Backend unit tier = Surefire (no Docker); `*IT` = Failsafe (Docker/Testcontainers tier). Run a single IT via the `integration-test` *lifecycle phase* (not the `failsafe:integration-test` goal — `@{argLine}` crash, `[[maven-arglineation-goal-crash]]`) with `-Djacoco.skip=true`; skip the unit tier with a no-match `-Dtest=Zzz`.
- Drive async runner results with `runnerBroker.pollActiveExecutions()` after `drainQueue()` — never sleep on the 5s scheduler (Trap T6). The worker pool is OFF in the test profile, so `drainQueue` (`dequeue → executeQueuedDispatch`) stands in for the worker leg (`[[story-3-17b-queue-activation-seams]]`).
- Verify on Linux/Docker CI before merge — local green ≠ CI green (`[[verify-ci-fixes-in-clean-env]]`); Testcontainers needs a real Docker env (`[[wsl-linux-ci-reproduction]]`).

### Project Structure Notes

- No new module, package, Flyway migration, `DomainErrorCode`, `WorkflowEventType`, runner-contracts schema, config property, or OpenAPI change. Net change: **one new orchestration IT** (`WaitingForReviewTwoDispatchOrchestrationIT`). If a genuine wiring gap surfaces (not expected), the fix must be the smallest possible and called out explicitly in the Change Log + Completion Notes.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3b-2] — stub, AC-shape, dependencies, sequencing `(3b-1 → 3b-2)`.
- [Source: docs/superpowers/specs/2026-06-16-runner-two-phase-execution-contract-design.md] — authoritative design (sub-project #1, Story B); the open question this story resolves.
- [Source: _bmad-output/implementation-artifacts/3b-1-thread-execution-substage-to-runner-stage-token-dispatch-and-adapter.md] — predecessor (done); the sub-stage threading + the "do not re-add the re-dispatch (that is 3b-2)" hand-off note.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/approval/TechnicalApprovalService.java:154,205,566,604,615] — the real accept path, eligibility gate, re-dispatch call, PR-link gate.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java:464,541,590,618] — dispatch entry points + sub-stage-aware in-flight guard.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/ContextBundleService.java:421] — `deriveExecutionSubStage` (plan-approved → PR_OUTPUT).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/MockRunnerAdapter.java:71,79] — `pinScenarioForWorkflowRun` + scenario resolution.
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/workflow/PrOutputOrchestrationIT.java] and [.../ImplementationPlanOrchestrationIT.java] — harness + seed patterns to copy.
- [Source: deliveryline-backend/src/main/resources/application.yml:208-226] — prod `auto-dispatch: true`; [src/test/resources/application.yml:78-92] — test-profile OFF.
- Dependencies: 3b-1 (sub-stage threading — required, done), 3.11 (`dispatchPlanGeneration`), 3.12 (`validateAndEnrichPrOutput` + in-flight guard), 3.20 (`acceptImplementation` — the re-dispatch, done), 3.15 (GitHub PR link persistence), 1.5 (transition table). Parallel: 3b-3 (artifact availability on ingest — its production wiring is done in-test here, DD1).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- `mvnw integration-test -Dit.test=WaitingForReviewTwoDispatchOrchestrationIT` (Testcontainers/Failsafe, `-Djacoco.skip=true`, unit tier skipped via `-Dtest=Zzz`): **Tests run: 2, Failures: 0, Errors: 0** — BUILD SUCCESS. Logs confirm dispatch #1 `subStage=implementationPlan` → `onPlanStageSucceeded ... to=WaitingForReview`, then real `acceptImplementation ... transition WaitingForReview to Executing` → `acceptImplementation dispatchImplementation` → `dispatchImplementation entry ... subStage=prOutput` (idempotencyKey `pr-output-dispatch:...:2`), and the duplicate-dispatch `in-flight no-op`.
- Regression `mvnw integration-test -Dit.test=SpecStageOrchestrationIT,ImplementationPlanOrchestrationIT,PrOutputOrchestrationIT`: **Tests run: 10, Failures: 0, Errors: 0** — BUILD SUCCESS (no production change, no regression).
- `mvnw spotless:check`: exit 0 (clean after `spotless:apply` reflow).

### Completion Notes List

- **Test-only story, as predicted — ZERO production-code changes.** Confirmed by code trace (Task 1) that the plan-accept → PR-phase re-dispatch already exists (story 3.20 `TechnicalApprovalService.transitionAndDispatch:566-607` → `dispatchImplementation` in the MANDATORY tx) and that 3b-1's sub-stage threading + `ContextBundleService.deriveExecutionSubStage` flip the second dispatch to `PR_OUTPUT`. No new trigger / listener / hook added (the #1 disaster to prevent). Net change = one new IT.
- **Deliverable:** `WaitingForReviewTwoDispatchOrchestrationIT` with 2 tests:
  1. `fullWalkSpecToPrOutputReachesCompletedViaTwoDispatches` — the headline walk `approveSpec → dispatch #1 (plan) → WaitingForReview (no prOutput, no github_pr link) → acceptImplementation(plan) → dispatch #2 (prOutput) → WaitingForReview → acceptImplementation(prOutput) → Completed`, with AC3 sub-stage routing + AC5 link gating + the logging-task ListAppender pins (AC1/2/3/5 + logging).
  2. `duplicatePlanAcceptOrPrDispatchWhileInFlightIsIdempotentNoOp` — AC4: both a direct `dispatchImplementation` and a same-key duplicate `acceptImplementation(plan)` while dispatch #2 is `queued` are no-ops (count stays 2, run stays `Executing`).
- **DD1 (availability):** drove the REAL `ArtifactOperationService.markAvailable` against each ingested artifact (the pending op row exists from `recordOperation`; the pending artifact row carries no `storage_ref`/checksum until `markAvailable`, so the helper writes its own payload+ref+SHA-256). Defensive skip if 3b-3 has already promoted it to `available`.
- **DD2 (github_pr link):** seeded the active `github_pr` link via a direct `integration_links` insert rather than `IntegrationLinkService.linkGitHubPr` — that call resolves the PR through the `github-mock`/`github-real` GitHubAdapter, which is not on the classpath under `{test, linear-mock}`. The link makes `assertPrLinkPresentAndMatches` pass (it compares the active link's `external_ref` to itself). The enrich-persists-link path stays covered by `RunnerBrokerUnitTest`/`RepositoryWorkspaceServiceIT`/`IntegrationLinkGitHubPrFoundationContract` (not duplicated, AC5).
- **Versions read live** (`currentVersions` helper) mirroring `ApprovalVersionBinder` (artifact.version + linked runner execution's `context_bundle_version`, bootstrap→1) so the accept command's version binding is robust, not hard-coded (observed plan=v1/bundle v1, prOutput=v1/bundle v2).
- **AC7 honored:** no new `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema, config property, REST endpoint, ArchUnit rule, or transition-table edge.
- **Remaining before merge:** verify on Linux/Docker CI (`[[verify-ci-fixes-in-clean-env]]`, `[[wsl-linux-ci-reproduction]]`); recommend code-review with a different LLM.

### File List

- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WaitingForReviewTwoDispatchOrchestrationIT.java` (new)

### Change Log

| Date | Change |
|---|---|
| 2026-06-16 | Story 3b.2 drafted: verification + headline two-dispatch orchestration IT. Open question resolved by code trace — the plan-accept → PR-phase re-dispatch ALREADY exists (3.20, `TechnicalApprovalService:604-605`); story is test-only. |
| 2026-06-16 | Implemented `WaitingForReviewTwoDispatchOrchestrationIT` (2 tests: full walk to Completed + in-flight idempotency no-op). ZERO production code changed (verification-only, as predicted). New IT 2/2 green; sibling orchestration ITs 10/10 green (no regression); Spotless clean. Status → review. |

| 2026-06-16 | Code review (bmad-code-review, 3 adversarial layers): 1 decision → patch, 2 patches, 1 defer, 8 dismissed (false positives verified vs source — notably the "Critical cleanDatabase FK-order" claim, which had RESTRICT's direction backwards). Applied 3 test patches to `WaitingForReviewTwoDispatchOrchestrationIT`: AC5 gate-negative (`ARTIFACT_PR_LINK_MISMATCH` on no-link accept), AC3 direct gate-predicate assertion (`deriveExecutionSubStage` flips IMPLEMENTATION_PLAN→PR_OUTPUT), AC1 re-checkpoints (executionStatus + final count==2). IT 2/2 green (Failsafe/Testcontainers), Spotless clean. Status → done. |

## Review Findings

_Code review 2026-06-16 (bmad-code-review; 3 parallel adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor). Diff = 1 new test file, zero production changes — independently confirmed. 8 findings dismissed as false positives after source verification (notably the Edge Hunter's "Critical cleanDatabase FK-order violation" — `ON DELETE RESTRICT` blocks deleting the **parent** `workflow_events` while children exist; the test deletes children `artifact_operations`/`artifacts` first, which is correct FK-safe order, and the green test run confirms it; and the "runner queue table not cleaned" claim — the queue is `runner_executions` rows, which cleanup deletes)._

- [x] [Review][Patch] AC5 gate-negative case missing — FIXED. Added a gate-negative assertion: the `prOutput` accept with NO active `github_pr` link now fails closed with `DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH` (the gate throws before any approval row / transition, so the run stays at `WaitingForReview` and the versions stay valid), then succeeds once the link is seeded. Distinct idempotencyKey isolates the two attempts. (The "binds to correct ref" half stays un-provable — see the Defer item on the self-comparing production gate.) [WaitingForReviewTwoDispatchOrchestrationIT.java]
- [x] [Review][Patch] AC3 enrich sub-stage gate not directly asserted — FIXED. Now asserts the exact gate predicate `ContextBundleService.deriveExecutionSubStage(runId)` directly: `IMPLEMENTATION_PLAN` after dispatch #1 (gate skipped) and `PR_OUTPUT` after the plan accept (gate active on dispatch #2 only). [WaitingForReviewTwoDispatchOrchestrationIT.java]
- [x] [Review][Patch] Headline test drops two AC1 re-checkpoints — FIXED. Re-asserts `executionStatus == "completed"` after dispatch #2 and re-pins `executionCount == 2` after reaching `Completed`. [WaitingForReviewTwoDispatchOrchestrationIT.java]
- [x] [Review][Defer] Production PR-link "match" gate compares a ref to itself — `assertArtifactPrLinkMatches` → `IntegrationLinkService` (~:596-600) compares `active.externalRef()` to itself, so a mismatched artifact-claimed PR ref can never be detected by the gate. Real but **pre-existing** (3.12/3.23 code), not introduced by this test-only story. — deferred, pre-existing
