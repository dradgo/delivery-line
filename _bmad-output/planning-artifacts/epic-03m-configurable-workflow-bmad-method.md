## Epic 3m: Configurable Workflow Definitions + BMAD-Method Preset

A single local-first operator gains the ability to run a project through a **configurable, data-driven sequence of steps** instead of the hardcoded spec → implement → review → integration pipeline. A **workflow definition** is an ordered list of **step definitions**; each step binds an **executor** — an existing runner kind (`claude`/`codex`/`manual`), a per-project credential, and (for method presets) a **BMAD role prompt** that tells the runner which BMAD agent to act as. The epic ships the **full BMAD method** (analyst → PM/PRD → UX → architect → epics/stories → dev-story → code-review → retro) as the first built-in preset definition, and a **basic editor** so operators can author their own custom definitions. Step outputs chain into the next step's context bundle (reusing FR54), human gates park between phases (reusing `WaitingForReview`), and every run stays inspectable and recoverable through the existing Epic 4 machinery.

This epic deliberately **adds a parallel, definition-driven run path additively** — it does **not** refactor the shipped Epic 1–4 hardcoded state machine. A project with no definition binding is byte-identical to pre-3m. BMAD runs as an *alternate* definition alongside the legacy pipeline, not as a replacement. Native branching/loop transitions are **out of scope**: BMAD's feedback loops (spec revision, architecture rework, dev↔review) are expressed via operator-driven **rerun-from-step** (Epic 4, FR31). The engine is **BMAD-agnostic** — BMAD lives entirely in seed data + a role-prompt catalog; the sequencer contains no method-specific logic.

The central architectural decision — whether definition-driven runs reuse the existing `workflow_runs` state machine with a generic current-step cursor, or introduce a parallel run type — is **settled up front by an ADR + spike (story 3m-1)** before any dependent story starts. Source: correct-course discussion `sprint-change-proposal-2026-07-19.md`. FRs covered: FR-Nx1–FR-Nx5 (provisional, pending PRD insertion).

**Documentation increment (owned inside Epic 3m):** Epic 3m completion requires a **configurable-workflow + BMAD-method walkthrough** doc — select a definition, bind executors per step, run the full method, approve/reject/rerun at gates, inspect typed artifacts, author a custom definition — shipped alongside the feature stories (story 3m-11).

**Prerequisites:** Epic 3c complete (Project aggregate + per-project encrypted credentials + `ProjectConnectorResolver`); Epic 3d complete (runner-kind registry incl. `manual`, `WaitingForManualExecution`, advisory reviewer model, step log viewer); Epic 4 available (rerun-from-step FR31/FR32, recovery/inspection). The runner-contracts / runner-broker seams, the FR54 context-bundle mechanism, and the state registry + `workflow_runs.current_state` CHECK (story 1.5) are the seams this epic extends.

**ADRs:** `docs/adr/00NN-configurable-workflow-run-model.md`, `docs/adr/00NN+1-per-step-executor-binding.md` (both authored under story 3m-1).

### Story List (11 stories)

```
Spike & Foundations
3m-1   Spike + ADR: executor-invokes-BMAD-agent proof + run-model decision
3m-2   WorkflowDefinition + StepDefinition schema + registries (Flyway)

Engine & Binding (headline capability)
3m-3   Definition-driven run engine
3m-4   Per-step executor binding + resolution (REST + CLI)

BMAD Preset
3m-5   BMAD role-prompt catalog + 8-phase preset seed
3m-6   Typed BMAD artifacts + phase-to-phase chaining
3m-7   Human-gated steps + rerun-from-step for BMAD loops

Surfaces
3m-8   UI: definition selection + per-step executor config + BMAD run inspection
3m-9   Basic custom-definition editor (UI + CLI)

Cross-cutting
3m-10  Foundation-gate widening + test-suite extension
3m-11  Documentation increment
```

**Sequencing:** 3m-1 → 3m-2 → (3m-3, 3m-4) → 3m-5 → 3m-6 → 3m-7 → (3m-8, 3m-9) → 3m-10 → 3m-11.

**Follow-up (added by 3m-1 code-review, 2026-07-19):** `3m-1b` — *Headless BMAD-agent live-run proof*. Split out of 3m-1 (whose AC2 recorded-proof was deferred): drive a runner headlessly as a vendored BMAD skill with Docker + egress + a real credential, capture a real transcript, and finalize the provisional per-phase automatable-vs-`manual` map (3m-1 findings §3) that seeds 3m-5's defaults. Human-gated (needs a live credential); does not structurally block 3m-2/3m-3. Runbook: `docs/spikes/3m-1-findings.md` §4.

---

### Story 3m-1: Spike + ADR — Executor-Invokes-BMAD-Agent Proof & Run-Model Decision

As a backend developer,
I want a time-boxed spike that proves an existing runner can headlessly act as a named BMAD agent and return a typed artifact, plus an ADR fixing the run-model,
So that every dependent story is built on a verified invocation path and a settled state-machine decision.

**Acceptance Criteria:**

1. **Given** the spike gate, **Then** the story is a **spike** — its deliverable is a recorded proof + ADRs, not production wiring; any throwaway code is clearly marked and not merged into the runtime path.
2. **Given** the `claude`/`codex` runner kinds + `runner.mjs`, **When** the spike drives a runner with a **BMAD role prompt** (e.g. "act as `bmad-create-architecture`"), **Then** it demonstrates the runner invoking that BMAD agent **headlessly** and returning output that conforms to a candidate **typed-artifact** schema — the proof is recorded (transcript/output captured).
3. **Given** the "signal unavailable" contingency (mirrors 3d-7 D5), **If** headless BMAD-agent invocation is *not* reliably achievable for a runner kind, **Then** the spike records that finding and the epic falls back to the **`manual` runner kind** for that phase as the documented Phase-1 path, rather than fabricating an invocation mechanism.
4. **Given** the central run-model question, **Then** **ADR `00NN-configurable-workflow-run-model`** decides whether definition-driven runs (a) reuse the existing `workflow_runs` state machine with a generic *current-step cursor* over the definition, or (b) introduce a parallel run type — with reuse option (a) preferred unless the spike surfaces a blocker; the decision records how `WaitingForReview`/`WaitingForManualExecution` gates and Epic 4 recovery attach.
5. **Given** the executor model, **Then** **ADR `00NN+1-per-step-executor-binding`** records the binding shape (runner kind + per-project credential + BMAD role prompt) and its resolution through `ProjectConnectorResolver`, confirming **no new credential subsystem** (reuses 3c `project_credentials`).
6. **Given** BMAD's feedback loops, **Then** the ADR records the Phase-1 decision to express loops via Epic 4 **rerun-from-step** (FR31) rather than native branching, and notes what a future native-loop epic would add.
7. **Given** the spike outcome, **Then** a short findings note lists confirmed assumptions, the chosen run-model, and any story-scope adjustments the proof forces (feeding back into 3m-2..3m-11 before they start).

### Story 3m-2: `WorkflowDefinition` + `StepDefinition` Schema + Registries (Flyway)

As a backend developer,
I want a persisted workflow-definition model with ordered steps and per-step executor-binding fields, plus registry/prefix entries,
So that "steps as data" has a durable, drift-tested home the engine and editor can build on.

**Acceptance Criteria:**

1. **Given** the current migration head, **When** the backend starts, **Then** Flyway applies the **next free version** adding `workflow_definitions` and `workflow_definition_steps` tables (additive, replay a no-op).
2. **Given** `workflow_definitions`, **Then** it stores `id` (`wfd_` public-id prefix registered), `key text` (e.g. `bmad-method`), `name`, `kind` (CHECK in a `definition_kind` value set: `builtin`/`custom`), `archived_at timestamptz NULL` (retention-readiness rule), `created_at`; a **partial unique index** enforces one active definition per `key` where `archived_at is null` (per the "one active per key" pattern).
3. **Given** `workflow_definition_steps`, **Then** each row stores `id` (`wfs_` prefix registered), `definition_id` FK, `step_index int NOT NULL`, `step_key text` (e.g. `analyst`,`pm`,`architect`; CHECK in a `bmad_step_key` value set for preset steps, free-text allowed for custom), `runner_kind` (reuses the existing runner-kind registry incl. `manual`), `bmad_role text NULL` (the role-prompt selector), `human_gated boolean NOT NULL DEFAULT false`, `produces_artifact_kind text` (CHECK in an `artifact_kind` value set — see 3m-6), plus a unique index on `(definition_id, step_index)`.
4. **Given** the per-project selection, **Then** an additive column binds a `Project` (Epic 3c aggregate) to a chosen `workflow_definition_id` (nullable → default hardcoded pipeline when null), so selecting BMAD is **strictly opt-in** and a project with null is byte-identical to pre-3m.
5. **Given** the central registries + drift-test pattern (story 1.4 / 3c-2), **Then** `definition_kind`, `bmad_step_key`, `artifact_kind`, and the `wfd_`/`wfs_` prefixes are added to the authoritative registries and drift-tested against DB CHECK ↔ API schema ↔ any frontend allowed-value lists.
6. **Given** new domain error codes as needed (e.g. `WORKFLOW_DEFINITION_NOT_FOUND`, `STEP_EXECUTOR_NOT_CONFIGURED`, `DEFINITION_STEP_INDEX_CONFLICT`), **Then** they follow the **DomainErrorCode three-sites rule** (ProblemDetailsCatalog + registry-api-schema-placeholders manifest) verified under `-Pfoundation-gate`.
7. **Given** ArchUnit boundaries, **Then** definition/step domain logic lives in `application.workflow` (or a new `application.definition` slice) with **no adapter imports leaking into the domain**; executor binding reuses the SPI ports, not adapter classes.
8. **Given** tests, **Then** coverage asserts: migration replay-safety, registry/prefix/CHECK drift for all three new value sets, the partial-unique "one active per key" constraint, the nullable project→definition binding defaults to the legacy pipeline, and no existing pipeline behavior changes when the binding is null.

### Story 3m-3: Definition-Driven Run Engine

As a backend developer,
I want a generic sequencer that executes a workflow definition's steps in order,
So that a project bound to a definition (BMAD or custom) runs step-by-step through the same dispatch/park/inspect machinery as the built-in pipeline.

**Acceptance Criteria:**

1. **Given** the run-model ADR (3m-1 §4), **When** a run starts on a project bound to a `workflow_definition_id`, **Then** the engine walks `workflow_definition_steps` by `step_index`, tracking a **current-step cursor** on the run per the ADR's chosen model — the legacy hardcoded pipeline path is untouched for null-binding projects (byte-identical parity asserted).
2. **Given** each step, **When** the engine advances to it, **Then** it dispatches the step through the **existing runner broker / `runner-contracts`** using that step's resolved executor (story 3m-4), records a **`runner_executions` row** (FR53) for the step, and appends a governed **`WorkflowEventType`** for step-start (mirrored into the registry + **both fixture sites**; OpenAPI enum regenerated byte-identically).
3. **Given** FR54 context bundling, **When** step N completes, **Then** the engine composes step N+1's **context bundle** from the accumulated run context **plus step N's output artifact**, so each phase sees the prior phases' outputs (bundle inspectable per FR55).
4. **Given** a `human_gated` step, **When** the step's output is produced, **Then** the run **parks** in the appropriate gate state (`WaitingForReview`, reusing 3b/3d) before advancing; a non-gated step advances automatically to the next `step_index`.
5. **Given** the `manual` runner kind on a step, **Then** the engine parks in `WaitingForManualExecution` (reuses 3d-3/3d-4) exactly as the built-in pipeline does — no new manual path.
6. **Given** the final step, **When** it completes (and any gate clears), **Then** the run transitions to the terminal `completed` state; the full step-by-step history is inspectable (FR22–FR25) showing which executor acted at each step (FR23).
7. **Given** a step-dispatch failure (runner error/timeout), **Then** the run enters the standard failed/stalled handling (Epic 4) exposing failed step, last successful step, and next safe action (NFR3) — a mid-definition failure never corrupts the run (NFR6).
8. **Given** ArchUnit + boundaries, **Then** the engine lives in application and reaches runners via SPI ports only (no adapter imports); the sequencer contains **no BMAD-specific logic** (BMAD is pure data from 3m-5).
9. **Given** tests, **Then** coverage asserts: ordered step traversal, per-step `runner_executions` + event + fixture/OpenAPI drift, output→next-bundle chaining (FR54), gate parking (human + manual), completion transition, mid-definition failure surfacing via Epic 4, and null-binding legacy-pipeline parity.

### Story 3m-4: Per-Step Executor Binding + Resolution (REST + CLI)

As an operator,
I want to bind an executor (runner kind + credential + BMAD role) to each step of a definition and have the engine resolve it at dispatch,
So that "configurable executors per step" is real — each phase runs on the model/agent I chose.

**Acceptance Criteria:**

1. **Given** a definition's steps, **Then** a governed **REST endpoint + CLI command** (story 1.7 parity) let an operator set, per step, the `runner_kind` (`claude`/`codex`/`manual`), the **per-project credential** binding (reuses 3c `project_credentials`, decrypted in memory only, never logged), and the `bmad_role` prompt selector — persisted on `workflow_definition_steps` (custom defs) or a per-project step-override table for the shared BMAD preset (§6).
2. **Given** resolution at dispatch (story 3m-3 §2), **When** a step runs, **Then** the engine resolves the executor through **`ProjectConnectorResolver`** using the step's bound credential — exactly as automated runners resolve their adapters today; a same-model choice is permitted but recorded (provenance, mirrors 3d-2 §4).
3. **Given** an unconfigured step, **When** the run reaches a step with no resolvable executor, **Then** it fails fast with typed `STEP_EXECUTOR_NOT_CONFIGURED` (Problem Details, story 1.8) — never a silent default or an opaque 500 (the "adapter exception → opaque 500" trap is guarded).
4. **Given** mandatory `Idempotency-Key` + `X-Actor-Identity` headers (stories 1.9 / 2.13), **Then** binding mutations follow standard conventions; replay same-key+fingerprint replays, different fingerprint raises `IDEMPOTENCY_KEY_CONFLICT`.
5. **Given** allowed-actions (story 2.14), **Then** configuring a step executor is gated by a backend-reported action; a new registry value is added per drift test.
6. **Given** the shared BMAD **preset** is `builtin` and immutable, **Then** per-project executor choices for preset steps are stored as **per-project step overrides** (not mutations of the shared preset rows), so two projects can run BMAD with different executors — the resolution precedence (override → step default) is explicit and tested.
7. **Given** typed errors, **Then** Problem Details cover at least: `STEP_EXECUTOR_NOT_CONFIGURED`, `WORKFLOW_DEFINITION_NOT_FOUND`, `RUNNER_KIND_NOT_SUPPORTED`, `IDEMPOTENCY_KEY_CONFLICT`, `ACTION_NOT_ALLOWED` — contract tests assert `code`+`status`, never human text.
8. **Given** OpenAPI + drift (story 1.21), **Then** the new binding endpoints + DTOs appear in the regenerated snapshot.
9. **Given** tests, **Then** coverage asserts: bind persists + resolves via per-project credential, unconfigured-step fast-fail typed error, per-project overrides isolate two projects on the same preset, idempotent replay + conflict, allowed-action gating, CLI/REST equivalence, and no credential material in logs.

### Story 3m-5: BMAD Role-Prompt Catalog + 8-Phase Preset Seed

As a backend developer,
I want a catalog mapping each BMAD step key to its BMAD agent, plus a seeded built-in `bmad-method` definition,
So that selecting BMAD for a project yields the full method as ordered, executable steps with sensible default executors.

**Acceptance Criteria:**

1. **Given** the BMAD agent skills present in this repo, **Then** a **role-prompt catalog** maps each `bmad_step_key` to its agent: `analyst`→`bmad-agent-analyst`, `pm`→`bmad-create-prd`, `ux`→`bmad-create-ux-design`, `architect`→`bmad-create-architecture`, `epics`→`bmad-create-epics-and-stories`, `story`→`bmad-create-story`, `dev`→`bmad-dev-story`, `review`→`bmad-code-review`, `retro`→`bmad-retrospective` — the catalog is the single source the `bmad_role` selector (3m-4) draws from.
2. **Given** a **seed migration** (next free Flyway version, replay-safe), **Then** a `builtin` `workflow_definitions` row `key=bmad-method` plus its ordered `workflow_definition_steps` are seeded: **analyst → pm → ux → architect → epics → story → dev → review → retro**, each with its `step_key`, default `runner_kind`, `bmad_role`, `human_gated` flag, and `produces_artifact_kind` (3m-6).
3. **Given** default gating, **Then** the seed marks the natural human-decision phases as `human_gated=true` (at minimum `pm`, `architect`, `review` — the product/technical approval boundaries FR8/FR16), while purely generative phases default non-gated; the defaults are documented and overridable per project (3m-4/3m-7).
4. **Given** the catalog is data + config (not hardcoded engine logic), **Then** the engine (3m-3) remains BMAD-agnostic — adding/removing a preset step is a data change, and ArchUnit asserts no `bmad-*` string literals leak into the sequencer.
5. **Given** the spike fallback (3m-1 §3), **If** a phase's agent can't be driven headlessly by `claude`/`codex`, **Then** the seed sets that step's default `runner_kind=manual` (parks for manual execution) rather than an unrunnable binding — the default is a documented, changeable choice.
6. **Given** registry drift (3m-2 §5), **Then** every seeded `step_key`, `bmad_role`, and `artifact_kind` is a registered, drift-tested value — the seed cannot reference an unregistered value.
7. **Given** idempotent seeding, **Then** re-running the seed migration (or replay) does **not** duplicate the preset (guarded by the partial-unique "one active per key", 3m-2 §2).
8. **Given** tests, **Then** coverage asserts: the catalog covers all 9 step keys, the seeded definition has exactly the 9 steps in order with valid registered values, default gating matches the documented set, replay/seed idempotency, and the engine executes the seeded definition end-to-end (with `manual` fallbacks parking correctly).

### Story 3m-6: Typed BMAD Artifacts + Phase-to-Phase Chaining

As an operator,
I want each BMAD phase to produce a typed, inspectable artifact that feeds the next phase,
So that the run carries a real document lineage (brief → PRD → architecture → epics → story → code → review) rather than opaque blobs.

**Acceptance Criteria:**

1. **Given** the `artifact_kind` value set (3m-2), **Then** it enumerates the BMAD outputs: `brief`, `prd`, `ux_design`, `architecture`, `epics`, `story`, `code`, `review`, `retro` — each registered + drift-tested; each phase's `produces_artifact_kind` (3m-5 seed) references one.
2. **Given** the existing artifact model + `runner-contracts` output, **When** a phase completes, **Then** its output is persisted as an artifact of the declared `artifact_kind`, with lineage to its `workflow_run` + `runner_execution` (FR24), inspectable in run history (FR55) — reusing the artifact store, **not** a new one.
3. **Given** FR54 chaining (engine 3m-3 §3), **When** phase N+1's context bundle is composed, **Then** it **includes the typed artifact(s)** from the relevant prior phases (e.g. `architect` sees `prd`; `story` sees `epics`+`architecture`; `dev` sees `story`) — the per-step "which prior artifacts" input map is part of the step definition, defaulting to "all prior" for Phase 1 simplicity.
4. **Given** artifact-view contracts (the `isArtifactView` requirement), **Then** each typed artifact DTO satisfies the read contract (artifactId + title + body-markdown) so the existing artifact inspection UI renders BMAD outputs without a bespoke surface.
5. **Given** redaction (story 1.10 / ADR 0025), **Then** every typed artifact passes the same redaction guarantee before persistence/egress as any runner output — no BMAD artifact bypasses redaction.
6. **Given** the `code` phase specifically, **Then** its artifact reuses the existing implementation-output/PR-linkage lineage (FR20/FR40) rather than inventing a parallel representation — BMAD's `dev` step lands in the same place the built-in pipeline's implementation output does.
7. **Given** OpenAPI + drift (story 1.21), **Then** any new artifact-kind values + DTO fields appear in the regenerated snapshot; a new `WorkflowEventType` for typed-artifact production is mirrored into both fixture sites.
8. **Given** tests, **Then** coverage asserts: each phase persists the correct `artifact_kind` with lineage, prior-artifact chaining feeds the right inputs downstream, artifact DTOs satisfy `isArtifactView`, redaction on every kind, `code`-phase reuse of implementation-output lineage, and registry/drift for the artifact-kind set.

### Story 3m-7: Human-Gated Steps + Rerun-From-Step for BMAD Loops

As an operator,
I want to approve/reject a phase's output and rerun a phase when I reject it,
So that BMAD's feedback loops (spec revision, architecture rework, dev↔review) work without native branching.

**Acceptance Criteria:**

1. **Given** a `human_gated` step (3m-5), **When** its artifact is produced, **Then** the run parks in `WaitingForReview` (reuses 3b/3d Decision Bar) surfacing the typed artifact; the operator can **approve** (advance to next `step_index`) or **reject** (structured feedback, FR9/FR17) — the human decision governs (advisory reviewer model 3d-2 may also attach).
2. **Given** a rejection, **When** the operator reruns the phase, **Then** it uses **Epic 4 rerun-from-step (FR31/FR32)**: a new `runner_executions` row for the re-attempt, prior history preserved append-only (NFR4), the rejection feedback folded into the re-run's context bundle so the agent sees why it was rejected.
3. **Given** BMAD's canonical loops, **Then** at least these are demonstrated via gate+rerun (not native branch): `pm`/`prd` revision (product acceptance FR8), `architect` rework, and `dev`↔`review` (technical acceptance FR16) — each expressed as "reject → rerun same step with feedback."
4. **Given** a rerun of an *upstream* phase, **When** an earlier phase's artifact changes, **Then** the behavior for already-produced downstream artifacts is **explicitly defined** (Phase 1: downstream artifacts from a superseded upstream are marked stale/superseded in lineage, not silently overwritten — reuses artifact-lineage/supersede semantics) — and this is documented as the Phase-1 loop limitation.
5. **Given** governance (FR45/FR46), **Then** each approve/reject/rerun records actor identity + role (who approved which phase) in append-only history; product vs. technical acceptance stay distinguishable (FR21) across BMAD phases.
6. **Given** allowed-actions (story 2.14), **Then** approve/reject/rerun on a BMAD phase reuse the existing governed actions (no new bespoke action per phase) — a gated BMAD step exposes the same decision affordances as any `WaitingForReview` run.
7. **Given** native branching is out of scope (3m-1 §6), **Then** a doc/ADR note records that automatic loop-back transitions (e.g. auto-return to `analyst` on PRD rejection) are deferred; Phase 1 loops are operator-driven rerun-from-step only.
8. **Given** tests, **Then** coverage asserts: gated phase parks + surfaces artifact, approve advances, reject+rerun preserves history and folds feedback into the re-run bundle, upstream-rerun marks downstream stale (no silent overwrite), actor/role recorded per decision, and the three canonical loops each complete a reject→rerun→approve cycle.

### Story 3m-8: UI — Definition Selection, Per-Step Executor Config & BMAD Run Inspection

As an operator,
I want to pick a workflow definition for a project, configure each step's executor, and watch a phased BMAD run,
So that I can drive the full method from the web app without touching the CLI.

**Acceptance Criteria:**

1. **Given** a project's configuration surface (Epic 3c UI), **Then** a **Workflow Definition selector** lets the operator choose the built-in `bmad-method` (or leave the default pipeline); selecting it reveals the ordered step list.
2. **Given** the selected definition, **Then** a **Per-Step Executor panel** lets the operator bind, per step, the runner kind (`claude`/`codex`/`manual`), the per-project credential, and (for BMAD steps) shows the resolved `bmad_role` — persisting via the 3m-4 endpoints; unconfigured steps are visibly flagged before a run can start.
3. **Given** a running BMAD workflow, **Then** the **run-detail view** renders the phased progression (analyst→…→retro) showing current step, each step's executor (FR23), its typed artifact (reusing the artifact inspection surface from 3m-6/FR55), and per-step logs (reuses 3d-5 log viewer) — no bespoke per-phase surface.
4. **Given** a human-gated phase parked in `WaitingForReview`, **Then** the existing **Decision Bar** (3d-2/3b) surfaces the phase's typed artifact with approve/reject/rerun (3m-7) — the operator experiences BMAD gates identically to any review.
5. **Given** state signifiers, **Then** step status (pending/running/parked/done/failed/superseded) uses a **color-independent** signifier; stream/gate transitions announce via a live region (liveAnnouncer pattern).
6. **Given** allowed-actions, **Then** every affordance (select definition, bind executor, approve/reject/rerun) is driven by a **backend-reported action** — no client-side authority; disabled states reflect backend gating.
7. **Given** accessibility, **Then** all new surfaces are keyboard-operable with explicit labels — **WCAG 2.1 AA, axe zero `wcag2aa` violations**.
8. **Given** OpenAPI-generated client drift (the regen trap), **Then** any new DTO consumed by the UI is pulled via `npm run generate-api`; `npm run build` (tsc -b, incl. tests) is green before the story is claimed done.
9. **Given** tests, **Then** Vitest + Playwright + axe cover: definition selection, per-step executor config incl. unconfigured-flagging, phased run rendering with per-step executor + artifact, gated-phase Decision Bar approve/reject/rerun, color-independent + live-region signifiers, and allowed-action-driven affordances.

### Story 3m-9: Basic Custom-Definition Editor (UI + CLI)

As an operator,
I want to create and edit custom workflow definitions — add/reorder/remove steps and bind executors,
So that I'm not limited to the BMAD preset and can compose my own pipelines.

**Acceptance Criteria:**

1. **Given** a governed **REST + CLI + UI** path, **Then** an operator can create a `custom` `workflow_definition` (unique `key`, name) and add ordered steps, each with `step_key` (free-text for custom), `runner_kind`, credential binding, optional `bmad_role`, `human_gated`, and `produces_artifact_kind` — persisted per 3m-2.
2. **Given** step ordering, **Then** the operator can **reorder and remove** steps; `step_index` stays contiguous + unique (3m-2 constraint) after every mutation, enforced server-side (a reorder that would collide raises typed `DEFINITION_STEP_INDEX_CONFLICT`, never a 500).
3. **Given** the `builtin` BMAD preset is immutable (3m-4 §6), **Then** the editor **cannot mutate** preset rows; an operator wanting a BMAD variant **clones** the preset into a new `custom` definition (explicit action), which is then freely editable — clone provenance recorded.
4. **Given** validation, **Then** a definition cannot be **activated for a project** with an unconfigured/invalid step; typed Problem Details cover `DEFINITION_STEP_INDEX_CONFLICT`, `STEP_EXECUTOR_NOT_CONFIGURED`, `WORKFLOW_DEFINITION_NOT_FOUND`, plus idempotency/allowed-action errors.
5. **Given** `Idempotency-Key` + `X-Actor-Identity` + allowed-actions, **Then** all editor mutations follow the standard governed-mutation conventions (stories 1.9/2.13/2.14); a new allowed-action registry value is added per drift test.
6. **Given** archival not deletion, **Then** retiring a custom definition sets `archived_at` (3m-2) — never a row delete; a definition in use by a project cannot be archived without an explicit guard/typed error.
7. **Given** accessibility + drift, **Then** the editor UI is keyboard-operable (WCAG 2.1 AA; axe zero `wcag2aa`); new endpoints/DTOs appear in the regenerated OpenAPI snapshot; FE client via `generate-api`.
8. **Given** tests, **Then** coverage asserts: create/add/reorder/remove keeps indices valid, preset immutability + clone-to-edit, activation blocked on invalid steps, archive-not-delete + in-use guard, idempotency/allowed-action/CLI-REST parity, and axe a11y on the editor.

### Story 3m-10: Foundation-Gate Widening + Test-Suite Extension

As a backend + frontend developer,
I want the foundation gate and test suites extended to cover Epic 3m,
So that configurable-workflow/BMAD regressions are caught at the same CI gates as the rest of the system.

**Acceptance Criteria:**

1. **Given** the foundation gate (story 1.23), **Then** it asserts the new authoritative values are registered + drift-tested (registry ↔ DB CHECK ↔ API schema ↔ fixtures): `definition_kind`, `bmad_step_key`, `artifact_kind`, the `wfd_`/`wfs_` prefixes, every new `WorkflowEventType`, new allowed-action values, and new domain error codes (three-sites).
2. **Given** the run-model invariant (3m-1 ADR), **Then** a test asserts a **null-binding project is byte-identical to the pre-3m pipeline** — the legacy path is provably untouched.
3. **Given** the engine's BMAD-agnosticism (3m-3 §8 / 3m-5 §4), **Then** an ArchUnit/contract test asserts **no `bmad-*` literals in the sequencer** — BMAD lives entirely in seed data + catalog config.
4. **Given** the preset-immutability invariant (3m-4/3m-9), **Then** a test asserts per-project executor overrides never mutate the shared `builtin` preset rows and two projects can run BMAD with divergent executors.
5. **Given** the loop/lineage invariant (3m-7 §4), **Then** a test asserts an upstream rerun marks downstream artifacts stale/superseded and **never** silently overwrites or deletes prior artifacts (append-only, NFR4).
6. **Given** the frontend (3m-8/3m-9), **Then** Vitest + Playwright + axe coverage is extended for the Definition Selector, Per-Step Executor panel, phased run view, and the custom-definition editor, under the existing CI tiers.
7. **Given** coverage thresholds (story 2.27 pattern), **Then** thresholds extend to the new packages (definition/engine/executor-binding/artifact-chaining application code) — minimum 80% line; any redaction/secret-adjacent code 90%.
8. **Given** the gate, **Then** "Epic 3m backend + frontend suites green" is required for foundation-gate PRs.

### Story 3m-11: Documentation Increment

As an operator joining the pilot,
I want a configurable-workflow + BMAD-method walkthrough,
So that I can select a definition, bind executors per step, run the full method, and author a custom pipeline unaided.

**Acceptance Criteria:**

1. **Given** `docs/configurable-workflow-bmad-walkthrough.md`, **Then** it follows a linear sequence: select the BMAD definition for a project → bind an executor to each phase → start a run → approve/reject/rerun at each human gate → inspect each phase's typed artifact → author a custom definition by cloning + editing.
2. **Given** the *why*, **Then** it explains that steps are configurable **data** (not a hardcoded pipeline), that each step's executor is any runner kind + a BMAD role, and that the built-in BMAD preset is one definition among future custom ones.
3. **Given** the loop section, **Then** it explains Phase-1 loops are **operator-driven rerun-from-step** (not automatic branching), that rejection feedback folds into the re-run, and that upstream reruns mark downstream artifacts superseded.
4. **Given** the manual-fallback section, **Then** it explains that any phase whose agent can't run headlessly defaults to `manual` execution (reuses 3d manual mode) and how to switch a phase between automated and manual.
5. **Given** the glossary (story 1.22) + NFR43, **Then** new concepts (`workflow definition`, `step definition`, `executor binding`, `BMAD role`, `BMAD preset`, `typed artifact`, `custom definition`) are added to `docs/glossary.md`; no concept introduced without an entry.
6. **Given** the ADR index, **Then** the run-model + executor-binding ADRs (3m-1) are linked from `docs/index.md` and the ADR index.
7. **Given** the link-check CI step, **Then** all internal links resolve, the doc is visible from `docs/index.md`, and it is browser-based with no OS-specific instructions.
8. **Given** the epic doc-increment rule, **Then** Epic 3m cannot close without this walkthrough merged **+ a named human-validator placeholder** included (mirrors 3d-10 / the recurring doc-gate).
