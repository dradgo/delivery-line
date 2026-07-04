# Sprint Change Proposal — 2026-07-04

**Trigger:** Epic 3g retrospective (2026-07-04)
**Author:** Amelia (Developer) facilitating; Alex (Project Lead) approving
**Scope classification:** **Moderate** (backlog reorganization; no PRD/architecture-goal change)
**Related docs:** `epic-3g-retro-2026-07-04.md`, `epic-3f-retro-2026-07-01.md`, `epic-03h-pre-review-quality-gates.md`

---

## Section 1 — Issue Summary

The Epic 3g retrospective surfaced two facts that require backlog action before Epic 3h starts:

1. **Epic 3g's token-accounting half shipped hollow.** Stories 3g-3/3g-4 passed every acceptance criterion, but each AC exercised only *mock* token data (`buildUsage()` read solely `DELIVERYLINE_USAGE_MOCK_FILE`). In production, real codex usage was never captured (0/76 rows). A second, latent defect — a `REQUIRES_NEW` token side-write nulled by a stale-entity full-row UPDATE (`markCompleted`, no `@DynamicUpdate`) — would have destroyed real data even once captured. Both were found *after* epic close; remediation is partly landed on branch `feat/archive-unarchive-ui` (codex `--json` real capture committed `299c560`/`2e9523b`; the `@DynamicUpdate` clobber fix uncommitted) and partly still open (Claude-runner parity, JSONL-stdout log-viewer readability). This remediation is currently untracked branch work.

2. **The token-clobber is a B1-family bug on a "safe" epic.** It is a transaction-boundary / stale-entity / replay defect — the exact class the 3f retro named as the running cost of the still-un-extracted **B1** shared replay-safe afterCommit pattern. B1 was framed in the 3f retro (action A1) as "a committed Epic 4 deliverable," but Epic 4's 28-story list contains **no explicit B1 story** — it is only an implicit pointer. Epic 3h's three fix-loops (3h-1 build, 3h-2 lint, 3h-5 CI) will each re-derive that same async machinery, *before* Epic 4, unless B1 is extracted first.

**Evidence:** retro §4 (two-layer failure), retro §6 (3h dependency), sprint-status token-clobber/mock-only notes, `git log` (`299c560`, `2e9523b`), working-tree modifications to `RunnerExecutionEntity.java`, `RunnerExecutionService.java`, `ReviewResultHarvester.java`, `review-result.v1.schema.json`.

---

## Section 2 — Impact Analysis

**Epic Impact**
- **Epic 3g** — FR74 (per-step token accounting + rollup) scope is unchanged, but its *delivered value* is incomplete until real capture lands. Epic stays `in-progress` until a new completion story (3g-5) is `done`. FR73 (provenance) is genuinely complete.
- **Epic 3h** — plan shape unchanged, but it gains a hard **prerequisite** (B1 extraction) and a soft one (trust 3g's token seam against real output). 3h-1's "BUILD records zero tokens" assertion (AC6) rides on 3g's token accounting.
- **Epic 4** — no story removed (B1 was never an explicit Epic 4 story). Action A1 from the 3f retro is reassigned from "Epic 4" to "pre-3h."

**Story Impact**
- **New:** `3g-5` (token real-capture completion), `3h-0` (B1 shared replay-safe afterCommit helper — prerequisite enabler).
- **Unchanged:** 3h-1..3h-6 acceptance criteria; 3h-1/3h-2/3h-5 will *consume* the 3h-0 helper rather than re-derive it (an implementation-note addition, not an AC change).

**Artifact Conflicts**
- **PRD** — no change. FR74 scope is not reduced or redefined; 3g-5 completes existing scope.
- **Architecture** — B1 warrants a short ADR when extracted (helper module + reuse rule). ADR-0030 (governed delivery tail, authored with 3h-1) should reference the 3h-0 helper as the fix-loop substrate. No architecture *goal* changes.
- **UX** — none.
- **Process / DoD** — new convention (D1): capture/telemetry stories require a real-producer AC + an epic-close real-run smoke gate. Routes to the create-story checklist / definition-of-done.

**Technical Impact**
- 3g-5 lands the uncommitted clobber fix, adds Claude-runner real capture, improves JSONL log readability, and adds the missing real-run verification.
- 3h-0 extracts `REQUIRES_NEW` + advisory-lock + swallow/log + idempotent-reinvoke + no-clobber-save into one tested helper reused by 3f-3/3f-7/3f-8 (retrofit optional) and consumed by 3h-1/3h-2/3h-5.

---

## Section 3 — Recommended Approach

**Selected path: Direct Adjustment (Option 1) — add two stories, re-sequence one commitment.** No rollback (3g provenance is sound; token work is forward-fixable). No MVP/PRD scope change.

- **Effort:** Low–Medium. 3g-5 is largely landing + completing in-flight branch work. 3h-0 is a focused refactor over three existing, tested hook sites.
- **Risk:** Low. 3h-0 *reduces* risk (it is the fix for the exact bug family that has now bitten two consecutive epics). Doing it before 3h means its three fix-loops are built once, correctly.
- **Sequencing:** `3g-5` → `3h-0` → `3h-1 … 3h-6`. 3g-5 and 3h-0 are independent of each other and may run in parallel; both must be `done` before 3h-1.

**Why B1 as `3h-0` (a prerequisite enabler at the head of Epic 3h) rather than a new micro-epic or an Epic 4 story:** it directly gates 3h-1's fix-loop, so co-locating it as 3h's first item makes the dependency legible in the tracker while honoring "before 3h." (Alternative considered: a standalone tech-enabler epic — rejected as heavier ceremony for a single refactor.)

---

## Section 4 — Detailed Change Proposals

### 4.1 New story — `3g-5` (under Epic 3g)

```
Story: 3g-5  Token Real-Capture Completion (FR74 delivery closure)
Epic: 3g  Status: backlog

As the system, I want real agent token usage captured and legibly displayed for
every runner execution, so that FR74 delivers real data — not mock placeholders.

Acceptance Criteria (draft — finalized by create-story):
1. Claude-runner real token capture reaches parity with the committed codex `--json`
   path (real input/output/total from the agent's reported usage; best-effort/nullable).
2. The `@DynamicUpdate` (or equivalent re-read-before-save) fix lands so a REQUIRES_NEW
   token side-write is never clobbered by the ambient onResult full-row UPDATE; pinned
   by an IT that asserts real tokens survive the terminal transition.
3. Log-viewer readability is restored for JSONL-stdout runs (codex `--json` made
   runner.stdout JSONL) — human-readable step logs, no raw JSONL wall.
4. REAL-PRODUCER AC (D1): at least one test/verification drives a REAL agent execution
   (codex and claude) end-to-end and asserts non-null real token counts are persisted
   AND surfaced (per-step + run rollup) — not mock determinism.
5. Redaction/no-secret posture preserved; no schemaVersion bump beyond the additive
   review-arm `usage` already on review-result.v1.
6. Must be `done` before 3h-1 relies on the token seam (3h-1 AC6 no-token BUILD).
```
Rationale: formalizes the in-flight branch remediation with ACs, closes FR74's real delivery, and installs the D1 real-producer gate on the very story that needs it.

### 4.2 New story — `3h-0` (prerequisite enabler at head of Epic 3h)

```
Story: 3h-0  Shared Replay-Safe afterCommit Side-Effect Helper (B1 extraction)
Epic: 3h  Status: backlog  (prerequisite — must precede 3h-1)

As the platform, I want one documented, tested helper encapsulating the replay-safe
afterCommit side-effect pattern (REQUIRES_NEW + advisory lock + swallow/log +
idempotent re-invoke + no-clobber save), so that new async hooks reuse it instead of
re-deriving it — and the 3g token-clobber class cannot recur.

Acceptance Criteria (draft — finalized by create-story):
1. A named helper/module extracts the pattern proven in 3f-3 (dependency release),
   3f-7 (rollup recursion), 3f-8 (sweep): REQUIRES_NEW propagation, tx-scoped advisory
   lock, swallow-and-log, idempotent re-invoke, and no-clobber save (@DynamicUpdate /
   re-read) so a side-write is never nulled by an ambient full-row UPDATE.
2. Helper is unit- + IT-tested (real Postgres) with explicit proof-points for the
   clobber-avoidance and replay-idempotence properties.
3. At least one existing hook (3f-7 or 3f-8) is retrofitted onto the helper as a
   reference consumer (full 3f retrofit optional/forward).
4. A short ADR records the helper + the reuse rule; ADR-0030 (3h delivery tail) refs it
   as the substrate for the build/lint/CI fix-loops.
5. 3h-1/3h-2/3h-5 fix-loops are authored to CONSUME this helper (implementation-note in
   each; no AC change to those stories).
6. Foundation-gate discipline honored (no new state/action/event unless the helper needs
   one; drift-tested if so).
```
Rationale: reassigns 3f-retro action A1 from "Epic 4" to "pre-3h"; makes 3h's three fix-loops the helper's first consumers.

### 4.3 Epic 3h stories 3h-1 / 3h-2 / 3h-5 — implementation-note addition (no AC change)

```
ADD to each of 3h-1, 3h-2, 3h-5 (implementation notes, not ACs):
"The bounded fix-loop (build / lint / CI) MUST consume the 3h-0 shared replay-safe
afterCommit helper — do NOT re-derive REQUIRES_NEW + advisory-lock + swallow/log +
idempotent re-invoke inline."
```

### 4.4 Process change (D1) — create-story checklist / Definition of Done

```
ADD to the story-creation checklist and epic Definition of Done:
- Any capture / telemetry / metrics story MUST include at least one acceptance criterion
  that exercises the REAL producer end-to-end (real agent output → real value persisted
  and surfaced), not only mock determinism.
- An epic that adds runtime capture is not `done` until one REAL run demonstrates the
  captured value (epic-close real-run smoke gate).
```
Rationale: the 3g root cause was ACs testing the pipe, not the water. (Already recorded as retro action items A1/A2; this formalizes them into the process artifact.)

---

## Section 5 — Implementation Handoff

**Scope: Moderate → Product Owner / Developer.**

| Change | Owner | Action |
|---|---|---|
| Add `3g-5` + `3h-0` to `sprint-status.yaml` (backlog); note 3h-0 as 3h prerequisite | PO/DEV | on approval (this workflow updates the tracker) |
| Author `3g-5` story file | create-story (Dev) | next |
| Author `3h-0` story file | create-story (Dev) | next (parallel with 3g-5) |
| Fold consume-3h-0 note into 3h-1/3h-2/3h-5 | create-story (Dev) | when those stories are drafted |
| Add D1 gate to create-story checklist / DoD | PO/DEV | with 3g-5 drafting |

**Success criteria:** `3g-5` and `3h-0` both `done` (with their real-run / clobber-avoidance proof-points) before `3h-1` begins; Epic 3g flips to `done` when 3g-5 lands; ADR for the 3h-0 helper authored.

**Sequencing:** `3g-5 ∥ 3h-0` → both `done` → `3h-1 → 3h-2 → 3h-3 → 3h-4 → 3h-5 → 3h-6`.
