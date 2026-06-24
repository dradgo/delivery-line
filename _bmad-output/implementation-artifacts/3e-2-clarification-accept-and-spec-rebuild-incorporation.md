# Story 3e.2: Clarification Accept + Spec-Rebuild Incorporation

Status: backlog

<!-- Proposed via systematic-debugging diagnosis 2026-06-23; Epic 3e formally slotted via sprint-change-proposal-2026-06-23.md (correct-course). Depends on 3e-1 (creation seam). -->

> **⚠️ READ FIRST — this CLOSES the FR10 loop; most of the back half already exists but is starved of inputs.** Story 2.12 built the full incorporation machinery — `ClarificationLifecycleService` (`answered→accepted`, `accepted→incorporated`, `accepted→superseded`, `answered→rejected_invalid`) and `ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild`, which is **already wired** into `ArtifactOperationService.markAvailable` and fires on **every** SPEC artifact becoming available (verified `ArtifactOperationService.java:395–405`). But the loop is **starved at three points**: (1) **nothing transitions `answered → accepted`** — there is no `accept_clarification` AllowedAction and `markAccepted` has **zero production callers**, so the sweep's `accepted`-only filter always finds nothing; (2) **`acknowledgesQuestion` is an explicit STUB** — a case-sensitive substring scan of the new spec's bytes for the literal `questionId`, with a `TODO(epic-3-runner-contracts): replace with a structured clarification_acknowledgements block` (verified `ClarificationLifecycleOrchestrator.java:251–285`); (3) **a re-dispatched spec does NOT graft onto the original spec lineage**, so the original clarification's pinned `artifactId` is not in the new spec's lineage and the sweep **skips it** (see R3 — the central architectural trap).
>
> **3e-2 builds: an explicit accept action → a spec-rebuild action that feeds answered clarifications into the spec context bundle → a structured `clarification_acknowledgements` channel the runner emits → the sweep consuming it (replacing the substring stub) → the rebuilt spec grafting as a `newVersion` so lineage holds.** Depends on **3e-1** (open clarifications must exist before they can be answered/accepted).
>
> **Depends on 3e-1. Same proposed Epic 3e. Ratify via correct-course before `dev-story`.**
>
> **Four reconciliations the live codebase forces (read all four before coding):**
>
> 1. **The sweep already fires — on `markAvailable`, not `newVersion`.** The orchestrator's own javadoc says "hooked into `newVersion`" but the **actual** call site is `ArtifactOperationService.markAvailable` (L395), which runs for every SPEC version (incl. v1, via the broker's `markArtifactAvailable`). So you do NOT need to wire the sweep — you need to (a) give it `accepted` rows to act on and (b) replace its acknowledgement oracle. The "newVersion" javadoc is **stale** — fix it.
> 2. **`markAccepted` has no trigger.** `AllowedAction` has only `answer_clarification` (no accept). `ClarificationLifecycleService.markAccepted` is called by nobody. Add an `accept_clarification` action + command + REST/CLI + a `WorkflowCommandService.acceptClarification` that calls `markAccepted` (the answer twin of `submitAnswer`). See R1. (Alternative considered + rejected: auto-accept on answer — it collapses the `answered`/`accepted` distinction the sweep and 2.12 audit depend on. R1.)
> 3. **A re-dispatched spec mints a FRESH lineage, breaking the sweep's lineage scope.** The broker ingests every spec via `recordOperation(CREATE)` → a brand-new `art_` with `parentArtifactId = null`. `newVersion`/`createNextVersion` (the graft) has **zero production callers** (verified `ArtifactOperationService.java:492–512`). The sweep scopes by `lineageArtifactIds(newSpec)` (parent-walk) ∩ `accepted` clarifications — a clarification pinned to v1 is invisible to a v2 in a different lineage. **This is the crux (R3):** the spec-rebuild path must produce the new spec as a `newVersion` graft of the prior spec, OR the sweep scope must widen from lineage to workflow-run. Recommend the graft.
> 4. **Answers reach the runner by reference, never inline — they must be MATERIALIZED into the input bundle.** `priorFeedbackReferences` carries `{referenceId, kind}` only (no content) and the runner reads referenced content from its mounted input dir. So feeding an answer to the rebuild = adding a `clarification.answered` feedback ref **and** materializing the (redacted) question+answer as an input file the runner can read — not embedding answer text in the bundle JSON. See R4 + the redaction gate.

## Story

As a product reviewer,
I want to accept my clarification answers and trigger a spec regeneration that incorporates them, with the runner reporting which questions it actually addressed,
so that the visible-incorporation lifecycle (2.12) closes for real: accepted answers drive a rebuilt spec, and each clarification is marked `incorporated` or `superseded` based on what the new spec genuinely did — not a brittle substring guess.

## Acceptance Criteria

> Reconciled against the live codebase (2.12 machinery present but input-starved). Where epic/FR10 wording assumes a working loop, the reconciled AC below is authoritative; rationale in Dev Notes (R1–R7).

1. **Given** allowed-actions (2.14) and a `WaitingForSpecApproval` run with an `answered` clarification, **Then** a new `AllowedAction ACCEPT_CLARIFICATION("accept_clarification")` is registered (drift: enum + `allowed-actions.placeholder.json` + `AllowedActionRegistryPinTest` wire pin) and surfaced by `WorkflowInspectionService.computeActionMatrix` for the reviewer role (`product_reviewer`/`workflow_owner`) at `WaitingForSpecApproval`; a REST `POST /workflows/{id}/clarifications/{clarificationId}/accept` + CLI parity drives `WorkflowCommandService.acceptClarification` → `ClarificationLifecycleService.markAccepted` (`answered → accepted`). Re-accepting an already-`accepted` row is idempotent-friendly; accepting an `open`/terminal row surfaces the existing lifecycle guard error. Mirrors the `answer_clarification` wiring (2.11/2.13).

2. **Given** an accepted clarification, **Then** a new `AllowedAction REGENERATE_SPEC("regenerate_spec_with_clarifications")` (same drift sites) lets the reviewer trigger a spec rebuild from `WaitingForSpecApproval`: it transitions the run back to `Investigating` and **re-dispatches the spec (INVESTIGATION) stage** via the existing `WorkflowOrchestrationService.retrySpecGeneration` path (L~300–331) — **reused, not duplicated** — incrementing the existing spec-loop counter for a deterministic idempotency key.

3. **Given** the spec rebuild dispatch, **Then** `ContextBundleService.assembleForSpecInvestigation` (L770+) adds, to `priorFeedbackReferences`, one `{referenceId: clarificationId, kind: "clarification.answered"}` entry per `accepted` clarification of the run (alongside the existing `spec.rejection` entries), **and** the bundle composer materializes each accepted clarification's `questionId`+`questionText`+`answerText` as a **redaction-policed** input file under the runner input dir (referenced, never inlined into the bundle JSON) so the runner can read the answer. The materialized content passes the existing adversarial redaction fixture gate (no secret leakage; answer text is reviewer-authored but still policed).

4. **Given** the runner-result contract, **Then** `runner-result.v1.schema.json` `specArtifact` gains an **optional** `clarificationAcknowledgements` array (declared in `properties`, not `required`, **no** `schemaVersion` bump — same additive discipline as 3e-1's `questions`). Each item: `{ "questionId": string (same pattern), "addressed": boolean }`. Both `runner.mjs` files (BYTE-IDENTICAL) emit it at `stage === 'spec'` from a fenced ` ```clarificationAcknowledgements ` block (mirror 3e-1's fence-split); both offline mocks emit a deterministic acknowledgement (`addressed:true` for any question id present in the input bundle) so the loop IT is deterministic.

5. **Given** the rebuilt spec is ingested, **Then** it is recorded as a **`newVersion` graft of the prior spec** (NOT a fresh lineage) so the prior clarification's pinned `artifactId` is in the new spec's `lineageArtifactIds` and the sweep considers it. (Implementation: route the spec-rebuild ingest through `ArtifactOperationService.newVersion`/`createNextVersion` for a run that already has a spec, OR widen `ClarificationLifecycleOrchestrator` lineage scope to the workflow-run — **R3 decides; the graft is recommended**. Whichever is chosen, an IT proves a v1-pinned clarification is swept on v2 availability.)

6. **Given** the sweep oracle, **Then** `ClarificationLifecycleOrchestrator.acknowledgesQuestion(...)` (the documented stub seam, L251–285) is **replaced** by consuming the structured `clarificationAcknowledgements` from the rebuilt spec's runner result (`addressed == true` ⇒ `markIncorporated`, else `markSuperseded` with `no_effect_reason = "spec_runner_skipped_question"` — already in the allowed vocabulary, `ClarificationLifecycleService.java:74–80`). The acknowledgements must be **plumbed to the sweep**: persisted on/with the spec artifact at ingest (the sweep runs inside `markAvailable` which has only the artifact, not the runner result) — e.g. an `artifact_clarification_acknowledgements` side-store keyed by artifactId, or carried on the artifact metadata read at sweep time. The substring scan + its `TODO` comment are deleted; the stale "hooked into newVersion" javadoc is corrected to "markAvailable".

7. **Given** the rebuilt spec, **When** the sweep runs, **Then** the FE incorporation lifecycle (2.18 `ClarificationRegion`) reflects each clarification moving `accepted → incorporated`/`superseded`, and a subsequent EXECUTION-stage context bundle includes `incorporated` clarifications via the existing `collectExecutionFeedbackReferences` (`ContextBundleService.java:1034–1038`, already present) — proving the full chain end-to-end.

8. **Given** tests, **Then** a **full-loop IT** (Failsafe + Testcontainers) proves: 3e-1 creates an `open` clarification → `/answer` → `/accept` (`accepted`) → `regenerate_spec_with_clarifications` re-dispatches → mock spec v2 ingested as a graft carrying `clarificationAcknowledgements:[{Q, addressed:true}]` → `markAvailable` sweep marks the clarification `incorporated`; a parallel case with `addressed:false` ⇒ `superseded`; lineage-scope proven (v1-pinned clarification swept on v2). Unit: `acceptClarification` wiring; the new sweep oracle over structured acknowledgements; contract valid/invalid for `clarificationAcknowledgements`; runner emission (both runners). Drift: 2 new AllowedActions + any new event types green. Redaction fixture green for the materialized answer file. `application.*` ≥80% coverage.

## Tasks / Subtasks

- [ ] **Task 1 — `accept_clarification` action + command + REST/CLI** (AC: 1)
  - [ ] `domain/registry/AllowedAction.java` — add `ACCEPT_CLARIFICATION("accept_clarification")`. Drift: `allowed-actions.placeholder.json` + a wire pin in `architecture/AllowedActionRegistryPinTest`.
  - [ ] New command `application/workflow/commands/AcceptClarificationCommand.java` (mirror `SubmitClarificationCommand`; carries `workflowRunId`, `clarificationId`, actor, idempotencyKey, correlationId — version-binding optional, `markAccepted` is on the answered row). Register per the WorkflowCommand fan-out recipe ([[epic3b-command-and-approval-wiring-fanout]] / `docs/patterns/registry-recipe.md §2`).
  - [ ] `WorkflowCommandService.acceptClarification(...)` (`@Transactional`) → `ClarificationLifecycleService.markAccepted(workflowRunId, clarificationId, actor)`.
  - [ ] REST `WorkflowController` `POST /{workflowRunId}/clarifications/{clarificationId}/accept` (mirror `answerClarification` L959–1034) + `WorkflowCommands` CLI parity (HUMAN actor). OpenAPI regen (this DOES change `openapi.json` — a new operation; regen `schema.d.ts` too — [[openapi-regen-frontend-client-drift-cascade]]).
  - [ ] `computeActionMatrix` — add `ACCEPT_CLARIFICATION` to the `WaitingForSpecApproval` reviewer-role action set (next to `ANSWER_CLARIFICATION`). Matrix test cases.

- [ ] **Task 2 — `regenerate_spec_with_clarifications` action → reuse `retrySpecGeneration`** (AC: 2)
  - [ ] `domain/registry/AllowedAction.java` — add `REGENERATE_SPEC("regenerate_spec_with_clarifications")` (same drift sites).
  - [ ] Command + `WorkflowCommandService.regenerateSpecWithClarifications(...)` → call the existing `WorkflowOrchestrationService.retrySpecGeneration(workflowRunId, correlationId)` (L300–331). It already: no-ops an in-flight dispatch, builds `specDispatchKey(runId, specRejectionLoopCount)`, enqueues INVESTIGATION. Confirm it transitions `WaitingForSpecApproval → Investigating` (or add that transition if `retrySpecGeneration` assumes a different entry state — verify the transition table allows `WaitingForSpecApproval → Investigating`; the spec-rejection retry loop already uses it per [[shared-rejection-taxonomy-check-needs-app-guards]]).
  - [ ] `computeActionMatrix` — surface `REGENERATE_SPEC` at `WaitingForSpecApproval` for the reviewer role (gate: only meaningful when ≥1 `accepted` clarification exists — surface always, let the service no-op/inform if none, OR compute presence; note the choice).

- [ ] **Task 3 — Feed accepted clarifications into the spec rebuild bundle** (AC: 3)
  - [ ] `application/runner/ContextBundleService.java#assembleForSpecInvestigation` (L770+) — after the `priorRejections` loop (L793–798), append one `priorFeedbackReferences` entry `{referenceId: clarification.publicId(), kind: "clarification.answered"}` per `accepted` clarification (read via the injected `ClarificationReadPort.listByWorkflowRunId`, filter `STATUS_ACCEPTED`). The method signature/caller must pass or fetch them — thread an `acceptedClarifications` list from the assembler's caller (the spec-investigation bundle build site), mirroring how `priorRejections` is threaded.
  - [ ] **Materialize** each accepted clarification as a referenced input file (questionId + questionText + answerText) under the runner input dir via the existing artifact/input materialization path (find where `priorFeedbackReferences` referenced content is written for `spec.rejection` — mirror it). **Route through the redaction policy** (`RedactionClassificationPolicy` / the secret-fixture gate, story 1.10/2.24) so the materialized file is policed exactly like artifact content. Never inline `answerText` into the bundle JSON (trap: bundle 2KB payload cap — [[context-bundle-2kb-payload-cap.md]] — and the redaction contract).
  - [ ] Bundle composition test: an accepted clarification appears as a `clarification.answered` feedback ref + a materialized, redaction-clean input file; `open`/`answered`-but-not-`accepted` clarifications do NOT (only `accepted` feed the rebuild).

- [ ] **Task 4 — `clarificationAcknowledgements` contract + runner emission** (AC: 4)
  - [ ] `runner-result.v1.schema.json` `specArtifact` — add optional `clarificationAcknowledgements` array (items `{questionId (same pattern), addressed (boolean)}`, `additionalProperties:false`); not `required`; no `schemaVersion` bump (3e-1 discipline). Valid (with/without) + invalid fixtures.
  - [ ] Both `runner.mjs` (BYTE-IDENTICAL) — fence-split ` ```clarificationAcknowledgements ` (sibling of 3e-1's `clarifications` split); set `artifact.clarificationAcknowledgements` only when non-empty at `stage === 'spec'`. Offline mocks: for each question id materialized in the input bundle, emit `{questionId, addressed:true}` (deterministic). Non-fatal on malformed JSON.
  - [ ] Runner unit tests (both runners).

- [ ] **Task 5 — Graft the rebuilt spec + plumb acknowledgements to the sweep** (AC: 5, 6)
  - [ ] **Graft (R3):** ensure a spec result for a run that ALREADY has a spec is ingested as a `newVersion` of the prior spec (so lineage holds). Options — pick one, record in Completion Notes: (a) in `RunnerBroker.handleSuccess`, when `artifactType==SPEC` and a prior active spec exists for the run, route to `ArtifactOperationService.newVersion(priorSpecId, payloadRef, actor)` instead of `recordOperation(CREATE)`; or (b) widen `ClarificationLifecycleOrchestrator` scope from `lineageArtifactIds` to all run clarifications. **Recommend (a)** — it preserves spec version lineage the 2.12 supersession semantics already assume. Add an IT proving a v1-pinned clarification is swept on v2.
  - [ ] **Plumb acknowledgements:** the sweep runs inside `markAvailable` which sees only the artifact. Persist the rebuilt spec's `clarificationAcknowledgements` at ingest so the sweep can read them by artifactId. Recommended: a small side-store (`spec_clarification_acknowledgements(artifact_id, question_id, addressed)`, additive Flyway, next free version) written in `handleSuccess` next to artifact ingest; the orchestrator reads it in `sweepAfterSpecRebuild`. (Avoid stuffing into artifact payload bytes — keeps the redaction/cap contract clean.)
  - [ ] **Replace the oracle:** `ClarificationLifecycleOrchestrator.acknowledgesQuestion(bytes, questionId)` → `wasAddressed(acknowledgements, questionId)`; `addressed==true` ⇒ `markIncorporated`, else `markSuperseded(... "spec_runner_skipped_question" ...)`. Delete the substring scan + `TODO`. Correct the stale class javadoc ("hooked into `newVersion`" → "`markAvailable`").

- [ ] **Task 6 — FE: accept + regenerate controls; incorporation reflection** (AC: 1, 2, 7)
  - [ ] Regenerate `schema.d.ts` FIRST (new endpoints/actions → check:api drift gate — [[openapi-regen-frontend-client-drift-cascade]], [[story-3c-9-projects-management-ui-reconciliations]]).
  - [ ] `ClarificationRegion`/decision-bar — add Accept + Regenerate-spec governed buttons gated by the new AllowedActions (no bare role text — the `no-bare-actor-role-text` eslint rule); reuse `useWorkflowMutation`. The incorporation lifecycle chips already exist (2.18) — verify they reflect `accepted`/`incorporated`/`superseded`.
  - [ ] Vitest + axe; (optional) extend the critical-journey e2e with the accept→regenerate→incorporated path.

- [ ] **Task 7 — Tests** (AC: 8, all)
  - [ ] **Full-loop IT** (Failsafe + Testcontainers) — the create→answer→accept→regenerate→graft-v2(+acknowledgements)→sweep chain; assert `incorporated` (addressed:true) and `superseded` (addressed:false); lineage-scope proven.
  - [ ] Unit: `acceptClarification`/`markAccepted` wiring; new sweep oracle over structured acknowledgements; bundle injection of accepted clarifications + redaction.
  - [ ] Contract: `clarificationAcknowledgements` valid/invalid; runner emission (both runners).
  - [ ] Drift: 2 new AllowedActions (placeholder + pins), any new event/Flyway (FlywaySchemaContractTest if a side-store table is added), OpenAPI + schema.d.ts (new endpoints — NOT byte-identical this time).
  - [ ] Redaction fixture green over the materialized answer input file.
  - [ ] Naming/tier: `@SpringBootTest`+Testcontainers ⇒ `*IT` via the lifecycle phase ([[maven-arglineation-goal-crash]]).

- [ ] **Logging instrumentation** (cross-cutting)
  - [ ] Accept, regenerate-dispatch, bundle injection, sweep-oracle decision — instrument (NOT N/A). Never log `questionText`/`answerText`/payload bytes (trap T12). Pin ≥1 line per new branch.

## Dev Notes

### Why these ACs are reconciled (epic/FR wording vs. the live codebase)

| Wording assumption | Reality (verified 2026-06-23) | Reconciliation |
|---|---|---|
| "accepted answers are incorporated into a rebuilt spec" (FR10/2.12) | `markAccepted` has **zero** prod callers; no `accept_clarification` action ⇒ the sweep's `accepted` filter is always empty. | Add `accept_clarification` action + command + REST/CLI + `acceptClarification` (R1, Task 1). |
| "the spec runner acknowledges which questions it addressed" | `acknowledgesQuestion` is a substring-scan **stub** with a `TODO(epic-3-runner-contracts)`. | Add optional `clarificationAcknowledgements` to the contract, emit from both runners, consume in the sweep (Task 4–5, R5). |
| "a rebuilt spec supersedes the old one and the sweep runs over it" | The sweep IS wired (on `markAvailable`, not `newVersion` — stale javadoc), but a re-dispatched spec mints a **fresh lineage** so the prior clarification is out of scope; `newVersion` has zero callers. | Graft the rebuilt spec via `newVersion` (or widen sweep scope) — R3, Task 5. |
| "answers flow to the runner" | `priorFeedbackReferences` is reference-by-id (no content); runner reads referenced files from its input dir. | Add a `clarification.answered` feedback ref + **materialize** the answer as a redaction-policed input file (R4, Task 3). |

### R1 — Explicit accept action (auto-accept rejected)
2.12 modeled `answered → accepted` as a distinct, audited PM judgment ("I accept this answer for incorporation"), and the sweep acts ONLY on `accepted`. Auto-accepting on `/answer` was considered and rejected: it erases the `answered`/`accepted` distinction (a reviewer could answer to think out loud without committing it to a rebuild), breaks the 2.12 event audit, and makes the sweep fire on un-vetted answers. Add an explicit `accept_clarification` — the structural twin of `answer_clarification` (2.11/2.13) and of how `accept_implementation`/`reject` wrap a lifecycle service.

### R2 — Reuse `retrySpecGeneration`; do not build a second spec-dispatch path
The spec-rejection retry loop already re-dispatches INVESTIGATION via `WorkflowOrchestrationService.retrySpecGeneration` (L300–331): in-flight no-op, `specDispatchKey(runId, specRejectionLoopCount)` idempotency, enqueue. `regenerate_spec_with_clarifications` is the same operation with a different trigger — reuse it. Confirm the `WaitingForSpecApproval → Investigating` edge is legal (the reject→retry loop uses it). Do not duplicate dispatch logic.

### R3 — The lineage trap is the crux of this story
`ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild` considers a clarification only if `c.artifactId ∈ lineageArtifactIds(newSpec)` (parent-walk, L237–249) AND `c.status == accepted`. A clarification is pinned to the v1 spec's `artifactId`. The broker ingests every spec via `recordOperation(CREATE)` → fresh `art_`, `parentArtifactId=null` → v2 is in its OWN lineage → v1's clarification is NEVER swept. `newVersion`/`createNextVersion` (the graft that sets `parentArtifactId`) exists but has **zero production callers** — it was built (2.12) as exactly this seam and left dormant. Resolve by grafting the rebuilt spec (recommended — preserves the version lineage the supersession semantics assume) or by widening the sweep scope to the workflow-run. Either way, an IT MUST prove a v1-pinned clarification is acted on when v2 becomes available. This is the single most likely place for the loop to silently no-op.

### R4 — Answers are materialized, not inlined (redaction + 2KB cap)
The bundle JSON is reference-by-id and capped (`CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES`, [[context-bundle-2kb-payload-cap.md]]); answer text must NOT be embedded in it. Mirror how `spec.rejection` feedback content is made available to the runner (referenced input file under the mounted input dir) and run the materialized answer file through the **redaction classification policy** (1.10/2.24) and the adversarial secret-fixture gate — reviewer-authored answer text is still untrusted content for redaction purposes. The runner reads the file by following the `clarification.answered` reference.

### R5 — Acknowledgements must be persisted at ingest because the sweep can't see the runner result
The sweep runs inside `ArtifactOperationService.markAvailable` (L395), which has the artifact but NOT the runner-result JSON (that lives in the broker). So the structured `clarificationAcknowledgements` the runner emits must be persisted at broker ingest (recommended: a small additive `spec_clarification_acknowledgements` side-store keyed by artifactId, next free Flyway version) and read by the orchestrator at sweep time. Do not push them through the artifact payload bytes (keeps the redaction/cap contract clean and avoids re-parsing spec markdown). `no_effect_reason="spec_runner_skipped_question"` is already in the allowed vocabulary (`ClarificationLifecycleService.java:74–80`) for the not-addressed case.

### R6 — The execution-stage feedback path already consumes `incorporated` clarifications
`ContextBundleService.collectExecutionFeedbackReferences` (L1010–1038) already adds `{clarification.incorporated}` refs for incorporated clarifications to the EXECUTION bundle. So once 3e-2 produces `incorporated` rows, the downstream implementation stage automatically carries them — no further wiring. AC7 just proves this end-to-end; do not rebuild it.

### R7 — Stale documentation to fix
`ClarificationLifecycleOrchestrator` class javadoc (L24–35) says "Hooked into `ArtifactOperationService.newVersion`" — the real hook is `markAvailable` (L395). Correct it while you're replacing the oracle, so the next reader isn't misled about where the sweep fires.

### Verified seams (file:line, 2026-06-23)
- Sweep is real + wired on availability — `application/artifact/ArtifactOperationService.java:395–405` (NOT `newVersion`).
- `markAccepted` zero callers — `application/clarification/ClarificationLifecycleService.java:138–171`.
- Acknowledgement STUB + `TODO(epic-3-runner-contracts)` — `application/clarification/ClarificationLifecycleOrchestrator.java:251–285`; lineage scope `:237–249`; `accepted` filter `:96–102`.
- `newVersion`/`createNextVersion` graft, zero callers — `application/artifact/ArtifactOperationService.java:492–512`.
- Spec re-dispatch reuse — `application/workflow/WorkflowOrchestrationService.java:300–331` (`retrySpecGeneration`).
- Spec bundle feedback seam — `application/runner/ContextBundleService.java:770–810`; execution incorporated-feedback `:1010–1038`.
- Only `answer_clarification` action — `domain/registry/AllowedAction.java:8`; answer REST `WorkflowController.java:959–1034`.
