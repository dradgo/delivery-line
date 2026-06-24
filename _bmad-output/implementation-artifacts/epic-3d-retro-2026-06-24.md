# Epic 3d Retrospective — Per-Step Execution Control, Observability & Manual Execution

**Date:** 2026-06-24
**Facilitator:** Amelia (Developer)
**Project Lead:** Alex
**Epic Status:** Complete-as-scoped (10/10 stories `done`)
**Retrospective Type:** Fifth retrospective (carries Epic 3c action items A1–A3 / P1–P3 / TD1–TD3)

---

## 1. Epic Summary

| Dimension | Result |
|---|---|
| Scope | Per-project **reviewer model** (advisory 2nd-LLM verdict); `manual` runner kind + `WaitingForManualExecution`; live + historical step-log streaming (SSE); read-only **diagnostic console** (ADR-0025, security-gated); post-run **provider usage/limit** status (spike-gated); **soft hide/archive** of obsolete runs (ADR-0027, archive-not-delete) |
| Stories shipped | **10/10 done** — reviewer (3d-1/2), manual exec (3d-3/4), observability (3d-5/6/7), lifecycle (3d-8), cross-cutting (3d-9/10) |
| Critical defects shipped | **0** (sixth consecutive epic) |
| Foundation gate | Widened to **26 named contracts** (#21–#26 delegate-run Epic 3d tests, zero re-authoring) |
| Security gate | **Held** — ADR-0025 read-only console `Proposed → Accepted` (Alex, 2026-06-22); input-disabled pty = *provable* non-mutation (no stdin path end-to-end), stronger than policy-only |
| Spike gate | **Held honestly** — neither Claude CLI / Anthropic API nor Codex expose 5h/weekly windows headless → shipped documented `not_exposed`, never a fabricated value |
| New domain error codes | 3, all auto-covered by `ProblemDetailsCoverageFoundationContract` (#7) |
| Parity | **Byte-identical** to pre-3d when no reviewer binding + non-manual kind (both features strictly opt-in) |
| Review intensity | 3d-2 (3 patches, one survived 2 prior rounds), 3d-3 (9), 3d-5 (7), **3d-7 (11)** — heavy but converged to 0 criticals |

**Goal achieved:** A single local operator now controls **how each step is executed, reviewed, observed, and retired** — a second LLM can advise (never gate) at the review bar; a step can be run by hand through a governed `manual` path when headless auth is unavailable; container logs stream live and replay after; a read-only console attaches to a live runner under signed-off threat model; provider limit status is reported honestly (or honestly absent); obsolete runs hide without erasing audit history. Built on the Epic 3c `Project` aggregate, per-project credentials, and `ProjectConnectorResolver`.

**Headline (Alex's #1):** The epic delivered an **extensible per-project execution-control structure** — reviewer kind, runner kind, observability, and lifecycle are now per-project data the operator can extend, not hard-coded global posture. The foundation built to be *built on*.

---

## 2. Team Participants

- **Amelia (Developer)** — facilitator
- **Alice (Product Owner)** — opt-in posture, advisory-not-gating decision
- **Charlie (Senior Dev)** — manual-dispatch chokepoint, SSE/console seam reuse, broker ingest split
- **Dana (QA Engineer)** — security/spike gate enforcement, redaction fixtures, foundation-gate widening
- **Winston (Architect)** — Epic 3c → 3d → 3e seam continuity
- **Alex (Project Lead)** — convener

---

## 3. Successes & Strengths

### 3.1 An extensible execution-control structure — *Alex's headline*
Reviewer model, `manual` runner kind, log/console observability, and soft-hide lifecycle all landed as **per-project, per-stage-capable data** behind the 3c `Project` aggregate. The operator can now extend each axis (a new reviewer binding, a new runner mapping) without touching global config — exactly the "build a structure I can extend" outcome. 3e-4 already consumes this to add per-step runner mapping next epic.

### 3.2 Both honest gates held — *P1 + P2 from the 3c retro, delivered*
- **Security gate (P1):** 3d-6 did not close until ADR-0025 moved `Proposed → Accepted` with a recorded sign-off. The input-disabled design is *stronger than the baseline asked for* — there is no stdin path end-to-end, so non-mutation is provable, not policy-promised.
- **Spike gate (P1) + honesty:** 3d-7 confirmed real providers don't expose 5h/weekly windows headless and shipped the documented `not_exposed` state. It could have fabricated a plausible number; it refused to. That restraint is the win.
- **Mock-vs-real bar (P2):** 3d-2 / 3d-5 / 3d-7 each tested both the offline-mock full surface and the real-provider graceful-degrade path.

### 3.3 Seam-and-reuse keeps compounding — *fifth straight epic*
3d-6's read-only console reused 3d-5's `LineBuffer` (byte-buffered UTF-8 at frame boundaries), bounded executor, and `SubscriptionGate` near-identically — a second streaming surface for a fraction of the first one's cost. 3d-8 needed **no migration at all** because `archived_at` already existed on `workflow_runs` since V1. The architectural foresight tax keeps paying back.

### 3.4 Review depth converged instead of thrashing
3d-7 took 11 review patches and 3d-3 took 9 — but every one converged to 0 criticals, and the heaviest catches were real (capture-after-secret-scan ordering; numeric coercion `providerUsageInt → Double`; `usedFraction` finite/range clamp). 3d-2's "shared-tx degrade marker" patch survived **two prior review rounds** before being caught — evidence the adversarial review layers are doing work the earlier passes missed.

### 3.5 Manual execution rejoins the real pipeline — no validation bypass
A by-hand artifact re-enters the **same** runner-contracts output validation as an automated runner (3d-4), via a deliberately *separate* `ingestManualResult` path that skips workspace/`captureAndPush` steps a park never created — rather than a fragile refactor of `handleSuccess`. Governed, idempotent, resubmittable on invalid.

---

## 4. Challenges & Growth Areas

### 4.1 The capture container failed its own fix — *A1, now two retros running* 🚨
The 3c retro's headline corrective (A1: structural `MEMORY.md` fix, one-line index, detail in committed docs) **did not hold**. `MEMORY.md` went **31.9KB at end of 3c → 37.5KB now**, and this session loaded it only *partially* — meaning the guardrails (predicted-flake warnings, trap notes) silently don't fire. This is the *exact* failure mode the 3c retro named, made worse: a manual per-epic trim is a tax we forget, and a half-loaded index is an invisible regression. **This retro converts A1 from an aspiration into an executed structural change (see §8).**

### 4.2 Off-request-thread idempotency is accumulating across stories
The pattern "`complete()` runs in a separate transaction after the state commit; if it throws it marks the row `FAILED`, causing a stale re-read on same-key retry" was deferred as "pre-existing, low-impact single-operator" in **3d-3, 3d-6, AND 3d-8**. Three stories is no longer one-off. Per Alex's call (Q3), this gets a **named entry** (B1) rather than a fourth silent per-story defer.

### 4.3 Plan artifacts still go stale on Flyway head — *A3 recurred*
3d-1's epic said "V18+"; real head was V19. 3d-7 drifted V20→V23 and shipped V24. A3 ("stamp the real head at story creation") was the corrective and it still bit — because the *proposal's* number is what gets copied. The fix needs teeth: a grep-the-real-head step in `create-story`, not a reminder.

### 4.4 `projectId` on run-read DTOs deferred a third time — *P3 unmet*
P3 (add `projectId` to run-read DTOs *early in 3d* to unblock TD3 for 3d-5 and 3d-8 at once) was not done. Both stories worked around it run-scoped. It is now formally scheduled as **3f-6** (completes 3c-9 AC6). Accepted as landing in 3f — but it's the second epic this specific DTO gap has been punted.

---

## 5. Previous Retrospective Follow-Through (Epic 3c → Epic 3d)

| 3c commitment | Status | Evidence |
|---|---|---|
| **P1** Hold security/spike gates (3d-6, 3d-7) | ✅ **Held** | ADR-0025 signed off; spike ran, shipped honest `not_exposed` |
| **P2** Mock-vs-real bar (3d-2, 3d-5) | ✅ **Held** | Both mock + real paths tested across 3d-2/5/7 |
| **A2** Size fan-out width in planning | ⚠️ Partial | 3d-3 reconciled 6 `new Project(...)` sites — but width still discovered at build, not planned up front |
| **A3** Stamp real Flyway head at story creation | ❌ **Recurred** | 3d-1 V18→V19, 3d-7 V20→V23→V24 drift |
| **P3** Add `projectId` to run-read DTOs early in 3d | ❌ **Not done** | Deferred again → now scheduled as 3f-6 (TD3 still open) |
| **A1** Structural `MEMORY.md` fix (<24.4KB, one-line index) | ❌→🔧 **Regressed, fixed this retro** | 31.9KB → 37.5KB partial-load; structural trim executed in this retro (§8 A1) |
| **TD1** Ticket-source identity routing | ⏳ Carried | Still open; not touched in 3d |

**The honest signal:** The *discretionary, gate-shaped* commitments held cleanly (P1/P2 — they had a hard "doesn't close until signed" mechanism). The *housekeeping* commitments that relied on remembering (A1 memory trim, A3 Flyway head, P3 DTO field) all slipped — because they had no enforcing seam. The lesson repeats from Epic 3: **a commitment without a gate is a wish.** A1 and A3 need mechanisms, not intentions.

---

## 6. Significant Discovery — Epic 3e Plan Impact

**No epic update required — and 3d actively de-risked 3e.** Epic 3e (Clarification Loop Activation) activates the dormant front-half of the Epic-2 PM loop. Three of its stories consume seams 3d *just built*:

- **3e-3** (spec-phase advisory reviewer) extends 3d-2's reviewer execution to the `WaitingForSpecApproval` gate — same `step_reviews` table, same `GET /reviewer-verdict`, stage-agnostic reuse.
- **3e-4** (per-step runner mapping) *resolves* 3d-3's deferred Open Decision #1 (per-stage-per-project granularity) and builds the runner-kind UI 3d-3 left unbuilt.
- **3e-5** (spec-stage observability) extends 3d-5/3d-6's log viewer + read-only console to the `Investigating` stage — endpoints already stage-agnostic, no schema change.
- **3e-1** (clarification `questions[]` channel) explicitly cites 3d-7's `providerUsage` **additive-optional field, no `schemaVersion` bump** as its contract pattern.

**Latent item to carry, not block:** manual pr-output submission (3d-4 OQ-2) ships as format-validate-and-ingest *without* `captureAndPush`/git-linkage validation. If any 3e story routes a pr-output through the manual path, this gap surfaces. Flagged, not blocking.

---

## 7. Readiness Assessment — Epic 3d

| Dimension | Status | Notes |
|---|---|---|
| Stories complete | ✅ 10/10 `done` | 3d-10 committed `b9f289a` (2026-06-24) |
| Foundation gate | ✅ Green | Widened to 26 contracts (#21–#26) |
| Testing & quality | ✅ Strong | 0 criticals (6th straight); coverage floors held (80% / 90% secret-adjacent) |
| Security | ✅ Signed | ADR-0025 read-only console review accepted (Alex, 2026-06-22) |
| Spike honesty | ✅ Clean | `not_exposed` shipped over fabrication |
| Parity | ✅ Proven | Byte-identical with no reviewer binding + non-manual kind |
| Deployment / stakeholder | N/A | Solo local-first pilot; no external gate |
| Standing weakness | ⚠️→🔧 Capture container | `MEMORY.md` overflow — structurally fixed this retro (A1) |

**Verdict (confirmed by Alex):** Epic 3d is complete-as-scoped and complete against the foundation gate. The carried debt (off-request idempotency B1, manual pr-output OQ-2, `projectId` DTO P3/TD3) is recorded as explicit Epic-3e/4/5 input rather than buried. Epic is `done`.

---

## 8. Action Items

### Process / Capture
| ID | Action | Owner | Mechanism (so it can't silently slip) |
|---|---|---|---|
| **A1** | **Structural `MEMORY.md` fix — EXECUTED this retro:** hard-trim the index to one-line (<200 char) pointer entries; reconciliation detail lives only in per-story files + `docs/` topic files. | Alex | Done in this retro; future entries must be one-liners at write time |
| **A2** | Size fan-out width in planning — a story adding a port method / record component lists impl + test-fake + construction sites up front (grep-first). | Team | Fold into `create-story` checklist |
| **A3** | **Give A3 teeth:** `create-story` greps the real Flyway head on disk and stamps it in the story file — do not copy the proposal's number. | Team | `create-story` step, not a reminder |

### Technical Debt — named, not silently deferred
| ID | Item | Disposition |
|---|---|---|
| **B1** | Off-request-thread `complete()` idempotency (3d-3/3d-6/3d-8) | Decide consciously in Epic 4 (recovery): accept-as-MVP with a written rationale, or fix with a single shared idempotent-complete seam. No fourth silent defer. |
| **TD1** | Ticket-source identity routing (carried from 3c) | Still open; Epic 3e/3f will press on it |
| **TD3 / P3** | `projectId` on run-read DTOs + queue attribution | Scheduled as **3f-6** (completes 3c-9 AC6) |
| Epic-5 | True purge/retention; row-growth dedup on duplicate `onResult` (3d-7); orphan parked-row reclamation (3d-3) | Owned by Epic 5 |
| OQ-2 | Manual pr-output full git-validation (3d-4) | Carry; surfaces only if a 3e manual path routes pr-output |

### Epic 3e Preparation
| ID | Action | Owner |
|---|---|---|
| P1 | 3e is well-set by 3d seams (3e-3/4/5 reuse 3d-2/3d-3/3d-5/6). Verify those seams stage-agnostic before 3e-3/3e-5 build. | Winston |
| P2 | 3e-1 follows 3d-7's additive-optional-field, no-`schemaVersion`-bump pattern for `questions[]`. | Charlie |
| P3 | Keep the honest-gate discipline: 3e has no security/spike gate, but the mock-vs-real bar applies to 3e-1's runner emission (both runners byte-identical). | Dana |

### Team Agreements
- **A commitment without a gate is a wish.** P1/P2 held because they had a "doesn't close until signed" mechanism; A1/A3/P3 slipped because they relied on memory. Every housekeeping action item now gets an enforcing seam or it doesn't get written.
- **Honest absence beats fabricated presence.** 3d-7 `not_exposed` is the standard — never invent a value to fill a surface.
- **Name the recurring defer.** A pattern deferred in 2+ stories (B1) becomes a tracked decision, not a per-story footnote.

---

## 9. Key Takeaways

1. **Per-step execution control is real and extensible.** Reviewer / runner-kind / observability / lifecycle are now per-project data the operator extends — the structure built to be built on (3e-4 already extends it).
2. **The honest gates held.** Security sign-off (3d-6) and the spike's `not_exposed` honesty (3d-7) are the epic's integrity proof — P1/P2 from the 3c retro delivered exactly because they had hard mechanisms.
3. **A commitment without a gate is a wish.** A1 (memory), A3 (Flyway head), P3 (DTO field) all slipped for lack of an enforcing seam — so this retro gives A1 an executed fix and A3 a `create-story` step.
4. **Seam-reuse is still the engine.** 3d-6 rode 3d-5's streaming substrate; 3d-8 needed no migration. Fifth straight epic of foresight paying forward.
5. **Name the recurring defer.** Off-request idempotency (B1) hit three stories — it's now an Epic-4 decision, not a silent footnote.
6. **3d de-risked 3e.** No epic update needed; 3d delivered the exact seams 3e-3/3e-4/3e-5 consume.

---

## 10. Next Steps

1. Review this retrospective.
2. **A1 executed** — `MEMORY.md` structurally trimmed in this retro; keep new entries one-line at write time.
3. Carry **B1** (off-request idempotency) into Epic 4 recovery planning as a conscious decision.
4. Confirm **3f-6** owns the `projectId`-on-DTOs gap (TD3/P3).
5. Begin Epic 3e (`bmad-create-story` → `3e-1` clarification creation seam — the never-built front-half that activates the reported symptom). Epic 3e flips to `in-progress` when its first story is created.

---

*Retrospective complete. Epic 3's job was to make one real run real; Epic 3c's job was to make many projects possible; Epic 3d's job was to make per-step control, observation, and honest manual execution real on top of them — done, with the gates that mattered actually held. Epic 3e's job: activate the clarification loop that was built but never wired.*
