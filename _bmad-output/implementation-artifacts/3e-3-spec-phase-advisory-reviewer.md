# Story 3e.3: Spec-Phase Advisory Reviewer (WaitingForSpecApproval)

Status: backlog

<!-- Added to Epic 3e via sprint-change-proposal-2026-06-23.md (correct-course addendum). Depends on 3d-2 (reviewer substrate) + 3e-1 (open clarifications as review context). Validation optional: run validate-create-story before dev-story. -->

> **⚠️ READ FIRST — this is an EXTENSION of the 3d-2 advisory reviewer, not a new reviewer subsystem.** Story 3d-2 built the entire advisory-LLM-reviewer substrate — `RunnerStage.REVIEW`, the `step_reviews` verdict table, `resolveReviewerKind`/`hasReviewerBinding`, `enqueueReviewerIfConfigured`, the `review-result.v1` schema, the `GET /reviewer-verdict` endpoint, and the Reviewer Verdict Panel — but **only fires it at the execution-output phase (`WaitingForReview`).** `enqueueReviewerIfConfigured(...)` is called from `onPlanStageSucceeded` (`WorkflowOrchestrationService.java:751`) and `onPrOutputStageSucceeded` (`:953`) — **NOT** from `onSpecStageSucceeded` (`:349`), the `WaitingForSpecApproval` entry. 3e-3 fires the SAME advisory reviewer at the spec phase, over the spec artifact, with **ticket + spec + open clarifications** as its review context, and surfaces the verdict in the spec Decision Bar. It is **advisory-only** — it never auto-approves/rejects the spec; the human PM decision remains governing (mirror 3d-2 DD-2).
>
> **Three reconciliations the live codebase forces (read all three before coding):**
>
> 1. **The reviewer trigger already exists — you call it from one more place.** `enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId)` enqueues a `RunnerStage.REVIEW` over the producing execution's artifact, async + non-blocking, inside the transition tx, and degrades every failure mode through the worker (panel "unavailable"). Add a call to it from `onSpecStageSucceeded` (the spec-ready transition), guarded so the spec-phase reviewer fires only when the project has a reviewer binding. The reviewer-kind binding (`reviewer_model_kind`) is **run-level, not per-stage** (`resolveReviewerKind`), so the same binding governs the spec reviewer and the execution reviewer — no new project field. See R1.
> 2. **The spec-review context bundle must include the OPEN CLARIFICATIONS — this is why 3e-3 depends on 3e-1.** The existing reviewer bundle (`ContextBundleService.assembleForReview`, `:730`) sets `priorFeedbackReferences` EMPTY ("the verdict is a fresh second opinion") and references only the reviewed artifact. For the spec phase the user explicitly wants the reviewer to weigh the **ticket + spec + open questions**. Add a spec-review bundle variant (or extend `assembleForReview`) that includes the run's `open` clarifications (reference-by-id + redaction-policed materialized content, mirror 3e-2 R4) so the reviewer reads the questions. Without 3e-1 there are no clarifications to include. See R2.
> 3. **`step_reviews` + the verdict endpoint already persist/serve any reviewed artifact — the gap is "which stage triggers" + the FE surfacing at the spec phase.** `step_reviews` stores `reviewed_artifact_id` + version + `outcome` + `rationale` generically; `GET /reviewer-verdict` already serves it. So persistence is reused unchanged. The net-new is: (a) the spec-phase trigger (R1), (b) the spec-review bundle (R2), (c) surfacing the verdict panel in the **spec** Decision Bar (the FE panel exists for `WaitingForReview`; render it at `WaitingForSpecApproval` too). See R3.

## Story

As a product reviewer,
I want a project-configured second LLM to review the spec — with the ticket, the specification, and the open clarification questions as its context — before I approve it,
So that I get a governed second opinion at the spec-approval gate (not only at the execution-output gate), without surrendering my human approval authority.

## Acceptance Criteria

> Reconciled against the live codebase: 3d-2 built the reviewer substrate but wired it only at `WaitingForReview`. The reconciled ACs below extend it to `WaitingForSpecApproval`; rationale in Dev Notes (R1–R6).

1. **Given** a project with a reviewer binding (`reviewer_model_kind` set — the same 3d-1/3d-2 binding, NOT a new field), **When** a spec artifact becomes available and the run advances `Investigating → WaitingForSpecApproval` (`onSpecStageSucceeded`), **Then** an advisory reviewer invocation (`RunnerStage.REVIEW`) is enqueued over the spec artifact via the existing `enqueueReviewerIfConfigured` — async, non-blocking, inside the spec-ready transition tx, resolved through `ProjectConnectorResolver` using the per-project reviewer credential (decrypted in memory only, never logged).

2. **Given** the spec-review context, **Then** the reviewer's input bundle includes the **ticket summary + the spec artifact + the run's `open` clarifications** (question id + text, reference-by-id with redaction-policed materialized content per 3e-2 R4) — so the reviewer can assess the spec *and* flag whether the open questions are adequately handled. (Execution-phase reviewer bundles are unchanged — this is a spec-review-only bundle variant.)

3. **Given** the reviewer run, **Then** a `runner_executions` row is recorded for it (FR53 inspectability) and a `step_reviews` row persists the structured `outcome` + `rationale` + reviewer/producer model identities, with `reviewed_artifact_id` = the spec artifact + version (the existing generic `step_reviews` write — no schema change).

4. **Given** the advisory contract (ADR 0026 / 3d-2 DD-2), **Then** the spec-phase verdict is **surfaced** to the human in the `WaitingForSpecApproval` Decision Bar via the existing Reviewer Verdict Panel; it does **not** auto-approve or auto-reject the spec, `reviewer_gating_enabled` is not consulted, and the human approve/reject/answer/accept actions are unchanged.

5. **Given** provenance + self-review detection (3d-2 AC4), **Then** the verdict records which model produced the spec and which produced the review; a same-model self-review is flagged as a warning in the panel (reused unchanged).

6. **Given** no reviewer binding on a project, **Then** behavior is byte-identical to pre-3e-3 (no spec reviewer run, no panel) — strictly opt-in per project, the parity hot path (`hasReviewerBinding == false ⇒ no enqueue`).

7. **Given** a reviewer-run failure (misconfig, missing credential, provider error, timeout), **Then** it degrades gracefully through the SAME single path as 3d-2: the spec gate is **not** blocked, the panel shows "review unavailable" with the reason, the failed reviewer execution is recorded — a failed second opinion never strands the spec approval.

8. **Given** redaction (story 1.10) + the spec-review bundle (AC2), **Then** the reviewer's input (incl. the materialized clarification content) and output pass the same redaction guarantees + adversarial secret-fixture gate as any runner artifact before persistence/egress.

9. **Given** the `GET /reviewer-verdict` endpoint (3d-2), **Then** it serves the spec-phase verdict for a `WaitingForSpecApproval` run with no contract change (the endpoint is artifact/run-scoped, not stage-bound) — confirm + test; OpenAPI snapshot byte-identical.

10. **Given** tests, **Then** coverage asserts: the spec reviewer enqueues on `WaitingForSpecApproval` entry when bound and NOT when unbound (parity); the spec-review bundle includes ticket + spec + open clarifications; the verdict persists to `step_reviews` against the spec artifact and surfaces advisory-only in the spec Decision Bar; human spec decision unaffected; graceful degradation on reviewer failure; self-review flagged; redaction on the spec-review egress incl. clarification content; `application.*` ≥80% line coverage.

## Tasks / Subtasks

- [ ] **Task 1 — Fire the advisory reviewer at the spec phase** (AC: 1, 6)
  - [ ] `application/workflow/WorkflowOrchestrationService.java#onSpecStageSucceeded` (`:349`) — after the `Investigating → WaitingForSpecApproval` transition commits (mirror the placement in `onPlanStageSucceeded` `:751`), call `enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId)`. The method already: resolves `hasReviewerBinding` (no-throw), no-ops on no binding (parity), enqueues `RunnerStage.REVIEW`, pins the reviewed artifact best-effort, and swallows enqueue failures (run remains correctly advanced). **Do not** duplicate that logic — reuse it.
  - [ ] Confirm `pinReviewedArtifactBestEffort`/the compose derivation resolves the **spec** artifact for an INVESTIGATION-stage producing execution (it derives the reviewed artifact from the producing execution's stage). If the derivation is execution-stage-specific, extend it to map INVESTIGATION → the spec artifact. Note the decision in Completion Notes.

- [ ] **Task 2 — Spec-review context bundle (ticket + spec + open clarifications)** (AC: 2, 8)
  - [ ] `application/runner/ContextBundleService.java` — add a spec-review path (new `assembleForSpecReview` OR a flag on `assembleForReview` `:730`) that, in addition to the reviewed spec artifact + ticket summary, includes the run's `open` clarifications: one `priorFeedbackReferences` entry `{referenceId: clarificationId, kind: "clarification.open"}` per open clarification + the question id/text **materialized as a redaction-policed referenced input file** (reuse the 3e-2 materialization helper / the `spec.rejection` reference-content pattern; never inline into the bundle JSON — respects the 2KB cap [[context-bundle-2kb-payload-cap]]).
  - [ ] The reviewer bundle composer selects this variant when the REVIEW execution reviews a SPEC artifact (derive from the reviewed artifact's type). Execution-stage review bundles (plan/pr-output) are unchanged.
  - [ ] `ClarificationReadPort.listByWorkflowRunId` is already injected into `ContextBundleService` (3.10) — filter `STATUS_OPEN`. (Open clarifications exist only once 3e-1 ships — dependency.)

- [ ] **Task 3 — Surface the verdict in the spec Decision Bar** (AC: 4, 5, 9)
  - [ ] Frontend — render the existing Reviewer Verdict Panel (3d-2) in the `WaitingForSpecApproval` Decision Bar context (next to the spec approve/reject/answer/accept controls), fed by the existing `GET /reviewer-verdict` (run-scoped). Reuse the panel component + the self-review warning + the "unavailable" state; no new panel.
  - [ ] Confirm the verdict-fetch hook keys off the run (not a hardcoded `WaitingForReview` state) so it loads at `WaitingForSpecApproval`. Adjust the state gating if it was scoped to execution review only.
  - [ ] Vitest + axe over the panel rendered in the spec context.

- [ ] **Task 4 — Backend verification of reused surfaces** (AC: 3, 9)
  - [ ] Confirm `step_reviews` write (the 3d-2 harvester, `ReviewResultHarvester`) records a spec-phase verdict with `reviewed_artifact_id` = spec + version with no schema change (it stores any reviewed artifact). Add an IT path for a spec-stage REVIEW harvest.
  - [ ] Confirm `GET /reviewer-verdict` serves the spec-phase verdict (run-scoped). OpenAPI snapshot byte-identical (no contract change). If the endpoint filters by stage, widen it.

- [ ] **Task 5 — Tests** (AC: 10, all)
  - [ ] **Spec-reviewer enqueue IT** (Failsafe + Testcontainers): a project WITH a reviewer binding → a spec result drives `onSpecStageSucceeded` → a `RunnerStage.REVIEW` execution is enqueued over the spec artifact; a project WITHOUT a binding → NO reviewer enqueue (parity, byte-identical to pre-3e-3).
  - [ ] Spec-review bundle test: the composed bundle includes ticket + spec ref + open-clarification refs + materialized (redaction-clean) question content.
  - [ ] Harvest IT: a spec-stage `review-result.v1` harvests into `step_reviews` against the spec artifact; `GET /reviewer-verdict` serves it.
  - [ ] Graceful degradation: a reviewer misconfig/failure on the spec phase yields panel "unavailable", spec gate NOT blocked, failed reviewer execution recorded (reuse the 3d-2 degradation assertions).
  - [ ] Redaction: adversarial fixture over the spec-review input incl. clarification content + the reviewer output.
  - [ ] FE: Vitest + axe for the verdict panel in the spec Decision Bar; advisory-only (no auto-approve).
  - [ ] Naming/tier: `@SpringBootTest`+Testcontainers ⇒ `*IT` via the lifecycle phase ([[maven-arglineation-goal-crash]]).

- [ ] **Logging instrumentation** (cross-cutting)
  - [ ] Spec-phase reviewer enqueue decision (`bound|parity`), bundle variant selection, verdict harvest — instrument (NOT N/A). Never log spec/clarification/answer text or reviewer credential (trap T12). Pin ≥1 log line per new branch.

## Dev Notes

### Why these ACs are reconciled (request vs. the live codebase)

| Request | Reality (verified 2026-06-23) | Reconciliation |
|---|---|---|
| "perform review on the WaitingForSpecApproval phase" | The advisory reviewer (3d-2) fires only at `WaitingForReview` — `enqueueReviewerIfConfigured` is called from `onPlanStageSucceeded`/`onPrOutputStageSucceeded`, not `onSpecStageSucceeded`. | Call the SAME trigger from `onSpecStageSucceeded` (R1, Task 1). |
| "Reviewer can get ticket, specification and opened questions" | The reviewer bundle (`assembleForReview`) carries only the reviewed artifact + empty prior-feedback. | Add a spec-review bundle variant that includes the ticket + spec + run's `open` clarifications (R2, Task 2) — hence the 3e-1 dependency. |
| "and review it" (advisory) | `step_reviews` + `GET /reviewer-verdict` + the verdict panel already exist (3d-2), generic over any reviewed artifact. | Reuse persistence + endpoint; surface the panel in the spec Decision Bar (R3, Task 3). Advisory-only — no auto-approve (3d-2 DD-2). |

### R1 — One more call site, run-level reviewer binding
The cheapest correct change: `onSpecStageSucceeded` calls `enqueueReviewerIfConfigured` exactly as the two execution callbacks do. The reviewer binding is **run-level** (`resolveReviewerKind`/`hasReviewerBinding` take only `workflowRunId`), so the spec reviewer and the execution reviewer share one project binding — no per-stage reviewer field, no new resolver. All the hard parts (async non-blocking, in-tx enqueue, graceful degradation, self-review flag, parity hot path) are already solved by 3d-2 and inherited for free. The only spec-specific care is that the reviewed artifact derivation resolves the SPEC for an INVESTIGATION-stage producing execution (Task 1 second bullet).

### R2 — The spec reviewer needs the open clarifications → depends on 3e-1
The user's intent ("ticket, specification and opened questions") makes the spec-review bundle richer than the execution-review bundle. Execution review is "a fresh second opinion over one artifact" (empty prior-feedback by design). Spec review wants the open questions so the reviewer can judge whether the spec leaves them unresolved. Reuse the 3e-2 redaction-policed materialization (reference-by-id + input file, never inline — 2KB cap + redaction contract). This is why 3e-3 sequences AFTER 3e-1 (no open clarifications exist before it).

### R3 — Persistence + endpoint are stage-agnostic; only trigger + FE surfacing are net-new
`step_reviews.reviewed_artifact_id` + version is generic; the harvester writes whatever artifact the REVIEW execution reviewed; `GET /reviewer-verdict` is run-scoped. So no schema/endpoint change — confirm + test the spec-artifact path and (if any) widen a stage filter. The FE panel exists; render it in the spec Decision Bar and ensure its fetch isn't hardcoded to `WaitingForReview`.

### R4 — Advisory-only is structural, inherited from 3d-2
3d-2 DD-2: the verdict never auto-approves/rejects; `reviewer_gating_enabled` (3d-1, default false) is not consulted this epic. 3e-3 keeps that posture at the spec gate — the human approve/reject/answer/accept (2.13/3e-2) actions are untouched; the panel is presentational. Gating-capable spec review is a later epic if ever.

### R5 — Out of scope
Per-stage reviewer model selection (a different reviewer LLM for spec vs execution) — out of scope; one run-level reviewer binding governs both. Reviewer auto-gating the spec — out of scope (advisory only). The reviewer proposing answers to the open clarifications — out of scope (it flags adequacy; answering remains the human's via 3e-1/`/answer`).

### Verified seams (file:line, 2026-06-23)
- Reviewer trigger (execution-only today) — `WorkflowOrchestrationService.java:751` (onPlanStageSucceeded), `:953` (onPrOutputStageSucceeded), helper `enqueueReviewerIfConfigured` `:769`, `pinReviewedArtifactBestEffort` `:825`. Spec entry that LACKS the call — `onSpecStageSucceeded` `:349`.
- Reviewer bundle (empty prior-feedback) — `ContextBundleService.assembleForReview:730`; clarification read port already injected (3.10) `:73`/`:1034`.
- Reviewer kind/binding (run-level) — `ProjectRuntimeConfigResolver.resolveReviewerKind:190` / `hasReviewerBinding:185`.
- Verdict persistence/serve — `application/review/ReviewResultHarvester.java`; `GET /reviewer-verdict` (3d-2). step_reviews generic over reviewed artifact.
