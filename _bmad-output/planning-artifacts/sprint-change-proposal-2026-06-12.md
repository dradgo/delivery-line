# Sprint Change Proposal — 2026-06-12

**Author:** Alex (via Correct-Course workflow)
**Trigger type:** Reprioritization / discovery — surface an already-planned capability earlier
**Scope classification:** **Minor** (backlog reorganization; no new requirements, no architecture change)
**Status:** APPROVED 2026-06-12

---

## Section 1 — Issue Summary

**Request:** Operators need a UI **button to rerun a failed agent run** — particularly when the run failed for a **configuration reason** (e.g., a missing/incorrect runner secret or provider API key that has since been fixed). Question raised: *is this already planned, and if so, can it be moved into the active sprint slice (3a/2b)?*

**Finding:** **Yes — it is already specified.** No new story authoring is required; this is a prioritization move, not a scope change.

- The exact feature is **Story 3.30 — "UI Minimum-Viable-Recovery Baseline (Failed Stage / Last Successful / Retry Action)"**, previously parked in the deferred **epic-3b** slice.
- Its backend dependency is **already DONE**: **Story 1.18** shipped the `RecoveryService` retry baseline, the `deliveryline retry` CLI, and a `POST /api/v1/workflows/{workflowRunId}/retry` REST endpoint already exists on `WorkflowController`.
- The deeper "rerun from an **earlier** step after fixing a runner config" case is also already planned, in **Epic 4** (Stories 4.7 `RecoveryService.rerunFromStep`, 4.12 REST endpoint, 4.22 Decision Bar deeper actions). Story 4.7's user story literally reads *"re-execute the implementation after fixing a runner config."*

## Section 2 — Impact Analysis

**Epic impact**
- **Epic 3b → Epic 2b:** Story 3.30 moves out of the deferred epic-3b slice into the active **epic-2b** (Full PM-Loop UX) frontend slice. Story id is **unchanged** (kept as 3-30 / 3.30), matching the existing repo precedent where story 6-9 was pulled forward "id unchanged."
- **Epic 4:** unaffected. The deeper rerun-from-step path (4.7/4.12/4.22) stays in Epic 4 per the user's chosen scope.

**Story impact**
- 3.30 (frontend) becomes a `backlog` item in the active 2b slice, ready to be picked up via `bmad-create-story` (backlog → ready-for-dev) after the in-flight 2-28 closes.
- No other story changes. 3.30 AC10 ("add `POST .../retry` if not already added") is effectively a no-op verification because the endpoint already exists.

**Artifact conflicts**
- **PRD:** none — recovery baseline shipping with E1 CLI + E3 UI is already the stated thesis-marker promise (Epic 1 refinement R1).
- **Architecture:** none — no new components; reuses the existing `RecoveryService` + retry REST path. The `RecoveryService` ArchUnit scope-lock lift (story 4.28) is **not** required for 3.30 (3.30 calls only the already-exposed retry baseline).
- **UX:** 3.30 reuses existing patterns — Decision Bar `recovery_operator` stub (story 2.19), Run Context Strip (2.16), confirmation dialog (2.23), `state-error` token + WCAG (2.3 / 2.25). No new UX spec needed.

**Technical impact**
- Frontend only: `useRetryWorkflow` mutation hook + Decision Bar `recovery_operator` real implementation + failure-treatment rendering in the run timeline / Run Context Strip / Queue Item. Backend untouched.

## Section 3 — Recommended Approach

**Selected path: Option 1 — Direct Adjustment (backlog reprioritization).**

Pull **only Story 3.30 into the active epic-2b slice**. Rationale:
- Delivers exactly the requested button.
- **Lowest effort / lowest risk:** backend is already done, so this is a self-contained frontend story with no cross-epic dependency and no architecture lift.
- Keeps the deeper Epic 4 recovery machinery (RecoveryService extensions, scope-lock lift, new ArchUnit surface) deferred, avoiding an early, heavier Epic 4 pull.

Rejected alternatives:
- *Also pull 4.7 + 4.12 into 3a:* unnecessary for a retry button; introduces the 4.28 scope-lock dependency and new backend surface. Revisit if/when "rerun from an earlier stage after a config fix" becomes a concrete operator need.
- *Author a new config-aware story:* redundant — 3.30 already renders the failure **category** and gates the retry on backend allowed-actions, which covers configuration-class failures once the config is corrected.

**Effort:** Low (single frontend story). **Risk:** Low. **Timeline:** Fits the current 2b slice; no replan.

## Section 4 — Detailed Change Proposals

**1. `_bmad-output/implementation-artifacts/sprint-status.yaml`**
- Inserted `3-30-...` into the **Epic 2b** section (status `backlog`) with a pull-forward annotation referencing this proposal; backend dependency (1-18) noted as done; id-unchanged precedent (6-9) cited.
- Replaced the original `3-30-...` line in the **Epic 3b** section with a pointer comment redirecting to the Epic 2b entry.

**2. `_bmad-output/planning-artifacts/epic-03-agent-execution.md`**
- Added a dated **Sprint note** under the Story 3.30 heading recording the pull-forward, the id-unchanged decision, the already-shipped backend (1.18), and the Epic-4 boundary for the deeper rerun-from-step case. Acceptance criteria unchanged.

## Section 5 — Implementation Handoff

**Scope: Minor → Developer agent.**
- **Next step:** when 2-28 closes, run `bmad-create-story` on **3-30** (`backlog → ready-for-dev`), then `bmad-dev-story` to implement.
- **Deliverable:** the "Retry failed step" button on `Failed` runs (Decision Bar `recovery_operator` mode), failure-category surfacing in the run timeline / Run Context Strip / Queue Item, and the `useRetryWorkflow` hook wired to the existing `POST .../retry` endpoint.
- **Success criteria:** Story 3.30 ACs 1–11, including: retry confirmation dialog with consequence text; scope discipline (only the retry action exposed in E3 — no reconcile/resume/rerun-from-arbitrary-step); axe-core a11y clean; tests against the story-1.23 execution-failure-with-retry fixture.

**Note on "configuration issues":** 3.30's retry re-executes the last failed step with a fresh runner — correct for a config failure that has been fixed externally (secret/API key added, runner config corrected). Rerunning from an *earlier* stage after a config fix is the deeper Epic 4 case (4.7/4.12/4.22), intentionally left deferred.
