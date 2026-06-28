# Story 3f.3: Run-Dependency Graph + WaitingForDependencies Gating

Status: done

<!-- 2026-06-27 bmad-create-story context-engine pass. Target sprint key: 3f-3-run-dependency-graph-and-waiting-for-dependencies-gating. -->

> READ FIRST - this is the dependency-gating substrate for Epic 3f. It adds a durable run-dependency DAG, the non-terminal `WaitingForDependencies` workflow state, declaration/read APIs, release-on-completion behavior, and blocked-run visibility. It does not create split proposals, approve splits, create child runs, mint subtickets, move a parent into `Split`, or implement split-parent rollup. Story 3f-5 consumes this service when committing a split. Story 3f-7 makes a split prerequisite eventually reach `Completed`, then this story's unchanged resolver releases its dependents.

## Story

As an operator,
I want to declare that one run can only start after other runs complete,
so that I can sequence decomposed work and the system holds dependent runs until their prerequisites are done.

## Acceptance Criteria

1. Given the current Flyway head is `V27__add_parent_run_id_and_split_state.sql`, then the next migration creates an additive `run_dependencies` join table with columns `run_id text not null`, `depends_on_run_id text not null`, `created_at timestamptz not null default now()`, primary key `(run_id, depends_on_run_id)`, FKs to `workflow_runs(public_id)` with `on delete restrict on update cascade`, a self-edge CHECK rejecting `run_id = depends_on_run_id`, and indexes for both lookup directions. Existing rows are unchanged and `FlywaySchemaContractTest` covers the table, FKs, CHECK, PK, and indexes.
2. Given the `WorkflowState` registry, then add non-terminal `WAITING_FOR_DEPENDENCIES("WaitingForDependencies")` using PascalCase wire value. Widen all three workflow-state CHECK constraints (`workflow_runs.current_state`, `workflow_events.prior_state`, `workflow_events.resulting_state`), update the API placeholder/OpenAPI allowable values, and update the transition table with `INBOX -> WAITING_FOR_DEPENDENCIES` and `WAITING_FOR_DEPENDENCIES -> INVESTIGATING`. No zero-dependency run enters this state.
3. Given dependency declaration, then `RunDependencyService` records edges only while both dependent and prerequisite are pre-execution. Dependent runs may be `Inbox` or `WaitingForDependencies`; prerequisites must not be `Executing`, `Completed`, `Failed`, `TakenOver`, `Reconciled`, `Paused`, or `Split`.
4. Given dependency declaration would introduce a cycle, then `RunDependencyService` rejects it with new three-sites error code `RUN_DEPENDENCY_CYCLE` (enum, `ProblemDetailsCatalog`, `registry-api-schema-placeholders.json` problem URI), mapped to HTTP 409 non-retryable and carrying `runId`, `dependsOnRunId`, and `reason=cycle_detected`.
5. Given gated dispatch, then a newly created or declared run with unmet prerequisites is parked in `WaitingForDependencies` and is not auto-dispatched. A run with zero prerequisites or all prerequisites already `Completed` follows the existing dispatch path and reaches `Investigating` normally. Existing top-level submit with no dependencies stays byte-identical except for additive schema/API state lists.
6. Given a run transitions to `Completed`, then `RunDependencyReleaseService` finds dependent runs, and for each dependent in `WaitingForDependencies` whose every prerequisite is now `Completed`, transitions it to `Investigating` and dispatches spec generation. Release is idempotent and best-effort: catch/log per-dependent `RuntimeException` so one failed dependent does not roll back or hide the completing run's transition.
7. Given a prerequisite fails, is taken over, is reconciled, remains split, or remains waiting/manual, then dependents stay in `WaitingForDependencies`. They are not auto-failed, not cascade-cancelled, and not released. The blocked state and failed/unfinished blocker are visible in read models and UI.
8. Given the read model and FE, then workflow detail exposes prerequisites, dependents, blocked-on ids/states, and an `isBlockedByDependencies`/equivalent boolean. `WaitingForDependencies` renders as an explicit waiting/blocked state in queue/detail badges. Allowed actions for a `WaitingForDependencies` run are view-only plus archive/unarchive where the existing archive rule applies; no spec/plan/manual/approval actions are exposed.
9. Given standalone use outside split, then REST and CLI can declare dependencies between existing runs and inspect them. REST endpoint shape: `POST /api/v1/workflows/{workflowRunId}/dependencies` with `dependsOnRunIds[]` and required `Idempotency-Key`; `GET /api/v1/workflows/{workflowRunId}/dependencies` returns prerequisites/dependents. CLI parity: `workflow dependencies add --run <runId> --depends-on <runId>[,<runId>]` and `workflow dependencies show --run <runId>`.
10. Given tests, then coverage asserts Flyway/registry/CHECK/state-machine drift; cycle guard; duplicate-edge idempotency; invalid self-edge at service and DB; dependent parks and is not dispatched; completing the last prerequisite releases and dispatches it; failed prerequisite remains blocked; zero-dependency parity; read model/REST/CLI/FE visibility; OpenAPI and `schema.d.ts` regeneration; `application.*` line coverage stays at or above 80%.

## Tasks / Subtasks

- [x] Task 1 - Add `V28` dependency schema and drift coverage (AC: 1, 10)
  - [x] Confirm the true next Flyway number before coding. As of this story creation, head is `V27__add_parent_run_id_and_split_state.sql`; use `V28__add_run_dependencies_and_waiting_for_dependencies.sql` unless a newer migration exists.
  - [x] Create `run_dependencies` with `run_id`, `depends_on_run_id`, `created_at`, PK `(run_id, depends_on_run_id)`, FKs to `workflow_runs(public_id)`, self-edge CHECK, and indexes for dependent/prerequisite lookups.
  - [x] In the same migration, widen all three workflow-state CHECK constraints to include `'WaitingForDependencies'`, copying the V20/V27 drop-and-readd style.
  - [x] Extend `FlywaySchemaContractTest` for the table, CHECK, PK/FKs, indexes, and state CHECK alignment.

- [x] Task 2 - Register `WaitingForDependencies` state and transitions (AC: 2, 8, 10)
  - [x] Add `WAITING_FOR_DEPENDENCIES("WaitingForDependencies")` to `WorkflowState`.
  - [x] Update `WorkflowTransitionTable.defaultTable()` with `INBOX -> WAITING_FOR_DEPENDENCIES` and `WAITING_FOR_DEPENDENCIES -> INVESTIGATING`; do not add direct transitions to approval/review/completed states.
  - [x] Update `TransitionTableCrossProductFoundationContract` and focused transition tests.
  - [x] Update REST/OpenAPI allowable-values surfaces and `registry-api-schema-placeholders.json`; regenerate `openapi.json` and frontend `schema.d.ts`.
  - [x] Update frontend `workflowStateMapping.ts` so `WaitingForDependencies` maps to `blocker` or a clear waiting/blocking semantic, and add `WorkflowStateBadge` axe coverage for the new value.

- [x] Task 3 - Add dependency domain records, ports, and persistence adapter (AC: 1, 3, 4, 7, 10)
  - [x] Add neutral records such as `RunDependencyEdge`, `RunDependencyGraphView`, and `BlockedDependencyView` under `application.workflow` or `application.workflow.spi`.
  - [x] Add a dependency read/write port with `addDependencies`, `findPrerequisites`, `findDependents`, `findBlockedOn`, and `allPrerequisitesCompleted` operations.
  - [x] Implement persistence consistently with current adapters. For cycle checks, prefer a PostgreSQL recursive CTE through `NamedParameterJdbcTemplate` over loading the full graph into memory.
  - [x] Make duplicate edge declarations idempotent; do not throw on an existing `(run_id, depends_on_run_id)` row.

- [x] Task 4 - Implement `RunDependencyService` declaration rules (AC: 3, 4, 5, 9, 10)
  - [x] Validate public id prefixes with `PublicIdPrefixes.require(..., WORKFLOW_RUN)`.
  - [x] Reject blank/empty dependency lists as `INVALID_COMMAND_PAYLOAD`.
  - [x] Reject self-dependency before DB write, with the DB CHECK as a backstop.
  - [x] Load dependent and prerequisite snapshots. Missing run -> `RUN_NOT_FOUND`; archived run -> precondition rejection; duplicate ids in request -> canonicalize/dedupe.
  - [x] Enforce declaration state rules. Dependent may be `Inbox` or `WaitingForDependencies`; prerequisites must not be `Executing`, `Completed`, `Failed`, `TakenOver`, `Reconciled`, `Paused`, or `Split`.
  - [x] Run cycle detection before inserting each new edge. Throw `RUN_DEPENDENCY_CYCLE` with deterministic details.
  - [x] If newly added dependencies are unmet, transition the dependent to `WaitingForDependencies` using `WorkflowTransitionService` with reason `waiting_for_dependencies`.
  - [x] Keep split commit out of this service. 3f-5 will call it after child run creation.

- [x] Task 5 - Add gated dispatch seam for new and existing runs (AC: 4, 5, 6, 10)
  - [x] Do not change top-level `WorkflowCommandService.submit` semantics for no-dependency runs.
  - [x] Add an application seam for 3f-5 to create/accept a child run, declare dependencies, dispatch spec generation only when there are no unmet dependencies, and park in `WaitingForDependencies` when there are unmet dependencies.
  - [x] Do not call private `WorkflowOrchestrationService.enqueueDispatch`; use public `dispatchSpecGeneration` after transitioning to/ensuring `Investigating`.
  - [x] Manual-runner routing remains owned by `WorkflowOrchestrationService.enqueueDispatch`; dependency release should call `dispatchSpecGeneration` and let that existing chokepoint decide queue vs manual.

- [x] Task 6 - Implement release-on-completion hook and service (AC: 6, 7, 10)
  - [x] Create `RunDependencyReleaseService` in `application.workflow`.
  - [x] On a completed prerequisite, query direct dependents through the dependency port.
  - [x] For each dependent: skip unless current state is `WaitingForDependencies`; if every prerequisite is `Completed`, transition to `Investigating` with a deterministic idempotency key, then call `workflowOrchestrationService.dispatchSpecGeneration(dependentRunId, correlationId)`.
  - [x] Catch/log `RuntimeException` per dependent and continue.
  - [x] Wire the release service into `WorkflowTransitionService` for committed `Completed` transitions using the same post-commit discipline as completion sync.
  - [x] Keep 3f-7 behavior out: if a prerequisite is `Split`, do nothing until 3f-7 later transitions that prerequisite to `Completed`.

- [x] Task 7 - Surface dependency read model in REST, CLI, and frontend (AC: 7, 8, 9, 10)
  - [x] Extend `WorkflowInspectionService.WorkflowStatusView`/`WorkflowDetailResponse` with prerequisites, dependents, blocked-on entries, and `blockedByDependencies`.
  - [x] Keep summary rows lean. Do not add per-row dependency lists to `/workflows`.
  - [x] Add REST `POST /api/v1/workflows/{workflowRunId}/dependencies` and `GET /api/v1/workflows/{workflowRunId}/dependencies` with required idempotency on mutation.
  - [x] Add CLI parity in `WorkflowCommands`: `dependencies add` and `dependencies show` or equivalent.
  - [x] Add frontend rendering in run detail for blocked-on state and update `RunReviewQueueItem`/badge tests so `WaitingForDependencies` is explicit and not shown as an unknown state.

- [x] Task 8 - Error-code and contract updates (AC: 4, 10)
  - [x] Add `RUN_DEPENDENCY_CYCLE("RUN_DEPENDENCY_CYCLE")` to `DomainErrorCode`.
  - [x] Register it in `ProblemDetailsCatalog` as `409 Conflict`, title `Run dependency cycle`, retryable `false`.
  - [x] Add the problem URI to `registry-api-schema-placeholders.json`.
  - [x] Add focused tests through `RegistryContractTest` / Problem Details coverage to prove the three-sites contract.

- [x] Task 9 - Focused tests and verification (AC: 1-10)
  - [x] Unit-test cycle detection, self-edge rejection, duplicate-edge idempotency, and state preconditions.
  - [x] Persistence IT: create several runs, insert dependencies, prove lookup by prerequisite and dependent, prove DB self-edge CHECK, and prove FK rejection for dangling ids.
  - [x] Orchestration/service tests: dependent with unmet prerequisites parks; no-dependency run dispatches exactly as before; completing the last prerequisite releases and dispatches; failed prerequisite leaves dependent blocked; release swallows/logs one dependent failure and continues.
  - [x] REST/CLI contract tests for declare/show endpoints and idempotency replay.
  - [x] FE tests for badge mapping, queue rendering, detail blocked-on rendering, and axe.
  - [x] Run backend spotless, focused backend tests, foundation/registry/Flyway/OpenAPI tests, `npm.cmd run check:api`, focused frontend tests, and relevant lint/build checks.

- [x] Logging instrumentation (cross-cutting; required)
  - [x] Add SLF4J parameterized logs at `RunDependencyService` entry/exit, dependency declaration decision, duplicate-edge replay, cycle rejection, state parking, release resolver entry/exit, per-dependent release, dispatch, and swallowed release failures.
  - [x] Required context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus `dependsOnRunId`/`dependentRunId` as applicable.
  - [x] Log ids, states, counts, and reason codes only. Do not log ticket text, artifact bodies, raw payloads, tokens, or PII.
  - [x] Add focused log assertions for at least: cycle rejection at WARN, dependent parked, and release swallowed failure.

### Review Findings

<!-- bmad-code-review 2026-06-28: 3 layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Initial triage: 2 decision-needed, 9 patch, 1 defer, 3 dismissed. ACs 1-9 audited satisfied; AC10 largely satisfied.
RESOLUTION (2026-06-28): both decisions resolved via per-graph advisory-lock serialization. Applied 8 patches (2 race fixes + P1 state-guard/SKIPPED + P3 'Completed' param + P4 FE null-state + P5 graphView 3→2 queries + P8 Flyway FK-action assertions + P9 service tests). Dismissed 2 (idempotency=by-design structural; re-declare gap=INBOX guard correct). 1 left as Low action-item (release dispatch tx durability). 1 deferred (liveness).
VERIFIED: RunDependencyServiceTest+RunDependencyReleaseServiceTest 16/16; FlywaySchemaContractTest FK IT 1/1; RunDependencyPersistenceIT+RunDependencyReleaseIT 8/8 (real Postgres); FE RunDependencyPanel 5/5 + eslint clean; spotless applied; zero new SpotBugs findings. Full-suite application.* ≥80% JaCoCo gate = CI tier (not reproduced locally). -->

- [x] [Review][Patch] APPLIED (advisory-lock serialization) Cycle-guard TOCTOU race — concurrent opposite-direction declarations persist a real cycle — `declareDependencies` is `@Transactional` at READ_COMMITTED with no row/advisory lock or SERIALIZABLE escalation, and `run_dependencies` has only the self-edge CHECK (no acyclicity constraint). Simultaneous `A→B` and `B→A` each run the recursive-CTE probe against the pre-commit graph, both pass, both insert → stored cycle; both runs `Inbox` → permanent mutual block (the exact deadlock the probe exists to prevent). Consequence: `GET /dependencies` `graphView` then walks an assumed-acyclic graph. Fix is ambiguous (advisory lock vs SERIALIZABLE vs exclusion constraint vs accept-risk). [blind+edge, High] [RunDependencyService.java declareDependencies / RunDependencyPersistenceAdapter.java wouldCreateCycle / V28 migration]
- [x] [Review][Patch] APPLIED (advisory-lock serialization) Declaration↔completion ordering race strands a dependent forever — if a prerequisite commits `Completed` in the window after `declareDependencies` reads its (non-Completed) state but before the new edge commits, the prerequisite's `afterCommit` release hook reads `findDependents` and sees no dependent, so the dependent commits parked in `WaitingForDependencies` with all prerequisites already `Completed` and is never re-driven. Same class: `releaseDependentsOf` reads `dependent.state()` outside a tx and skips a dependent still mid-park (read as `Inbox`), never revisiting it. Fix ambiguous (re-evaluate-after-insert vs serialize graph mutations vs completion). [blind, High] [RunDependencyService.java declareDependencies / RunDependencyReleaseService.java releaseDependentsOf]
- [x] [Review][Patch] APPLIED — dispatchWhenUnblocked dispatches with no current-state guard — when `blockedOn` is empty it calls `dispatchSpecGeneration` regardless of `run.currentState()`; a run already `Executing`/terminal would attempt an illegal `<state>→Investigating` transition. Inverse: when blocked but not `Inbox`/`WaitingForDependencies`, it returns `PARKED` without parking. [blind+edge, Medium] [RunDependencyService.java dispatchWhenUnblocked]
- [x] [Review][Dismiss] Idempotency-Key handling — BY DESIGN, no change. REST `declareRunDependencies` uses the same `requireNonBlankIdempotencyKey` guard as all 16 sibling endpoints (no real asymmetry — REST format-validation lives in the service tier for endpoints that reserve). The story's Transaction-and-Idempotency-Notes deliberately chose **structural** idempotency for declaration (composite PK + `ON CONFLICT DO NOTHING` + deterministic `wait-deps:` park key) rather than a key reservation; same-request replay is safe. Adding a full reservation is out of scope. [blind+auditor, Medium → dismissed by-design]
- [x] [Review][Dismiss] dispatchWhenUnblocked re-declare gap — NO CHANGE NEEDED. The `INBOX`-only park guard is correct: a run already in `WaitingForDependencies` is *already* parked, and a `WaitingForDependencies → WaitingForDependencies` self-transition is not in the transition table. Adding new unmet prerequisites to a parked run leaves it parked (correct end state); the deterministic `wait-deps:` key is idempotent. The advisory lock now also serializes such a re-declaration against the run's release. [blind, Low → dismissed]
- [ ] [Review][Patch][Action-item] Release transition + dispatch share one REQUIRES_NEW tx with no retry — a transient `dispatchSpecGeneration` failure rolls back the `Investigating` transition, leaving the dependent stranded with no automatic re-release. NOT FIXED: no clearly-better option without an outbox/retry, and the behavior matches the documented best-effort intent (Transaction-and-Idempotency-Notes). Left as a known limitation for a future durability pass. [blind+auditor, Low] [RunDependencyReleaseService.java releaseDependentsOf]
- [x] [Review][Patch] APPLIED — Hardcoded `'Completed'` SQL literals can silently diverge from `WorkflowState.COMPLETED.value()` — `FIND_BLOCKED_ON_SQL` / `ALL_PREREQUISITES_COMPLETED_SQL` embed the literal with no compile-time or test pin. [blind, Low] [RunDependencyPersistenceAdapter.java]
- [x] [Review][Patch] APPLIED — Frontend renders possibly-null dependency state via silent badge fallback — `RunDependencyRef.state` is nullable/optional and `RunDependencyPanel` passes it straight to `WorkflowStateBadge`; a null renders as the generic palette rather than surfacing the gap. [blind, Low] [RunDependencyPanel.tsx / WorkflowDetailResponse.java RunDependencyRef.from]
- [x] [Review][Patch] APPLIED — Workflow-detail read issues 3 extra queries unconditionally. `graphView` now derives `blockedOn` from the already-fetched prerequisites (filter `state != Completed`) → 2 queries instead of 3, identical result, and drops the separate blocked-SQL round trip. [blind, Low] [RunDependencyPort.java graphView]
- [x] [Review][Patch] APPLIED (verified against real Postgres) FlywaySchemaContractTest does not assert FK referential actions — AC1 requires `on delete restrict on update cascade` but the test only checks FK column pairs, not `ON DELETE RESTRICT`/`ON UPDATE CASCADE`. [auditor, Low] [FlywaySchemaContractTest.java]
- [x] [Review][Patch] APPLIED — No service-level test for duplicate-edge idempotency / archived-run rejection (added `duplicateDependencyIdsAreDedupedBeforePersist`, `archivedDependentIsRejectedBeforeAnyWrite`, plus `gatedDispatchSkipsWhenRunPastPreExecution` for the new SKIPPED branch) — both behaviors exist (persistence IT + `archivedAt` guard) but lack a focused `RunDependencyServiceTest` case at the service boundary AC10 implies. [auditor, Low] [RunDependencyServiceTest.java]
- [x] [Review][Defer] No liveness guard for never-satisfiable prerequisites — a prerequisite left in `Inbox` (never submitted) blocks its dependent indefinitely; only direct transitive cycles are checked, not reachability-to-completion. [edge, Low] [RunDependencyService.java] — deferred, by-design per AC3 (Inbox prerequisites are permitted)

## Dev Notes

### Reconciled Scope

This story owns the durable dependency graph and `WaitingForDependencies` state. It must stop at "runs can be sequenced and released when prerequisites complete."

In scope:

- `run_dependencies` schema and indexes.
- `WorkflowState.WAITING_FOR_DEPENDENCIES("WaitingForDependencies")`.
- Transition table and drift contracts.
- Dependency declaration service with cycle guard.
- Release resolver after committed `Completed` transitions.
- Standalone REST/CLI declare + inspect surfaces.
- Read-model/UI visibility for blocked runs.

Out of scope:

- Split proposal generation and actions (`request_split`, `continue_as_single`, `repropose_split`) - story 3f-4.
- Split commit fan-out, child run creation, sub-ticket creation consumption, and parent `Split` transition - story 3f-5.
- Split-parent completion rollup and recursive depth cap - story 3f-7.
- Cascade-fail/cascade-cancel of dependents - explicit forward option in Epic 3f.
- Rewriting dependencies when a prerequisite is split. The contract is unchanged: dependents release only when the prerequisite run reaches `Completed`.

### Live Code Seams Verified 2026-06-27

- Current Flyway head is `V27__add_parent_run_id_and_split_state.sql`; 3f-3 should own `V28` unless another migration lands first.
- State wire values are PascalCase (`Split`, `WaitingForManualExecution`). Use `WaitingForDependencies`, not lowercase.
- State CHECKs exist in three places: `workflow_runs.current_state`, `workflow_events.prior_state`, and `workflow_events.resulting_state`.
- `WorkflowTransitionTable.defaultTable()` already has non-terminal `SPLIT -> COMPLETED`; add `WAITING_FOR_DEPENDENCIES -> INVESTIGATING` and an entry in the foundation contract.
- `WorkflowTransitionService.transition(...)` writes state changes with optimistic locking and appends `WORKFLOW_STATE_CHANGED`. It also already registers a post-commit `Completed` hook for Linear completion sync. Extend that post-commit discipline for dependency release; release errors must not roll back the completed transition.
- `WorkflowOrchestrationService.dispatchSpecGeneration(...)` is the public dispatch seam. It ensures `Investigating`, performs in-flight guard checks, and routes through the existing queue/manual runner chokepoint. Do not bypass it.
- `WorkflowRunCreatePort.create(publicId, initialState, projectId, parentRunId)` exists from 3f-2. 3f-5 will create child runs through this seam and then use this story's dependency service.
- `WorkflowRunReadPort.findByParentRunId` and `WorkflowRunSnapshot.parentRunId` exist from 3f-2; do not confuse lineage with dependency edges. Lineage is tree-shaped parent/child; dependencies are a DAG between runs.
- `WorkflowInspectionService.computeActionMatrix` already returns `VIEW_ONLY` for `SPLIT` and terminal-ish view states. Add `WAITING_FOR_DEPENDENCIES` as view-only, then let the existing archive/unarchive wrapper append archive action.
- `WorkflowController.listWorkflows` parses state filters through `WorkflowState.fromValue`, so the new state automatically works as a query filter once registry/OpenAPI/client are regenerated.
- Frontend state mapping currently does not include `Split` or `WaitingForManualExecution`; add `WaitingForDependencies` and consider filling those two prior omissions if tests expose the generic fallback.

### Previous Story Intelligence

- 3f-1 established a narrow pattern for optional capability seams: keep vendor-specific logic behind application services, prove fallback behavior, and pin logs for sensitive branches. Apply the same discipline to dependency declaration: one application service owns preconditions and idempotency, persistence only stores edges.
- 3f-2 established the migration/state drift pattern for Epic 3f: update all three state CHECKs, `WorkflowState`, `WorkflowTransitionTable`, `TransitionTableCrossProductFoundationContract`, OpenAPI, `schema.d.ts`, and foundation/registry/Flyway gates.
- 3f-2 review fixed schema drift around array-valued event details and reminded that generated schemas must be aligned in every owned site. For 3f-3, dependency arrays in REST/detail responses must be reflected in OpenAPI and frontend types together.
- 3f-2 left a deferred pre-existing drift: event-state enums omit `WaitingForManualExecution` in some event response surfaces. Do not silently worsen this. If updating allowable-values for `WaitingForDependencies`, verify whether existing surfaces include every current state and either fix the whole enum or explicitly document any untouched legacy drift.

### Architecture and Boundary Guardrails

- Backend follows domain/application/adapters boundaries. Keep dependency rules in `application.workflow`; persistence adapters only implement ports; REST/CLI stay thin.
- Use Flyway versioned SQL migrations only. Hibernate auto-DDL is not a schema source of truth.
- Use explicit relational tables, not JSON metadata, for the dependency graph.
- No application cache for dependency satisfaction. Query the current database state.
- Do not invent a new public-id prefix for dependency edges unless a product-visible dependency id is required; the join table PK is enough for MVP.
- Problem Details codes are three-sites: enum, catalog, manifest. The registry contract enforces parity.
- New workflow states and problem codes require foundation gate coverage.

### Transaction and Idempotency Notes

- Declaration endpoint must be idempotent under `Idempotency-Key`; replay should not duplicate edges or append contradictory state events.
- Edge insertion itself should be duplicate-safe through PK handling plus service-level dedupe.
- Release is best-effort after a committed prerequisite completion. If a dependent dispatch fails, log it and continue; the completed prerequisite remains completed.
- Prefer deterministic release idempotency keys so repeated completion hooks or manual release retries do not append duplicate `WaitingForDependencies -> Investigating` transitions.
- Be careful with `@Transactional` boundaries: if release calls dispatch and dispatch queues/manual-parks in the same transaction, a queue-full error should affect only that dependent release attempt, not the original completed transition.

### Latest Technical Context

- No new external API or library is required. Use the existing Spring transaction and post-commit patterns already present in `WorkflowTransitionService`.
- Spring's transaction synchronization callbacks run after the surrounding transaction has committed; use that model for dependency release so follow-on work cannot roll back the prerequisite completion. Source checked 2026-06-27: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html

### Testing Standards

- Unit tests run through Surefire; `@SpringBootTest`/Testcontainers integration classes must end in `IT` and run through Failsafe.
- Foundation/registry/transition drift tests live under `src/test/java/org/dradgo/foundation` and `.../contract`.
- `application.*` coverage must remain >=80%.
- OpenAPI change requires writing the snapshot, reviewing the diff, running it green, then regenerating the frontend client.
- Frontend changes need focused Vitest coverage plus axe where new visible state UI appears.

### References

- Epic: `_bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md` - Story 3f-3 and Cross-Cutting Notes.
- Architecture: `_bmad-output/planning-artifacts/architecture.md` - explicit relational model, Flyway SQL migrations, application/adapters boundaries, Problem Details, OpenAPI/client contracts.
- Previous story: `_bmad-output/implementation-artifacts/3f-2-parent-child-run-lineage-and-terminal-split-state.md` - V27 state/migration/read-model pattern and review findings.
- Previous story: `_bmad-output/implementation-artifacts/3f-1-ticket-source-subticket-creation-capability.md` - application seam and capability-gating precedent.
- Current seams: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java`, `WorkflowOrchestrationService.java`, `WorkflowInspectionService.java`, and `WorkflowTransitionTable.java`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story workflow.

### Debug Log References

- OpenAPI snapshot regenerated from the live app via `OpenApiSnapshotContractTest` with
  `-Dopenapi.snapshot.write=true`; diff was purely additive (+216 lines: the two `/dependencies`
  paths, the `RunDependencies`/`RunDependencyRef`/`DeclareRunDependenciesRequest` schemas, the
  `WorkflowDetail.dependencies` field, and `WaitingForDependencies`/`WaitingForManualExecution` added
  to the three event-state enums). `npm run generate-api` + `check:api` then reconciled `schema.d.ts`.
- Release hook trap: the dependent transition initially threw `InvalidDataAccessApiUsageException`
  inside `afterCommit` (stale JPA resources still bound during the post-commit phase) and was
  swallowed, leaving the dependent blocked. Fixed by running each dependent's release in its own
  `REQUIRES_NEW` transaction (also gives per-dependent isolation so a caught failure cannot poison a
  shared tx). Proven by `RunDependencyReleaseIT`.

### Completion Notes List

- Substrate scope honored: no split-proposal/commit/rollup behavior; this story stops at "runs can be
  sequenced and released when prerequisites complete."
- V28 adds `run_dependencies` (composite PK, two FKs to `workflow_runs.public_id` ON DELETE RESTRICT
  ON UPDATE CASCADE, self-edge CHECK, both lookup-direction indexes) and widens the three workflow
  -state CHECKs for `WaitingForDependencies`.
- `WaitingForDependencies` non-terminal state added with edges `Inbox -> WaitingForDependencies` and
  `WaitingForDependencies -> Investigating` (foundation cross-product contract updated). View-only in
  the action matrix; FE state-mapping → `blocker` (also filled the pre-existing `Split` /
  `WaitingForManualExecution` mapping omissions to remove silent `informational` fallback).
- `RUN_DEPENDENCY_CYCLE` three-sites code (enum + ProblemDetailsCatalog 409 non-retryable + manifest)
  — foundation gate green.
- Read-view types `BlockedDependencyView` / `RunDependencyGraphView` live in `application.workflow`
  (NOT `.spi`) so the REST `WorkflowDetailResponse` can map them without tripping the
  REST-stays-thin ArchUnit rule; the `RunDependencyPort` (spi) returns them. `ArchitectureBoundaryTest`
  green.
- Dependency-release wired into `WorkflowTransitionService` via the same post-commit discipline as
  Linear completion-sync (lazy `ObjectProvider`), but ungated by any feature flag.
- REST `POST/GET /api/v1/workflows/{id}/dependencies` + CLI `dependencies-add` / `dependencies-show`.
  Adding the controller dep fanned out to 16 `@WebMvcTest` slices (new `@MockitoBean
  RunDependencyService`); CLI used optional-setter injection to avoid the large unit-test ctor fan-out.
- Tests: `RunDependencyServiceTest` (9), `RunDependencyReleaseServiceTest` (4),
  `RunDependencyPersistenceIT` (6), `RunDependencyReleaseIT` (2), `RunDependencyEndpointContractTest`
  (4), `WorkflowDependencyCommandsTest` (3) + `FlywaySchemaContractTest` drift method + FE
  `RunDependencyPanel.test.tsx` (5). Verified green: foundation-gate, FlywaySchema/Registry/OpenApi
  snapshot, architecture boundaries, CLI registration, controller fan-out, and the full
  `deliveryline-frontend` workflows + routes vitest (757). Backend spotless + checkstyle clean; FE
  tsc + eslint + prettier + check:api clean. The module-wide JaCoCo ≥80% gate runs in the
  backend-contract-tests CI tier (not reproduced locally; new application classes carry dense
  unit + IT coverage).

### File List

**Backend — main**
- `deliveryline-backend/src/main/resources/db/migration/V28__add_run_dependencies_and_waiting_for_dependencies.sql` (new)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/BlockedDependencyView.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunDependencyGraphView.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/DeclareRunDependenciesCommand.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunDependencyService.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/RunDependencyReleaseService.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/RunDependencyPort.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/RunDependencyPersistenceAdapter.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/DeclareRunDependenciesRequest.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowEventsResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`
- `deliveryline-backend/src/main/resources/openapi/openapi.json`

**Backend — test**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunDependencyServiceTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunDependencyReleaseServiceTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunDependencyReleaseIT.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/RunDependencyPersistenceIT.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RunDependencyEndpointContractTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowDependencyCommandsTest.java` (new)
- 16 `@WebMvcTest(WorkflowController.class)` slices gained a `@MockitoBean RunDependencyService`
  (WorkflowAdapterEquivalenceTest, CommandModelSymmetryFoundationContract, CliRestEquivalenceContractTest,
  ProblemDetailsContractTest, and the AcceptImplementation / ApproveSpec / RejectSpec /
  RejectImplementation / Takeover / ManualArtifact / ManualBundle / Clarifications /
  AnswerClarification / AllowedActions / ArchiveRun / WorkflowControllerLogging endpoint contract tests)

**Frontend**
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)
- `deliveryline-frontend/src/features/workflows/components/workflowStateMapping.ts`
- `deliveryline-frontend/src/features/workflows/components/WorkflowStateBadge.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/RunDependencyPanel.tsx` (new)
- `deliveryline-frontend/src/features/workflows/components/RunDependencyPanel.test.tsx` (new)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx`

## Change Log

| Date | Version | Change |
|------|---------|--------|
| 2026-06-27 | 0.1 | Created ready-for-dev story for 3f-3 with dependency schema, `WaitingForDependencies` state, cycle guard, release resolver, read/REST/CLI/FE visibility, and drift/test guardrails. |
| 2026-06-28 | 1.0 | Implemented 3f-3: V28 `run_dependencies` + 3-CHECK widening; `WaitingForDependencies` state + transitions + foundation contract; `RUN_DEPENDENCY_CYCLE` three-sites code; `RunDependencyPort`/adapter (recursive-CTE cycle probe); `RunDependencyService` (declaration rules + gated-dispatch seam) + `RunDependencyReleaseService` (post-commit, per-dependent REQUIRES_NEW); read model (`WorkflowDetail.dependencies`) + REST declare/show + CLI `dependencies-add/show` + FE `RunDependencyPanel` + state mapping; OpenAPI/`schema.d.ts` regenerated. All focused/contract/foundation/architecture tests + FE suites green. Status → review. |
