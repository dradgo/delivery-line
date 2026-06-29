## Epic 3j: Ticket-Type Workflows — Bug vs Feature

The governed workflow stops treating every ticket the same. Today orchestration runs a **single hardcoded spine** — `spec(INVESTIGATION) → plan → implementation(EXECUTION / ExecutionSubStage{IMPLEMENTATION_PLAN, PR_OUTPUT}) → review(REVIEW)` — with the next stage chosen by `(stage, subStage)` in `RunnerBroker.onResult` (a `switch`, **not** a table). But a **bug** and a **feature** want different shapes: a bug should be **reproduced and root-caused** before a heavy spec is written, then carried by a **lightweight fix-plan** gate, then implemented → reviewed → delivered; a feature keeps the full spec-first spine. This epic introduces a bounded **workflow-profile** concept that orchestration consults so the *spine itself branches by ticket type*. Two built-in profiles ship — **`bug`** and **`feature`** — with **`feature` as the default**; the profile is **resolved from the connector ticket type** via a per-project `ticketType → WorkflowProfile` map, with an **operator override at intake**, and the **resolved profile is persisted on the run** so the orchestration decision is deterministic and auditable. Feature-profile runs remain **byte-identical** to the pre-3j spine (parity).

**Why this epic exists (net-new capability):** Bugs and features are governed identically today because the spine is hardcoded and type-blind — there is no place for orchestration to ask "what kind of work is this?" A bug forced through the full feature spine spends a heavy spec phase on something that first needs a reproduction and a root cause; conversely, a lightweight bug gate on a feature would skip the governance a feature needs. This epic adds the missing **type → profile → spine-shape** seam as **new product scope** (FR84/FR85). It is the **one** epic in the 3g–3l family that touches the **core stage-selection logic** in `RunnerBroker.onResult`, which is why it must be sequenced carefully against the in-flight Epic 3f split work and the Epic 3h delivery-tail work that touch the **same** broker path.

This is a **ticket-type-workflow** epic, not a connector or delivery-tail feature — it does not fit Epic 3i or 3h. It is **inserted after Epic 3i and before Epic 3k** purely for sequencing (it consumes the 3i-4 Sentry→bug promotion, which lands on the `bug` profile). Source: this sprint-change-proposal.

**Prerequisites & reused substrates (all done):**
- **The hardcoded orchestration spine + `(stage, subStage)` selection** (`RunnerBroker.onResult`, `RunnerStage`, `ExecutionSubStage{IMPLEMENTATION_PLAN, PR_OUTPUT}`) — the profile is the new discriminator the next-stage `switch` consults; the **recommended** bug **triage/repro** phase rides the existing **INVESTIGATION** stage with a bug **sub-stage** rather than a brand-new `RunnerStage` (lighter `switch(stage)` fan-out).
- **The `Ticket` domain type** — already carries the connector ticket type; the profile resolver reads it (additive nullable type field at END only if not already present).
- **Per-project child-table config precedent** (`project_runner_kinds`, 3e-4) + **`ProjectRuntimeConfigResolver`** — the `ticketType → WorkflowProfile` map is a sibling child table resolved through the same 3-layer resolver.
- **Both `runner.mjs` entrypoints + their offline mocks** — emit the bug-triage artifact deterministically; the bug prompts/gate copy are profile-specific.
- **`FlywaySchemaContractTest`** — the new map child table + the additive nullable `workflow_profile` run column are drift-tested here.
- **3i-4 Sentry error-source promotion** (done in 3i) — promotes a Sentry issue onto the **`bug`** profile, exercising the bug path end-to-end from a real intake.

**ADR note:** no new ADR — this epic extends the existing orchestration model rather than reshaping it; the profile branch and its parity guarantee are recorded against the orchestration-spine documentation and cross-referenced from the 3h delivery-tail ADR-0030 (shared `onResult` path). The bug-path sub-stage decision (ride INVESTIGATION vs new `RunnerStage`) is recorded inline in story 3j-2.

### Story List (3 stories)

```
Foundation
3j-1   Workflow-profile concept + ticket-type resolution (registry, per-project map, intake override, run.profile)   [item 7]

Orchestration branch
3j-2   Bug workflow path — triage/repro phase + lightweight fix-plan; orchestration branches by profile               [item 7]

Visibility
3j-3   FE — profile/type visibility on queue + detail; intake type override control                                   [item 7]
```

> Story 3j-1 is the foundation — it introduces the `WorkflowProfile` registry, the per-project map, the intake override, and the persisted `workflow_profile` run column, **without** changing orchestration (every run still takes the feature spine). 3j-2 is the integration story — it makes `RunnerBroker.onResult` consult `run.workflow_profile` and branches the `bug` profile into the triage/repro + lightweight-fix-plan shape, holding feature parity. **3j-2 depends on 3j-1** (the persisted profile). 3j-3 (FE) depends on the read-model fields surfaced by 3j-1 and the intake override path. Detailed, reconciled implementation stories live at `{implementation_artifacts}/3j-1..3j-3-...md`.

---

### Story 3j-1: Workflow-Profile Concept + Ticket-Type Resolution

As an operator submitting governed work,
I want each run to carry a workflow profile derived from its ticket type (with an override I can set at intake),
So that the system knows whether to govern the run as a bug or a feature — deterministically and auditably — without yet changing how any run executes.

**Acceptance Criteria:**

1. **Given** a new `WorkflowProfile` registry, **Then** two built-in profiles are added — `BUG("bug")` and `FEATURE("feature")` — with `FEATURE` the **default**; registry value + drift coverage (the new-registry three-sites discipline). The registry is **closed** to these two (a general template engine is an explicit forward option, not this epic).
2. **Given** the `Ticket` domain type (which carries the connector ticket type), **Then** the connector ticket type is exposed for resolution (additive nullable field appended at the END only if not already present — no reshaping of `Ticket`); a ticket with no resolvable type yields `null` and falls through to the default profile.
3. **Given** the per-project child-table config precedent (`project_runner_kinds`, 3e-4) at the **next-free Flyway head**, **Then** a `project_workflow_profiles(project_id FK, ticket_type text, workflow_profile text, PK(project_id, ticket_type))` map child table is created; replay-safe; in `FlywaySchemaContractTest`. A `ProjectRuntimeConfigResolver` method resolves `(projectId, ticketType) → WorkflowProfile`, returning `FEATURE` when the project has no mapping for that type (parity).
4. **Given** intake (`WorkflowCommandService.submit` / the 3i-2 intake-browse path), **Then** the profile is resolved from the ticket type via the per-project map, **with an explicit operator override** accepted at submit (REST + CLI) that wins over the map; an unknown override value is rejected with an existing validation error (no new error code).
5. **Given** the resolved profile must drive a later orchestration decision, **Then** it is **persisted on the run** as an additive **nullable** `workflow_profile` column on `workflow_runs` (next-free Flyway head, indexed only if needed, replay-safe, in `FlywaySchemaContractTest`); legacy/pre-3j rows stay `NULL` and **resolve to `FEATURE`** at read time (parity — no backfill).
6. **Given** the read model, **Then** `WorkflowRunSnapshot` / run summary + detail views expose the run's `ticketType` and resolved `workflowProfile` (nullable, defaulting to `feature` for legacy rows); the summary **exact-field contract test** (`containsExactlyInAnyOrder`) is updated for the new field to avoid the silent CI-only break; OpenAPI + `schema.d.ts` regenerate.
7. **Given** orchestration is **unchanged** in this story, **Then** every run — bug or feature — still takes the existing feature spine; 3j-1 only resolves and persists the profile (the branch lands in 3j-2). A normal feature submit is byte-identical to pre-3j.
8. **Given** tests, **Then** coverage asserts: `WorkflowProfile` registry drift; `(projectId, ticketType) → profile` map resolution + default-to-`feature` fallback; intake override wins over the map; persisted `workflow_profile` round-trips and legacy `NULL → feature` parity; Flyway/CHECK drift for the map table + run column; summary exact-field contract updated; OpenAPI/`schema.d.ts` regen; `application.*` ≥80% coverage.

### Story 3j-2: Bug Workflow Path — Triage/Repro + Lightweight Fix-Plan

As an operator running a bug-profile ticket,
I want the run reproduced and root-caused first, then carried by a lightweight fix-plan gate before implementation,
So that bugs are governed by a path that fits them — repro → fix-plan → implement → review → deliver — while feature runs keep the full spec-first spine unchanged.

**Acceptance Criteria:**

1. **Given** the orchestration spine and `(stage, subStage)` next-stage selection in `RunnerBroker.onResult`, **Then** the selection consults `run.workflow_profile` (resolved/persisted by 3j-1; `NULL → feature`) as the **branch discriminator** — the `switch` is widened, **not** replaced by a table.
2. **Given** a `bug`-profile run (recommended, recorded in this story's ADR note), **Then** the **triage/repro** phase **rides the existing INVESTIGATION stage with a bug sub-stage** (e.g. `InvestigationSubStage.BUG_TRIAGE`) — producing a **reproduction + root-cause** artifact — rather than introducing a brand-new `RunnerStage` (the lighter `switch(stage)` fan-out the locked decision prefers); the alternative (new `RunnerStage`) is documented as rejected for fan-out cost.
3. **Given** the bug path after triage, **Then** the run proceeds to a **lightweight fix-plan** gate — a lighter analogue of the feature spec-approval gate (reusing the existing gate-park + approve/reject seam, **no** new `WorkflowState`) — then to `implementation(EXECUTION / PR_OUTPUT) → review(REVIEW) → deliver`, so the bug path keeps a governance gate but skips the heavy spec.
4. **Given** a `feature`-profile run (and any legacy `NULL → feature` run), **Then** it is **byte-identical** to the pre-3j spine (`spec → plan → implementation → review → deliver`) — the parity hot path: no extra stage, sub-stage, dispatch, or event for feature runs.
5. **Given** the runner layer, **Then** both `runner.mjs` entrypoints emit the **bug-triage artifact** (reproduction + root cause) deterministically when dispatched for the bug sub-stage (byte-identical across the two entrypoints), and **both offline mocks** emit a deterministic triage artifact; the **bug prompts and fix-plan gate copy are profile-specific** (the bug path asks the model to reproduce + root-cause, the fix-plan gate frames a fix rather than a full spec).
6. **Given** redaction + provenance, **Then** the bug-triage artifact (repro steps, stack/root-cause context) passes the redaction/secret-fixture gate as any outbound artifact; nothing secret is logged (ids/lengths only); the resolved profile that drove the branch is recorded in the governed run history (auditable).
7. **Given** the in-flight broker work, **Then** this story's `onResult` change is **serialized** against Epic 3f (split fan-out) and Epic 3h (delivery-tail push relocation), which touch the **same** path — the branch is added without regressing those arms (parity ITs for the split + delivery arms remain green).
8. **Given** tests, **Then** a full IT proves: a `bug`-profile run takes INVESTIGATION/BUG_TRIAGE → lightweight fix-plan gate → implementation → review → deliver, producing the triage artifact; a `feature`-profile run and a legacy `NULL` run are byte-identical to pre-3j (parity); both runners + both mocks emit the deterministic triage artifact; the `onResult` branch coexists with the split + delivery arms; `application.*` ≥80% coverage.

### Story 3j-3: FE — Profile/Type Visibility + Intake Override Control

As an authorized user scanning the queue or submitting work,
I want to see each run's ticket type and resolved workflow profile, and to override the profile at intake,
So that the bug-vs-feature shape of a run is visible at a glance and I can correct the profile before a run starts.

**Acceptance Criteria:**

1. **Given** the run read model (3j-1), **Then** the Run Review Queue row and the run detail page render a **profile/type badge** showing the run's `ticketType` and resolved `workflowProfile` (e.g. a `bug` / `feature` badge), with a `feature` fallback rendered for legacy `null` rows (parity — never "unknown/stuck").
2. **Given** the intake/browse view (the 3i-2 filtered-intake surface), **Then** it offers a **profile override control** (defaulted to the type-resolved profile, operator can switch to the other built-in profile) that is carried into the submit payload (the 3j-1 override path).
3. **Given** `schema.d.ts` is regenerated **first** from the 3j-1 OpenAPI changes, **Then** the badge + override control are typed against the regenerated client (no hand-authored types).
4. **Given** accessibility, **Then** the badge + override control meet WCAG 2.1 AA and are **axe-clean**; the announcer reflects an override change (the `useLiveAnnouncement` one-commit-lag pattern is honored — assert via `waitFor`).
5. **Given** the FE traps, **Then** the implementation honors the react-refresh no-fn-export rule (helpers in sibling `.ts`) and the TanStack `validateSearch` discipline if the override is reflected in URL state.
6. **Given** tests, **Then** **Vitest** covers: profile/type badge renders for `bug` / `feature` / legacy-`null`→`feature` fallback; the intake override control defaults to the resolved profile and submits the chosen override; axe-clean on the badge + control + intake view; the announcer reflects the override (via `waitFor`).

---

### Cross-Cutting Notes

- **CRITICAL — shared `onResult` path serialization:** 3j-2 branches the next-stage selection in **`RunnerBroker.onResult`**, the **same** code path mutated by in-flight **Epic 3f** (split commit fan-out / rollup) and **Epic 3h** (delivery-tail push relocation + BUILD/LINT/delivery gates). These three epics **must be serialized** on that path — land them in a deliberate order, rebase each onto the prior, and keep parity ITs for the split arm and the delivery arm green as the profile branch is added. Do **not** develop 3j-2 in parallel with an open 3f/3h broker change.
- **Foundation-gate widening:** the new `WorkflowProfile` registry (`bug`, `feature` — closed), the `project_workflow_profiles` map child table + the additive nullable `workflow_profile` run column (Flyway, `FlywaySchemaContractTest`), the recommended `InvestigationSubStage.BUG_TRIAGE` sub-stage discriminator (no new `RunnerStage` — the deliberately lighter `switch(stage)` footprint), and the additive bug-triage runner-contract artifact are drift-tested at the existing gates — folded into each story, **no separate gate story**. **No** new `WorkflowState` / `AllowedAction` / `WorkflowEventType` / `DomainErrorCode` (the bug path reuses the existing gate-park + approve/reject seam and the run-history record — lighter foundation-gate footprint than 3f/3h).
- **Parity is the contract:** a `feature`-profile run and every legacy `NULL → feature` run must be **byte-identical** to the pre-3j spine — no extra stage, dispatch, event, or read-model behavior on the feature hot path. Parity ITs guard this in both 3j-1 (no orchestration change) and 3j-2 (the branch).
- **Read-model / OpenAPI:** 3j-1 widens the summary + detail responses (`ticketType`, `workflowProfile`) — **update the summary exact-field contract test** (`containsExactlyInAnyOrder`) and regenerate OpenAPI + `schema.d.ts` (regen `schema.d.ts` **first** for the 3j-3 FE work).
- **FRs covered:** **3j-1** + **3j-2** deliver **FR84** (a governed run follows a workflow profile selected by its ticket type) and **FR85** (a distinct bug path: reproduction/triage + lightweight fix-plan); **3j-3** surfaces the profile/type + intake override. This epic introduces **new PRD scope** (FR84/FR85) — it is not an activation of deferred work.
- **Cross-epic deps:** consumes **3i-4** Sentry→bug promotion (a promoted Sentry issue lands on the `bug` profile, exercising the bug path from a real intake) and the **3i-2** intake-browse surface (host for the profile override control). Compose-with: 3h-3 BMAD reviewer mode could become a per-profile reviewer default (forward option).
- **Forward options (out of scope):** a general workflow-template engine (arbitrary profiles); more built-in profiles (`spike` / `chore` / `hotfix`); per-profile reviewer-mode defaults (compose with the 3h-3 BMAD mode); embedding the override into the connector-side ticket type taxonomy.
