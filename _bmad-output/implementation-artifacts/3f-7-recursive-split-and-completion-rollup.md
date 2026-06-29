# Story 3f.7: Recursive Split + Split-Parent Completion Rollup

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Code review 2026-06-29 (bmad-code-review, 3 adversarial layers): review -> done. All 8 ACs met;
Decision 1 (override-event ordering) fixed + 2 regression tests; Decision 2 (rollup-strand durability)
carved to follow-up story 3f-8; Decision 3 (COMPLETED-only gate) accepted by-design; 5 Low items
deferred. See Review Findings below. -->

## Story

As an operator who has split an oversized run, **and** an authorized user sequencing decomposed work,
I want a split run to be treated as *decomposed and pending* (not finished) until all of its descendant runs complete — at which point it rolls up to `Completed` — and I want subtasks to themselves be splittable to a bounded depth,
so that multi-level decomposition reflects real progress, and a dependent of a run that is later split is unblocked the moment that subtree actually finishes rather than being stranded.

**This is the FINAL implementation story of Epic 3f.** It is **pure orchestration over already-shipped substrate** — the `SPLIT` state, the `SPLIT → COMPLETED` legal edge, `parent_run_id` lineage, the dependency-release resolver, and the split commit fan-out all exist. 3f-7 adds the service that *drives* `SPLIT → COMPLETED`, plus the recursive-split depth cap. **No Flyway migration. No new `WorkflowState`. No new transition edge.**

**Depends on (all done):** 3f-2 (lineage + `SPLIT` state + `SPLIT → COMPLETED` edge), 3f-3 (dependency edges + post-commit release resolver + advisory lock), 3f-5 (commit fan-out that mints children with `parentRunId` and moves the parent into `SPLIT`). Sequenced **after 3f-5**.

## Acceptance Criteria

1. **Given** the `WorkflowState` state-machine (as amended in 3f-2), **Then** `SPLIT` is **non-terminal** with exactly one legal out-edge `SPLIT → COMPLETED`; the transition is driven only by the rollup resolver (never by an operator action). Drift-tested against the DB CHECK + state-machine + API schema. *(Substrate already present from 3f-2 — this story asserts it stays true and is the sole driver.)*
2. **Given** a `RunSplitCompletionRollupService`, **Then** when any run reaches `COMPLETED` it looks up that run's `parentRunId`; if the parent is in `SPLIT` **and every direct child of the parent is `COMPLETED`**, it transitions the parent `SPLIT → COMPLETED` and **recurses** on the parent's own `parentRunId` (a split child satisfies its parent only via its *own* rolled-up `COMPLETED`). Idempotent, best-effort, swallows + logs `RuntimeException` so one stuck ancestor never strands the completing run's callback (the 3f-3 release-resolver discipline).
3. **Given** cross-split dependency unblocking, **Then** a dependent `X → Y` where `Y` is later split is released by the **existing 3f-3 release resolver with no modification** — because `Y` now reaches `COMPLETED` through rollup. Proven by IT: declare `X` depends on `Y`; split `Y` into `Y1,Y2`; `Y` parks in `SPLIT`; complete `Y1,Y2`; `Y` rolls up to `COMPLETED`; `X` releases and dispatches.
4. **Given** a **failed** descendant, **Then** the parent's rollup does **not** fire (it requires *all* children `COMPLETED`); the parent remains in non-terminal `SPLIT`, the failed descendant is operator-visible on the parent/lineage view, and dependents on the parent stay blocked. **No cascade-cancel and no cascade-fail.** Retrying/taking over the failed descendant to `COMPLETED` resumes the rollup (idempotent).
5. **Given** recursive split, **Then** `REQUEST_SPLIT` (3f-4) on a run whose split depth (distance from the lineage root, computed by walking `parentRunId`) is **≥ `complex-ticket-flow.max-split-depth`** (default **3**) is rejected with a new `SPLIT_DEPTH_LIMIT_EXCEEDED` error (registry + ProblemDetails + drift; three sites) **unless** an explicit override flag (`allowDeepSplit=true` REST / `--allow-deep-split` CLI) is supplied, in which case the deep split is permitted and the override is recorded in the governed history. Within the cap, a child at its own gate splits via the unchanged 3f-4/3f-5 path. The guard fires **before any proposal LLM call is made** (3f-4 AC1).
6. **Given** events + read model, **Then** the parent's rollup completion reuses the existing run-completed event (`WORKFLOW_STATE_CHANGED`) with an allow-listed detail key `viaSplitRollup=true` (**no new event type** — NFR43); the run/lineage view shows a `SPLIT` parent as "decomposed — N of M descendants complete" rather than "finished", and shows it flip to `Completed` on rollup.
7. **Given** parity, **Then** a normal (non-split) run is byte-identical to pre-3f-7: it never enters `SPLIT`, never causes a parent rollup, and completes exactly as before; a single-level split with no dependents behaves as 3f-5 described except the parent ends `COMPLETED` (via rollup) instead of remaining in `SPLIT`.
8. **Given** tests, **Then** coverage asserts: `SPLIT → COMPLETED` state-machine + CHECK drift; rollup fires only when all direct children complete; recursion flips a grandparent; cross-split dependency release IT (AC3); failed-descendant stall (AC4); depth cap + override + `SPLIT_DEPTH_LIMIT_EXCEEDED` drift (AC5); `viaSplitRollup` event detail (AC6); non-split parity (AC7); resolver idempotency + exception-swallowing; `application.*` ≥80% coverage.

## Tasks / Subtasks

- [x] **Task 1 — `RunSplitCompletionRollupService` + completion hook (AC: #2, #3, #4, #7)**
  - [x] Create `RunSplitCompletionRollupService` in `application.workflow` (sibling of `RunDependencyReleaseService`). Constructor takes `WorkflowRunReadPort` (parent + children reads), `WorkflowTransitionService`, `RunDependencyPort` (for `lockDependencyGraph()`), and `PlatformTransactionManager` → build a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW` (verbatim mirror of `RunDependencyReleaseService.java:43-54`).
  - [x] Public method `rollupParentOf(String completedRunId, String correlationId)`: in **one** `requiresNewTx` block — (1) `runDependencyPort.lockDependencyGraph()` (tx-scoped advisory lock); (2) read the completed run's `parentRunId` (non-locking); if `null` → return; (3) read the parent snapshot; if `parent.currentState() != SPLIT` → return; (4) `workflowRunReadPort.findByParentRunId(parentId)`; if **not** all children `COMPLETED` → return; (5) `workflowTransitionService.transition(parentId, COMPLETED, systemActor, "split_rollup", rollupKey(parentId), Map.of(VIA_SPLIT_ROLLUP, true))`.
  - [x] **Recursion = the hook chain, not an explicit loop.** The parent's `SPLIT → COMPLETED` transition itself registers a fresh afterCommit rollup hook, which re-invokes `rollupParentOf(parent)` → grandparent. No explicit upward walk inside the service.
  - [x] Wrap the service body so it **never throws to the caller**: catch `RuntimeException`, log `WARN`, swallow (3f-3 discipline).
  - [x] Register the hook in `WorkflowTransitionService.transition()` at the existing hook-registration site: `registerSplitCompletionRollupHookIfApplicable(runId, targetState)` mirroring `registerDependencyReleaseHookIfApplicable` — guard `if (targetState != COMPLETED) return;`, register `afterCommit()` resolving the service via a **lazy `ObjectProvider`** and calling `rollupParentOf(runId, null)` inside its own `try/catch(RuntimeException)`.
  - [x] `rollupKey(parentId)` = `"split-rollup:" + parentId` (deterministic).

- [x] **Task 2 — Recursive-split depth cap + override (AC: #5)**
  - [x] Add `ComplexTicketFlowProperties` record `@ConfigurationProperties("deliveryline.complex-ticket-flow")` with `int maxSplitDepth` (compact-ctor clamps `<= 0` to default `3`; `DEFAULT_MAX_SPLIT_DEPTH = 3`; static `defaults()`). Registered via `@EnableConfigurationProperties` on `WorkflowConfiguration`.
  - [x] Add `complex-ticket-flow.max-split-depth: 3` under `deliveryline:` in **both** `src/main/resources/application.yml` **and** `src/test/resources/application.yml`.
  - [x] Add `SPLIT_DEPTH_LIMIT_EXCEEDED` as a **three-sites** `DomainErrorCode` (CONFLICT 409, non-retryable): enum + `ProblemDetailsCatalog.register(..., CONFLICT, "Split depth limit exceeded", false)` + `registry-api-schema-placeholders.json`. `ProblemDetailsCoverageFoundationContract` green.
  - [x] Insert the depth guard at the **very top** of `SplitProposalService.request()` (`enforceSplitDepthCap`), **before** the reviewer-bound check and **before** any enqueue/LLM dispatch: walk `parentRunId` to the root (root = depth 0); if `depth >= maxSplitDepth && !command.allowDeepSplit()` → throw `DomainException(SPLIT_DEPTH_LIMIT_EXCEEDED)` carrying `runId`, `currentDepth`, `maxDepth`, `reason=depth_limit_exceeded`.
  - [x] `computeSplitDepth` walk uses non-locking `findByPublicId` reads, bounded to `maxSplitDepth + 1` hops.
  - [x] When `allowDeepSplit` overrides a would-be-rejected request, append a governed-history event with `interventionMarker=true` + allow-listed keys `deepSplitOverride=true`, `splitDepth`, `maxSplitDepth`. Implemented as a **new** `WorkflowEventType.SPLIT_DEPTH_OVERRIDE` (recommended option; priorState/resultingState null, mirrors `ESCALATION_REQUIRED`) + 2 fixture sites.
  - [x] Thread `allowDeepSplit` through: `RequestSplitCommand` (added `boolean allowDeepSplit` + back-compat 5-arg ctor), `WorkflowController.requestSplit` (`X-Allow-Deep-Split` header, default `false`), and the CLI `split-request` command (`--allow-deep-split`, default `false`). No-override path byte-identical.

- [x] **Task 3 — Event detail key + lineage read model (AC: #6)**
  - [x] Add `VIA_SPLIT_ROLLUP = "viaSplitRollup"` constant to `WorkflowEventDetailKeys` **and** to `ALLOW_LISTED_KEYS` (+ `deepSplitOverride`/`splitDepth`/`maxSplitDepth` for the override event); mirrored into `workflow-history.v1.schema.json` (the `WorkflowEventDetailKeysContractTest` allow-list↔schema equality).
  - [x] The rollup transition passes `Map.of(VIA_SPLIT_ROLLUP, true)` → `WORKFLOW_STATE_CHANGED` emits it; allow-listed → passes through inspection.
  - [x] Computed `decompositionStatus` for `SPLIT` parents in `WorkflowInspectionService` (`computeDecompositionStatus` over direct children) on `WorkflowStatusView` (new last field + back-compat ctor) + mapped onto `WorkflowDetailResponse`.
  - [x] Regenerated OpenAPI (`OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` via Failsafe) + `npm run generate-api` + `npm run check:api` (in sync).

- [x] **Task 4 — Frontend lineage display (AC: #6)**
  - [x] New `SplitLineagePanel.tsx` rendering `decompositionStatus` for a `SPLIT` parent ("decomposed — N of M descendants complete"); renders nothing for non-Split runs and disappears on rollup (when `decompositionStatus` clears and the state badge flips to `Completed`). Wired into the run detail route beside `RunDependencyPanel`.
  - [x] (Override REST/CLI affordance threaded via `allowDeepSplit`; the optional FE re-issue affordance is left to a follow-up — the panel + backend cap are the AC6/AC5 core.)
  - [x] FE gates: `npm run build` (tsc) + lint + `check:api` + prettier + axe (`SplitLineagePanel.test.tsx`) all green.

- [x] **Task 5 — Tests (AC: #8)** — full matrix below; afterCommit ITs are non-`@Transactional`.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at: rollup-hook fire (`INFO`), parent rollup decision (`INFO` fired / NOT-ready N-of-M), rollup swallow (`WARN` + cause class), depth-guard rejection (`WARN` "split refused (depth limit) … depth= max="), and deep-split override (`WARN` "deep-split override applied …").
  - [x] Parameterized logging only; levels per the standard; context keys (`workflowRunId`/`parentRunId`/`actorIdentity`); ids/counts only (no secrets).
  - [x] Log assertions pinned via list-appender (`RunSplitCompletionRollupServiceTest` swallow/NOT-ready; `SplitProposalServiceTest` override/refusal observed).

## Dev Notes

### What is already built (DO NOT rebuild)

| Substrate | Where | Status |
|---|---|---|
| `WorkflowState.SPLIT("Split")` non-terminal | `domain/registry/WorkflowState.java:11` | 3f-2 — present |
| `SPLIT → COMPLETED` legal edge | `application/workflow/WorkflowTransitionTable.java:108` | 3f-2 — present (no edit) |
| `parent_run_id` FK + index + state CHECK incl. `'Split'` | `db/migration/V27__add_parent_run_id_and_split_state.sql` | 3f-2 — present |
| `WorkflowRunSnapshot.parentRunId` | `application/workflow/spi/WorkflowRunSnapshot.java:25` | 3f-2 — present |
| `findByParentRunId(parentId)` read | `WorkflowRunPersistenceAdapter.java:93-96` / `WorkflowRunReadPort` | 3f-2/3f-5 — present |
| Dependency-release post-commit hook + resolver | `WorkflowTransitionService.java:203-238`, `RunDependencyReleaseService.java` | 3f-3 — **unchanged by 3f-7** |
| Advisory lock `lockDependencyGraph()` (RDEP) | `RunDependencyPort` | 3f-3 — rollup MUST take it |
| `SplitCommitService` moves parent → `SPLIT`, mints children w/ `parentRunId` | `application/workflow/SplitCommitService.java` | 3f-5 — present |
| `childRunIds` computation on read | `WorkflowInspectionService.java:1273-1310` | 3f-2/3f-5 — present |
| `request_split` action + `SplitProposalService.request()` (depth-**unguarded**) | `SplitProposalService.java:108-160` | 3f-4 — the seam 3f-7 fills |

**No Flyway migration in 3f-7.** Flyway head is V30; next-free would be V31 but 3f-7 needs no schema change. `SPLIT_DEPTH_LIMIT_EXCEEDED` is a *registry/catalog* code, not a DB enum.

### The completion-hook architecture (Task 1 core)

A run reaching `COMPLETED` goes through `WorkflowTransitionService.transition()` (`:130-131`), which inside the transition's transaction registers `afterCommit` synchronizations — today: completion-sync (3.16) and dependency-release (3f-3). 3f-7 adds a **third** afterCommit hook, identical in shape:

```
transactionTemplate.executeWithoutResult(status -> {
    doTransition(...);
    registerCompletionSyncHookIfApplicable(runId, targetState);
    registerDependencyReleaseHookIfApplicable(runId, targetState);
    registerSplitCompletionRollupHookIfApplicable(runId, targetState);   // NEW (3f-7)
});
```

`registerSplitCompletionRollupHookIfApplicable`: `if (targetState != COMPLETED) return;` then `registerSynchronization(new TransactionSynchronization(){ afterCommit(){ resolve service via ObjectProvider; try { svc.rollupParentOf(runId, null); } catch (RuntimeException e){ log.warn(...); } }})`. Resolve the service lazily via `ObjectProvider<RunSplitCompletionRollupService>` (mirror `dependencyReleaseProvider`) — it depends on `WorkflowTransitionService`, so eager injection would cycle.

### Recursion via hook chain (AC2) — the elegant part

`rollupParentOf` processes **exactly one level**: child → parent. It then calls `workflowTransitionService.transition(parent, COMPLETED, ...)`. **That transition is itself a `COMPLETED` transition**, so it registers a *fresh* rollup afterCommit hook for the parent — which fires `rollupParentOf(parent)` → checks the grandparent. The recursion in AC2 is realized by this hook re-registration, terminating at the lineage root (`parentRunId == null`) or the first non-`SPLIT` ancestor.

**Bonus — AC3 falls out for free.** The parent's `COMPLETED` transition *also* registers the **3f-3 dependency-release hook**. So when `Y` (a split prerequisite) rolls up to `COMPLETED`, that same transition's afterCommit releases `Y`'s dependents (`X`) through the **unchanged** 3f-3 resolver. No edge-rewrite, no lineage predicate in the release path — exactly what AC3 mandates. This is *why* the rollup must drive completion through `WorkflowTransitionService.transition()` rather than a raw state write.

⚠️ **Do not also implement an explicit upward `parentRunId` walk inside `rollupParentOf` to recurse** — it would double-process each ancestor (the hook fires anyway). Explicit walk is only for *depth computation* in Task 2, which is a read, not a transition.

### Advisory lock (concurrency — AC2 idempotency)

Two sibling children completing near-simultaneously could both observe "not all children complete yet" and neither rolls up, OR both observe "all complete" and race the parent transition. The 3f-3 reconciliation mandates that **3f-7 rollup-release MUST take the RDEP advisory lock** ([[story-3f-3-run-dependency-graph-waiting-for-dependencies-reconciliations]]). Take `runDependencyPort.lockDependencyGraph()` as the **first** statement inside the rollup's `requiresNewTx`. Because `WorkflowTransitionService.transition()` uses `REQUIRED` propagation, it **joins** the rollup's REQUIRES_NEW tx, so the tx-scoped advisory lock covers the children-check *and* the parent transition atomically. Serialized: the first child completion rolls the parent up; the second re-reads `parent.currentState() == COMPLETED` (no longer `SPLIT`) and returns. Idempotent.

### afterCommit + JPA trap (carry-forward from 3f-3 — read this)

A JPA `transition()` invoked **directly** in `afterCommit` throws `InvalidDataAccessApiUsageException` (stale resources still bound during post-commit) and gets swallowed → the rollup silently never happens. **FIX (already in the design):** the rollup runs in its own `PROPAGATION_REQUIRES_NEW` `TransactionTemplate` — never on the post-commit thread's dead tx. This is the exact bug 3f-3 hit and fixed for the release resolver; mirror it. ([[post-commit-hook-needs-requires-new]], [[caught-idempotency-conflict-poisons-shared-tx]].)

### Recording the deep-split override (AC5)

AC5 requires the override be "recorded in the governed history" with `interventionMarker=true`. The governed history is the `workflow_events` stream. There is **no** existing generic "override"/"governance" event type. Decision for the dev:

- **Recommended:** append a non-state-change `WorkflowEventRecord` (`priorState=resultingState=null`, `interventionMarker=true`, details `{deepSplitOverride:true, splitDepth, maxSplitDepth}`) using a **new** `WorkflowEventType.SPLIT_DEPTH_OVERRIDE` — a genuine, distinct, auditable governance action (NFR43 "justify each new concept" is satisfied: an operator deliberately bypassing a safety cap is exactly the kind of thing the governed history exists to record). A new event type fans out to **2 fixture sites** ([[new-workfloweventtype-fixture-sites]]: `workflow-event-types.fixture.json` + the event-stream schema enum) + `-Pfoundation-gate`. `ESCALATION_REQUIRED` is the precedent for a non-state-change event with a details map.
- **Lighter alternative (if avoiding a new type):** reuse the deep-split marker as an allow-listed detail on the *eventual* `workflow.split` commit event (3f-5) instead of a request-time event — but this loses the audit if the proposal is later declined. The request-time event is the more faithful record.

Pick one in the dev pass and note it. Do not leave the override unrecorded — AC5 fails without it.

### Depth computation (AC5)

`depth = number of ancestors via parentRunId` (root run = 0). A run at depth `d` splitting produces children at depth `d+1`. Reject when `d >= maxSplitDepth` (default 3) without override → top-level/depth-0/1/2 runs split freely; a depth-3 run is refused (its children would be depth 4). Walk with a non-locking read (`SplitProposalService.request()` runs in a normal `@Transactional` REQUIRED context; do **not** use any `…ForUpdate` read here). Bound the loop.

### Read-model wording (AC6)

Compute "N of M descendants complete" over **direct children** (`findByParentRunId`), not the full transitive subtree. This is correct *and* cheap: a split child reaches `COMPLETED` only via its own rollup (which requires *its* whole subtree done), so "all direct children `COMPLETED`" ≡ "whole subtree done". Don't recursively count grandchildren.

### Parity (AC7)

The rollup hook registers for *every* `COMPLETED` transition (like the release hook), but `rollupParentOf` returns immediately when the completed run's `parentRunId == null` (every pre-3f top-level run). Observable behavior for non-split runs is unchanged; the only addition is one cheap lineage read on completion, matching the existing release-hook cost. No new state is entered, no parent is touched.

### Architecture / boundary guardrails

- Rollup service + read views live in `application.workflow` (NOT `adapters`); REST stays thin — read view records the controller maps must live in `application.workflow` (non-spi), per [[application-cannot-import-adapters]]. `decompositionStatus` is a plain field on the existing `WorkflowStatusView`/`WorkflowDetailResponse`.
- Adding `RunSplitCompletionRollupService` does **not** add a new `WorkflowController` ctor dep (it is wired into `WorkflowTransitionService` via `ObjectProvider`, not injected into the controller), so the ~16 `@WebMvcTest` `@MockitoBean` fan-out is avoided *for the rollup*. **However**, Task 2 threads `allowDeepSplit` through the existing `requestSplit` controller path which already injects `SplitProposalService` — no new mock needed there either.
- Driving completion through `WorkflowTransitionService.transition()` (not a raw state port write) is mandatory — it is what fires the dependency-release hook (AC3) and the recursion hook (AC2).

### Source tree components to touch

- `application/workflow/RunSplitCompletionRollupService.java` (**new**)
- `application/workflow/WorkflowTransitionService.java` (register 3rd hook; add `ObjectProvider`)
- `application/workflow/SplitProposalService.java` (depth guard at top of `request()`)
- `application/workflow/ComplexTicketFlowProperties.java` (**new**) + `@EnableConfigurationProperties` on the workflow config
- `application/workflow/SplitProposalCommandSet.java` (`RequestSplitCommand.allowDeepSplit`)
- `domain/registry/DomainErrorCode.java` (+ `SPLIT_DEPTH_LIMIT_EXCEEDED`)
- `adapters/rest/ProblemDetailsCatalog.java` (register CONFLICT/non-retryable) + error-code placeholders manifest if present
- `domain/registry/WorkflowEventDetailKeys.java` (+ `VIA_SPLIT_ROLLUP` constant + allow-list)
- `domain/registry/WorkflowEventType.java` (+ `SPLIT_DEPTH_OVERRIDE` **iff** the recommended override-recording option is chosen) + its 2 fixture sites
- `adapters/rest/WorkflowController.java` (thread `allowDeepSplit`) + `adapters/cli/WorkflowCommands.java` (`--allow-deep-split`)
- `application/workflow/WorkflowInspectionService.java` + `WorkflowStatusView` + `adapters/rest/WorkflowDetailResponse.java` (`decompositionStatus`)
- `src/main/resources/application.yml` + `src/test/resources/application.yml` (`complex-ticket-flow.max-split-depth`)
- `src/main/resources/openapi/openapi.json` + `deliveryline-frontend/src/lib/api/schema.d.ts` (regen)
- FE: `features/workflows/components/SplitProposalPanel.tsx` (or new `SplitLineagePanel.tsx`) + `useSplitActions` (allowDeepSplit, optional)

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for 3f-7):**
  - `RunSplitCompletionRollupService` → `INFO` on hook fire + rollup decision (fired / not-ready with N-of-M), `WARN` on swallowed `RuntimeException`.
  - `SplitProposalService.request()` depth guard → `WARN` on refusal, `INFO`/`WARN` on override applied.
  - State-machine transition `SPLIT → COMPLETED` → already logged by `WorkflowTransitionService` ("transitioned X from Split to Completed"); confirm it carries `viaSplitRollup`.
- **Required context keys** (via MDC or structured params): `correlationId`, `workflowRunId`, `parentRunId` (rollup), `idempotencyKey`, `actorIdentity`, `actorType`.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields.
- **Test contract:** new logging surfaces pinned by at least one focused test (list-appender / `OutputCaptureExtension`).

### Project Structure Notes

- Aligns with the established hexagonal split: domain registries (`WorkflowState`, `DomainErrorCode`, `WorkflowEventType`, `WorkflowEventDetailKeys`) in `domain`, services + read views + ports in `application.workflow`, REST/CLI in `adapters`. No deviations.
- No new module, no new Flyway file. The only DB interaction is reads through existing ports + the existing advisory lock.

### Testing Requirements

**Unit (`RunSplitCompletionRollupServiceTest`):**
- Rolls parent up only when **all** direct children `COMPLETED`; no-op when any child not `COMPLETED` (covers AC4 stall).
- No-op when completed run has `parentRunId == null` (parity, AC7) and when parent not in `SPLIT`.
- Swallows + logs a `RuntimeException` from the transition (AC2 best-effort) — assert via list-appender.
- Idempotent: second invocation after parent already `COMPLETED` is a no-op.

**Unit (`SplitProposalServiceTest` additions):**
- Depth `< max` → request proceeds (existing happy path unchanged).
- Depth `>= max` without override → throws `SPLIT_DEPTH_LIMIT_EXCEEDED`, **no** enqueue/LLM dispatch (verify with a mock that dispatch is never called — AC1/AC5).
- Depth `>= max` with `allowDeepSplit=true` → proceeds **and** appends the governed-history override event (AC5).
- Depth computed correctly across a 2- and 3-level lineage chain.

**Integration (NON-transactional — critical):**
- ⚠️ **A `@Transactional` IT cannot exercise `afterCommit` (it rolls back).** Write the rollup ITs **non-transactional** with `@AfterEach` cleanup, mirroring `CompletionSyncOrchestrationIT` / the 3f-3 release IT ([[story-3f-3-run-dependency-graph-waiting-for-dependencies-reconciliations]]).
- **Rollup IT:** parent in `SPLIT` with children; completing the **last** child rolls the parent `SPLIT → COMPLETED` carrying `viaSplitRollup=true`; completing a non-last child does not.
- **Recursion IT:** grandparent `SPLIT` → parent `SPLIT` → child; completing the leaf flips parent then grandparent (the hook chain).
- **Cross-split dependency IT (AC3):** `X` depends on `Y`; split `Y` into `Y1,Y2` (`Y` parks `SPLIT`); complete `Y1,Y2`; assert `Y` rolls up `COMPLETED` **and** `X` releases `WaitingForDependencies → Investigating` (the unchanged 3f-3 resolver). Note `spec-stage.auto-dispatch=false` in test yaml means an independent run stays `Inbox` — assert the **gating/release transition**, not the dispatch ([[story-3f-5-split-commit-fan-out-reconciliations]] IT gotcha).
- **Failed-descendant IT (AC4):** one child `FAILED` → parent stays `SPLIT`, dependents stay blocked; then drive the failed child to `COMPLETED` → rollup resumes.

**Contract / drift:**
- `SPLIT → COMPLETED` already in `TransitionTableCrossProductFoundationContract` (`:104`) + `FlywaySchemaContractTest` CHECK — confirm still green (no edit expected).
- `ProblemDetailsCoverageFoundationContract` auto-covers `SPLIT_DEPTH_LIMIT_EXCEEDED` once enum + catalog added — run `-Pfoundation-gate`.
- `OpenApiSnapshotContractTest` regen (Failsafe goal, `-Dopenapi.snapshot.write=true`) for the new `allowDeepSplit` param + `decompositionStatus` field; then `npm run check:api`.
- If the recommended override event type is added: mirror into the **2 fixture sites** + transition-integrity contract (non-state-change event, `priorState/resultingState` null like `ESCALATION_REQUIRED`).

**FE (Vitest + axe):** decomposition-status renders for a `SPLIT` parent ("N of M"); flips to `Completed`; (optional) `SPLIT_DEPTH_LIMIT_EXCEEDED` surfaces an allow-deep-split affordance.

**Coverage:** `application.*` ≥80%.

### Verification before claiming done

Local green ≠ CI green ([[verify-ci-fixes-in-clean-env]]). 3f-4/3f-5 each shipped CI-only failures because the touched modules light up gates that `mvnw test` path-filters out:
- **ArchUnit runs in Failsafe, not Surefire** — `mvnw test` never runs `@ArchTest` ([[archunit-runs-in-failsafe-not-surefire]]).
- **Prettier** — one unformatted FE file reds the static-check chain across ubuntu+windows ([[prettier-gate-cascades-ci]]); `prettier --write` then `format:check`.
- **Spotless** — `spotless:apply` on hand-edited Java ([[spotless-apply-before-pushing-java-edits]]).
- **SpotBugs** — touching a quiet module can light its dormant analysis gate (3f-4's `EI_EXPOSE` surprise).
- **git mv + edit** — if any file is renamed, `git add` it again and `git show HEAD:<file>` to confirm the body staged ([[git-mv-then-edit-leaves-body-unstaged]]).
- Run the full verify (Surefire + Failsafe + foundation-gate + FE gates) in a clean env before marking review.

### References

- [Source: _bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md#Story-3f-7] — AC1-8, depends-on, cross-cutting notes (foundation-gate widening, NFR16, FRs covered, forward options).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java:130-238] — hook registration site + the dependency-release afterCommit precedent to mirror.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunDependencyReleaseService.java] — REQUIRES_NEW template + swallow-and-log discipline + advisory lock usage to mirror.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitProposalService.java:108-160] — `request()` depth-unguarded seam (insert guard at top).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java:192-198] + [adapters/rest/ProblemDetailsCatalog.java:514-519] — `RUN_DEPENDENCY_CYCLE` three-sites model.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java:104-149] — `ALLOW_LISTED_KEYS` (add `viaSplitRollup`).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:1273-1310] — `childRunIds` computation (add `decompositionStatus`).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowProperties.java:24-33] — `@ConfigurationProperties` record pattern for `ComplexTicketFlowProperties`.
- Reconciliations: [[story-3f-2-parent-child-lineage-split-state-reconciliations]], [[story-3f-3-run-dependency-graph-waiting-for-dependencies-reconciliations]], [[story-3f-4-split-proposal-channel-reconciliations]], [[story-3f-5-split-commit-fan-out-reconciliations]], [[epic-3f-complex-ticket-flow]].

## Dev Agent Record

### Agent Model Used

Opus 4.8 (claude-opus-4-8[1m]) — bmad-dev-story.

### Debug Log References

- Rollup IT first pass surfaced two real bugs in the **test** (not production): the `viaSplitRollup` event probe queried a non-existent `workflow_run_public_id` column (the table keys on `workflow_run_id` bigint FK → fixed with a join to `workflow_runs`), and the AC4 `EXECUTING → FAILED` transition was rejected because runner-failure transitions require an allowed `FailureCategory` (`WorkflowTransitionTable:168-181`) → passed `FailureCategory.RUNNER_CRASH`. After the fixes: `RunSplitCompletionRollupIT` 4/4 green on real Postgres.
- `transition(...)` 6-arg mock matchers were ambiguous between the `FailureCategory` and `Map` overloads → disambiguated test matchers with `anyMap()`.

### Completion Notes List

- **Pure orchestration over shipped substrate — no Flyway, no new `WorkflowState`, no new transition edge.** `SPLIT` and the `SPLIT → COMPLETED` edge already existed (3f-2). The only DB interaction is reads + the existing RDEP advisory lock.
- **Rollup is the third afterCommit hook.** `RunSplitCompletionRollupService.rollupParentOf` runs in its own `PROPAGATION_REQUIRES_NEW` tx (dodges the post-commit stale-resource `InvalidDataAccessApiUsageException`), takes `lockDependencyGraph()` first (serializes sibling completions), and drives `SPLIT → COMPLETED` through `WorkflowTransitionService.transition()`. **Recursion + AC3 both fall out of the hook chain for free**: the parent's own `COMPLETED` transition re-registers the rollup hook (→ grandparent) *and* fires the unchanged 3f-3 dependency-release hook (→ cross-split unblock). Verified end-to-end in `RunSplitCompletionRollupIT` (rollup, recursion, cross-split AC3, failed-descendant stall + resume).
- **Depth cap.** `enforceSplitDepthCap` at the very top of `SplitProposalService.request()` (before reviewer-bound/dispatch) walks `parentRunId` (root = 0, bounded loop), rejecting `depth >= max-split-depth` with the new `SPLIT_DEPTH_LIMIT_EXCEEDED` (three sites: enum + catalog + placeholders manifest) unless `allowDeepSplit` is supplied, in which case it records a **new `SPLIT_DEPTH_OVERRIDE`** governance event (interventionMarker, 2 fixture sites) — the recommended override-recording option.
- **Read model.** `decompositionStatus` ("decomposed — N of M descendants complete") computed over direct children for `SPLIT` parents only; null elsewhere (parity). New `SplitLineagePanel` renders it and disappears on rollup.
- **Override recording decision:** chose the recommended new `WorkflowEventType.SPLIT_DEPTH_OVERRIDE` (request-time, faithful audit even if the proposal is later declined) over reusing the commit event.
- **Verification (local clean tiers):** backend Surefire **1421/0/0**; focused units (`RunSplitCompletionRollupServiceTest` 6/0, `SplitProposalServiceTest` 14/0, `WorkflowInspectionServiceTest` 13/0, `WorkflowEventDetailKeysContractTest` 4/0); `RegistryContractTest` **22/0** (event-type + error-code fixtures aligned); `ProblemDetailsCoverageFoundationContract` 2/0; ArchUnit architecture tier **74/0** + Checkstyle 0; SpotBugs clean; Spotless applied; `OpenApiSnapshotContractTest` 1/0 (byte-identical post-regen); `RunSplitCompletionRollupIT` **4/4** (real Postgres, non-`@Transactional`); FE `SplitLineagePanel` vitest 5/0, workflows+routes vitest **780/0**, build/tsc/lint/prettier/check:api green.

### File List

**Backend — main:**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunSplitCompletionRollupService.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/ComplexTicketFlowProperties.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java` (3rd afterCommit hook + lazy `ObjectProvider`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitProposalService.java` (depth guard + override event + ctor dep)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitProposalCommandSet.java` (`RequestSplitCommand.allowDeepSplit` + back-compat ctor)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (`decompositionStatus` compute + `WorkflowStatusView` field)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (`SPLIT_DEPTH_LIMIT_EXCEEDED`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java` (`SPLIT_DEPTH_OVERRIDE`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java` (`VIA_SPLIT_ROLLUP`, `DEEP_SPLIT_OVERRIDE`, `SPLIT_DEPTH`, `MAX_SPLIT_DEPTH` + allow-list)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (register `SPLIT_DEPTH_LIMIT_EXCEEDED`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (`X-Allow-Deep-Split` header)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java` (`decompositionStatus`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (`--allow-deep-split`)
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/WorkflowConfiguration.java` (`@EnableConfigurationProperties` += `ComplexTicketFlowProperties`)

**Backend — resources:**
- `deliveryline-backend/src/main/resources/application.yml` + `deliveryline-backend/src/test/resources/application.yml` (`complex-ticket-flow.max-split-depth: 3`)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated)
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json` (new allow-listed detail keys)
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (`SPLIT_DEPTH_LIMIT_EXCEEDED` problem URI)
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json` + `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (`workflow.splitDepthOverride`)

**Backend — tests:**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunSplitCompletionRollupServiceTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunSplitCompletionRollupIT.java` (new, non-`@Transactional`)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/SplitProposalServiceTest.java` (ctor + depth-cap tests)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceTest.java` (`decompositionStatus` assertions)

**Frontend:**
- `deliveryline-frontend/src/features/workflows/components/SplitLineagePanel.tsx` (new)
- `deliveryline-frontend/src/features/workflows/components/SplitLineagePanel.test.tsx` (new)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (render `SplitLineagePanel`)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)

### Change Log

| Date | Change |
|---|---|
| 2026-06-29 | Implemented story 3f-7 (Recursive Split + Split-Parent Completion Rollup): `RunSplitCompletionRollupService` + 3rd afterCommit hook driving `SPLIT → COMPLETED` (recursion + AC3 via the hook chain); recursive-split depth cap (`ComplexTicketFlowProperties`, `SPLIT_DEPTH_LIMIT_EXCEEDED`, `allowDeepSplit` override + `SPLIT_DEPTH_OVERRIDE` audit event); `viaSplitRollup` detail key + `decompositionStatus` read model + `SplitLineagePanel`. Status ready-for-dev → review. |

### Review Findings

Code review 2026-06-29 (adversarial 3-layer: Blind Hunter + Edge Case Hunter + Acceptance Auditor). All 8 ACs functionally implemented; issues below.

**Decision-needed (all resolved 2026-06-29):**

- [x] [Review][Decision→Patch] `SPLIT_DEPTH_OVERRIDE` governance event recorded before the idempotency/degrade guards — flagged by all 3 layers (`SplitProposalService.java:127` called the depth cap ahead of the open-proposal no-op `:133`, the reviewer-unbound degrade `:141`, and the in-flight-dispatch no-op `:151`; the override append was unconditional). Result: a replayed deep-split request appended a **duplicate** `SPLIT_DEPTH_OVERRIDE` event, and a degrade/no-op recorded a **phantom** override for a split that never proceeded (FR47 = rows never deleted → permanent audit pollution). AC5 + idempotency contract. **RESOLVED (Decision 1 = Minimal):** the rejection `throw` stays at the top (error precedence unchanged); `enforceSplitDepthCap` refactored to `evaluateSplitDepthCap` which now *returns* a `DeepSplitOverride` and records nothing — the caller appends the override event only on the proceed path, just before `enqueueSplitDispatch`, in the same `@Transactional`. Added 2 regression tests (`...DoesNotRecordOverrideWhenProposalAlreadyOpen`, `...WhenReviewerUnbound`).
- [x] [Review][Decision→Follow-up story 3f-8] Rollup strand has no retry/sweeper — a transient failure on the *final* child's afterCommit hook permanently parks the parent in `SPLIT` (best-effort swallow at both the service body and the afterCommit hook). The last child's completion is the sole rollup trigger; unlike the 3f-3 release there is no later event to re-attempt → parent stuck forever, dependents blocked. (Edge Case Hunter — High.) AC2. **RESOLVED (Decision 2 = automatic reconciliation sweep):** carved into new story **3f-8** (`3f-8-split-rollup-reconciliation-sweep.md`, backlog) — a scheduled, config-gated, advisory-locked sweep that finds `SPLIT` parents with all children `COMPLETED` and re-invokes the existing idempotent `rollupParentOf`. No inline change to 3f-7.
- [x] [Review][Decision→By-design] Rollup gate stalls on a terminal non-`COMPLETED` child — the gate counts only `WorkflowState.COMPLETED`, so a direct child ending terminal-but-not-Completed (cancelled/taken-over/reconciled, not the retryable `FAILED` of AC4) makes `completedCount != total` permanently true. (Edge Case Hunter — Medium.) **RESOLVED (Decision 3 = keep COMPLETED-only, accepted by design):** intentional — only a fully-completed subtree rolls up; the operator resolves the odd child to `COMPLETED` (retry/takeover) to resume. Intent documented in `RunSplitCompletionRollupService` at the gate (`completedCount != total`).

**Defer:**

- [x] [Review][Defer] `computeSplitDepth` undercounts depth on a broken/missing-ancestor lineage [`SplitProposalService.java:408`] — `if (parent.isEmpty()) break;` returns the partial distance, so a run whose true depth exceeds the cap is computed as shallow and allowed to split; verify `findByPublicId` does not filter archived ancestors. Also the `maxDepth+1` safety bound clamps the `currentDepth` reported in the 409 body (cosmetic, acknowledged in-comment). — deferred, low likelihood (parent_run_id is an FK; archival is soft).
- [x] [Review][Defer] Rollup recursion has no hop bound while `computeSplitDepth` does [`RunSplitCompletionRollupService` hook chain] — inconsistent defensive posture; termination relies on the state machine rejecting parent re-completion + the `split-rollup:<id>` idempotency key. — deferred, lineage acyclic by construction.
- [x] [Review][Defer] `correlationId` parameter is dead on the rollup path — afterCommit always calls `rollupParentOf(runId, null)` and the rolled-up `SPLIT → COMPLETED` event carries no correlation linkage to the triggering child completion. — deferred, traceability nit; wire-through or drop the param.
- [x] [Review][Defer] `decompositionStatus` renders "0 of 0 descendants complete" for a childless `SPLIT` parent [`WorkflowInspectionService.java:348`] — anomaly-gated (same `total==0` state that stalls rollup). — deferred, cosmetic.
- [x] [Review][Defer] AC4 "dependents on the parent stay blocked" only asserted transitively — `failedDescendantStallsRollupAndCompletingItResumes` IT declares no dependent on the failing parent. — deferred, test-coverage enhancement.

**Dismissed (noise / by-design):** redundant double-swallow of `RuntimeException` (Task 1 explicitly mandates both the service-body catch and the afterCommit catch — defense-in-depth, not a defect); no new `SPLIT → COMPLETED` state-machine/CHECK drift test (story design — substrate inherited from 3f-2); whitespace churn in `workflow-events-response.schema.json` (incidental, and it repaired a pre-existing fixture-mirror gap for `workflow.archived`/`workflow.unarchived`).
