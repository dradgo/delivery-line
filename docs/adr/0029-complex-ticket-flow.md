# ADR 0029 — Complex Ticket Flow (Run Split, Parent→Child Lineage & Run Dependencies)

**Status:** Proposed (2026-06-24) — to be confirmed during Epic 3f story creation (3f-1..3f-6)
**Driver:** Epic 3f (PRD FR70 split/decompose, FR71 run dependencies, FR72 queue project filter). Real pilot tickets are frequently too coarse to specify or implement in a single governed pass. The operator needs a governed way, at the spec- or implementation-review gate, to **split** an oversized run into smaller subtasks, **sequence** the resulting work, and still keep the portfolio of runs navigable — without losing lineage or breaking append-only history.

## Context

Through Epic 3e the governed workflow is strictly linear: one ticket → one run → one spec → one plan → one PR output. NFR16 codifies this — "one governed run must map to one ticket, one repository context, and one implementation lineage **unless a human explicitly reconciles the record**." There is, today, no governed mechanism to perform that explicit reconciliation by decomposition: `workflow_runs` has no parent/child relationship, `TicketSourceAdapter` (ADR 0007) is read + comment only (no ticket creation), there is no run-to-run sequencing, and — per story 3c-9 AC6, deferred as backend-blocked — the run review queue cannot attribute or filter runs by project.

Three substrates already exist and should be reused rather than re-derived:
- the **advisory-reviewer channel** (ADR 0026 — `RunnerStage.REVIEW`, persisted verdicts, advisory-only) reads an artifact and emits structured output for human consideration;
- the **batch-submission fan-out** (story 3-18) mints N independent runs best-effort and non-transactionally with per-item idempotency;
- the **terminal-state + soft-hide precedents** (`cancelled_for_takeover` story 3-22; archive ADR 0027) show how to retire a run without erasing history.

This ADR records how the split feature composes those substrates and three new model elements — parent→child lineage, a run-dependency DAG, and a split-proposal channel — while keeping NFR16 and append-only history (FR47/NFR4) intact.

## Decision

**1. Split is the NFR16 "explicit reconciliation" case, not a violation of it.** Decomposing one run into several breaks the 1:1 ticket↔run↔lineage shape deliberately. The operator's **`approve_split`** action *is* the explicit human reconciliation NFR16 already permits. Lineage is preserved (decision 2), so the record is reconciled, not lost.

**2. Parent→child lineage via `parent_run_id` + a terminal `Split` state.** A new nullable `workflow_runs.parent_run_id` FK (additive migration, indexed; existing rows `NULL` = top-level) records each child's origin. A new **terminal** `WorkflowState` `Split` (`split`) — modelled on `cancelled_for_takeover` — receives the parent on commit, with legal transitions **into** `Split` only from `WaitingForSpecApproval`/`WaitingForReview` and none out. A new `WorkflowEventType` `workflow.split` (two fixture sites, `childRunIds` detail key) appends the decomposition to the parent's append-only history. Children re-run **all** phases fresh (spec → plan → …) scoped to their slice; the parent's artifacts are not copied into children (a child may receive the parent spec only as context-bundle input).

**3. Source sub-tickets where the connector supports it; internal-only otherwise.** `TicketSourceAdapter` gains `createSubticket(...)` behind a `TicketSourceCapabilities.supportsTicketCreation` flag (default `false`). Connectors that can create tickets (Linear first) mint a linked sub-ticket per subtask and post a parent-link back-reference; connectors that cannot keep the default and the commit proceeds **internal-only** (the child links to the parent's ticket). A split is never blocked by a connector's inability to create tickets.

**4. The split proposal is an advisory reviewer-style channel with a three-action loop — no new gate state.** `request_split` triggers a reviewer-style LLM call (reusing ADR 0026's `RunnerStage.REVIEW` over the spec/plan artifact) that emits a **structured proposal** — `subtasks[]` + proposed `dependencies[]` — via a fenced ` ```split ` JSON block from both runner entrypoints (byte-identical, OpenSpec fence-split precedent) and both offline mocks. The proposal is **persisted** until the operator acts and surfaced as an **advisory overlay at the existing gate** (Split Proposal Panel) with three actions: **approve** (`approve_split` → commit), **continue as one ticket** (`continue_as_single` → dismiss, normal gate actions resume), and **re-propose with feedback** (`repropose_split` → re-run with the operator's feedback as a redaction-policed `priorFeedbackReferences` input, tracked by a `split_proposal_loop_count` with an escalation-marker safety valve — the spec-rejection / clarification-incorporation pattern). The parent only leaves its gate on **approve**; the proposal never auto-commits.

**5. Commit is best-effort + non-transactional with a zero-child guard.** `SplitCommitService.commit` reuses the story-3-18 shape: each subtask is processed in its own transaction (sub-ticket-or-internal-only → mint child run with `parent_run_id` → record dependency edges), so one subtask's failure does not poison the rest. The parent transitions to `Split` and appends `workflow.split` **only if ≥1 child run was created**; if every subtask fails, the split aborts and the parent is left untouched at its gate. Commit is idempotent (keyed by parent run + proposal; per child by parent + ordinal) so a replayed approve neither double-creates sub-tickets/children nor double-terminates the parent.

**6. Run dependencies are an acyclic graph gated by a `WaitingForDependencies` state.** A new `run_dependencies(run_id, depends_on_run_id)` join table (additive migration; self-edge rejected by CHECK) records edges; `RunDependencyService` enforces acyclicity (a cycle-forming edge → new `RUN_DEPENDENCY_CYCLE` error code, three sites). A new **non-terminal** `WorkflowState` `WaitingForDependencies` holds a run with unmet prerequisites — the one place auto-dispatch at creation is suppressed. When any run reaches `Completed`, a `RunDependencyReleaseService` transitions each dependent whose **every** prerequisite is now `Completed` to `Investigating` and dispatches it (idempotent, best-effort). A run with zero/satisfied dependencies dispatches normally (parity).

**7. A failed prerequisite leaves dependents blocked and visible — no cascade-cancel.** Dependents of a failed run remain in `WaitingForDependencies` (not released, not auto-failed); the blocked state and the failed blocker are operator-visible. Cascade-cancel is an explicit forward option, not in Epic 3f.

**8. Portfolio visibility surfaces the existing project FK.** The run-list read model and queue DTO expose each run's `projectId` (from the 3c-7 FK — no new persistence); `/workflows` gains an optional `projectId` filter (absent = all = parity; unknown id → existing `PROJECT_NOT_FOUND`). This completes story 3c-9 AC6.

## Alternatives Considered

### Alt 1 — Sub-steps within a single run instead of child runs
**Rejected.** Decomposing the implementation plan into finer steps keeps one run, one ticket, one lifecycle — it does not deliver "several tasks with less work each," cannot carry independent review gates or dependencies across the slices, and does not match the operator's mental model of splitting a too-large ticket.

### Alt 2 — Manual operator-defined subtasks (no LLM proposal)
**Rejected as the primary path.** Hand-typing N subtask scopes ignores the "with less work" assistance the operator asked for; the reviewer-style channel already exists (ADR 0026) and gives a governed, iterable proposal. (Manual entry remains a possible future fallback if the LLM channel is unavailable.)

### Alt 3 — A dedicated `WaitingForSplitApproval` gate state for the proposal
**Rejected.** A proposal that the operator may decline or iterate on does not need to move the parent out of its gate; an advisory overlay (decision 4) reuses the ADR 0026 substrate almost wholesale and lets the split compose with the normal spec/plan actions. The parent's only state change is the terminal `Split` transition on approve.

### Alt 4 — Hard-fail the split when the connector cannot create tickets
**Rejected.** It would make split unavailable on GitHub/GitLab projects until those adapters gain ticket creation. Internal-only fallback (decision 3) keeps the capability universal; the lineage link is preserved regardless of whether an external sub-ticket exists.

### Alt 5 — All-or-nothing transactional commit
**Rejected.** A single transaction spanning N external ticket creations + N run creations couples unrelated failures and fights the existing async/best-effort posture (story 3-18, ADR 0026). Best-effort + the zero-child guard (decision 5) gives partial progress without ever half-terminating the parent.

### Alt 6 — Cascade-cancel dependents when a prerequisite fails
**Rejected for Epic 3f.** Auto-cancelling downstream work on a failure is a strong, hard-to-reverse action better decided alongside Epic 4 recovery semantics; blocking + surfacing (decision 7) is the conservative default.

## Consequences

### Positive
- Operators can decompose an oversized ticket through a governed, iterable, LLM-assisted path that preserves full parent→child lineage and append-only history.
- Run dependencies make "do this after those finish" an explicit, inspectable state rather than manual coordination.
- The feature is overwhelmingly additive — it reuses the reviewer channel, batch fan-out, ticket-adapter abstraction, and terminal-state precedents rather than introducing new subsystems.
- Story 3f-6 closes the long-deferred 3c-9 queue-scoping gap as a side effect of needing portfolio visibility.

### Negative
- Two new workflow states (`Split` terminal, `WaitingForDependencies` non-terminal), one event type, four allowed-actions, one error code, two migrations, and a capability flag to maintain across registries, fixtures, and the foundation gate.
- A `WaitingForDependencies` run depends on its prerequisites (and ultimately a human if one fails) to proceed — operator queues must surface blocked runs as explainable, not stalled.
- Split multiplies run count; without 3f-6's project filter the queue would become hard to navigate (hence 3f-6 ships in the same epic).

### Neutral
- `parent_run_id` is `NULL` for all pre-3f runs; the dependency table and `createSubticket` capability are opt-in — existing linear flows are byte-identical unless split/dependencies are used.
- Whether an external sub-ticket exists for a child is a connector-capability detail; downstream governance treats internal-only and externally-ticketed children identically except in recorded linkage.
- Cascade-cancel, GitHub/GitLab `createSubticket`, and per-subtask proposal editing are deliberately deferred forward options, not gaps in the model.

## References
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-06-24.md`] — Epic 3f proposal; FR70/FR71/FR72.
- `_bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md` — epic + 6-story breakdown.
- `docs/adr/0026-per-step-advisory-reviewer-model.md` — reviewer-style channel reused for the split proposal.
- `docs/adr/0007-ticket-source-abstraction.md` — `TicketSourceAdapter` extended with `createSubticket` + capability flag.
- `docs/adr/0024-manual-execution-mode.md`, `docs/adr/0027-obsolete-execution-soft-hide.md` — new-WorkflowState + terminal/retire precedents + ADR format.
- PRD NFR16 (1:1 ticket↔run "unless a human explicitly reconciles"), FR47 / NFR4 (append-only history).
- `docs/glossary.md` — `split`, `subtask`, `child run`, `run dependency`, `WaitingForDependencies` entries to be introduced (3f documentation increment).
