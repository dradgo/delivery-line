# Sprint Change Proposal — Spec-Stage (Investigating) Runner Console Visibility + Placement

**Date:** 2026-06-24
**Author:** Alex (via bmad-correct-course)
**Mode:** Batch
**Status:** Proposed → awaiting approval

---

## Section 1 — Issue Summary

A run viewed in the **Investigating** status shows **no console output** (reported against
`http://localhost:8080/workflows/run_bc3bf1d400374379a0bb8156e937ba55`).

`Investigating` is the **spec-generation stage**: `WorkflowTransitionTable` dispatches a spec
runner on `INBOX → INVESTIGATING` (story 3a-1), so a runner container is live and producing
output during `Investigating` exactly as during `Executing`. Yet the run-detail page shows none
of it.

**Root cause (verified on the live codebase, 2026-06-24):**
`WorkflowInspectionService.baseActionMatrix`'s `INVESTIGATING` arm (`WorkflowInspectionService.java:908-918`)
returns only `VIEW_ONLY` (+ `ANSWER_CLARIFICATION` when there is an open clarification). It never
offers `VIEW_RUNNER_LOGS` or `OPEN_DIAGNOSTIC_CONSOLE`. The frontend gates both observability
surfaces on those actions (`index.tsx:146,161`), so the `StepExecutionLogViewer` (3d-5) and
`ReadOnlyDiagnosticConsole` (3d-6) never render for an `Investigating` run.

This is a **coverage gap**, not a broken subsystem: stories 3d-5 (AC6) and 3d-6 (AC4) scoped their
affordances to the execution states (`Executing`/`Failed`/`Paused`/`WaitingForReview`, and
`Executing`-only for the console). The spec stage was an unintended blind spot. The streaming
endpoints themselves (`GET /runner-logs/stream`, `GET /diagnostic-console/stream`) are
**stage-agnostic** and already handle the spec runner — only the matrix gate is missing.

A second, cosmetic request rides along: the user wants this console-output control rendered at the
**bottom of the run-detail page, below the action buttons** (the `WorkflowDecisionBar`). Today the
viewers render near the **top** of the route (`index.tsx:194-206`), above the bar (`:294`).

**Evidence:**
- `WorkflowInspectionService.java:908-918` — `INVESTIGATING` arm (no log/console actions).
- `WorkflowInspectionService.java:933-952` — `EXECUTING` arm (the affordances, with the owner-only console split).
- `WorkflowTransitionTable.java:31-63` — `INBOX → INVESTIGATING`; INVESTIGATING is a live spec-runner state.
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx:194-206` (viewers at top) vs `:294` (Decision Bar at bottom).

---

## Section 2 — Impact Analysis

- **Epic impact:** None regresses. The fix is added as a **new story (3e-5)** in **Epic 3e**
  (per user direction). Thematic fit is reasonable — `Investigating` *is* the spec phase Epic 3e
  already targets (3e-1 clarification emission, 3e-3 spec-phase reviewer).
- **Story impact:** One net-new story, `3e-5`. No existing story changes.
- **PRD:** No change. Extends existing **FR65** (live + historical step-log viewing) and **FR68**
  (read-only diagnostic console) to the spec stage. No new requirement.
- **Architecture:** **ADR 0025** (live observability + read-only console) was scoped to execution
  states — gets a short amendment note extending coverage to `Investigating`. Security posture is
  **identical** (read-only/input-disabled console, owner-only, live-only re-check at attach), so
  **no new sign-off gate** (contrast 3d-6 AC1, whose sign-off already ratified the design).
- **UI/UX:** Placement change — log viewer + diagnostic console move below the Decision Bar.
- **Contracts / tech impact:** **No** new `AllowedAction`, event type, error code, Flyway migration,
  or `runner-result` field. `view_runner_logs` / `open_diagnostic_console` already exist in the
  enum, the frontend placeholder, and the `getAllowedActions` `@Schema(allowableValues)` → **OpenAPI
  snapshot byte-identical, no `schema.d.ts` regen**. Test impact is the matrix exact-list assertions
  + a couple of FE DOM-order assertions.

---

## Section 3 — Recommended Approach

**Option 1 — Direct Adjustment** (add one story to Epic 3e). **Recommended.**

- Effort: **Low–Medium** (one matrix `switch` arm + its tests; a JSX relocation + its tests; a
  one-line ADR note).
- Risk: **Low** (no contract/schema/migration churn; the streaming plumbing is already shipped and
  stage-agnostic; the change is gated and reversible).
- Options 2 (rollback) and 3 (MVP review): **Not applicable** — nothing to roll back; MVP unaffected.

**Rationale:** the substrates (3d-5 log viewer, 3d-6 console) are done and the endpoints already
serve the spec runner; the symptom is a single missing matrix affordance. A contained story closes
it with minimal surface area and no contract risk.

---

## Section 4 — Detailed Change Proposals

### 4.1 New story (implementation artifact)

`_bmad-output/implementation-artifacts/3e-5-spec-stage-runner-observability-and-decision-bar-placement.md`
— **Story 3e.5: Spec-Stage (Investigating) Runner Log & Console Visibility + Decision-Bar-Relative
Placement** (status `backlog`). 7 ACs; full reconciliations + tasks. (Written alongside this proposal.)

### 4.2 Epic doc — `_bmad-output/planning-artifacts/epic-03e-clarification-loop-activation.md`

- Story List header: **4 stories → 5 stories**; add 3e-5 to the list.
- Add a **Story 3e-5** section after 3e-4.
- Cross-Cutting Notes / FRs: note 3e-5 extends FR65/FR68 (Epic 3d) to the spec stage.

### 4.3 Consolidated epics — `_bmad-output/planning-artifacts/epics.md`

- Epic 3e "Positioning" / FRs line: add a sentence that 3e-5 extends FR65/FR68 observability to the
  `Investigating` (spec-generation) state + relocates the run-detail placement.

### 4.4 Sprint status — `_bmad-output/implementation-artifacts/sprint-status.yaml`

- Epic 3e header comment: **4 stories → 5 stories**.
- Add `3e-5-spec-stage-runner-observability-and-decision-bar-placement: backlog` after the 3e-4 line.

### 4.5 Implementation surface (for the dev agent — detail in the story file)

- **Backend:** `WorkflowInspectionService.baseActionMatrix` `INVESTIGATING` arm — add
  `VIEW_RUNNER_LOGS` (all roles) + `OPEN_DIAGNOSTIC_CONSOLE` (`workflow_owner` only) to both the
  open-clarification and no-open branches, mirroring `EXECUTING` (minus `await_outcome` /
  `view_provider_usage_status`). Update `WorkflowInspectionServiceAllowedActionsTest` `Investigating`
  rows + the open/no-open clarification tests. No placeholder/OpenAPI/Flyway change.
- **Frontend:** `routes/workflows/$workflowRunId/index.tsx` — move the `StepExecutionLogViewer` +
  `ReadOnlyDiagnosticConsole` blocks below `<WorkflowDecisionBar/>`; gating unchanged. Update FE
  route tests for the new DOM order + an `Investigating`-state render case.
- **Docs:** ADR 0025 amendment note (Investigating coverage, identical posture).

---

## Section 5 — Implementation Handoff

- **Scope classification: Moderate** (backlog addition spanning backend + frontend; no strategic
  replan). Route to **Developer agent** with PO awareness of the new backlog entry.
- **Deliverables:** the four artifact edits above (already drafted in this correct-course run) + the
  new story file; implementation per the story's tasks.
- **Success criteria:** an `Investigating` run shows live spec-runner logs (and, for the owner, the
  read-only console) rendered below the Decision Bar; matrix + FE tests green; OpenAPI snapshot
  byte-identical; `application.*` ≥80% coverage holds.
- **Sequencing:** independent of 3e-1..3e-4 — can be picked up any time within Epic 3e.
