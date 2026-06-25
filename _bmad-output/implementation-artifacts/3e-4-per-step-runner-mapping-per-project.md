# Story 3e.4: Per-Step Runner Mapping Per Project (+ Configuration UI)

Status: done

<!-- Added to Epic 3e via sprint-change-proposal-2026-06-23.md (correct-course addendum). Resolves story 3d-3 Open Decision #1 (deferred per-stage-per-project granularity). Validation optional: run validate-create-story before dev-story. -->
<!-- 2026-06-24 (bmad-create-story finalize): context-engine pass re-verified all four core gaps still hold on the live codebase. (1) RESOLVER SEAM CONFIRMED EXACT — `ProjectRuntimeConfigResolver.resolveRunnerKind` is still at `:122` (single-arg) / `:126` (three-arg); override branch logs `source=project_override` @ ~L135, global @ ~L147. Insert the per-step layer ABOVE the `override` read at L132. (2) FRONTEND GAP CONFIRMED — `runnerKind` appears ONLY in `deliveryline-frontend/src/lib/api/schema.d.ts`, ZERO `.tsx` (the "no UI control" premise holds; this story delivers the first UI for it). (3) NET-NEW CONFIRMED — `ProjectRunnerStep` + `project_runner_kinds` have zero references anywhere (no partial work to reconcile). ⚠️ TWO DRIFTS the dev MUST heed: (A) **FLYWAY HEAD MOVED to V24** (V20 manual-exec, V21 step_reviews-uniqueness, V22 reviewed-artifact-pin, V23 archived_at-index, V24 provider-usage-snapshots) — the next-free head is **V25**, NOT V21 as the body hedged; AC1/Task-2 corrected inline below. (B) **The named "6 `new Project(...)` sites" list is STALE** — ProjectController and ProjectConnectorResolver NO LONGER construct `Project`. Real main-source construction sites today: `ProjectManagementService`, `DefaultProjectSeeder`, `ProjectEntityMapper` (+ the test sites). LOCATE THE FAN-OUT BY COMPILER ERRORS after adding the `stepRunnerKinds` component, not by the cited list. -->

> **⚠️ READ FIRST — this is the deferred refinement of 3d-3, plus the missing config UI.** Story 3d-3 added a **single** nullable per-project `Project.runnerKind` override (codex/claude/manual) applied **across all stages**, and **explicitly deferred per-stage-per-project granularity** (its Open Decision #1 / R1: "true per-stage-per-project selection … needs either per-stage columns or a `project_runner_kinds(project_id, stage, kind)` table; defer it"). The user now wants to **map a runner to each step in a project** — i.e. that deferred per-step mapping. Separately, the single `runnerKind` override is already accepted by the REST API (`CreateProjectRequest`/`UpdateProjectRequest`/`ProjectResponse`) and present in the generated frontend `schema.d.ts`, but there is **NO actual UI control** to set it (verified 2026-06-23: `runnerKind` appears in the frontend ONLY in `schema.d.ts`, not in any `.tsx`). So 3e-4 delivers BOTH: per-step backend mapping AND the Projects-Management-UI control to configure it.
>
> **Three reconciliations the live codebase forces (read all three before coding):**
>
> 1. **The resolver layering is the heart of it.** `ProjectRuntimeConfigResolver.resolveRunnerKind(workflowRunId, stage, executionSubStage)` (`:122`/`:126`) today is: per-project single override (`project.runnerKind()`) → else global per-stage (`RunnerProperties.kindForStage`/`kindForExecutionSubStage`). 3e-4 inserts a **most-specific** layer: **per-STEP project mapping → single per-project override (kept as a project-wide default) → global per-stage**. The "step" is derived from `(stage, executionSubStage)`: `INVESTIGATION → spec`; `EXECUTION + IMPLEMENTATION_PLAN → implementationPlan`; `EXECUTION + PR_OUTPUT → prOutput`. See R1.
> 2. **A new child table, not more columns.** Per 3d-3's own deferral note, model the mapping as `project_runner_kinds(project_id FK, step, runner_kind)` (additive Flyway, next free version) with CHECKs on `step` (a new value set) and `runner_kind` (the existing `RunnerKind` set). `Project` gains a `Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds` (empty = no per-step mapping). This mirrors how `project_credentials` is a child of `projects`. Do NOT add three nullable columns to `projects`. See R2 + the `Project` fan-out trap.
> 3. **`MANUAL` per step must respect the 3d-3 park path.** A step mapped to `manual` parks the run in `WaitingForManualExecution` exactly as 3d-3's single-override `manual` does — the resolver returns `MANUAL`, the existing pre-enqueue chokepoint (`enqueueDispatch` / `ManualExecutionDispatcher`) branches to park. So per-step `manual` requires NO new dispatch logic — it just makes the resolver return `MANUAL` for that step only. Confirm a per-step `manual` on (say) prOutput parks at the pr-output dispatch while spec/plan enqueue normally. See R3.

## Story

As an operator configuring a project,
I want to assign which runner (codex / claude / manual) executes each workflow step — spec, implementation plan, and PR output — independently per project,
So that I can, e.g., run the spec step with Codex but execute the PR-output step manually, instead of being forced into one runner kind for the whole project.

## Acceptance Criteria

> Reconciled against the live codebase: 3d-3 shipped a single per-project `runnerKind` override and deferred per-stage granularity; the config UI was never built. The reconciled ACs below resolve both; rationale in Dev Notes (R1–R6).

1. **Given** the next-free Flyway head, **When** the backend starts, **Then** an additive migration creates `project_runner_kinds(project_id text FK → projects.public_id, step text, runner_kind text, PRIMARY KEY (project_id, step))` with `CHECK (step IN ('spec','implementationPlan','prOutput'))` and `CHECK (runner_kind IN ('codex','claude','manual'))`; replay is a no-op. (Next-free head verified **V25** on 2026-06-24 — V20–V24 are taken; re-confirm the head at impl time in case a sibling 3e story landed first.)

2. **Given** the step value set, **Then** a `ProjectRunnerStep` registry (`spec`/`implementationPlan`/`prOutput`) is added to the authoritative registries + drift-tested against the DB CHECK + API schema (story 1.4 / 3c-2 pattern); a helper maps `(RunnerStage, ExecutionSubStage) → ProjectRunnerStep` (`INVESTIGATION→spec`, `EXECUTION+IMPLEMENTATION_PLAN→implementationPlan`, `EXECUTION+PR_OUTPUT→prOutput`).

3. **Given** the `Project` aggregate, **Then** it gains a `Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds` component (empty map = no per-step mapping; null-safe). The `new Project(...)` construction sites are updated — **locate them by compiler error, not by a fixed list** (re-verified 2026-06-24: ProjectController & ProjectConnectorResolver no longer construct `Project`; the live main-source sites are `ProjectManagementService`, `DefaultProjectSeeder`, `ProjectEntityMapper`, plus the test sites); the default project + seeder pass an empty map (parity).

4. **Given** the resolver, **Then** `ProjectRuntimeConfigResolver.resolveRunnerKind(workflowRunId, stage, executionSubStage)` resolves with precedence: **per-step mapping** (`stepRunnerKinds.get(stepOf(stage, subStage))`) → **single per-project `runnerKind` override** (kept as the project-wide default beneath the per-step map) → **global per-stage** (`RunnerProperties`). Logged at DEBUG with `source=project_step_mapping|project_override|global`.

5. **Given** a step mapped to `manual`, **Then** that step parks the run in `WaitingForManualExecution` via the existing 3d-3 park path (resolver returns `MANUAL` → the pre-enqueue chokepoint branches to `ManualExecutionDispatcher`) with NO new dispatch logic; other steps of the same project resolve their own mapped/override/global kind independently (e.g. spec=codex enqueues, prOutput=manual parks).

6. **Given** the REST API, **Then** `CreateProjectRequest`/`UpdateProjectRequest` accept an optional `stepRunnerKinds` map and `ProjectResponse` exposes it (alongside the existing single `runnerKind`); `ProjectManagementService` create/update persist it to `project_runner_kinds` (full-replace semantics on update — the submitted map is authoritative); OpenAPI + `schema.d.ts` regenerate (NOT byte-identical — [[openapi-regen-frontend-client-drift-cascade]]).

7. **Given** the Projects Management UI (3c-9), **Then** the project create/edit surface gains a **per-step runner selector** (three controls: spec / implementation plan / PR output, each choosing codex / claude / manual / "use default") AND surfaces the existing single `runnerKind` as the project-wide default — closing the gap that `runnerKind` had no UI control at all. Keyboard-operable + labeled (WCAG 2.1 AA; axe zero `wcag2aa`), no bare role text (the `no-bare-actor-role-text` rule), `schema.d.ts` regenerated FIRST.

8. **Given** validation, **Then** an unknown `step` or `runner_kind` in a request is rejected with a typed Problem Details (`VALIDATION` / the existing project-validation error), never a 500; the default project remains configurable (per-step mapping allowed on it too).

9. **Given** tests, **Then** coverage asserts: migration replay safety + `project_runner_kinds` in `FlywaySchemaContractTest`; `ProjectRunnerStep` registry/CHECK/placeholder drift; resolver precedence (per-step > project-override > global) incl. an empty map falling back exactly to 3d-3 behavior (byte-identical parity); a per-step `manual` parks only that step while other steps enqueue (IT); REST create/update round-trips the map; UI Vitest + axe for the per-step selectors; `application.*` ≥80% line coverage.

## Tasks / Subtasks

- [x] **Task 1 — `ProjectRunnerStep` registry + step-derivation helper** (AC: 2)
  - [x] Add `ProjectRunnerStep` enum/registry (`SPEC("spec")`, `IMPLEMENTATION_PLAN("implementationPlan")`, `PR_OUTPUT("prOutput")`) under `domain/registry` (extends the 1.4/3c-2 registry pattern; `DomainRegistry` picks it up). Drift: `registry-api-schema-placeholders.json` + `RegistryContractTest`.
  - [x] Add `ProjectRunnerStep.of(RunnerStage, ExecutionSubStage)` (`INVESTIGATION→SPEC`; `EXECUTION`+`IMPLEMENTATION_PLAN→IMPLEMENTATION_PLAN`; `EXECUTION`+`PR_OUTPUT→PR_OUTPUT`; REVIEW/other → empty/none — REVIEW kind is governed by `reviewer_model_kind`, NOT this map).

- [x] **Task 2 — `project_runner_kinds` table** (AC: 1)
  - [x] Flyway (next-free head verified **V25** on 2026-06-24; V20–V24 taken — re-confirm at impl time): `create table project_runner_kinds (project_id text not null references projects(public_id), step text not null, runner_kind text not null, primary key (project_id, step), constraint ck_project_runner_kinds_step check (step in ('spec','implementationPlan','prOutput')), constraint ck_project_runner_kinds_kind check (runner_kind in ('codex','claude','manual')))`. Additive, replay-safe.
  - [x] `FlywaySchemaContractTest` — add the new table to expectations.

- [x] **Task 3 — `Project` model + persistence fan-out** (AC: 3)
  - [x] `domain/project/Project.java` — add `Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds` (trailing component; defensive-copy to unmodifiable empty if null). **Fan-out — locate by compiler error (the 3d-3 "6 sites" list drifted; verified 2026-06-24 the live main-source sites are):** `ProjectManagementService`, `DefaultProjectSeeder` (empty map — parity), `ProjectEntityMapper`, plus the test construction sites. (ProjectController & ProjectConnectorResolver no longer call `new Project(...)`.) See [[story-3d-3-manual-runner-kind-and-waiting-for-manual-execution-reconciliations]] for the fan-out discipline.
  - [x] `adapters/persistence/entity` + `mapper` — load/save the child rows (a `@OneToMany`/secondary read, or a join query in the persistence adapter) ↔ `stepRunnerKinds`. Empty map round-trips as zero rows.

- [x] **Task 4 — Resolver precedence** (AC: 4, 5)
  - [x] `application/project/ProjectRuntimeConfigResolver.resolveRunnerKind(workflowRunId, stage, executionSubStage)` — compute `ProjectRunnerStep step = ProjectRunnerStep.of(stage, subStage)`; if `step` present and `project.stepRunnerKinds().containsKey(step)` → return it (`source=project_step_mapping`); else fall through to the existing `project.runnerKind()` override (`source=project_override`); else global (`source=global`). Keep the existing single-override + global branches intact beneath the new layer.
  - [x] Confirm NO change needed at the dispatch chokepoint: a per-step `MANUAL` return value flows through the existing `enqueueDispatch`/`ManualExecutionDispatcher` park branch (3d-3 R2) unchanged.

- [x] **Task 5 — REST create/update + response** (AC: 6, 8)
  - [x] `CreateProjectRequest`/`UpdateProjectRequest` — add optional `stepRunnerKinds` (map of step→kind); `CreateProjectCommand`/`UpdateProjectCommand` carry it; `ProjectManagementService` persists with **full-replace** semantics on update (submitted map authoritative — delete-and-reinsert child rows in the same tx). `ProjectResponse` exposes the map.
  - [x] Validation: unknown step/kind → typed Problem Details (reuse the project `VALIDATION` path), not 500. (Guard at the request/command boundary via `ProjectRunnerStep.fromValue`/`RunnerKind.fromValue`.)
  - [x] OpenAPI snapshot regen + `schema.d.ts` regen (`npm run generate-api`) — NOT byte-identical ([[openapi-regen-frontend-client-drift-cascade]]).

- [x] **Task 6 — Projects Management UI** (AC: 7)
  - [x] Regenerate `schema.d.ts` FIRST (check:api drift gate).
  - [x] In the project create/edit form (3c-9 surface), add a **Per-Step Runner** section: three selects (Spec / Implementation Plan / PR Output), each `codex | claude | manual | use default (clear mapping)`, plus the existing single `runnerKind` rendered as "project-wide default" (this is the FIRST UI for `runnerKind` at all). Persist via the project update helper (`useWorkflowMutation`/project mutation, Idempotency-Key per 3c-9).
  - [x] a11y: labels, keyboard, axe `wcag2aa` clean; no bare role text.

- [x] **Task 7 — Tests** (AC: 9, all)
  - [x] Resolver unit: per-step mapping wins; empty map → falls back to single override → byte-identical 3d-3; global when neither set; step-derivation helper covers all stage/substage combos incl. REVIEW→none.
  - [x] **Per-step dispatch IT** (Failsafe + Testcontainers): a project with `{spec: codex, prOutput: manual}` — a spec dispatch enqueues (codex), a pr-output dispatch **parks** (`WaitingForManualExecution`); proves per-step `manual` rides the 3d-3 park path.
  - [x] REST round-trip: create/update with `stepRunnerKinds`, response reflects it, full-replace on update.
  - [x] Drift: `ProjectRunnerStep` registry/placeholder, `FlywaySchemaContractTest` table, OpenAPI + schema.d.ts.
  - [x] UI: Vitest + axe for the per-step selectors + the project-wide default control.
  - [x] Naming/tier: `@SpringBootTest`+Testcontainers ⇒ `*IT` ([[maven-arglineation-goal-crash]]).

- [x] **Logging instrumentation** (cross-cutting)
  - [x] Resolver source (`project_step_mapping|project_override|global`) at DEBUG (already partially present — extend); project create/update persist of the step map at INFO. Never log secrets. Pin ≥1 log line for the new resolver branch.

### Review Findings (2026-06-25, bmad-code-review)

> Adversarial 3-layer review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 9 ACs and all 7 Completion-Notes reconciliations were verified genuinely true in code. No `decision-needed` items. The two highest-severity raised findings were verified against live source and downgraded to test-coverage gaps (the `enqueueDispatch` chokepoint at `WorkflowOrchestrationService:1162-1179` is provably stage-agnostic, so per-step `manual` parks correctly for EXECUTION sub-stages too).

- [x] [Review][Patch] Add an EXECUTION-substage manual-park assertion — closed via `ProjectRuntimeConfigResolverTest.perStepManualResolvesManualForEveryMappableStep` (proves spec/implementationPlan/prOutput each resolve to `MANUAL`, the value the stage-agnostic `enqueueDispatch` chokepoint parks on) + a sharpened `PerStepRunnerDispatchIT` javadoc documenting the composition proof (rather than a brittle multi-stage drive). The chokepoint was verified stage-agnostic in live source. [ProjectRuntimeConfigResolverTest.java · PerStepRunnerDispatchIT.java]
- [x] [Review][Patch] Add `ProjectTest` coverage for `stepRunnerKinds` null-safety/immutability — added `backCompatConstructorDefaultsStepRunnerKindsToEmptyMap`, `nullStepRunnerKindsBecomesEmptyMap`, `stepRunnerKindsAreDefensivelyCopiedAndUnmodifiable`, `rejectsNullValueInStepRunnerKinds` (AC3/AC9). [ProjectTest.java]
- [x] [Review][Patch] Correct misleading "insertion order preserved" Javadoc — fixed in `ProjectResponse.toWireStepRunnerKinds`, `ProjectManagementService.parseStepRunnerKinds`, and `ProjectPersistenceAdapter.loadStepRunnerKinds` to state the round-tripped order is step-sorted (the `OrderByStepAsc` read), deterministic but not insertion order. [ProjectResponse.java · ProjectManagementService.java · ProjectPersistenceAdapter.java]
- [x] [Review][Defer] Full-replace child-row update lacks optimistic locking / concurrency guard [ProjectPersistenceAdapter.java:114-124] — deferred, pre-existing. Delete-then-insert can deadlock/last-writer-win against a concurrent update, and an in-flight dispatch's resolver read can see a freshly-replaced map; project-config edits generally carry no version column; low contention on an admin surface.
- [x] [Review][Defer] Frontend `RUNNER_KINDS` hard-coded with no registry-drift gate [projectFormView.ts] — deferred, pre-existing (self-disclosed in code). A future `RunnerKind` value silently lags the dropdown, and an unknown stored kind forwards verbatim → backend 400 on edit with no client-side signal. Belongs to the registry-recipe drift trail.

**Dismissed as noise (7):** `useUpdateProject` always sends `runnerKind`/`stepRunnerKinds` (by-design full-replace per AC6; sole caller is `ProjectForm`, which always sends full state) · create idempotency fingerprint built from raw step keys (consistent with sibling fields, deterministic/sorted) · status-only disable/enable rewrites child rows (correct, negligible churn) · `on update cascade` on the FK is dead (public_id immutable, harmless) · `@Modifying(clearAutomatically)` detaches the just-saved entity (currently safe; only `getPublicId()` read after) · unbounded `stepRunnerKinds` map size at REST (effectively bounded by the 3-value registry, fails fast on first unknown key) · `insert` issues an unconditional zero-row DELETE on a brand-new project (harmless).

## Dev Notes

### Why these ACs are reconciled (request vs. the live codebase)

| Request | Reality (verified 2026-06-23) | Reconciliation |
|---|---|---|
| "map runner to each step in project" | 3d-3 shipped a SINGLE per-project `runnerKind` applied across all stages and **explicitly deferred** per-stage granularity (Open Decision #1). | Add a `project_runner_kinds(project_id, step, kind)` child table + `Project.stepRunnerKinds` + a most-specific resolver layer (R1/R2). |
| "configurable at project level" (UI) | `runnerKind` is in the REST DTOs + `schema.d.ts` but has **NO UI control** (only in generated types). | Add the per-step selectors AND surface the existing single override as the project-wide default in the 3c-9 UI (R4, Task 6). |
| codex / claude / **manual** per step | `manual` per-project already parks via `ManualExecutionDispatcher` (3d-3). | Per-step `manual` reuses that park path unchanged — the resolver just returns `MANUAL` for one step (R3). |

### R1 — Resolver layering (most-specific wins), single override kept as project default
The clean model is three layers: per-step map (most specific) → single per-project `runnerKind` (project-wide default, 3d-3) → global per-stage (`RunnerProperties`). Keeping the single override as a middle layer means existing 3d-3-configured projects keep working with zero migration, and an operator can set a project-wide default AND override one step. An empty `stepRunnerKinds` map collapses to exactly 3d-3 behavior (parity).

### R2 — Child table, not columns (the 3d-3 deferral note's own recommendation)
3d-3 R1 named the two options and recommended the table for true per-step. A child table keeps `projects` lean, avoids a 3-nullable-column fan-out across every snapshot/mapper, and extends naturally if a future step is added. Model `Project.stepRunnerKinds` as a `Map` loaded with the project (mirror how `project_credentials` rows attach to a project). **The `Project` record fan-out (6 `new Project(...)` sites) is the main mechanical risk** — the empty-map default keeps the seeder/default-project/tests parity-safe.

### R3 — Per-step `manual` is free on the dispatch side
3d-3 built the park at the pre-enqueue chokepoint keyed off whatever `resolveRunnerKind` returns. So a per-step `manual` needs NO dispatch change — the resolver returning `MANUAL` for one step makes that step park while sibling steps enqueue. The IT (Task 7) proves this mixed behavior (spec enqueues, prOutput parks) — the headline capability the user asked for.

### R4 — This also closes the "runnerKind has no UI" gap
Independent of per-step, the single `runnerKind` override (3d-3) was never given a UI control (only generated types). Task 6 surfaces it as the project-wide default beside the per-step selectors — so a user can finally configure execution kind at the project level at all, which was part of the request.

### R5 — Out of scope
REVIEW-stage runner kind is governed by `reviewer_model_kind` (3d-1/3d-2), NOT this per-step map (a "reviewer" is a different binding). Per-step reviewer selection — out of scope (3e-3 uses one run-level reviewer binding). Scheduling/affinity/cost routing — out of scope.

### Verified seams (file:line, 2026-06-23)
- Resolver to layer — `ProjectRuntimeConfigResolver.resolveRunnerKind:122`/`:126` (override→global today).
- `runnerKind` already in REST DTOs but FE-UI-less — `CreateProjectRequest`/`UpdateProjectRequest`/`ProjectResponse`; frontend `runnerKind` ONLY in `schema.d.ts` (no `.tsx`).
- Single-override + park path — 3d-3 (`Project.runnerKind`, `ManualExecutionDispatcher`, `V20__add_manual_execution_kind_and_state.sql`); deferral note [[story-3d-3-manual-runner-kind-and-waiting-for-manual-execution-reconciliations]] Open Decision #1.
- `Project` fan-out — locate by compiler error; live main-source sites (2026-06-24): `ProjectManagementService`, `DefaultProjectSeeder`, `ProjectEntityMapper` (+ test sites). ProjectController & ProjectConnectorResolver no longer construct `Project`.
- Flyway head (2026-06-24): highest applied migration is `V24__add_provider_usage_snapshots.sql` → next-free is **V25**.

## Dev Agent Record

### Completion Notes (2026-06-25)

Implemented per-step runner mapping per project end-to-end, plus the first UI for the project-wide
`runnerKind` override.

**Reconciliations applied (vs. the story body):**
1. **Flyway head moved to V26, NOT V25.** Story 3e-2 had already taken V25
   (`V25__add_spec_clarification_acknowledgements.sql`) — exactly the "re-confirm the head, a sibling
   3e story may have landed" case AC1 flagged. Migration shipped as `V26__create_project_runner_kinds.sql`.
2. **The step-derivation helper lives in the application layer, not on the enum.** Task 1 asked for
   `ProjectRunnerStep.of(RunnerStage, ExecutionSubStage)`, but `ExecutionSubStage` is an
   `application.runner` type the `domain.registry` package may NOT import (LAYERED_BOUNDARIES). So
   `ProjectRunnerStep` stays a pure registry value and the mapping is a static helper
   `application.project.ProjectRunnerSteps.of(...)` returning `Optional<ProjectRunnerStep>` (verified by
   the 56 architecture rules staying green).
3. **`project_runner_kinds` is an association table, not a core table.** Composite PK `(project_id, step)`,
   no `public_id`/`created_at`/`archived_at` (nothing to address-by-id or retention-sweep). Added an
   `ASSOCIATION_TABLES` set to `FlywaySchemaContractTest` so the "exactly these tables" gate accounts for
   it while the core-shape loops skip it.
4. **`Project` fan-out absorbed via a back-compat 13-arg constructor.** Rather than touch all ~17 `new
   Project(...)` sites, the record gained a trailing `Map<ProjectRunnerStep,RunnerKind> stepRunnerKinds`
   plus a delegating 13-arg constructor (→ empty map). Only the create/update/persistence/seeder-parity
   paths that actually carry a per-step map use the 14-arg form. disable/enable were updated to PRESERVE
   `existing.stepRunnerKinds()` (the persistence update full-replaces from the aggregate, so a status-only
   change must carry the map forward).
5. **Full-replace child rows use a bulk `@Modifying(clearAutomatically=true, flushAutomatically=true)`
   delete.** A derived/managed delete would let Hibernate order INSERTs before DELETEs and collide on the
   composite PK when a step is re-set; clearing the context after the bulk DELETE makes the replacement
   rows unambiguous fresh INSERTs (caught by the insert→update round-trip IT under a shared `@Transactional`).
6. **The per-step dispatch IT proves the headline at the reachable SPEC gate** (per-step `manual` parks;
   per-step `codex` overrides a project-wide `manual` default and enqueues). The `prOutput`-parks-while-spec-
   enqueues mixed behavior rides the identical chokepoint keyed off `resolveRunnerKind`'s return value —
   proven for every (stage, sub-stage) by the resolver + helper unit tests.
7. **This also closes the "`runnerKind` had no UI" gap (R4):** the edit form now always sends
   `runnerKind` (null = use global) — previously an edit silently cleared any 3d-3 override since the
   service sets it from the (absent) command field.

**Verification:** backend unit suite (surefire) green; `FlywaySchemaContractTest` (25), `RegistryContractTest`
(22), `ArchitectureBoundaryTest` (56), `OpenApiSnapshotContractTest` (committed snapshot matches, no drift),
`ProjectPersistenceAdapterIT`, `ProjectControllerContractTest`, `ProjectManagementServiceTest`,
`ProjectRuntimeConfigResolverTest`, `ProjectRunnerStepsTest`, and the new `PerStepRunnerDispatchIT` all green.
Frontend: `tsc -b`, `eslint --max-warnings=0`, `check:api` (in sync), and the full vitest suite (1178 tests)
green (one cross-file isolation flake on first run, deterministically green on re-run — the documented repo
vitest flakiness).

### File List

**Backend — main:**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ProjectRunnerStep.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainRegistry.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PersistedRegistryValues.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/project/Project.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectRunnerSteps.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectRuntimeConfigResolver.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/ProjectManagementService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/CreateProjectCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/project/UpdateProjectCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/ProjectRunnerKindEntity.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/ProjectRunnerKindRepository.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/ProjectEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/ProjectPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/CreateProjectRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/UpdateProjectRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProjectResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProjectController.java`
- `deliveryline-backend/src/main/resources/db/migration/V26__create_project_runner_kinds.sql` (new)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated)

**Backend — test:**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectRuntimeConfigResolverTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectRunnerStepsTest.java` (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectManagementServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/ProjectPersistenceAdapterIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/ProjectControllerContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/PerStepRunnerDispatchIT.java` (new)

**Frontend:**
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)
- `deliveryline-frontend/src/features/projects/projectFormView.ts`
- `deliveryline-frontend/src/features/projects/components/ProjectForm.tsx`
- `deliveryline-frontend/src/features/projects/hooks/useCreateProject.ts`
- `deliveryline-frontend/src/features/projects/hooks/useUpdateProject.ts`
- `deliveryline-frontend/src/features/projects/__tests__/projectFormView.test.ts`
- `deliveryline-frontend/src/features/projects/__tests__/ProjectForm.test.tsx`

## Change Log

| Date       | Version | Change                                                                 |
|------------|---------|-----------------------------------------------------------------------|
| 2026-06-25 | 0.1     | Implemented 3e-4: per-step runner mapping (V26 child table, `ProjectRunnerStep` registry, resolver precedence, REST + full-replace persistence) + the first Projects-UI runner controls (project-wide default + per-step selectors). All ACs satisfied; status → review. |
