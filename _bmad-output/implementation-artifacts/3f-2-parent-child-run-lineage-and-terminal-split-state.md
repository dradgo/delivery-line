# Story 3f.2: Parent→Child Run Lineage + Non-Terminal `Split` State

Status: done

<!-- 2026-06-26 bmad-create-story context-engine pass. Target sprint key: 3f-2-parent-child-run-lineage-and-terminal-split-state. (The sprint key/filename retains the word "terminal" for stability; the RECONCILED scope is NON-TERMINAL — see the prominent note below. 3f-1 already marked epic-3f in-progress.) -->

> READ FIRST — this is a **schema + registry + read-model substrate** story. It adds the *lineage data shape* (`parent_run_id`), the *new non-terminal `SPLIT` state*, the *`workflow.split` event type*, the *optional `parentRunId` create-seam parameter*, and the *read-model exposure* of parent/child relationships. It does **NOT** drive any run into or out of `SPLIT`, create child runs, mint sub-tickets, declare dependencies, persist split proposals, or add split actions/endpoints. Nothing at runtime transitions a run to `SPLIT` in this story — story **3f-5** (commit fan-out) drives runs *into* `SPLIT`; story **3f-7** (rollup) drives the single `SPLIT → COMPLETED` out-edge. You are laying rails, not running trains on them.

> ⚠️ **TITLE/SCOPE REVERSAL — `SPLIT` is NON-TERMINAL.** An earlier draft of this epic intended `SPLIT` as a *terminal, none-out* state. The reconciled design (ADR-0029, finalized by story 3f-7) makes `SPLIT` **non-terminal with exactly one legal out-edge `SPLIT → COMPLETED`**, driven only by the 3f-7 completion rollup — never by an operator action. Implement it as non-terminal. The sprint-status key still says "terminal-split-state" purely so the filename/key stays stable; do not let that mislead you.

## Story

As an authorized user,
I want a split to preserve the relationship between the original run and its subtasks, with the original run moving to an explicit decomposed state,
So that the governed history shows that one run was decomposed into several, with full lineage — satisfying NFR16's "human explicitly reconciles the record" escape hatch rather than silently breaking the 1:1 ticket↔run invariant.

## Acceptance Criteria

1. **Schema — `parent_run_id`.** Given the next-free Flyway head (current head is `V26__create_project_runner_kinds.sql`, so this story owns `V27`), then an **additive nullable** `parent_run_id text` column (FK → `workflow_runs.public_id`) is added to `workflow_runs` and indexed; the migration is replay-safe and appears in `FlywaySchemaContractTest`. Existing rows stay `NULL` (parity — no lineage). A self-FK to a non-existent parent is rejected by the FK; `parent_run_id == public_id` (self-parent) is not a concern of this story's schema but must never be written by code.

2. **Registry + state-machine — non-terminal `SPLIT`.** Given the `WorkflowState` registry, then a new state `SPLIT("Split")` (**PascalCase wire value `Split`** to match every existing state — NOT lowercase `split`) is added as a **non-terminal** "decomposed, awaiting children" disposition, with:
   - the `current_state` CHECK on `workflow_runs` **and** the `prior_state` / `resulting_state` CHECKs on `workflow_events` all widened to include `'Split'` (in the same `V27` migration);
   - state-machine entries: legal transitions **into** `SPLIT` from `WAITING_FOR_SPEC_APPROVAL` and `WAITING_FOR_REVIEW`; and **exactly one out-edge `SPLIT → COMPLETED`** (declared legal here; *driven* only by the 3f-7 rollup);
   - drift-tested against the DB CHECK + state-machine table + API schema (`TransitionTableCrossProductFoundationContract`, `WorkflowEventsResponse` allowable-values, `OpenApiSnapshotContractTest`).

3. **Read model — lineage exposure.** Given the read model, then `WorkflowRunSnapshot` and the run summary/detail views expose `parentRunId`, and the **detail** view additionally exposes the list of **child run ids** for a parent; the relationship is queryable **by parent** (a repository finder by `parent_run_id`) and **by child** (the `parentRunId` field on the snapshot). These are consumed by the FE in story 3f-5.

4. **Event — `workflow.split`.** Given a new `WorkflowEventType SPLIT("workflow.split")` (dotted-lowercase wire value), then it is registered and **mirrored into both event-fixture sites** (the registry fixture `workflow-event-types.fixture.json` and the `fixture-event-streams` JSON-schema `eventType` enum); it carries `childRunIds` via a **new allow-listed detail key** (ids only, count bounded). Adding the event type itself does **not** change `openapi.json` (event types are not enumerated in the live OpenAPI schema — they live in test fixtures), hence the epic's "(OpenAPI byte-identical)" note applies to *this AC only*. No production code emits `workflow.split` in 3f-2 (3f-5 emits it on the parent at commit).

5. **Create seam — optional `parentRunId`.** Given run creation (`WorkflowRunCreatePort.create` / `WorkflowCommandService`), then the create seam accepts an **optional** `parentRunId` so a child run can record its parent at creation. Default behaviour (top-level run, `parentRunId == null`) is **byte-identical to pre-3f** — preserve this with a back-compatible overload (do not force every existing caller/test fake to change).

6. **Tests.** Given tests, then coverage asserts: Flyway/registry/CHECK/state-machine drift for `SPLIT`; `parent_run_id` round-trips through entity↔snapshot; the transition *into* `SPLIT` is legal only from the two gates and the only out-edge is `SPLIT → COMPLETED`; `workflow.split` event-type drift across both fixture sites + the new detail key; the read model exposes `parentRunId` + child ids and is queryable both directions; **parity** (a normal top-level submit is unchanged, `parentRunId` null); `application.*` line coverage stays **≥80%**.

## Tasks / Subtasks

- [x] **Task 1 — `V27` Flyway migration: `parent_run_id` column + index, and widen the three state CHECKs** (AC: #1, #2)
  - [x] Create `deliveryline-backend/src/main/resources/db/migration/V27__add_parent_run_id_and_split_state.sql`. (Confirm `V26` is still the head before naming; if a higher head appeared, use the true next-free number and update this story's references.)
  - [x] Add the column + FK + index following the V17 `project_id` precedent exactly:
    ```sql
    alter table workflow_runs
        add column parent_run_id text null;
    alter table workflow_runs
        add constraint fk_workflow_runs_parent_run foreign key (parent_run_id)
            references workflow_runs (public_id) on delete restrict on update cascade;
    create index idx_workflow_runs_parent_run_id
        on workflow_runs (parent_run_id)
        where parent_run_id is not null;
    ```
    Use a **partial index** (`where parent_run_id is not null`) — the vast majority of rows are top-level (`NULL`), mirroring the `V23` `archived_at` partial-index precedent. `on delete restrict` mirrors the `project_id` FK and is safe (Epic 5 owns purge of decomposed records).
  - [x] Widen all **three** `current_state`/`prior_state`/`resulting_state` CHECK constraints to include `'Split'`, following the `V20` precedent verbatim (drop + re-add each constraint). The three constraints are:
    - `workflow_runs.ck_workflow_runs_current_state`
    - `workflow_events` `prior_state` CHECK
    - `workflow_events` `resulting_state` CHECK

    Copy the existing allow-list (now including `'WaitingForManualExecution'` from V20) and append `'Split'`.
  - [x] Keep the migration replay-safe / forward-only (no `if exists` games beyond what V20 used). Verify `FlywaySchemaContractTest` still passes with the new head.

- [x] **Task 2 — `WorkflowState.SPLIT` registry value** (AC: #2)
  - [x] In `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java`, add `SPLIT("Split")`. Place it logically (e.g. after `WAITING_FOR_REVIEW` or grouped with the other non-finished dispositions) with a comment: non-terminal "decomposed, awaiting children"; reached at split-commit (3f-5) from the two gates; sole out-edge `SPLIT → COMPLETED` via the 3f-7 rollup.
  - [x] **CRITICAL:** the wire value is PascalCase `"Split"`, matching every existing state and the CHECK literal. Do **not** use `"split"`.
  - [x] Do **NOT** add `SPLIT` to `ArtifactOperationService.TERMINAL_RUN_STATES` (`COMPLETED, FAILED, RECONCILED`) — `SPLIT` is non-terminal. Leave that set unchanged.

- [x] **Task 3 — Transition table + foundation contract** (AC: #2, #6)
  - [x] In `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java` (`defaultTable()`):
    - add `SPLIT` to the target sets of `WAITING_FOR_SPEC_APPROVAL` and `WAITING_FOR_REVIEW`;
    - add a new entry for `SPLIT` with the single target `COMPLETED` (use the existing `put(rules, ...)` helper).
    - `assertCoversAllStates()` will fail until `SPLIT` has an entry — adding the `SPLIT → {COMPLETED}` entry satisfies it.
  - [x] Update the **owned-by-test reference matrix** in `deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java` (`EXPECTED_ALLOWED_TARGETS`): add `SPLIT` to the two gate states' expected targets and add the `SPLIT → EnumSet.of(COMPLETED)` entry. The cross-product test then proves every *other* edge into/out of `SPLIT` is `ILLEGAL_TRANSITION`. (This is the "transition-table change fans to the contract" pattern — the table and the contract are two sources that must agree.)
  - [x] `SPLIT → COMPLETED` needs no special guard (mirror `WAITING_FOR_REVIEW → COMPLETED`, which has none). Do not require a `reason`/`FailureCategory` for it.

- [x] **Task 4 — `parent_run_id` persistence: entity, mapper, repository, snapshot** (AC: #1, #3, #5)
  - [x] `WorkflowRunEntity`: add `@Column(name = "parent_run_id") private String parentRunId;` and a getter. Add a `create(publicId, currentState, projectId, parentRunId)` overload; keep the existing 2-arg and 3-arg `create(...)` overloads delegating with `parentRunId = null`.
  - [x] `WorkflowRunEntityMapper`: add a `toNewEntity(publicId, initialState, projectId, parentRunId)` overload (keep the 3-arg delegating to it with `null`); add `parentRunId` to `toSnapshot(...)`.
  - [x] `WorkflowRunRepository`: add a finder for children, e.g. `List<WorkflowRunEntity> findByParentRunIdOrderByCreatedAtDescIdDesc(String parentRunId)` (matches the existing `OrderByCreatedAtDescIdDesc` finder convention).
  - [x] `WorkflowRunSnapshot` (in `application.workflow.spi`): add a `String parentRunId` component. **This is a record-component fan-out** — every construction site must add the argument. Update `WorkflowRunEntityMapper.toSnapshot` and **every test/fake** that constructs `WorkflowRunSnapshot` (grep the whole `src/test` tree for `new WorkflowRunSnapshot(`). Default new arg to `null` in fixtures that don't care about lineage.

- [x] **Task 5 — Create-seam threading: `WorkflowRunCreatePort` + `WorkflowCommandService` + adapter** (AC: #5)
  - [x] `WorkflowRunCreatePort`: add `WorkflowRunSnapshot create(String publicId, WorkflowState initialState, String projectId, String parentRunId)`. Make the **existing 3-arg method a `default`** that calls the 4-arg with `parentRunId = null` — this preserves all current callers and any test fakes implementing the port (do NOT delete the 3-arg method).
  - [x] `WorkflowRunPersistenceAdapter`: implement the 4-arg `create(...)`, passing `parentRunId` through `toNewEntity`.
  - [x] `WorkflowCommandService`: the existing `submit` path keeps calling the 3-arg (top-level, `parentRunId = null`) so its behaviour is byte-identical. **Do not** add a public split-submit API here — 3f-5's `SplitCommitService` will call the 4-arg create directly when minting children. (Threading a `parentRunId` all the way through `submit` is out of scope; only the *port* must accept it.)

- [x] **Task 6 — `WorkflowEventType.SPLIT` + detail key + both fixture sites** (AC: #4, #6)
  - [x] `WorkflowEventType`: add `SPLIT("workflow.split")` (dotted-lowercase). 
  - [x] `WorkflowEventDetailKeys`: add `public static final String CHILD_RUN_IDS = "childRunIds";` and include it in `ALLOW_LISTED_KEYS` (visible in history; ids only). It is **not** a server-only key.
  - [x] **Fixture site 1:** add `"workflow.split"` to `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json` (`workflowEventTypes` array). `RegistryContractTest` set-compares, so ordering is free — but keep it tidy.
  - [x] **Fixture site 2:** add `"workflow.split"` to the `eventType` enum in `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json`.
  - [x] If you add a *typed-shape* block for detail keys in that schema, document `childRunIds` as an array of run-id strings, bounded. **Do not** add `workflow.split` to any of the 5 *scenario* streams (`happy-path-success.json`, etc.) — those exercise real transitions and would then require re-vendoring the frontend copies (`deliveryline-frontend/src/test/fixtures/event-streams/`) under the `event-stream-drift.test.js` gate. No scenario stream needs a split event in 3f-2.
  - [x] **Bounding:** when the emitter (3f-5) populates `childRunIds`, the count is bounded by the number of subtasks; in 3f-2 just ensure the detail-key carrying mechanism accepts a `List<String>` and that `WorkflowInspectionService.filterDetails` passes it through (it iterates `ALLOWED_DETAIL_KEYS` and copies non-null values — adding the constant to the list is sufficient). No new event is emitted in this story, so a focused unit test that builds a `WorkflowEventRecord` with `details = Map.of(CHILD_RUN_IDS, List.of(...))` and asserts it survives `filterDetails` is the right coverage.

- [x] **Task 7 — REST DTOs + API schema regen** (AC: #2, #3)
  - [x] Add `"Split"` to the **three** `allowableValues` arrays in `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowEventsResponse.java` (`terminalState` in `WorkflowRunRef`, `priorState` + `resultingState` in `WorkflowEventResponse`). *(These arrays are currently missing `WaitingForManualExecution` — a pre-existing drift; add only `Split` here, do not scope-creep the V20 fix unless a test forces it.)*
  - [x] Surface `parentRunId` on the run **summary** DTO (`WorkflowSummaryResponse` + `WorkflowRunSummaryView`) and `parentRunId` + `childRunIds` (List) on the **detail** DTO (`WorkflowDetailResponse` + `WorkflowStatusView`). Compute `childRunIds` in `WorkflowInspectionService` via the new `findByParentRunId...` finder. **Keep `childRunIds` off the summary list** to avoid an N+1 child query per queue row — detail-only is sufficient for the 3f-5 lineage view and AC3.
  - [x] **OpenAPI is NOT byte-identical for this story.** The state allowable-values change *and* the new DTO fields both regenerate `openapi.json`. Run `OpenApiSnapshotContractTest` with `-Dopenapi.snapshot.write=true` to refresh `src/main/resources/openapi/openapi.json`, review the diff (only the expected additions), then run it normally to confirm green.
  - [x] Regenerate the frontend client types: run the project's `npm run generate-api` (per the OpenAPI-regen-drift recipe) so `schema.d.ts` matches; otherwise the FE `check:api` reds. No FE component work is in scope (3f-5/3f-6 consume these fields) — just keep the generated client in sync.

- [x] **Task 8 — Tests & drift gates** (AC: #1–#6)
  - [x] Unit: `WorkflowState` parse round-trip for `Split`; `WorkflowTransitionTable` accepts the two into-edges + the single out-edge and rejects a sample illegal edge (e.g. `SPLIT → EXECUTING`).
  - [x] Foundation/contract: `TransitionTableCrossProductFoundationContract` (updated matrix) green; `RegistryContractTest` green for the new state + event type; `FixtureEventStreamSchemaConformanceContractTest` green with the widened enum; `FlywaySchemaContractTest` green with `V27`.
  - [x] Persistence: an `*IT` (Testcontainers, name ending `IT`, Failsafe tier) that creates a parent run, creates a child via the 4-arg port with `parentRunId` set, asserts the child snapshot carries `parentRunId`, and `findByParentRunId...` returns the child. Assert a self-/dangling-FK insert fails.
  - [x] Read model: `WorkflowInspectionService` detail view returns `parentRunId` + `childRunIds`; summary returns `parentRunId`; a run with no children returns an empty `childRunIds` (not null) and a top-level run returns `parentRunId == null` (parity).
  - [x] Event: detail-key pass-through test (Task 6). Optional fixture-stream additions are *not* required.
  - [x] Parity: a normal `WorkflowCommandService.submit` produces `parent_run_id = NULL` and unchanged behaviour; `OpenApiSnapshotContractTest` shows *only* the intended additions.
  - [x] Coverage: `application.*` ≥80% (the persistence/read-model additions are the main new lines — cover the finder + view assembly).
  - [x] Run `mvn -pl deliveryline-backend spotless:apply` after Java edits, then the format/static-check chain. Run the **foundation gate** (`-Pfoundation-gate`) because a new `WorkflowState` *and* a new `WorkflowEventType` are added (registry/placeholder drift). Architecture (ArchUnit) rules run in the Failsafe tier — verify there, not Surefire alone.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] This story adds little runtime behaviour (no service drives `SPLIT`). Where you *do* add code paths — the read-model assembly (`childRunIds` lookup) and the create-port `parentRunId` branch — add SLF4J parameterized logs: `INFO` when a run is created with a non-null `parentRunId` ("creating child run {} under parent {}"), `DEBUG` on the child-id lookup count. Use parameterized logging, never concatenation.
  - [x] Carry `workflowRunId` and (when present) `parentRunId` as parameters/MDC. Never log payloads/secrets (there are none here).
  - [x] Pin one new log line in a focused test (list-appender or `OutputCaptureExtension`) — e.g. the "creating child run" line on the 4-arg create path.

## Dev Notes

### Reconciled Scope — what 3f-2 is and is NOT

**IS (substrate):** lineage column + FK + index; the `SPLIT` state (registry + 3 CHECKs + transition table + contract + API allowable-values); the `workflow.split` event type + `childRunIds` detail key + both fixture sites; the optional `parentRunId` create-port parameter (back-compatible overload); read-model exposure of `parentRunId` (summary+detail) and `childRunIds` (detail).

**IS NOT (later stories):**
- Creating child runs / sub-tickets / split commit — **3f-5** (`SplitCommitService`, calls the 4-arg `create` you build here).
- Driving a run *into* `SPLIT` or emitting `workflow.split` — **3f-5**.
- The `SPLIT → COMPLETED` rollup that *drives* the out-edge — **3f-7** (`RunSplitCompletionRollupService`). You declare the edge legal; nothing fires it yet.
- `run_dependencies` / `WaitingForDependencies` — **3f-3** (do not add that state or table here).
- Split proposal persistence / `request_split`/`approve_split` actions / split REST endpoints — **3f-4 / 3f-5**.
- Queue project filter / FE lineage rendering — **3f-6 / 3f-5**.

### Live Code Seams Verified 2026-06-26

- **Flyway head:** `V26__create_project_runner_kinds.sql` → this story owns **`V27`**. Migrations dir: `deliveryline-backend/src/main/resources/db/migration/`.
- **`workflow_runs` table** created in `V1__create_workflow_core_tables.sql` (columns: `public_id`, `current_state` + CHECK, `created_at`, `archived_at`; `project_id` added in `V17`). The **`project_id` FK + index** in `V17__create_projects_and_credentials.sql` is the exact additive-column precedent to copy. The **partial index** pattern is `V23__add_workflow_runs_archived_at_index.sql`.
- **State CHECK widening precedent:** `V20__add_manual_execution_kind_and_state.sql` widened all three CHECKs (`workflow_runs.current_state`, `workflow_events.prior_state`, `workflow_events.resulting_state`) to add `'WaitingForManualExecution'` — copy this drop+re-add structure exactly.
- **Non-terminal-state end-to-end precedent:** story **3d-3** added `WAITING_FOR_MANUAL_EXECUTION` (registry + V20 CHECKs + transition table + `WorkflowEventsResponse` allowable-values + foundation contract). Follow the same surface map for `SPLIT`.
- **`WorkflowState`** registry: `domain/registry/WorkflowState.java` — all wire values are **PascalCase** (`COMPLETED("Completed")`). New: `SPLIT("Split")`.
- **Transition table:** `application/workflow/WorkflowTransitionTable.java` — `Map<WorkflowState, Set<WorkflowState>>` via `defaultTable()` + `put(...)` helper; terminal states declared with an empty target set; `assertCoversAllStates()` requires every state to have an entry. `WAITING_FOR_REVIEW → COMPLETED` is the unguarded into-completed edge to mirror.
- **Foundation contract:** `src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java` — `EXPECTED_ALLOWED_TARGETS` is the test-owned mirror matrix that must be updated in lockstep with the table.
- **`TERMINAL_RUN_STATES`:** `application/artifact/ArtifactOperationService.java` (`COMPLETED, FAILED, RECONCILED`) — leave unchanged; `SPLIT` is non-terminal.
- **Create port:** `application/workflow/spi/WorkflowRunCreatePort.java` (`create(publicId, initialState, projectId)`); adapter `adapters/persistence/WorkflowRunPersistenceAdapter.java`; service call site `application/workflow/WorkflowCommandService.java` (`workflowRunCreatePort.create(... WorkflowState.INBOX, projectId)`).
- **Entity/mapper:** `adapters/persistence/entity/WorkflowRunEntity.java` (already has 2-arg + 3-arg `create(...)` overloads — extend the pattern); `adapters/persistence/mapper/WorkflowRunEntityMapper.java` (`toNewEntity`, `toSnapshot`).
- **Snapshot:** `application/workflow/spi/WorkflowRunSnapshot.java` (record; 6 components today → 7 with `parentRunId`).
- **Read views/DTOs:** `WorkflowInspectionService.WorkflowRunSummaryView` + `WorkflowStatusView`; REST `adapters/rest/WorkflowSummaryResponse.java`, `WorkflowDetailResponse.java`, controller `adapters/rest/WorkflowController.java` (`GET /api/v1/workflows`, `GET /api/v1/workflows/{id}`).
- **Event type:** `domain/registry/WorkflowEventType.java` (dotted-lowercase). **Detail keys:** `domain/registry/WorkflowEventDetailKeys.java` (`ALLOW_LISTED_KEYS` / `SERVER_ONLY_KEYS`); filtered by `WorkflowInspectionService.filterDetails` (iterates `ALLOWED_DETAIL_KEYS`). **Emit seam:** `WorkflowEventWritePort.append(WorkflowEventRecord)` — not used in 3f-2.
- **Event fixture sites:** (1) `src/test/resources/contracts/events/workflow-event-types.fixture.json`; (2) `src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (`eventType` enum). Enforced by `RegistryContractTest` (set-compare) + `FixtureEventStreamSchemaConformanceContractTest`. Frontend vendored *scenario* copies are gated by `deliveryline-frontend/tools/fixtures/__tests__/event-stream-drift.test.js` (only matters if a scenario stream changes — it won't here).
- **OpenAPI snapshot:** `OpenApiSnapshotContractTest` (`-Dopenapi.snapshot.write=true` to refresh `src/main/resources/openapi/openapi.json`).

### Critical Traps (project memory)

- **State wire value is `"Split"` (PascalCase), event wire value is `"workflow.split"` (dotted-lowercase).** Two different conventions — do not cross them. The CHECK constraint literal is `'Split'`.
- **Three CHECK constraints**, not one: `workflow_runs.current_state` + `workflow_events.{prior_state, resulting_state}`. Missing the two event CHECKs will break when 3f-5/3f-7 write events whose `resulting_state`/`prior_state` is `Split`.
- **`WorkflowRunSnapshot` record-component fan-out:** adding `parentRunId` breaks every `new WorkflowRunSnapshot(...)` call site. Grep `src/test` and fix all of them (default `null`).
- **`WorkflowRunCreatePort` port change:** use a **`default` 3-arg method** delegating to the new 4-arg — do not delete the 3-arg signature, or you break callers and every test fake implementing the port.
- **New `WorkflowState` + new `WorkflowEventType` → run `-Pfoundation-gate`** (registry/placeholder drift). ArchUnit/`@ArchTest` rules run in **Failsafe**, not `mvnw test` — verify there.
- **OpenAPI is NOT byte-identical** for 3f-2 (state allowable-values + new DTO fields). Regenerate `openapi.json` *and* `schema.d.ts` (`npm run generate-api`); the epic's "(OpenAPI byte-identical)" applies only to the *event-type fixture mirroring*, which lives in test resources.
- **Spotless:** run `spotless:apply` on hand-edited Java before pushing or the format-static-check chain reds CI.
- **Testcontainers IT naming:** the persistence round-trip test must end in `IT` (Failsafe), not `Test` (Surefire), or it leaks into the Windows Surefire tier and reds CI.
- `SPLIT` must **not** join `TERMINAL_RUN_STATES` — it is non-terminal.

### Idempotency / Parity

- 3f-2 adds no idempotent write path of its own (no new emitter/service). The idempotency story is 3f-5 (commit keyed by parent run + proposal/ordinal). Here, the only invariant is **parity**: an unchanged top-level submit yields `parent_run_id = NULL`, no `Split` state, no `workflow.split` event — byte-identical to pre-3f except for the additive (nullable/absent) fields.

### ADR

- Co-authored with this epic: `docs/adr/0029-complex-ticket-flow.md` records the parent→child lineage model and **`SPLIT` as non-terminal with a `SPLIT → COMPLETED` rollup out-edge** (and why this satisfies NFR16's explicit-reconciliation hatch). The rollup *mechanics* are detailed by 3f-7; 3f-2 establishes the data + state substrate the ADR describes. If `0029` does not yet exist, add the lineage + non-terminal-`SPLIT` section here (3f-3 adds the dependency-DAG section, 3f-7 the rollup section).

### Testing Standards

- `@SpringBootTest` + Testcontainers classes are named `*IT` and run via Failsafe; unit tests via Surefire.
- Foundation/registry/transition drift tests live under `src/test/java/org/dradgo/foundation` and `.../contract`; run them after registry/state edits.
- `application.*` line coverage gate ≥80% (JaCoCo); the new finder + read-view assembly are the lines to cover.
- After OpenAPI change: write the snapshot, eyeball the diff, run the test green, regenerate the FE client.

### References

- Epic: `_bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md` — Story 3f-2 + Cross-Cutting Notes (foundation-gate widening, NFR16 reconciliation, vocabulary).
- Sprint change proposal: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-06-24.md` — Epic 3f technical impact + sequencing.
- Previous story: `_bmad-output/implementation-artifacts/3f-1-ticket-source-subticket-creation-capability.md` — sibling foundation (createSubticket capability); 3f-5 integrates 3f-1 + 3f-2 + 3f-3.
- Non-terminal-state precedent (story 3d-3 / V20): `_bmad-output/implementation-artifacts/3d-3-manual-runner-kind-and-waiting-for-manual-execution.md`; `deliveryline-backend/src/main/resources/db/migration/V20__add_manual_execution_kind_and_state.sql`.
- Additive-column precedent: `deliveryline-backend/src/main/resources/db/migration/V17__create_projects_and_credentials.sql` (`project_id` FK + index); `V23__add_workflow_runs_archived_at_index.sql` (partial index).
- Registry/contract: `deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java`; `.../contract/RegistryContractTest.java`; `.../contract/FixtureEventStreamSchemaConformanceContractTest.java`.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- `mvn -pl deliveryline-backend "-Dtest=WorkflowTransitionTableTest,WorkflowInspectionServiceAllowedActionsTest,WorkflowInspectionServiceTest,WorkflowRunPersistenceAdapterTest" "-DfailIfNoTests=false" test` - 74 tests, green.
- `mvn -pl deliveryline-backend "-Djacoco.skip=true" "-Dfrontend-maven-plugin.skip=true" "-Dit.test=OpenApiSnapshotContractTest,FlywaySchemaContractTest,WorkflowRunLineagePersistenceIT" "-Dfailsafe.failIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify` - 30 tests, green.
- `mvn -pl deliveryline-backend -Pfoundation-gate "-Djacoco.skip=true" "-Dfrontend-maven-plugin.skip=true" "-Dit.test=FoundationGateVerificationTest" "-Dfailsafe.failIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify` - 52 tests, green.
- `mvn -pl deliveryline-backend "-Dfailsafe.failIfNoSpecifiedTests=false" verify` - full backend verify green after building frontend package.
- `mvn -pl deliveryline-frontend package` - frontend package/lint/check/build green after escalated approval for npm cache access.
- `npm.cmd run check:api` - generated client in sync.
- `mvn -pl deliveryline-backend spotless:check` - green.

### Completion Notes List

- Added V27 additive lineage/state migration with nullable `parent_run_id`, FK, partial index, anti-self check, and all three workflow state CHECK constraints widened for `Split`.
- Added non-terminal `WorkflowState.SPLIT("Split")`, legal transitions into split from the two approval gates, and sole `SPLIT -> COMPLETED` out-edge; `SPLIT` remains outside terminal run-state handling.
- Threaded optional `parentRunId` through the create port, persistence entity/mapper/snapshot, repository finder, summary/detail DTOs, and inspection read model; detail views expose non-null `childRunIds`.
- Added `workflow.split`, allow-listed `childRunIds`, fixture/schema/OpenAPI/frontend-client drift updates, and CLI history schema support for the new detail key.
- Added persistence/read-model/transition/Flyway/foundation/OpenAPI contract coverage, including child-create logging and split-state parse round-trip.

### File List

- `deliveryline-backend/src/main/resources/db/migration/V27__add_parent_run_id_and_split_state.sql`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunCreatePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunReadPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunSnapshot.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/WorkflowRunEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowSummaryResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowEventsResponse.java`
- `deliveryline-backend/src/main/resources/openapi/openapi.json`
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapterTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/WorkflowRunLineagePersistenceIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowReadEndpointsContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowTransitionTableTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java`
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json`
- `deliveryline-frontend/src/lib/api/schema.d.ts`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Review Findings

_Adversarial code review 2026-06-26 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 1 decision-needed (resolved → patch), 3 patch, 1 deferred, 7 dismissed as noise._

- [x] [Review][Patch] Reconcile `childRunIds` schema bounds + item-pattern across the two new schemas — FIXED 2026-06-26: aligned CLI history `workflow-history.v1.schema.json:86-90` to the strict pattern `^run_[A-Za-z0-9_-]{4,}$` + `maxItems: 100`, matching the fixture `workflow-events-response.schema.json:208-212`. Same field now validates identically before 3f-5 emits the key.
- [x] [Review][Patch] `toPlainJsonValue` NPEs on nested JSON-null elements — FIXED 2026-06-26: replaced `List.copyOf`/`Map.copyOf` with `Collections.unmodifiableList`/`unmodifiableMap` (null-tolerant) in `WorkflowInspectionService`; added `java.util.Collections` import. The recursion into real `List`/`Map` (needed for `childRunIds`) is retained; `WorkflowInspectionServiceTest` green (12/12). Note: existing array-valued allow-listed keys (e.g. `taggedFeedback`) now render as real arrays rather than stringified JSON — verify their consumers/schemas when next touched.
- [x] [Review][Patch] V27 migration missing trailing newline — FIXED 2026-06-26: appended trailing newline to `V27__add_parent_run_id_and_split_state.sql`.
- [x] [Review][Defer] Event-state enums omit `WaitingForManualExecution` while adding `Split` [WorkflowEventsResponse.java priorState/resultingState/terminalState + openapi.json + schema.d.ts] — deferred, pre-existing V20/3d-3 drift; spec Task 7 explicitly scopes it out ("do not scope-creep the V20 fix unless a test forces it").

## Change Log

| Date | Version | Change |
|------|---------|--------|
| 2026-06-26 | 0.2 | Implemented story 3f-2 lineage substrate: V27 lineage/state migration, non-terminal `Split`, `workflow.split`, parent/child read model, create seam, schema/OpenAPI/client drift, and verification coverage. |
| 2026-06-26 | 0.1 | Created ready-for-dev story for 3f-2 — lineage `parent_run_id` schema, non-terminal `SPLIT` state (registry + 3 CHECKs + transition table + contract + API allowable-values), `workflow.split` event + `childRunIds` detail key + both fixture sites, back-compatible `parentRunId` create-seam, and read-model exposure. Live-code seams + traps reconciled. |
