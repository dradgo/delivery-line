# Story 4.29: Epic-4 Closure Gate — RecoveryService Scope-Lift Deferred Verification

Status: backlog

<!-- Follow-up story created 2026-07-08 to carry the dependency-gated ACs that story 4.28 could not
     satisfy at implementation time. Blocked until Epic-4 recovery stories 4.5–4.9 (and the full
     Epic-4 set) have merged. See _bmad-output/implementation-artifacts/deferred-work.md
     ("Deferred from: dev-story of story-4-28"). -->

## Story

As an architect closing Epic 4,
I want the dependency-gated portions of story 4.28 (the merge-gate, the Epic-4 close gate, the end-to-end proof that all five deeper recovery methods pass ArchUnit, the story-4.27 walkthrough cross-link, and the merge-time acknowledgment) executed and verified once stories 4.5–4.9 and the rest of Epic 4 have landed,
so that the RecoveryService scope-lift (already applied by 4.28) is formally closed and Epic 4's cross-cutting close gate (mirroring 1.23 / 2.29 / 3.36) actually passes.

## Context (READ FIRST)

**This story exists ONLY because story 4.28 was implemented while its dependencies were still `backlog`.** Story 4.28 (2026-07-08) applied the *buildable* slice — it removed the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` ArchUnit rule + registration, rewrote the `RecoveryService` Javadoc, authored `docs/adr/0033-recovery-service-scope-lift.md` + `docs/adr/README.md`, and added `RecoveryServiceScopeLiftMetaTest`. What it could **not** do — because the deeper recovery methods did not all exist yet — is the *closure* work below. Those items were recorded in `deferred-work.md` and are the entire scope of this story.

**⛔ HARD DEPENDENCY GATE — do NOT start this story until all of the following are `done`/merged:**
- `4-5` recovery-service resume — **already `done`** (2026-07-07).
- `4-6` reconcile, `4-7` rerun-from-step, `4-8` pause, `4-9` classify-failure — **`backlog` as of 2026-07-08**. These add the `reconcile` / `rerunFromStep` / `pause` / `classifyFailure` public methods to `RecoveryService`. AC6-clause-2 (below) cannot be proven until they exist.
- The rest of Epic 4 (4.10–4.27) merged, and story 4.27's Operator-validator named + walkthrough authored, for the Epic-4 close gate (AC2 below).

At authoring time, re-read `sprint-status.yaml` and confirm the gate before doing anything. If any prerequisite is still open, HALT and leave this story `backlog`.

## Acceptance Criteria

_(These are the deferred ACs from story 4.28, re-numbered for this story. Original 4.28 AC numbers noted in brackets.)_

1. **[was 4.28 AC3] Merge-gate.** A check asserts that stories 4.5–4.9 are all merged before this closure is considered satisfied. Because the 4.28 rule removal already merged, the gate here is a *verification* (not a build-blocker on 4.28): confirm — via `sprint-status.yaml` and/or the merged history — that `4-5`…`4-9` are `done`, and record the confirmation.
2. **[was 4.28 AC4 + AC9] Epic-4 close gate.** Mirroring the 1.23 / 2.29 / 3.36 close gates, verify: 4.1–4.27 all merged; the 4.28 scope-lift applied (rule gone, ADR 0033 present, meta-test green); the Operator-validator named (4.27 AC12); and the documented recovery walkthrough validated. Record the close-gate result. This is the architectural acknowledgment that Epic 4's scope has landed (satisfies cross-epic references such as 4.22 AC12 that assume "story 4.28 has occurred").
3. **[was 4.28 AC6 clause 2] End-to-end ArchUnit proof.** With all five deeper recovery methods present (`resume`, `reconcile`, `rerunFromStep`, `pause`, `classifyFailure`) on `RecoveryService`, confirm the full recovery surface compiles and the Failsafe architecture slice is green with the scope lock gone — i.e. the methods "work end-to-end without triggering ArchUnit failures." Add a focused assertion or IT if one adds value beyond the existing arch slice being green.
4. **[was 4.28 AC7] Walkthrough cross-link.** Once story 4.27's recovery walkthrough exists, add a reference to ADR 0033 in its "Background" section. (The pre-existing `docs/failure-recovery-walkthrough.md` and `docs/cli/workflow-commands.md` were already updated by 4.28; this AC is specifically the 4.27 increment's Background section.)
5. **ADR 0033 allow-list reconciliation.** Confirm ADR 0033 §(c)'s "what new scope is now allowed" table matches the *actual* merged `RecoveryService` public surface and the REST endpoints (4.10–4.14) as built. If any method/endpoint landed with a different name/signature than 0033 recorded, update the ADR's allow-list so the governance record stays exhaustive and accurate (per ADR 0033 §(e) step 5).

## Tasks / Subtasks

- [ ] **Task 1 — Confirm the dependency gate (AC1)**
  - [ ] Re-read `sprint-status.yaml`; confirm `4-5`…`4-9` are all `done`. If not, HALT — leave this story `backlog`.
- [ ] **Task 2 — End-to-end ArchUnit proof (AC3)**
  - [ ] Verify `RecoveryService` now exposes all five deeper methods + the Epic-1 baseline, compiles, and the Failsafe architecture slice (`**/architecture/**/*Test`) is green with `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` gone.
  - [ ] Confirm `RecoveryServiceScopeLiftMetaTest` still passes (rule absent, sibling present, ADR present).
- [ ] **Task 3 — ADR 0033 allow-list reconciliation (AC5)**
  - [ ] Diff ADR 0033 §(c) against the merged public surface + REST endpoints; update the allow-list table if anything drifted.
- [ ] **Task 4 — 4.27 walkthrough cross-link (AC4)**
  - [ ] If the story-4.27 walkthrough exists, add the ADR 0033 reference to its "Background" section.
- [ ] **Task 5 — Epic-4 close gate (AC2)**
  - [ ] Run the Epic-4 close-gate validation (mirror 1.23 / 2.29 / 3.36): all Epic-4 stories merged + scope-lift applied + Operator-validator named + walkthrough validated. Record the result.
- [ ] **Task 6 — Retire the deferral**
  - [ ] Remove/annotate the "Deferred from: dev-story of story-4-28" section in `deferred-work.md` as resolved, cross-referencing this story.
- [ ] **Logging instrumentation** — N/A (verification + docs only; no runtime code). Re-apply the full logging contract if any runtime code is touched.

## Dev Notes

- **Source of scope:** `_bmad-output/implementation-artifacts/deferred-work.md` → "Deferred from: dev-story of story-4-28 (2026-07-08)". That section is authoritative for what this story must close.
- **What 4.28 already did (do NOT redo):** removed the rule + registration (`ArchitectureRuleCatalog` / `ArchitectureBoundaryTest`), rewrote `RecoveryService` Javadoc, wrote `docs/adr/0033-recovery-service-scope-lift.md` + `docs/adr/README.md`, added `deliveryline-backend/src/test/java/org/dradgo/architecture/RecoveryServiceScopeLiftMetaTest.java`, and corrected the two stale doc references. This story is verification + closure, not re-implementation.
- **Meta-test is the durable guard:** `RecoveryServiceScopeLiftMetaTest` already keeps the lock removed and the sibling protected. This story does not add a second absence guard — it proves the *positive* end state (all five methods present + arch slice green).
- **ADR numbering discipline:** if this story authors any *new* ADR, take the next free number on disk (do not reuse 0033) — see `docs/adr/README.md` numbering-gap note and [[flyway-v31-cross-branch-collision]].
- **Verify in a clean env** before closing the Epic-4 gate ([[verify-ci-fixes-in-clean-env]]); note HEAD carried a pre-existing checkstyle drift at `WorkflowCommandService.java:1158` unrelated to the recovery scope work — ensure it (or its successor) is resolved before the full `mvnw verify` merge gate.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — "Deferred from: dev-story of story-4-28"]
- [Source: `_bmad-output/implementation-artifacts/4-28-architecture-lift-remove-recovery-service-scope-protected-lock-and-adr.md` — the parent story + its Scope-Boundary BUILD/DEFER table]
- [Source: `docs/adr/0033-recovery-service-scope-lift.md` — §(c) allow-list to reconcile, §(e) add-a-method process]
- [Source: `_bmad-output/planning-artifacts/epic-04-recovery.md` #Story-4.28 — original AC3/AC4/AC6/AC7/AC9]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
