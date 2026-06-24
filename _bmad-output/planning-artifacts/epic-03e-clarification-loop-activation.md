## Epic 3e: Clarification Loop Activation

A single local-first operator finally gets the **product-clarification loop working end-to-end**. When the spec runner has an open question, that question becomes a first-class, answerable **clarification** attached to the spec — visible in the review pane, not buried as prose inside `spec.md`. The reviewer answers it, **accepts** the answer, and triggers a **spec regeneration that incorporates accepted answers**; the runner reports which questions it actually addressed, and each clarification is durably marked `incorporated` or `superseded`. This epic builds **no new subsystem** — it activates the dormant front half of a feature whose back half shipped in Epic 2.

**Why this epic exists (the gap):** Epic 2 built the clarification **back half** — submission + `/answer` (story 2.11), the visible-incorporation lifecycle service + sweep (story 2.12), and the `ClarificationRegion` UI (story 2.18) — but the **front half was explicitly deferred to "Epic 3 runner-contracts" and never built.** Story 2.11 AC6 records the deferral verbatim: a clarification row is "created when the spec runner emits a question marker, **wired in Epic 3**." That wiring never landed. Verified on the live codebase (2026-06-23): `ClarificationWritePort.insertOpen(...)` — the only row-creating method — has **zero production callers**; `runner-result.v1`'s `specArtifact` has **no question channel**; the only clarification REST endpoint is `/answer`; `ClarificationLifecycleService.markAccepted` has **no trigger** (no `accept_clarification` action); and `ClarificationLifecycleOrchestrator.acknowledgesQuestion` is an explicit substring **stub** with a `TODO(epic-3-runner-contracts)`. Net effect: **no run ever shows a clarification question** — confirmed against real runs whose specs raised open questions ("Confirm whether…", "Decide whether…") that were silently dropped.

This is a **completion of the Epic 2 PM loop**, not a per-step-execution-control feature — it does not fit Epic 3d's theme. It is **inserted between Epic 3d and Epic 4** purely for sequencing (avoids renumbering E4–E6). Source: this sprint-change-proposal. It completes the deferred clarification-creation + incorporation portion of **FR9 / FR11 / FR13** (the spec-loop + visible-incorporation requirements Epic 2 owns).

**Prerequisites:** Epic 2 complete (2.11 submission/`/answer`, 2.12 incorporation lifecycle + sweep, 2.18 `ClarificationRegion`) — all done. The runner-contracts (`runner-result.v1`), the runner-broker spec-ingest seam (`RunnerBroker.handleSuccess`), the spec-investigation context bundle (`ContextBundleService.assembleForSpecInvestigation`), and both runner entrypoints (`runners/{codex,claude}/lib/runner.mjs`) are the seams this epic wires. The OpenSpec fence-split (story 3a-8) is the byte-identical-both-runners precedent for the new structured channel.

**ADR (proposed):** `docs/adr/0028-structured-clarification-channel.md` — the spec runner emits open questions and addressed-acknowledgements as a **structured additive channel** on `runner-result.v1` `specArtifact` (optional fields, no `schemaVersion` bump), replacing the substring-scan stub. Author alongside story 3e-1.

### Story List (5 stories)

```
Clarification Creation (closes the reported symptom)
3e-1   Spec-runner clarification emission + creation seam

Clarification Incorporation (closes the FR9/FR11/FR13 loop)
3e-2   Clarification accept + spec-rebuild incorporation

Spec-Phase Review (advisory second opinion at the spec gate)
3e-3   Spec-phase advisory reviewer (WaitingForSpecApproval)

Per-Project Execution Control (resolves 3d-3's deferred Open Decision #1)
3e-4   Per-step runner mapping per project (+ configuration UI)

Spec-Stage Observability (closes a separate "no console output" symptom)
3e-5   Spec-stage (Investigating) runner log & console visibility + decision-bar placement
```

> Story 3e-1 alone resolves the user-visible symptom (questions appear and are answerable). Story 3e-2 closes the full accept → regenerate → incorporate loop. Story 3e-3 extends the 3d-2 advisory LLM reviewer to the spec gate (consuming the open clarifications as review context — hence depends on 3e-1). Story 3e-4 delivers the per-step runner-kind mapping the user requested ("map a runner to each step") + the Projects-Management-UI control that 3d-3 left unbuilt — resolving 3d-3's deferred per-stage-per-project granularity. Story 3e-5 closes a **separate** reported symptom — a run in `Investigating` (spec generation) showed no console output — by extending the 3d-5/3d-6 log-viewer + read-only-console affordances to the spec stage and relocating them below the Decision Bar; it is independent of 3e-1..3e-4. Detailed, reconciled implementation stories live at `{implementation_artifacts}/3e-1..3e-5-...md`.

---

### Story 3e-1: Spec-Runner Clarification Emission + Creation Seam

As a product reviewer,
I want the spec runner's open questions to become first-class `open` clarifications attached to the spec,
So that when a spec needs my input I see explicit, answerable questions in the review pane instead of buried prose — and the `/answer` endpoint that already exists actually has something to answer.

**Acceptance Criteria:**

1. **Given** the runner-result contract, **Then** `runner-result.v1.schema.json`'s `specArtifact` gains an **optional** `questions[]` (items `{questionId (pattern `^[A-Za-z0-9._-]{1,128}$`), questionText (minLength 1)}`, `additionalProperties:false`), declared in `properties`, **not** `required`, with **no** `schemaVersion` bump (the 3d-7 `providerUsage` additive pattern); valid-with/without and invalid fixtures added.
2. **Given** the two runner entrypoints, **Then** both `runners/codex/lib/runner.mjs` and `runners/claude/lib/runner.mjs` (BYTE-IDENTICAL) split a fenced ` ```clarifications ` JSON block out of agent stdout (mirroring the OpenSpec fence-split) and set `artifact.questions` only at `stage === 'spec'` and only when non-empty; malformed JSON is non-fatal (never blocks spec delivery); both offline mocks emit one deterministic question.
3. **Given** a spec result with non-empty `questions`, **When** `RunnerBroker.handleSuccess` ingests the spec artifact (INVESTIGATION stage), **Then** a new `ClarificationIngestService` creates one `open` clarification per question via `ClarificationWritePort.insertOpen` — pinned to the just-ingested spec `artifactId`+version, with a **deterministic** idempotency key (`runner-result-clarification:{runnerExecutionId}:{questionId}`) so replay/re-harvest never duplicates.
4. **Given** the creation rides inside `handleSuccess`, **Then** it is **best-effort** (positioned with the SPEC `markArtifactAvailable` block, all `RuntimeException` swallowed + logged) so a creation failure never strands the execution `RUNNING`; an `IDEMPOTENCY_KEY_CONFLICT` on replay is caught and logged INFO as benign.
5. **Given** the audit, **Then** a new `WorkflowEventType CLARIFICATION_RAISED("clarification.raised")` is appended per created clarification, mirrored into both fixture sites (OpenAPI byte-identical), carrying `clarificationId`+`artifactId`+`questionId` via allow-listed detail keys.
6. **Given** the read model + UI (2.14/2.18), **Then** created `open` clarifications surface via `WorkflowInspectionService` + the existing `ClarificationRegion` with no new read endpoint or FE component (fixtures only), and `/answer` records an answer over a created clarification end-to-end.
7. **Given** redaction (story 1.10), **Then** `questionText` is never logged (lengths/ids only, trap T12) and the runner-emitted question text passes the same content posture as any artifact.
8. **Given** tests, **Then** coverage asserts: contract valid/invalid; runner unit (fence-split parses / absent omits / malformed non-fatal, both runners); broker ingest IT (a mock spec result with a question creates an `open` clarification, replay does not duplicate, a no-question result creates none — pre-3e parity); `CLARIFICATION_RAISED` drift; an `/answer` flow over a created clarification; `application.*` ≥80% line coverage.

### Story 3e-2: Clarification Accept + Spec-Rebuild Incorporation

As a product reviewer,
I want to accept my clarification answers and trigger a spec regeneration that incorporates them, with the runner reporting which questions it actually addressed,
So that the visible-incorporation lifecycle (2.12) closes for real: accepted answers drive a rebuilt spec, and each clarification is marked `incorporated` or `superseded` based on what the new spec genuinely did — not a brittle substring guess.

**Acceptance Criteria:**

1. **Given** allowed-actions (2.14), **Then** a new `ACCEPT_CLARIFICATION("accept_clarification")` action (registry + placeholder + pin drift) + REST `POST /workflows/{id}/clarifications/{clarificationId}/accept` + CLI parity drive `WorkflowCommandService.acceptClarification` → `ClarificationLifecycleService.markAccepted` (`answered → accepted`), surfaced for the reviewer role at `WaitingForSpecApproval`. (Auto-accept-on-answer was considered and rejected — it erases the `answered`/`accepted` distinction the sweep + 2.12 audit depend on.)
2. **Given** an accepted clarification, **Then** a new `REGENERATE_SPEC("regenerate_spec_with_clarifications")` action re-dispatches the spec stage by **reusing** `WorkflowOrchestrationService.retrySpecGeneration` (`WaitingForSpecApproval → Investigating → enqueue INVESTIGATION`) — not a duplicated dispatch path.
3. **Given** the spec rebuild bundle, **Then** `ContextBundleService.assembleForSpecInvestigation` adds one `{referenceId, kind:"clarification.answered"}` `priorFeedbackReferences` entry per `accepted` clarification **and** materializes each answer (`questionId`+`questionText`+`answerText`) as a **redaction-policed** referenced input file (never inlined into the bundle JSON — respects the 2KB cap + the redaction gate).
4. **Given** the runner-result contract, **Then** `specArtifact` gains an **optional** `clarificationAcknowledgements[]` (items `{questionId, addressed (boolean)}`, additive, no `schemaVersion` bump); both runners emit it (fence-split sibling of 3e-1); both mocks emit a deterministic acknowledgement.
5. **Given** the rebuilt spec, **Then** it is recorded as a **`newVersion` graft of the prior spec** (not a fresh lineage) so the prior clarification's pinned `artifactId` stays in the new spec's lineage and the sweep considers it — closing the central lineage-scope trap (`newVersion`/`createNextVersion` has zero callers today). An IT proves a v1-pinned clarification is swept on v2 availability.
6. **Given** the sweep oracle, **Then** `ClarificationLifecycleOrchestrator.acknowledgesQuestion` (the documented substring stub) is **replaced** by consuming the structured `clarificationAcknowledgements` (persisted at ingest, read at sweep time): `addressed==true ⇒ markIncorporated`, else `markSuperseded` with `no_effect_reason="spec_runner_skipped_question"`; the stub + its `TODO` are deleted and the stale "hooked into newVersion" javadoc is corrected to "markAvailable".
7. **Given** the FE incorporation lifecycle (2.18) + downstream context (story 3.10), **Then** clarifications visibly move `accepted → incorporated`/`superseded`, and a subsequent EXECUTION-stage context bundle carries `incorporated` clarifications via the existing `collectExecutionFeedbackReferences` (already present) — proving the full chain.
8. **Given** tests, **Then** a full-loop IT proves create→answer→accept→regenerate→graft-v2(+acknowledgements)→sweep marks `incorporated` (addressed:true) / `superseded` (addressed:false), with lineage-scope proven; unit coverage for accept wiring + the new oracle; contract + runner emission for `clarificationAcknowledgements`; the 2 new AllowedActions drift; redaction fixture over the materialized answer file; OpenAPI + `schema.d.ts` regen for the new endpoints (NOT byte-identical); `application.*` ≥80% coverage.

### Story 3e-3: Spec-Phase Advisory Reviewer (WaitingForSpecApproval)

As a product reviewer,
I want a project-configured second LLM to review the spec — with the ticket, the specification, and the open clarification questions as its context — before I approve it,
So that I get a governed second opinion at the spec-approval gate (not only at the execution-output gate), without surrendering human approval authority.

**Acceptance Criteria:**

1. **Given** a project with a reviewer binding (the existing 3d-1/3d-2 `reviewer_model_kind`, run-level — NOT a new field), **When** a spec becomes available and the run advances `Investigating → WaitingForSpecApproval`, **Then** the existing advisory reviewer (`RunnerStage.REVIEW`, `enqueueReviewerIfConfigured`) is enqueued over the spec artifact — async, non-blocking, in the spec-ready transition tx — by adding the call to `onSpecStageSucceeded` (today it fires only at the execution callbacks).
2. **Given** the spec-review context, **Then** the reviewer bundle includes the ticket + spec + the run's `open` clarifications (reference-by-id + redaction-policed materialized content) so the reviewer can assess the spec and whether the open questions are handled (depends on 3e-1).
3. **Given** the reviewer run, **Then** a `runner_executions` row + a `step_reviews` row persist the verdict against the spec artifact (the generic 3d-2 persistence — no schema change), surfaced advisory-only in the `WaitingForSpecApproval` Decision Bar via the existing Reviewer Verdict Panel; it never auto-approves/rejects the spec (`reviewer_gating_enabled` not consulted).
4. **Given** no reviewer binding, **Then** behavior is byte-identical to pre-3e-3 (no spec reviewer, no panel) — opt-in parity hot path.
5. **Given** a reviewer failure (misconfig/credential/provider/timeout), **Then** it degrades through the same single 3d-2 path — spec gate not blocked, panel "unavailable", failed reviewer execution recorded.
6. **Given** provenance + redaction, **Then** producer-vs-reviewer model identities are recorded (self-review flagged), and the spec-review input (incl. clarification content) + output pass the redaction/secret-fixture gate.
7. **Given** tests, **Then** coverage asserts: spec reviewer enqueues on spec-ready when bound and not when unbound (parity); bundle includes ticket + spec + open clarifications; verdict persists to `step_reviews` against the spec + surfaces advisory-only; human spec decision unaffected; graceful degradation; redaction; `GET /reviewer-verdict` serves the spec verdict (OpenAPI byte-identical).

### Story 3e-4: Per-Step Runner Mapping Per Project (+ Configuration UI)

As an operator configuring a project,
I want to assign which runner (codex / claude / manual) executes each workflow step — spec, implementation plan, PR output — independently per project,
So that I can run some steps with an agent and others manually, instead of one runner kind for the whole project.

**Acceptance Criteria:**

1. **Given** the next-free Flyway head, **Then** an additive `project_runner_kinds(project_id FK, step, runner_kind, PK(project_id, step))` table is created with CHECKs on `step` (`spec`/`implementationPlan`/`prOutput`) and `runner_kind` (`codex`/`claude`/`manual`); replay-safe; in `FlywaySchemaContractTest`.
2. **Given** a new `ProjectRunnerStep` registry (`spec`/`implementationPlan`/`prOutput`) + a `(RunnerStage, ExecutionSubStage) → step` helper, **Then** they are drift-tested against the DB CHECK + API schema (1.4/3c-2 pattern).
3. **Given** the `Project` aggregate, **Then** it gains `Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds` (empty = no mapping); the 6 `new Project(...)` sites are updated; default project + seeder pass empty (parity).
4. **Given** the resolver, **Then** `ProjectRuntimeConfigResolver.resolveRunnerKind(stage, subStage)` resolves with precedence per-step mapping → single per-project `runnerKind` override (3d-3, kept as project-wide default) → global per-stage; an empty map is byte-identical to 3d-3.
5. **Given** a step mapped to `manual`, **Then** that step parks the run in `WaitingForManualExecution` via the existing 3d-3 park path (resolver returns `MANUAL`) with no new dispatch logic; sibling steps resolve independently (e.g. spec=codex enqueues, prOutput=manual parks).
6. **Given** the REST API, **Then** create/update accept an optional `stepRunnerKinds` map (full-replace on update) and `ProjectResponse` exposes it alongside the existing single `runnerKind`; OpenAPI + `schema.d.ts` regenerate.
7. **Given** the Projects Management UI (3c-9), **Then** it gains per-step runner selectors (spec / implementation plan / PR output → codex/claude/manual/use-default) AND surfaces the existing single `runnerKind` as the project-wide default — closing the gap that `runnerKind` had no UI control (it was only in generated types); WCAG 2.1 AA + axe clean.
8. **Given** tests, **Then** coverage asserts: resolver precedence (per-step > override > global) + empty-map parity; a per-step `manual` parks only that step while others enqueue (IT); REST round-trips the map; registry/CHECK/Flyway/OpenAPI/schema.d.ts drift; UI Vitest + axe.

### Story 3e-5: Spec-Stage (Investigating) Runner Log & Console Visibility + Decision-Bar Placement

As a workflow owner watching a run during spec generation,
I want the live step-log viewer and the read-only diagnostic console to be available while the run is in `Investigating`, rendered below the action buttons,
So that I can see the spec runner's console output as it works (today the page shows nothing) and find that output in a consistent place beneath the decision controls.

**Context (the gap):** `Investigating` is the spec-generation stage — a runner container is live and producing output there (story 3a-1, `INBOX → INVESTIGATING`). But stories 3d-5 (AC6) and 3d-6 (AC4) scoped the `view_runner_logs` / `open_diagnostic_console` affordances to the execution states only, so `WorkflowInspectionService.baseActionMatrix`'s `INVESTIGATING` arm offers neither. The FE gates both observability surfaces on those actions, so a run viewed in `Investigating` shows **no console output**. The streaming endpoints are stage-agnostic and already serve the spec runner — only the matrix gate is missing.

**Acceptance Criteria:**

1. **Given** the allowed-action matrix, **When** a run is in `Investigating`, **Then** `baseActionMatrix` offers `VIEW_RUNNER_LOGS` for every role and `OPEN_DIAGNOSTIC_CONSOLE` for `workflow_owner` only (mirroring `EXECUTING`, excluding `await_outcome`/`view_provider_usage_status`), on both the open-clarification and no-open-clarification branches.
2. **Given** a live spec-generation run, **Then** the `StepExecutionLogViewer` streams the spec runner's logs and the owner's `ReadOnlyDiagnosticConsole` attaches to the live spec container — with no change to endpoints, ports, adapters, or `runner-result`/OpenAPI schemas.
3. **Given** the run-detail route, **Then** the log viewer + diagnostic console render **below** the `WorkflowDecisionBar` (the action buttons), not at the top of the page; gating is unchanged. Provider Limit Status + Failure surface keep their positions.
4. **Given** the registry/contract surfaces, **Then** there is no new `AllowedAction`/event/error code/Flyway/`runner-result` field; the two actions already exist in the enum + placeholder + `getAllowedActions` schema, so the OpenAPI snapshot is byte-identical (no `schema.d.ts` regen).
5. **Given** ADR 0025, **Then** an amendment note records that both affordances now cover `Investigating` on the identical security posture (input-disabled console, owner-only, live-only re-check) — no new sign-off gate.
6. **Given** tests, **Then** the `Investigating` matrix rows (both branches, `product_reviewer` + `workflow_owner`) + cross-product coverage test stay green; an `Investigating` run renders the viewer/console; both surfaces render below the Decision Bar; `application.*` ≥80% coverage holds.

> **Dependencies:** 3d-5 + 3d-6 (done). Independent of 3e-1..3e-4. **FRs:** extends FR65 (live/historical step logs) + FR68 (read-only diagnostic console) to the spec stage; no new requirement.

---

### Cross-Cutting Notes

- **Foundation-gate widening:** the new `AllowedAction`s (3e-2), the `clarification.raised` event type (3e-1), the `ProjectRunnerStep` registry + `project_runner_kinds` table (3e-4), any new error codes (three-sites), and the additive runner-contract fields are drift-tested at the existing gates — folded into each story, no separate gate story.
- **Documentation:** the Epic 2 PM-loop walkthrough (`docs/pm-loop-walkthrough.md`) gains a clarification-creation + incorporation + spec-review section (3e-2/3e-3); the per-step execution-control config is documented alongside 3e-4 (extends the 3d config walkthrough); new vocabulary confirmed in `docs/glossary.md`.
- **FRs covered:** **3e-1/3e-2** complete the deferred clarification-creation + incorporation portion of **FR9, FR11, FR13** (Epic 2 owns the definitions). **3e-3** extends the advisory-reviewer capability (**FR64**, Epic 3d) to the spec gate. **3e-4** extends per-project execution control (**FR66** manual execution + Epic 3c project config) to per-step granularity and delivers its config UI. **3e-5** extends spec-stage observability (**FR65** live/historical step logs + **FR68** read-only diagnostic console, Epic 3d) to the `Investigating` state and refines run-detail placement. This epic activates/extends deferred execution; it introduces no new PRD requirement.
