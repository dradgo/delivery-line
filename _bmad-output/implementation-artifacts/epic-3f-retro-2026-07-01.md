# Epic 3f Retrospective - Complex Ticket Flow

**Date:** 2026-07-01
**Facilitator:** Amelia (Developer)
**Project Lead:** Alex
**Epic Status:** Complete (8/8 stories `done`)
**Retrospective Type:** Seventh retrospective (carries Epic 3e commitments; hands off to Epic 3g, the 3g–3l family warm-up)

---

## 1. Epic Summary

| Dimension | Result |
|---|---|
| Scope | Broke the 1:1 ticket↔run shape: split an oversized run into governed child runs (LLM-proposed, operator-driven 3-action loop), preserved parent→child lineage, sequenced work via a run-dependency DAG, decomposed the parent into a non-terminal `Split` state that **rolls up** to `Completed`, recursed to a soft depth cap, and added project attribution + filter to the Run Review Queue. |
| Stories shipped | **8/8 done** — the planned 7 (3f-1..3f-7) plus **3f-8**, spun out of 3f-7's code review as a durability follow-up. |
| New persistence | V27 `parent_run_id`, V28 `run_dependencies`, V29 `split_proposals`, V30 `internal_subtask` integration type. |
| New states | `SPLIT` (non-terminal, exactly one rollup out-edge `SPLIT → COMPLETED`), `WaitingForDependencies` (non-terminal). |
| New error codes | `RUN_DEPENDENCY_CYCLE`, `SPLIT_DEPTH_LIMIT_EXCEEDED` (three-sites each). |
| New events | `workflow.split`, `SPLIT_DEPTH_OVERRIDE`. |
| Critical defects shipped | 0 known open high/medium findings at epic close. |
| Contract posture | Additive runner-contract split fields stayed v1-compatible; OpenAPI/`schema.d.ts` churn limited to real endpoint/DTO additions. |
| Review intensity | Heavy and decisive: 3f-3, 3f-4, 3f-7, 3f-8 each ran through bmad-code-review's 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor); multiple patches applied per story. |

**Goal achieved:** Epic 3f is the first feature to legitimately break the NFR16 1:1 ticket↔run invariant, doing so through the "human explicitly reconciles the record" escape hatch (`approve_split`). It delivered FR70 (split/decompose, lineage-preserving), FR71 (run dependencies / sequenced execution with rollup satisfaction across a split prerequisite), and FR72 (queue project attribution + filter, closing the long-deferred 3c-9 AC6).

**Headline:** The rollup architecture is the epic's high point — 3f-7 made recursion **and** cross-split dependency release *emergent* from the **unchanged** 3f-3 release resolver, because a split parent now genuinely reaches `COMPLETED`. But that same async, non-transactional, self-healing machinery was also the source of the epic's real cost: nearly every bug was in one family — replay / transaction-boundary / afterCommit-ordering — the exact class Epic 3e's retro warned would "magnify" in 3f.

---

## 2. Team Participants

- **Amelia (Developer)** — facilitator
- **Alice (Product Owner)** — product-loop, scope discipline, NFR16 reconciliation boundary
- **Charlie (Senior Dev)** — runner/broker, idempotency, afterCommit/transaction seams
- **Dana (QA Engineer)** — contract, replay, real-PG ITs, redaction, drift gates
- **Winston (Architect)** — reuse posture, rollup/dependency model, Epic 3g readiness
- **Alex (Project Lead)** — convener

---

## 3. Successes & Strengths

### 3.1 Orderly, dependency-respecting delivery (Alex's headline "went well")
The sequencing held from start to finish. 3f-1/3f-2/3f-3 landed as independent foundations, 3f-4/3f-5 as the integration, 3f-7 as the rollup, and 3f-8 spun off cleanly the moment review found the durability gap. No story blocked on a half-built dependency. That orderliness is *why* the bugs were catchable — each seam was isolated enough to review adversarially.

### 3.2 Rollup as emergent behavior, not new machinery
3f-7 is pure orchestration over shipped substrate — **no** new Flyway/state/transition-edge (the `SPLIT` state and `SPLIT → COMPLETED` edge already shipped in 3f-2). Recursion is the afterCommit hook chain re-firing for the grandparent; cross-split dependency unblocking comes **for free** through the unchanged 3f-3 resolver. This is the strongest architectural signal of the epic: the 3f-3 seam was reusable under adjacent pressure without modification.

### 3.3 A durability gap found by review, not by a stranded production run
3f-7's `SPLIT → COMPLETED` rollup is driven **solely** by the afterCommit hook on the **last child's** COMPLETED transition. A single transient failure of that one best-effort `REQUIRES_NEW` execution (advisory-lock timeout / deadlock / optimistic-lock / DB blip) permanently strands the parent in `SPLIT` with cross-split dependents blocked — because no later event re-fires it. Catching this in review rather than in production is exactly what the adversarial layers exist for. It became story 3f-8.

### 3.4 Reuse remained the default
- 3f-4 reused the 3d-2/3e-3 reviewer channel for the split proposal (fenced ```split JSON).
- 3f-5 reused the story-3-18 best-effort, non-transactional batch fan-out shape.
- 3f-8 reused 3f-7's idempotent `rollupParentOf` with no new transition/event/migration.

### 3.5 Scope discipline held
The plan's "forward options" list stayed a list: no cascade-cancel/cascade-fail, no GitHub/GitLab `createSubticket`, no per-subtask proposal editing, no depth-cap removal. A bigger product surface (split, dependencies, portfolio queue) landed without a bigger conceptual model beyond the two justified new states.

---

## 4. Challenges & Growth Areas

### 4.1 The effort and the bugs were concentrated in ONE class (Alex's headline "where it hurt")
Alex's honest signal — *"it took a lot of effort to make it actually work; there were a lot of bugs"* — maps precisely onto the record. The bugs were **not** typos or code-quality lapses; they were almost all in the same family:

- **3f-4:** harvester must be *total* — a catch-less `onResult` strands a RUNNING execution.
- **3f-4:** dispatch key must be distinct-per-attempt or replay silently no-ops.
- **3f-5 (prod bug):** because `commit()` is non-`@Transactional`, the parent-ref read had to switch to a **non-locking** finder.
- **3f-7 (Decision 1):** the `SPLIT_DEPTH_OVERRIDE` governance/audit append had to move **off** the top-of-`request()` path onto the *proceed* path, or replay/degrade/no-op polluted the governed history.
- **3f-8:** the stranded-parent discovery query must exclude archived parents (`and parent.archivedAt is null`) — else an archived `SPLIT` parent throws inside the swallowed rollup every tick, WARN-spams forever, and head-of-line-blocks the bounded oldest-first scan.

Every one is a **replay / transaction-boundary / afterCommit-ordering** bug. They don't surface on the happy path — only under replay, concurrent afterCommit hooks, or a transient lock failure. The high effort was the *cost of finding them*, and it was real work, not wasted motion.

### 4.2 Root cause: no shared replay-safe afterCommit pattern (this is 3e's B1, still open)
Epic 3f is the first place the system does durable, multi-actor, **replayable** side effects across transaction boundaries (3f-3 dependency release, 3f-7 rollup recursion, 3f-8 sweep — all `REQUIRES_NEW` + advisory lock + swallow/log + idempotent re-invoke). We re-derived that discipline **per story** instead of once. The pain Alex felt is the running cost of leaving Epic 3e's carried-over **B1** (shared idempotent/replay-safe side-effect pattern) unimplemented. 3f is the loudest evidence yet that the deferral is not free.

### 4.3 afterCommit hooks are the sharpest standing seam
`REQUIRES_NEW` propagation is mandatory (plain `REQUIRED` throws when the lock is taken). This single trap drove the design of three separate stories. Until it is consolidated, every new async hook carries the same footguns.

### 4.4 A "discovery query feeding transition()" must mirror the transition's own gates
The 3f-8 archived-parent trap generalizes: any scan/sweep query that feeds `transition()` must exclude exactly what the transition would reject, or it degrades into an infinite swallowed-exception loop that head-of-line-blocks the scan.

---

## 5. Previous Retrospective Follow-Through (Epic 3e → Epic 3f)

| 3e commitment | Status | Evidence in 3f |
|---|---|---|
| P1: verify Linear `issueCreate` / sub-issue fields before coding the adapter | ✅ Completed | 3f-1 shipped Linear `createSubticket` (sub-issue) + parent-link comment. |
| P2: record immutable proposal/operation identity before fan-out | ✅ Applied | 3f-4 distinct-per-attempt dispatch keys; 3f-5 keyed parent+proposal / parent+ordinal — the exact 3e-2 graft-replay lesson carried forward. |
| P3: close the `projectId` DTO gap with drift + a11y coverage | ✅ Completed | 3f-6 delivered, closing deferred 3c-9 AC6 (project column + filter, axe-clean, `useLiveAnnouncement` waitFor). |
| A2: treat caught persistence exception post-flush as session-poisoning | ⏳ Reinforced | New trap logged: a caught idempotency-conflict poisons the shared tx; must prevent the flush, pin with an IT. |
| B1: shared idempotent / replay-safe side-effect pattern | ⏳ **Still open — escalated** | 3f produced the strongest evidence yet (see 4.2). Now a committed Epic 4 deliverable (A1 below). |

**Continuity signal:** As in every recent retro, the *prep* actions landed cleanly and the *consolidation* debt (B1) stayed open. Epic 3f is where the cost of that pattern became measurable in developer effort.

---

## 6. Significant Discovery — Epic 3g Plan Impact

**No Epic 3g update required.** Epic 3g (Run Provenance & Token Accounting) is the deliberately-light warm-up of the 3g–3l family: pure additive read-model work with **no** new `WorkflowState`, `AllowedAction`, `WorkflowEventType`, or `DomainErrorCode`. After the state-machine, lineage, and afterCommit weight of 3f, 3g is a lower-risk epic by design, and it pins the additive-DTO conventions the heavier 3h–3l epics reuse.

**Carry-forward into 3g:**
- The summary **exact-field contract test** (`containsExactlyInAnyOrder` over `WorkflowSummaryResponse`) will silently break CI-only when 3g-1 adds `ticketTitle`; update the guard in the same commit.
- The additive runner-contract `usage` block must mirror into both `runner.mjs` entrypoints + both offline mocks byte-identically (the standing mock-vs-real bar).
- 3g-1's title/URL must be **immutable snapshots at run creation**, never live-resolved — the same "record immutable truth early" discipline 3f just paid to learn.

---

## 7. Readiness Assessment — Epic 3f

| Dimension | Status | Notes |
|---|---|---|
| Stories complete | ✅ Complete | 8/8 `done`; retrospective was the only remaining deferred key. |
| Testing and quality | ✅ Strong | Surefire 1432/0/0 at 3f-8 close; real-PG ITs on every risky path (rollup, dependency release, sweep, commit fan-out); FE vitest + axe green. |
| Open high/medium findings | ✅ None known | Closed before `done` across four adversarial reviews. |
| Durability edge (the 3f-7 strand) | ✅ Addressed **and now enabled** | 3f-8 sweep self-heals a stranded parent. **Action taken in this retro:** the sweep was `@ConditionalOnProperty`-gated OFF by default; per Alex's decision it is now `deliveryline.complex-ticket-flow.rollup-sweep.enabled: true` in the pilot config so a transient strand self-heals. |
| Deployment / stakeholder | Local-first, no external gate | No separate external stakeholder acceptance recorded. |
| Technical health | Good, with an honest caveat | The async/replay machinery *works* and is well-tested, but is **un-consolidated** (B1). Not fragile, but effort-expensive to extend safely until the shared pattern is extracted. |

**Verdict:** Epic 3f is genuinely complete — not "story-done, secretly broken." The bugs were found and closed under adversarial review. The one real forward risk is not a loose end in 3f; it is the still-deferred B1, whose cost 3f made visible.

---

## 8. Action Items

### Process / Quality

| ID | Action | Owner | Success criteria |
|---|---|---|---|
| A1 | **Extract the shared replay-safe afterCommit side-effect pattern** (`REQUIRES_NEW` + advisory lock + swallow/log + idempotent re-invoke) from the 3f-3 / 3f-7 / 3f-8 hooks into one documented, tested helper. **Committed by Alex as an Epic 4 (recovery) deliverable**, not carried debt. | Winston / Charlie | A named module + test proof-points; new async hooks reuse it instead of re-deriving it. |
| A2 | **Make 3-layer adversarial review mandatory for the afterCommit/replay story class** (not a per-story choice). 3f only caught its bugs because 3f-3/4/7/8 got it. | Dana | create-story flags replay/afterCommit stories for mandatory bmad-code-review. |
| A3 | **Codify "a discovery query feeding transition() must mirror the transition's own gates"** as a review-checklist item (the 3f-8 archived-parent trap). | Dana | Checklist line exists; reviewers check every sweep/scan query against the gate it feeds. |

### Technical Debt

| ID | Item | Disposition |
|---|---|---|
| B1 | Shared idempotent / replay-safe afterCommit pattern | **Escalated from "carry" to a committed Epic 4 deliverable** (A1). Was carried debt in the 3d and 3e retros; 3f proved the cost of leaving it implicit. |
| TD5 | 3f-8 `interval-ms` clamp is dead code (scheduler reads the raw property; fail-fast at startup on opt-in misconfig accepted) | Accepted by-design; noted so it is not later "fixed" as a phantom bug. |
| TD1–TD4 (from 3e) | clarification text bounds, prompt-injection posture, ack-fence dedup semantics, project-config concurrency | Still open; none block Epic 3g. |

### Epic 3g Preparation

| ID | Action | Owner |
|---|---|---|
| P1 | Update the summary exact-field contract test guard in the same commit that adds `ticketTitle` (avoid the silent CI-only break). | Dana |
| P2 | Mirror the additive runner-contract `usage` block into both `runner.mjs` entrypoints + both offline mocks byte-identically. | Charlie |
| P3 | Enforce snapshot-at-creation immutability for 3g-1 title/URL (no live resolution). | Winston |

### Team Agreements

- **The shared replay-safe pattern is no longer optional** — it is Epic 4 work, because the per-story re-derivation cost is now measured, not theoretical.
- **Adversarial review is the default for async/replay/afterCommit paths**, not a special case.
- **Reuse remains the default** — 3f-7's zero-new-machinery rollup is the model: extend an existing seam before adding a new one.

---

## 9. Key Takeaways

1. **The epic succeeded and the delivery was orderly** — 8/8 stories, dependency-respecting sequencing, scope held, NFR16 reconciliation done correctly.
2. **The rollup is the architectural high point** — recursion and cross-split dependency release emerged from the *unchanged* 3f-3 resolver; the seam was genuinely reusable.
3. **The pain was real and concentrated** — high effort and "a lot of bugs," almost all in the replay / transaction-boundary / afterCommit-ordering family.
4. **The root cause is a still-missing shared pattern (B1)** — 3f is the loudest evidence that deferring it has a running cost; it is now a committed Epic 4 deliverable.
5. **A durability gap was caught by review, not production** — and the safety-net sweep is now enabled for the pilot.
6. **Epic 3g is a deliberate breather** — additive read-model only; carry the exact-field, mock-parity, and snapshot-immutability disciplines forward.

---

## 10. Next Steps

1. Review this retrospective.
2. `epic-3f-retrospective` marked `done` in sprint status.
3. **Rollup-sweep enabled** in `deliveryline-backend/src/main/resources/application.yml` (`rollup-sweep.enabled: true`) — verify it starts cleanly in the pilot deploy.
4. Begin Epic 3g (3g-1 provenance backend + 3g-3 token backend are independent; may run in parallel), honoring P1–P3.
5. Carry **B1 / A1** into Epic 4 recovery planning as the named "extract the shared replay-safe afterCommit pattern" deliverable.

---

Amelia (Developer): "Epic 3f is reviewed. We broke the 1:1 invariant cleanly, the rollup is elegant, and we paid — visibly — for the one pattern we keep deferring. The good news: 3g is a breather, and B1 finally has a home in Epic 4."
