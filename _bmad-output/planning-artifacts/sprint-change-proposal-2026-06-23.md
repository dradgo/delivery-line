# Sprint Change Proposal — Add Epic 3e: Clarification Loop Activation

**Date:** 2026-06-23
**Author:** Alex (via Correct Course workflow)
**Mode:** Batch
**Scope classification:** Moderate (backlog reorganization — new 2-story epic; no rollback, no MVP change)
**Status:** Approved — artifacts updated

---

## Section 1 — Issue Summary

**Problem statement:** No workflow run ever displays a product **clarification question**, even when the spec runner clearly has open questions. The user observed this on real runs (`run_8172295672634c71b7c0ee28d6a5be0a`, `run_c53cacd1cae54f1eaaad3440e658b8c7`) whose generated specs are full of open questions written as prose ("Confirm whether this application must remain Java 8…", "Decide whether Swagger/Springfox is still needed…") — none of which became answerable clarifications.

**How discovered:** systematic-debugging investigation, 2026-06-23 (no code changed during diagnosis).

**Root cause — the clarification feature's front half was never built.** Epic 2 shipped the back half (submission + `/answer` in story 2.11, the visible-incorporation lifecycle + sweep in 2.12, the `ClarificationRegion` UI in 2.18) but deferred *creation* to "Epic 3 runner-contracts", which never landed.

**Evidence (verified against the live codebase):**
- `ClarificationWritePort.insertOpen(...)` — the only method that creates a clarification row — has **zero production callers** (only the adapter impl + the interface ref). Its javadoc names the missing "Epic 3 spec-runner question-marker handler".
- `runner-result.v1.schema.json` `specArtifact` carries only `contentReference` — **no channel** for the agent to emit questions.
- The only clarification REST endpoint is `/answer` — **no create endpoint**.
- `ClarificationLifecycleService.markAccepted` (`answered → accepted`) has **no trigger** — there is no `accept_clarification` `AllowedAction`.
- `ClarificationLifecycleOrchestrator.acknowledgesQuestion` is an explicit substring-scan **stub** with `TODO(epic-3-runner-contracts): replace with a structured clarification_acknowledgements block`.
- **Corroboration in the planning docs:** Story 2.11 AC6 (`epics.md`) records the deferral verbatim — a clarification row is "created when the spec runner emits a question marker, **wired in Epic 3**."

---

## Section 2 — Impact Analysis

**Epic impact:** No existing epic is modified or invalidated. The gap is *unbuilt deferred work* from Epic 2, deferred to "Epic 3" generically and orphaned across the Epic 3 / 3c / 3d split. A **new Epic 3e** captures it.

**Story impact:** Two net-new stories (3e-1 creation, 3e-2 incorporation). No existing story is reopened — the Epic 2 stories (2.11/2.12/2.18) remain `done`; their back-half code is correct and reused as-is.

**Artifact conflicts:**
- **PRD:** none. FR9/FR11/FR13 definitions are unchanged; Epic 3e activates their deferred execution (traceability annotated, not redefined).
- **Architecture:** additive only — an optional structured channel on `runner-result.v1` `specArtifact` (`questions[]`, `clarificationAcknowledgements[]`), no `schemaVersion` bump (the 3d-7 `providerUsage` precedent). Proposed ADR-0028.
- **UX:** none net-new — the `ClarificationRegion` + incorporation lifecycle chips (2.18) already exist; 3e adds accept/regenerate governed buttons gated by new AllowedActions.

**Technical impact:** backend (`ClarificationIngestService`, broker seam, accept command/REST/CLI, bundle feed, sweep oracle), both `runner.mjs` entrypoints + mocks (byte-identical), runner-contracts schema + fixtures, two `AllowedAction`s, one new `WorkflowEventType`, and (3e-2) a likely additive Flyway side-store for acknowledgements + routing the rebuilt spec through `newVersion` (graft).

---

## Section 3 — Recommended Approach

**Selected path: Direct Adjustment (add a new epic + 2 stories within the existing plan).** Rollback is N/A (nothing to revert — the gap is unbuilt). MVP review is N/A (no scope reduction; this *restores* planned MVP behavior).

**Rationale:** the back half is sound and reused; the work is purely additive and well-isolated behind existing seams. Splitting into two stories lets **3e-1 ship the entire user-visible symptom fix** (questions appear + are answerable) while **3e-2** absorbs the harder incorporation-loop reconciliations independently.

- **Effort:** 3e-1 Medium; 3e-2 High (lineage graft + answer materialization + acknowledgement plumbing).
- **Risk:** 3e-1 Low; 3e-2 Medium (the lineage-scope graft is the highest-risk reconciliation — see story R3).
- **Timeline:** inserted between Epic 3d and Epic 4; does not block 3d completion. 3e-1 can be pulled forward independently since it only depends on done Epic 2 work.

---

## Section 4 — Detailed Change Proposals

**New file:** `_bmad-output/planning-artifacts/epic-03e-clarification-loop-activation.md` — full epic definition (narrative, gap rationale, prerequisites, proposed ADR-0028, 2-story list with reconciled ACs).

**`_bmad-output/planning-artifacts/epics.md`:**
- Inserted an **Epic 3e** section between Epic 3d and Epic 4 (positioning = sequencing only).
- FR traceability table: FR9/FR11/FR13 annotated `Epic 2 / Epic 3e` (deferred clarification creation + incorporation activated in 3e).

**`_bmad-output/implementation-artifacts/sprint-status.yaml`:**
- New block `epic-3e: backlog` + `3e-1-...: backlog` + `3e-2-...: backlog` + `epic-3e-retrospective: deferred`, inserted between the Epic 3d retro and the Epic 4 header.

**Implementation stories (authored 2026-06-23, status flipped `drafted → backlog`):**
- `_bmad-output/implementation-artifacts/3e-1-spec-runner-clarification-emission-and-creation-seam.md`
- `_bmad-output/implementation-artifacts/3e-2-clarification-accept-and-spec-rebuild-incorporation.md`

**Forward obligation (not created here):** `docs/adr/0028-structured-clarification-channel.md` — author alongside 3e-1.

---

## Section 5 — Implementation Handoff

**Scope:** Moderate → route to **Developer agent** (backlog already reorganized; no PM/Architect replan needed).

**Sequencing:** 3e-1 first (self-contained, fixes the symptom) → 3e-2 (depends on 3e-1). Both run after the existing Epic 3d work or can interleave (3e depends only on done Epic 2 code, not on 3d).

**Success criteria:**
- 3e-1: a spec run with an emitted question creates an `open` clarification that renders in `ClarificationRegion` and is answerable via `/answer`; replay does not duplicate; a no-question run is byte-identical to pre-3e.
- 3e-2: create → answer → accept → regenerate → the rebuilt spec marks each clarification `incorporated`/`superseded` from structured acknowledgements; a v1-pinned clarification is provably swept on v2.

**Next step:** run `bmad-create-story` validation (optional) then `dev-story 3e-1`.

---

## Addendum (same day) — Two stories added to Epic 3e: 3e-3 + 3e-4

**Trigger:** follow-up correct-course request — "add new story to epic 3e. Perform review on the WaitingForSpecApproval phase (reviewer gets ticket + specification + opened questions). Make execution (codex/claude/manual) configurable at project level… map runner to each step in project."

**Clarifications resolved (AskUserQuestion):** (1) the spec-phase reviewer is the **automated LLM advisory reviewer** (extend 3d-2), not human-only aggregation; (2) execution config = **per-step runner mapping** + the missing UI (not just confirming the existing capability); (3) **two** stories, not one.

**Impact analysis (what already exists vs. net-new):**
- *Spec-phase review:* 3d-2 built the full advisory-reviewer substrate (`RunnerStage.REVIEW`, `step_reviews`, `enqueueReviewerIfConfigured`, `GET /reviewer-verdict`, Verdict Panel) but fires it **only** at `WaitingForReview` (`onPlanStageSucceeded`/`onPrOutputStageSucceeded`), **not** `onSpecStageSucceeded`. Net-new = the spec-phase trigger + a spec-review bundle carrying the open clarifications + FE surfacing the panel at the spec gate. → **3e-3** (depends on 3d-2 + 3e-1).
- *Per-project execution config:* 3d-3 shipped a **single** per-project `Project.runnerKind` (codex/claude/manual) across all stages and **explicitly deferred** per-stage granularity (its Open Decision #1); the single override is in the REST DTOs + `schema.d.ts` but has **no UI control** (verified: `runnerKind` appears in the frontend only in generated `schema.d.ts`). Net-new = per-step mapping (`project_runner_kinds` table + `ProjectRunnerStep` registry + resolver layering) + the Projects-Management-UI selectors. → **3e-4** (resolves 3d-3 Open Decision #1; depends on 3d-3 done).

**Recommended approach:** Direct Adjustment — add 3e-3 + 3e-4 to Epic 3e (now a 4-story epic). Both are extensions of existing, done substrates (3d-2 reviewer; 3d-3 runner-kind), so scope stays Moderate. No rollback, no MVP change. Per-step `manual` rides the existing 3d-3 park path with zero new dispatch logic (the headline simplification).

**Artifacts updated (this addendum):**
- New story files `3e-3-spec-phase-advisory-reviewer.md`, `3e-4-per-step-runner-mapping-per-project.md`.
- `epic-03e-...md` — story list 2 → 4 stories + 3e-3/3e-4 AC sections + FR/cross-cutting notes (3e-3 extends FR64; 3e-4 extends FR66 + Epic 3c project config).
- `sprint-status.yaml` — Epic 3e header 2 → 4 stories + `3e-3`/`3e-4: backlog` entries.

**FRs:** 3e-3 extends FR64 (advisory reviewer) to the spec gate; 3e-4 extends FR66 (manual execution) + Epic 3c project config to per-step granularity. No new PRD requirement.

**Handoff:** Developer agent. Sequencing — 3e-1 → 3e-3 (needs open clarifications) ; 3e-2 independent after 3e-1 ; 3e-4 independent (only needs 3d-3, done). **Forward obligation:** proposed ADR-0028 still applies to the runner-contract additive fields (3e-1/3e-2).
