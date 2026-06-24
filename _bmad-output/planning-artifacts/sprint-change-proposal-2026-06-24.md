# Sprint Change Proposal — Add Epic 3f: Complex Ticket Flow

**Date:** 2026-06-24
**Author:** Alex (via Correct Course workflow)
**Mode:** Incremental (collaborative discussion → batch write-up)
**Scope classification:** Major (new 6-story epic introducing net-new PRD scope — FR70/FR71/FR72; PM/Architect-level replan, no rollback, no MVP cut)
**Status:** Approved — artifacts updated

---

## Section 1 — Issue Summary

**Request:** Add the ability to **split** a governed run into several smaller subtasks at the specification / implementation-plan review gates, so an oversized ticket can be decomposed into less work per task. During discussion this expanded into a coherent **"complex ticket flow"** theme with three capabilities:

1. **Split** — at `WaitingForSpecApproval` / `WaitingForReview`, an **LLM proposes** a decomposition into smaller subtasks; the operator **approves**, **continues as one ticket**, or **re-proposes with feedback**. On approval the system creates a **child run** per subtask (a **source sub-ticket** where the connector supports it, otherwise internal-only), preserves **parent→child lineage**, and terminates the parent.
2. **Dependencies** — child (or any) runs can declare execution **dependencies** so a dependent run does not start until its prerequisites complete (e.g. "Task 3 after Task 1 + Task 2").
3. **Portfolio visibility** — the Run Review Queue gains **project attribution + a project filter** so the multiplied run count stays navigable.

**How discovered:** user correct-course request, 2026-06-24, refined through interactive design discussion (decisions recorded in Section 3).

---

## Section 2 — Impact Analysis

**Epic impact:** No existing epic is modified or invalidated. This is **net-new product scope** captured by a new **Epic 3f**, inserted between Epic 3e and Epic 4 (sequencing only — avoids renumbering E4–E6). It is **not** an activation of deferred work (contrast Epic 3e). Story 3f-6 completes one explicitly-deferred AC from story 3c-9 (queue-scoping AC6).

**Story impact:** Six net-new stories (3f-1..3f-6). No existing story is reopened. The done substrates below are **reused as-is**:
- Advisory-reviewer channel (3d-2 / 3e-3) → drives the split **proposal**.
- Batch-submission fan-out (story 3-18, best-effort + non-transactional) → the split **commit** shape.
- `TicketSourceAdapter` + capabilities (story 3-32) → the new `createSubticket` capability.
- `cancelled_for_takeover` terminal-state precedent (story 3-22, V15) → the new `SPLIT` state.
- Soft-hide/archive (3d-8) → parent disposition precedent.
- Project read side (3c-6/3c-7 `projectId` on runs) → 3f-6 attribution + filter.

**Artifact conflicts:**
- **PRD:** **net-new** — adds **FR70** (split/decompose, lineage-preserving), **FR71** (run dependencies / sequenced execution), **FR72** (queue project attribution + filter) under a new "Complex Ticket Flow" requirements subsection. **NFR16** (1:1 ticket↔run) is **not** changed — the split path invokes its existing "unless a human explicitly reconciles the record" escape hatch (documented in ADR-0029).
- **Architecture:** additive — `parent_run_id` column, `run_dependencies` table, two new `WorkflowState`s (`SPLIT` terminal, `WaitingForDependencies` non-terminal), one new `WorkflowEventType` (`workflow.split`), four new `AllowedAction`s, one new error code (`RUN_DEPENDENCY_CYCLE`), a `supportsTicketCreation` capability + `createSubticket` port method, and additive split-proposal fields on the runner-result channel. Proposed **ADR-0029**.
- **UX:** net-new — a Split Proposal Panel (advisory overlay at the existing gate, three actions), a `WaitingForDependencies` waiting state, run-view parent↔child lineage, and the queue project column + filter (reuses the 3c-9 selector).

**Technical impact:** backend (new services: `SplitProposalService`, `SplitCommitService`, `RunDependencyService` + release resolver; broker/orchestration seams; create-seam `parentRunId`), Linear adapter (`createSubticket`), both `runner.mjs` entrypoints + mocks (byte-identical split fence-split), runner-contract additive fields + fixtures, two Flyway migrations (`parent_run_id`; `run_dependencies`) + two `current_state` CHECK widenings, REST + CLI + FE across all six stories, OpenAPI + `schema.d.ts` regen, redaction fixtures, and foundation-gate drift across registries/states/events/actions/error-code.

---

## Section 3 — Recommended Approach

**Selected path: Direct Adjustment (add a new epic + 6 stories within the existing plan).** Rollback is N/A (nothing to revert). MVP review is N/A (this **adds** scope; it cuts nothing).

**Design decisions resolved during discussion (2026-06-24):**
- **Proposal source:** LLM-proposed decomposition (reviewer-style channel), not manual operator entry.
- **Gate mechanics:** three actions — **approve split** / **continue as one ticket** / **re-propose with feedback** — as an **advisory overlay at the existing gate** (no new gate state); the parent only leaves its gate on approve.
- **Subtask shape:** **child runs** in the parent's project, **with** real source sub-tickets where the connector supports it, **plus** a parent→child link.
- **Parent fate:** split is **terminal** for the parent (new `SPLIT` state); all phases (spec, plan, …) re-run fresh per child.
- **Adapter fallback:** if the connector **cannot** create tickets, proceed **internal-only** (children link to the parent ticket) — not a hard fail.
- **Commit failure policy:** **best-effort** (3-18 pattern) **with a zero-child guard** — the parent does **not** terminate if no children were created.
- **Dependencies:** declare run-to-run edges (acyclic); dependent parks in **`WaitingForDependencies`** until all prerequisites `COMPLETED`; a **failed** prerequisite leaves dependents blocked + visible (no cascade-cancel this epic).
- **Placement & naming:** its own epic — **Epic 3f "Complex Ticket Flow"** — not a single 3e-5 (the three-action loop + dependencies + queue filter make one story unrealistic).

**Rationale:** every capability rides a done substrate (reviewer channel, batch fan-out, ticket-adapter abstraction, terminal-state + archive precedents), keeping the work additive and well-isolated; splitting into six stories lets the foundations (3f-1/3f-2/3f-3/3f-6) land independently before the operator-facing split flow (3f-4 proposal → 3f-5 commit) integrates them.

- **Effort:** 3f-1 Medium; 3f-2 Medium; 3f-3 High (DAG + gated dispatch + release resolver); 3f-4 High (LLM channel + persisted three-action loop); 3f-5 High (integration fan-out + zero-child guard + idempotency); 3f-6 Low-Medium (completes deferred 3c-9 AC6).
- **Risk:** highest in 3f-3 (release-resolver correctness on completion) and 3f-5 (partial-failure + zero-child guard + idempotent replay).
- **Timeline:** inserted between Epic 3d/3e and Epic 4; does not block their completion.

---

## Section 4 — Detailed Change Proposals

**New file:** `_bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md` — full epic definition (narrative, gap rationale, prerequisites/reused substrates, proposed ADR-0029, 6-story list with reconciled ACs).

**`_bmad-output/planning-artifacts/prd.md`:**
- New requirements subsection "Complex Ticket Flow (Split, Dependencies & Portfolio Visibility)" with **FR70 / FR71 / FR72**.

**`_bmad-output/planning-artifacts/epics.md`:**
- Inserted an **Epic 3f** section between Epic 3e and Epic 4 (positioning = sequencing only) with story list + "FRs covered: FR70/FR71/FR72". (FR inventory/table back-port skipped — matches how Epics 3c/3d/3e recorded FR56–69 in the PRD only.)

**`_bmad-output/implementation-artifacts/sprint-status.yaml`:**
- New block `epic-3f: backlog` + `3f-1..3f-6: backlog` (one-line reconciliation per story) + `epic-3f-retrospective: deferred`, inserted between the Epic 3e retro and the Epic 4 header.

**Implementation stories (not authored here):** `_bmad-output/implementation-artifacts/3f-1..3f-6-...md` — to be authored via `bmad-create-story` before `dev-story`.

**Forward obligation (not created here):** `docs/adr/0029-complex-ticket-flow.md` — author alongside story 3f-2.

---

## Section 5 — Implementation Handoff

**Scope:** Major (net-new PRD scope) → the PRD/epics/sprint-status are updated here; story authoring + ADR-0029 remain. Route to **Developer agent** for story authoring + implementation; no further PM/Architect replan needed beyond this proposal.

**Sequencing:** foundations first — **3f-1**, **3f-2**, **3f-3**, **3f-6** are independent and parallelizable; then **3f-4** (proposal, needs the reviewer channel 3d-2/3e-3 done) → **3f-5** (commit, needs 3f-1/3f-2/3f-3/3f-4). 3f-6 can ship any time (completes 3c-9 AC6).

**Success criteria:**
- 3f-1: a Linear split creates linked sub-issues; a no-creation connector skips to internal-only.
- 3f-2: a split parent ends in `SPLIT` with queryable parent↔child lineage; normal runs are byte-identical.
- 3f-3: a dependent run parks in `WaitingForDependencies` and is released + dispatched only when its last prerequisite completes; cycles are rejected.
- 3f-4: request → re-propose-with-feedback → the proposal updates; decline restores normal gate actions; nothing is created until approve.
- 3f-5: approve → N children (mixed sub-ticket + internal-only) with lineage + dependency gating; the zero-child guard leaves the parent untouched; replay is idempotent.
- 3f-6: the queue shows project attribution and `?projectId=` scopes it; no-filter behavior is unchanged.

**Next step:** author `3f-1` (and ADR-0029) via `bmad-create-story`, then `dev-story 3f-1`.

---

## Appendix — Open items deferred forward (not in this epic)

- Cascade-cancel of dependents when a prerequisite fails (3f-3 leaves them blocked + visible).
- GitHub/GitLab `createSubticket` (3f-1 ships Linear only; others report `supportsTicketCreation=false`).
- Per-subtask editing in the Split Proposal Panel (3f-4 ships whole-proposal approve/decline/re-propose).
- True purge of split-parent records (Epic 5 owns purge).
