# Sprint Change Proposal — Recursive Split + Split-Parent Completion Rollup

**Date:** 2026-06-24
**Author:** Alex (via bmad-correct-course)
**Epic affected:** 3f — Complex Ticket Flow
**Scope classification:** Moderate (one new story + targeted AC amendments to 3 existing 3f stories; no rollback, no MVP change)
**Status:** Proposed — awaiting approval

---

## Section 1 — Issue Summary

Epic 3f (authored earlier today, `sprint-change-proposal-2026-06-24.md`) introduced split/decompose, run dependencies, and queue project filtering. While reviewing it for the multi-level case, two gaps surfaced:

1. **Recursive split is implied but its parent semantics are wrong for it.** A child run produced by a split (3f-5) is a normal run that re-runs all phases and reaches its own `WaitingForSpecApproval` / `WaitingForReview` gate, so the split actions (3f-4) are already surfaced on it — recursion is *structurally* free. But 3f-2 makes the split parent **terminal `SPLIT` immediately**. In a multi-level tree that means an interior node is declared "finished" the instant it is decomposed, before any of its descendants have done the work.

2. **A split run strands its dependents.** 3f-3's release resolver fires **only when a prerequisite reaches `COMPLETED`** (AC5), but a split run terminates in `SPLIT` and **never reaches `COMPLETED`**. So if run **X depends on Y** and **Y is then split**, X is stranded in `WaitingForDependencies` forever — its prerequisite can never reach the state the resolver waits on. This bites precisely when a *prerequisite* is itself split, i.e. the multi-level case.

**Evidence:** 3f-2 AC2 ("new **terminal** state `SPLIT` … none out"); 3f-3 AC5 ("when any run reaches `COMPLETED`, … for each [dependent] whose **every** prerequisite is now `COMPLETED`, transition it"); 3f-5 AC4 ("parent transitions to **terminal** `SPLIT`"). Nothing in 3f-1..3f-6 connects a `SPLIT` parent's eventual descendant completion back to either parent doneness or dependent release.

---

## Section 2 — Impact Analysis

- **Epic impact:** Epic 3f only. No other epic is affected; Epic 4 (recovery) is unaffected — a stalled rollup is operator-recoverable via the existing failed-child retry/takeover paths.
- **Story impact:**
  - **New: Story 3f-7** — Recursive Split + Split-Parent Completion Rollup.
  - **Amend 3f-2** — `SPLIT` becomes **non-terminal** with a single legal out-edge `SPLIT → COMPLETED`. (This reverses the "terminal, none out" decision and must be a conscious change.)
  - **Amend 3f-3** — release resolver needs **no edge-rewriting and no tree-walking**: a split parent now reaches `COMPLETED` via rollup, so dependents on it release through the *existing* resolver. Add the failed-descendant stall note.
  - **Amend 3f-5** — parent transitions to **non-terminal** `SPLIT` ("decomposed, awaiting children") at commit; recursion + soft depth cap honored at the request path.
  - **Light touch 3f-4** — `REQUEST_SPLIT` consults the new soft depth-cap guard (substance owned by 3f-7).
- **Artifact conflicts:** PRD (FR70/FR71 wording), `epics.md` (Epic 3f narrative + story list), `epic-03f-complex-ticket-flow.md` (narrative, 3f-2/3f-3/3f-5 ACs, new 3f-7 section, cross-cutting notes), `sprint-status.yaml` (Epic 3f block: add 3f-7, annotate 3f-2/3f-3/3f-5). ADR-0029 (proposed, owned by 3f-2) must record the rollup model.
- **Technical impact:** one state-machine edge (`SPLIT → COMPLETED`); one new resolver service (split-completion rollup) mirroring the 3f-3 release resolver (best-effort, idempotent, swallow+log); one new error code (`SPLIT_DEPTH_LIMIT_EXCEEDED`, three sites); one config property (`complex-ticket-flow.max-split-depth`, default 3); a request-time override flag. No new table; split depth is computed by walking the bounded `parentRunId` lineage chain.

---

## Section 3 — Recommended Approach

**Direct Adjustment (Option 1)** — add Story 3f-7 and amend 3f-2/3f-3/3f-5. Effort: **Medium**. Risk: **Low-Medium** (state-machine + resolver changes are well-precedented by 3f-3's own release resolver). No rollback (nothing built yet — all of Epic 3f is `backlog`). No MVP change.

### Design decisions (resolved in discussion 2026-06-24)

- **Dependency unblocking = rollup satisfaction, realized through parent completion.** No edge-rewriting. A split parent genuinely reaches `COMPLETED`; the existing 3f-3 release resolver then unblocks dependents with zero changes to its predicate.
- **Parent status = `COMPLETED` after children finish.** `SPLIT` is a **non-terminal** "decomposed, awaiting children" state. A **rollup resolver** transitions `SPLIT → COMPLETED` when **all direct children are `COMPLETED`**, and **recurses up** the tree (a split child counts as complete only once its own rollup fired, so grandparents flip naturally).
- **Failed child = stall + operator-visible, no cascade.** A failed child blocks its parent's rollup; the parent stays in non-terminal `SPLIT` with the failed descendant surfaced; dependents on the parent stay blocked. Mirrors 3f-3 AC6's failed-prerequisite policy. Operator retries/takes over the failed child to unstick the rollup.
- **Recursion depth = soft cap + override.** Default max split depth **3**; `REQUEST_SPLIT` deeper returns `SPLIT_DEPTH_LIMIT_EXCEEDED` unless an explicit, audited override flag is passed. Prevents runaway decomposition while keeping the escape hatch.

---

## Section 4 — Detailed Change Proposals

### 4.1 NEW — Story 3f-7: Recursive Split + Split-Parent Completion Rollup

> As an operator who has split an oversized run, **and** an authorized user sequencing decomposed work,
> I want a split run to be treated as *decomposed and pending* (not finished) until all of its descendant runs complete — at which point it rolls up to `Completed` — and I want subtasks to themselves be splittable to a bounded depth,
> So that multi-level decomposition reflects real progress, and a dependent of a run that later gets split is unblocked the moment that subtree actually finishes, rather than being stranded.

**Depends on:** 3f-2 (lineage + `SPLIT` state), 3f-3 (dependency edges + release resolver), 3f-5 (commit fan-out). Sequenced **after 3f-5**.

**Acceptance Criteria:**

1. **Given** the `WorkflowState` registry/state-machine (as amended in 3f-2), **Then** `SPLIT` is **non-terminal** with exactly one legal out-edge `SPLIT → COMPLETED`; the transition is driven only by the rollup resolver (never by an operator action). Drift-tested against the DB CHECK + state-machine + API schema.
2. **Given** a `RunSplitCompletionRollupService`, **Then** when any run reaches `COMPLETED` it looks up that run's `parentRunId`; if the parent is in `SPLIT` **and every direct child of the parent is `COMPLETED`**, it transitions the parent `SPLIT → COMPLETED` and **recurses** on the parent's own `parentRunId` (a split child satisfies its parent only via its *own* rolled-up `COMPLETED`). Idempotent, best-effort, swallows + logs `RuntimeException` so one stuck ancestor never strands the completing run's callback (the 3f-3 release-resolver discipline).
3. **Given** cross-split dependency unblocking, **Then** a dependent `X → Y` where `Y` is later split is released by the **existing 3f-3 release resolver with no modification** — because `Y` now reaches `COMPLETED` through rollup. Proven by IT: declare `X` depends on `Y`; split `Y` into `Y1,Y2`; `Y` parks in `SPLIT`; complete `Y1,Y2`; `Y` rolls up to `COMPLETED`; `X` releases and dispatches.
4. **Given** a **failed** descendant, **Then** the parent's rollup does **not** fire (it requires *all* children `COMPLETED`); the parent remains in non-terminal `SPLIT`, the failed descendant is operator-visible on the parent/lineage view, and dependents on the parent stay blocked. **No cascade-cancel and no cascade-fail.** Retrying/taking over the failed descendant to `COMPLETED` resumes the rollup (idempotent).
5. **Given** recursive split, **Then** `REQUEST_SPLIT` (3f-4) on a run whose split depth (distance from the lineage root, computed by walking `parentRunId`) is **≥ `complex-ticket-flow.max-split-depth`** (default **3**) is rejected with a new `SPLIT_DEPTH_LIMIT_EXCEEDED` error (registry + ProblemDetails + drift; three sites) **unless** an explicit override flag (`allowDeepSplit=true` REST / `--allow-deep-split` CLI) is supplied, in which case the deep split is permitted and the override is recorded in the governed history. Within the cap, a child at its own gate splits via the unchanged 3f-4/3f-5 path.
6. **Given** events + read model, **Then** the parent's rollup completion reuses the existing run-completed event with an allow-listed detail key `viaSplitRollup=true` (no new event type — NFR43); the run/lineage view shows a `SPLIT` parent as "decomposed — N of M descendants complete" rather than "finished", and shows it flip to `Completed` on rollup.
7. **Given** parity, **Then** a normal (non-split) run is byte-identical to pre-3f-7: it never enters `SPLIT`, never invokes the rollup resolver, and completes exactly as before; a single-level split with no dependents behaves as 3f-5 described except the parent ends `COMPLETED` (via rollup) instead of terminal `SPLIT`.
8. **Given** tests, **Then** coverage asserts: `SPLIT → COMPLETED` state-machine + CHECK drift; rollup fires only when all direct children complete; recursion flips a grandparent; cross-split dependency release IT (AC3); failed-descendant stall (AC4); depth cap + override + `SPLIT_DEPTH_LIMIT_EXCEEDED` drift (AC5); `viaSplitRollup` event detail; non-split parity; resolver idempotency + exception-swallowing; `application.*` ≥80% coverage.

### 4.2 AMEND — Story 3f-2 (Parent→Child Lineage + `SPLIT` State)

**AC2 — OLD:** "a new **terminal** state `SPLIT` … (legal transitions **into** `SPLIT` from `WaitingForSpecApproval` and `WaitingForReview`; **none out**)."
**AC2 — NEW:** "a new **non-terminal** state `SPLIT` … (legal transitions **into** `SPLIT` from `WaitingForSpecApproval` and `WaitingForReview`; **exactly one out-edge `SPLIT → COMPLETED`**, driven only by the split-completion rollup of story 3f-7). Drift-tested against the DB CHECK + state-machine + API schema."
**Rationale:** the rollup model requires the split parent to eventually reach `COMPLETED`. ADR-0029 (owned by 3f-2) records `SPLIT` as a non-terminal "decomposed, awaiting children" disposition that rolls up, and why this satisfies NFR16's explicit-reconciliation hatch.

### 4.3 AMEND — Story 3f-3 (Run-Dependency Graph + Gating)

**AC5 — add clause:** "A prerequisite that is **split** reaches `COMPLETED` via the story-3f-7 rollup, so dependents on it release through this **unchanged** resolver — no edge-rewriting and no lineage-walking predicate is added here."
**AC6 — add clause:** "Symmetrically, a **failed descendant of a split prerequisite** stalls that prerequisite's rollup (3f-7 AC4); its dependents therefore remain blocked and operator-visible — consistent with the failed-prerequisite no-cascade policy."

### 4.4 AMEND — Story 3f-5 (Split Commit Fan-Out)

**AC4 — OLD:** "the parent transitions to **terminal** `SPLIT` … only if ≥1 child run was created."
**AC4 — NEW:** "the parent transitions to **non-terminal** `SPLIT` ('decomposed, awaiting children'; rolls up to `COMPLETED` per 3f-7) + appends `workflow.split` **only if ≥1 child run was created**; the zero-child guard is unchanged (every subtask failed → split aborts, parent untouched)."
**AC (recursion) — add:** "A child created by this commit is a normal run; once it reaches its own gate it may itself be split via the unchanged request→approve path, subject to the 3f-7 depth cap. The commit sets each child's lineage so the 3f-7 rollup can later compute parent doneness."

### 4.5 AMEND (light) — Story 3f-4 (Split-Proposal Channel)

**AC1 — add clause:** "`REQUEST_SPLIT` consults the story-3f-7 depth-cap guard; a request beyond `complex-ticket-flow.max-split-depth` without the override flag is refused with `SPLIT_DEPTH_LIMIT_EXCEEDED` before any proposal LLM call is made."

### 4.6 AMEND — PRD (FR70 / FR71)

- **FR70 — add:** "Subtasks may themselves be split recursively, subject to a configurable depth limit; a split run is treated as *decomposed and pending* until all its descendant runs complete, at which point it rolls up to a completed state — preserving full multi-level lineage."
- **FR71 — add:** "A split prerequisite run satisfies its dependents once all of its descendant runs complete (rollup); a failed descendant holds the rollup, leaving dependents blocked and operator-visible with no cascade."

### 4.7 AMEND — `epics.md` Epic 3f narrative + story list

- Narrative: "…preserving parent→child lineage and **decomposing the parent into a non-terminal `Split` state that rolls up to `Completed` once all descendants finish**" (replace "terminating the parent"). Add a sentence on recursive split + depth cap.
- Story list: append "3f-7 recursive split + split-parent completion rollup".
- FRs covered: note 3f-7 extends FR70 (recursive) + FR71 (rollup satisfaction).

---

## Section 5 — Implementation Handoff

- **Scope:** Moderate → **Product Owner / Developer** coordination (backlog update + reconciled story authoring).
- **Sequencing:** 3f-7 runs **after 3f-5** (it depends on the committed lineage, the `SPLIT` state, and the dependency edges). Foundations 3f-2/3f-3 carry the amended ACs.
- **Forward obligation:** author the detailed reconciled story file `{implementation_artifacts}/3f-7-recursive-split-and-completion-rollup.md`; ADR-0029 records the rollup model (non-terminal `SPLIT` + rollup resolver + depth cap); `docs/complex-ticket-flow-walkthrough.md` gains a multi-level + rollup section; glossary adds "split rollup" / "split depth".
- **Success criteria:** the AC3 cross-split dependency IT passes; non-split parity holds; depth cap + override are drift-tested; foundation gate stays green with the new error code + state edge.

---

## Artifacts to be updated on approval

1. `epic-03f-complex-ticket-flow.md` — narrative; amend 3f-2/3f-3/3f-4/3f-5 ACs; add Story 3f-7; cross-cutting notes (new error code, state edge, config, rollup resolver).
2. `prd.md` — FR70/FR71 clauses.
3. `epics.md` — Epic 3f narrative + story list + FRs-covered.
4. `sprint-status.yaml` — add `3f-7-…: backlog`; annotate 3f-2/3f-3/3f-5.
5. Auto-memory — update `epic-3f-complex-ticket-flow.md` with the 7th story + the reversed `SPLIT`-terminality decision.
