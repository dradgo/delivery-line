# Sprint Change Proposal — 2026-05-19 (Follow-up)

**Project:** DeliveryLine
**Triggering Epic:** Epic 2 (pre-Story 2.1 governance)
**Trigger Source:** Logical inconsistency discovered in the original
                    `sprint-change-proposal-2026-05-19.md` framing of action A4
**Parent Proposal:** `sprint-change-proposal-2026-05-19.md` (approved 2026-05-19)
**Author:** Alex (Project Lead) + Amelia (Developer, facilitating)
**Status:** Approved (2026-05-19 — same-day follow-up)
**Mode:** Batch (presented as a single set of 6 edits)
**Scope Classification:** **Minor** — direct artifact-language edits; no epic add/remove/renumber, no story renumber, no PRD/architecture/UX impact

---

## 1. Issue Summary

The original sprint-change proposal (`sprint-change-proposal-2026-05-19.md`,
approved earlier today) codified Epic 1 retro action **A4 — Frontend-on-Windows
tooling spike** as a **hard prerequisite** for Story 2.1:

> "Story 2.1 cannot start until the frontend-on-Windows tooling spike (retro
> action A4) is documented in `docs/spikes/2026-05-frontend-on-windows.md`."
> — `epics.md:870` (pre-edit)

The spike's Q1 — the first and most foundational of its 5 questions — reads:

> "Run `mvn -pl deliveryline-frontend clean install` on a fresh Windows runner.
> Verify Node binary is downloaded to module-local cache..."
> — `docs/spikes/2026-05-frontend-on-windows.md:34` (pre-edit)

**Core problem:**

- Q1 cannot be executed until `deliveryline-frontend/pom.xml` exists with a
  configured `frontend-maven-plugin`
- That `pom.xml` is what **Story 2.1 creates** (AC2 of Story 2.1: "the
  `frontend-maven-plugin` (or equivalent) is configured to install Node 20.19+
  or 22.12+ locally, run `npm ci` and `npm run build`...")
- Therefore A4 (as a hard prerequisite for 2.1) and Story 2.1 (as the producer
  of A4's primary input) form a **circular dependency** — A4 cannot complete
  before 2.1, and 2.1 cannot start before A4. The work cannot start.

**Why this wasn't caught in the original proposal:**

- The retro and original proposal treated the spike as discovering "tooling
  fitness" in the abstract — as though a Windows runner could observe Node
  bundling behavior without a specific `pom.xml` to drive it
- In practice, `frontend-maven-plugin`'s Node-bundling behavior is configuration-
  specific (Node version, mirrors, cache location, lifecycle phase binding) —
  so the spike's findings only become actionable against a real configuration

**Evidence:**

- `docs/spikes/2026-05-frontend-on-windows.md:30-37` (Q1 definition — requires
  `mvn -pl deliveryline-frontend clean install`)
- `_bmad-output/planning-artifacts/epics.md:860` (Story 2.1 AC2 — creates the
  `frontend-maven-plugin` configuration the spike needs)
- `_bmad-output/planning-artifacts/epics.md:870` (the hard prerequisite the
  original proposal codified — being downgraded by this follow-up)

**Issue category:** Misunderstanding of original requirements — the spike's Q1
dependency on 2.1 outputs was not surfaced during the parent proposal's review.

---

## 2. Impact Analysis

### 2.1 Epic Impact

- **Epic 1:** No story changes. Retrospective document gets a language update
  (A4 deadline + critical-path bullet) to stay self-consistent with the
  downgrade. Epic 1 stays closed.
- **Epic 2:** No story renumbering, no story added/removed. Story 2.1 is
  unblocked to start; its ACs unchanged in substance (AC8 wording adjusted
  to remove the now-impossible "pre-story spike" attribution).
- **Epic 3 / 4 / 5 / 6:** No impact.

### 2.2 Story Impact

| Story | Change | Type |
|---|---|---|
| 2.1 | AC8 wording — drop "verified as part of the pre-story spike" tie; replace per-story Prerequisite block with Spike parallelism note | Language clarification only — no AC behavior change |
| (others) | No change | — |

### 2.3 Artifact Conflicts

| Artifact | Conflict | Action |
|---|---|---|
| **PRD** | No conflict | — |
| **Architecture doc** | No conflict | — |
| **UX spec** | No conflict | — |
| **Epics doc** | Lines 846, 866, 870 contain the hard-prerequisite framing | Edit per Section 4 below |
| **Spike doc** | Header status "Prerequisite for: Story 2.1" + Q1 framing | Edit per Section 4 below |
| **Epic 1 retro** | Lines 187, 217, 252 contain "Before story 2-1" + critical-path framing | Edit per Section 4 below |
| **sprint-status.yaml** | No conflict | — (Story 2.1 stays `backlog`; spike isn't tracked as a story) |
| **CI workflows** | No conflict | — (`dependency-edges` CI check is unaffected — it gates 2.15/2.17/2.18 on 2.24, not 2.1 on the spike) |

### 2.4 Technical Impact

None. No code, infrastructure, or test changes. Purely planning-artifact
language alignment.

---

## 3. Recommended Approach

**Selected: Option 1 — Direct Adjustment.**

- **Effort:** Low (6 artifact edits, ~30 min)
- **Risk:** Low (no code, no scope change, no story renumber)
- **Timeline impact:** Positive — Story 2.1 unblocked immediately

Options 2 (rollback) and 3 (MVP review) evaluated and rejected as N/A:
- Option 2: No completed work to roll back — Epic 2 hasn't started
- Option 3: MVP scope is unchanged; this is governance language alignment

**Rationale for downgrading A4 from hard prerequisite to parallel:**

1. **Q1 logically cannot run before 2.1 starts** (chicken-and-egg, documented above)
2. **Q2/Q3/Q4/Q5 can run any time**, but their findings are best applied against
   a real in-flight scaffold rather than a synthetic test harness
3. **Risk-discovery value is preserved** — the spike still runs, still publishes
   a report, still informs `.gitattributes` / port config / path-length mitigations
4. **Story 2.1 already has its own Windows enforcement** via AC7 (CI matrix
   with `windows-latest` runner) — failing Windows CI on 2.1 is build-blocking
   regardless of whether the spike found the issue first
5. **If a spike blocker surfaces that 2.1 cannot absorb**, the existing escape
   hatches still apply: (a) descope Windows from 2.1 with a fresh sprint-change,
   (b) spawn a follow-up story to address the blocker. These are unchanged from
   the original spike charter

---

## 4. Detailed Change Proposals

Six edits across four files. All edits are language-only — no AC behavior
changes, no scope additions, no story renumbers.

### Edit 1 — `_bmad-output/planning-artifacts/epics.md` (Epic 2 critical-path edges, ~line 846)

**OLD:**
> - **Frontend-on-Windows tooling spike** (action A4 from Epic 1 retro) must complete before **2.1** starts. See `docs/spikes/2026-05-frontend-on-windows.md`.

**NEW:**
> - **Frontend-on-Windows tooling spike** (action A4 from Epic 1 retro) runs **in parallel with** Story 2.1 — not as a prerequisite. Downgraded from hard prerequisite per `sprint-change-proposal-2026-05-19-followup.md` (2026-05-19): spike Q1 (`mvn -pl deliveryline-frontend clean install` on Windows) requires the frontend module that Story 2.1 creates — chicken-and-egg. Q2/Q3/Q4/Q5 run against the in-flight 2.1 branch and findings fold into AC9 mid-flight (or a follow-up story if blockers surface). See `docs/spikes/2026-05-frontend-on-windows.md`.

**Rationale:** Removes circular dependency. Preserves spike value as parallel
risk-discovery.

### Edit 2 — `_bmad-output/planning-artifacts/epics.md` (Story 2.1 AC8, ~line 866)

**OLD:**
> 8. **Given** a development workflow, **Then** `npm run dev` inside the frontend module starts Vite's dev server on a documented port (default 5173, configurable via PORT env per AC9c), proxying `/api/*` requests to the Spring Boot backend on `localhost:8080` — configured in `vite.config.ts`. The proxy config works identically on Windows PowerShell, Windows Git Bash, Ubuntu, and macOS — verified manually as part of the pre-story spike (A4).

**NEW:**
> 8. **Given** a development workflow, **Then** `npm run dev` inside the frontend module starts Vite's dev server on a documented port (default 5173, configurable via PORT env per AC9c), proxying `/api/*` requests to the Spring Boot backend on `localhost:8080` — configured in `vite.config.ts`. The proxy config works identically on Windows PowerShell, Windows Git Bash, Ubuntu, and macOS — verified manually during Story 2.1 implementation (informed by spike Q4 findings from `docs/spikes/2026-05-frontend-on-windows.md` if available; spike now runs in parallel per `sprint-change-proposal-2026-05-19-followup.md`, not pre-story).

**Rationale:** Removes "pre-story spike" attribution that's now logically false.
Verification responsibility stays inside Story 2.1 (where it always actually was).

### Edit 3 — `_bmad-output/planning-artifacts/epics.md` (Story 2.1 Prerequisite block, ~line 870)

**OLD:**
> **Prerequisite:** Story 2.1 cannot start until the frontend-on-Windows tooling spike (retro action A4) is documented in `docs/spikes/2026-05-frontend-on-windows.md`. The spike's outputs (line-ending strategy, path-length notes, port config) inform AC7/AC8/AC9 directly.

**NEW:**
> **Spike parallelism (revised per `sprint-change-proposal-2026-05-19-followup.md`):** The frontend-on-Windows tooling spike (`docs/spikes/2026-05-frontend-on-windows.md`, retro action A4) runs **in parallel** with Story 2.1 — no longer a hard prerequisite. Rationale: spike Q1 (`mvn -pl deliveryline-frontend clean install`) requires the very frontend module Story 2.1 creates, making the original prerequisite chicken-and-egg. Spike Q2 (`.gitattributes` / line endings), Q3 (path length), Q4 (Vite dev-server / proxy), and Q5 (HMR file-locking) run against the in-flight 2.1 branch; findings fold into AC9 mid-flight. If a spike blocker surfaces that cannot be absorbed into 2.1's scope, it spawns a follow-up story rather than blocking 2.1's start.

**Rationale:** Replaces the hard gate with a clear parallel-work contract,
including a documented escape hatch for blocker scenarios.

### Edit 4 — `docs/spikes/2026-05-frontend-on-windows.md` (header)

**OLD:**
> **Owner:** Dana (QA) + Elena (Junior Dev)
> **Status:** Not started
> **Prerequisite for:** Story 2.1 (Frontend Module Scaffolding)
> **Triggered by:** Epic 1 retrospective (2026-05-19), action A4;
>                   sprint-change proposal 2026-05-19
> **Output:** Findings + recommended configuration; consumed by 2.1 AC7/AC8/AC9

**NEW:**
> **Owner:** Dana (QA) + Elena (Junior Dev)
> **Status:** Running in parallel with Story 2.1
> **Relationship to Story 2.1:** Parallel risk-discovery (not a prerequisite — downgraded
>                   per `sprint-change-proposal-2026-05-19-followup.md`)
> **Triggered by:** Epic 1 retrospective (2026-05-19), action A4;
>                   sprint-change proposal 2026-05-19;
>                   follow-up sprint-change proposal 2026-05-19 (downgrade rationale)
> **Output:** Findings + recommended configuration; folded into 2.1 AC9 mid-flight,
>                   or spawns a follow-up story if a blocker surfaces that 2.1 cannot absorb

**Rationale:** Header reflects new operational status; preserves traceability
to both parent proposal and this follow-up.

### Edit 5 — `docs/spikes/2026-05-frontend-on-windows.md` (Q1 sequencing note)

**Add at top of "Q1 — frontend-maven-plugin Node bundling on Windows" section:**

> > **Sequencing note (per follow-up sprint-change):** Q1 requires the frontend module
> > Story 2.1 creates — there is no `deliveryline-frontend/pom.xml` to invoke until 2.1
> > lands its scaffold. Q1 therefore runs **against the in-flight 2.1 branch** (not before
> > 2.1 starts). Q2–Q5 below have no such dependency and can run any time. If Q1 surfaces
> > a blocker (e.g., `frontend-maven-plugin` doesn't work on Windows), the resolution
> > options stay the same as the original spike charter: absorb into 2.1, descope Windows
> > from 2.1 with formal sprint-change, or replace the offending tool.

**Also adjust Q1 first bullet:**

- OLD: "Run `mvn -pl deliveryline-frontend clean install` on a fresh Windows runner"
- NEW: "Run `mvn -pl deliveryline-frontend clean install` on a fresh Windows runner
  (against the Story 2.1 branch once scaffolded)"

**Rationale:** Makes the Q1-vs-2.1 sequencing explicit inside the spike doc itself,
so a reader who lands on the spike without seeing the proposal still understands
when Q1 runs.

### Edit 6 — `_bmad-output/implementation-artifacts/epic-1-retro-2026-05-19.md` (three locations)

**6a — A4 row in Technical Setup table (line 187):**

- OLD deadline cell: `**Before story 2-1**`
- NEW deadline cell: `**In parallel with story 2-1** (downgraded from "before 2-1" per `sprint-change-proposal-2026-05-19-followup.md` — Q1 needs the 2.1 scaffold to exist)`
- OLD success-criteria cell: `Spike report committed under docs/spikes/; blockers (if any) folded into 2-1 ACs`
- NEW success-criteria cell: `Spike report committed under docs/spikes/; blockers (if any) folded into 2-1 ACs mid-flight or spawn a follow-up story`

**6b — Section 9 "Critical Path Before Epic 2 Story 2-1" (line 214–218):**

- OLD bullet 2: `**A4: Frontend-on-Windows tooling spike** — Owner: Dana + Elena`
- NEW bullet 2: `~~**A4: Frontend-on-Windows tooling spike**~~ — **Moved to parallel** per `sprint-change-proposal-2026-05-19-followup.md` (Q1 chicken-and-egg with 2.1 scaffold). No longer a critical-path blocker; runs alongside 2.1.`

**6c — Section 12 "Next Steps" (line 250–253):**

- OLD: "Execute the 3 critical-path items before starting Epic 2 story 2-1" → 3-item bullet list
- NEW: "Execute the critical-path items before / alongside Epic 2 story 2-1" → 3-item list with explicit "before" / "in parallel with" / "in 2-1 itself" tags

**Rationale:** Retro stays self-consistent with the downgrade. Strikethrough on
critical-path bullet preserves history (reader can still see the original framing
was reconsidered) without misleading future readers.

---

## 5. Implementation Handoff

**Scope:** Minor → direct implementation by Developer agent (Amelia)
in the current session.

**Deliverables (all completed in this session):**

- 6 edits applied per Section 4
- This follow-up proposal written to
  `_bmad-output/planning-artifacts/sprint-change-proposal-2026-05-19-followup.md`
- No `sprint-status.yaml` update needed (Story 2.1 stays `backlog`; spike isn't
  a tracked story)

**Success criteria for the downgrade:**

- [x] `epics.md` no longer states Story 2.1 has a hard prerequisite on the spike
- [x] Spike doc clearly marked as "parallel" status with sequencing note on Q1
- [x] Epic 1 retro consistent with the downgrade (no contradiction between
      "before 2-1" in one section and "in parallel with 2-1" in another)
- [x] Both the parent proposal and this follow-up are reachable from every edited
      location (forward links + backward references)

**Next action after this proposal lands:**

- Run `bmad-create-story 2.1` in a fresh context window to produce the
  story spec
- The spike runs in parallel as a separate work-stream owned by Dana + Elena
  (no longer gating 2.1 kickoff)

---

## 6. Traceability

- **Parent proposal:** `sprint-change-proposal-2026-05-19.md`
- **Retro driving A4:** `_bmad-output/implementation-artifacts/epic-1-retro-2026-05-19.md`
- **Spike charter:** `docs/spikes/2026-05-frontend-on-windows.md`
- **Affected story:** `epics.md` § Story 2.1 (Frontend Module Scaffolding)
- **Approval:** Alex (Project Lead), 2026-05-19, batch-approved all 6 edits

---

*Follow-up sprint-change proposal complete. Story 2.1 unblocked. Spike continues
as parallel risk-discovery.*
