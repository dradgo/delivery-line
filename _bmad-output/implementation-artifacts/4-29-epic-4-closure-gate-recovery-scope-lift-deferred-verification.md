# Story 4.29: Epic-4 Closure Gate — RecoveryService Scope-Lift Deferred Verification

Status: review

<!-- Follow-up story created 2026-07-08 to carry the dependency-gated ACs that story 4.28 could not
     satisfy at implementation time. Was blocked until Epic-4 recovery stories 4.5–4.9 (and the full
     Epic-4 set) had merged. GATE NOW OPEN (confirmed 2026-07-19): every Epic-4 story 4.1–4.28 + 4.30
     is `done` in sprint-status.yaml; only this closure story remains. See
     _bmad-output/implementation-artifacts/deferred-work.md ("Deferred from: dev-story of
     story-4-28"). -->

## Story

As an architect closing Epic 4,
I want the dependency-gated portions of story 4.28 (the merge-gate, the Epic-4 close gate, the end-to-end proof that all five deeper recovery methods pass ArchUnit, the story-4.27 walkthrough cross-link, and the merge-time acknowledgment) executed and verified once stories 4.5–4.9 and the rest of Epic 4 have landed,
so that the RecoveryService scope-lift (already applied by 4.28) is formally closed and Epic 4's cross-cutting close gate (mirroring 1.23 / 2.29 / 3.36) actually passes.

## Context (READ FIRST)

**This story exists ONLY because story 4.28 was implemented while its dependencies were still `backlog`.** Story 4.28 (2026-07-08) applied the *buildable* slice — it removed the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` ArchUnit rule + registration, rewrote the `RecoveryService` Javadoc, authored `docs/adr/0033-recovery-service-scope-lift.md` + `docs/adr/README.md`, and added `RecoveryServiceScopeLiftMetaTest`. What it could **not** do — because the deeper recovery methods did not all exist yet — is the *closure* work below. Those items were recorded in `deferred-work.md` and are the entire scope of this story.

**✅ DEPENDENCY GATE — CONFIRMED OPEN as of 2026-07-19.** All prerequisites are `done` in `sprint-status.yaml`:
- `4-5` resume, `4-6` reconcile, `4-7` rerun-from-step, `4-8` pause, `4-9` classify-failure — **all `done`**. All five deeper methods now exist on `RecoveryService` (verified this session — see "Current-state verification" in Dev Notes), so AC3's end-to-end ArchUnit proof is exercisable.
- The rest of Epic 4 (4.10–4.27, 4.16a, 4.28, 4.30) — **all `done`**. Story 4.27's recovery walkthrough (`docs/failed-run-recovery-walkthrough.md`) is authored and already references ADR 0033 in its Background section (AC4 pre-satisfied — verify only).
- **Still re-confirm** `sprint-status.yaml` at dev time before flipping `epic-4` to `done` (Task 5). The one item NOT yet satisfiable by code alone is the **Operator-walkthrough validator name** (AC2) — it is still an unfilled placeholder and needs a human (Alex) to name the real operator; see Task 5.

This is a **verification + docs-only** story — NO re-implementation of the recovery methods, the rule removal, the ADR, or the meta-test (4.28 already shipped all of those). Do not HALT; the gate is open.

## Acceptance Criteria

_(These are the deferred ACs from story 4.28, re-numbered for this story. Original 4.28 AC numbers noted in brackets.)_

1. **[was 4.28 AC3] Merge-gate.** A check asserts that stories 4.5–4.9 are all merged before this closure is considered satisfied. Because the 4.28 rule removal already merged, the gate here is a *verification* (not a build-blocker on 4.28): confirm — via `sprint-status.yaml` and/or the merged history — that `4-5`…`4-9` are `done`, and record the confirmation.
2. **[was 4.28 AC4 + AC9] Epic-4 close gate.** Mirroring the 1.23 / 2.29 / 3.36 close gates, produce the operational close artifact `docs/epic-4-close-checklist.md` (mirror the structure of the existing `docs/epic-1-close-checklist.md`: a Checklist of concrete, inspectable rows, a "How to use", and a "Why this is a human gate" section). Its rows must verify: 4.1–4.28 + 4.30 all merged/`done`; the 4.28 scope-lift applied (rule gone, ADR 0033 present, meta-test green); the **Operator-walkthrough validator named** (4.27 AC12) — ⚠️ this is STILL an unfilled placeholder at `docs/failed-run-recovery-walkthrough.md:3` (`_____ (to be named before Epic 4 close)`) and requires a **human decision by Alex** to name the real operator + replace the placeholder; the recovery walkthrough validated + reachable from the docs top-level (no `docs/index.md` exists — the docs live as flat files under `docs/`; use the same visibility convention story 4.27 used, do NOT invent a new index). The close gate completes by flipping `epic-4: in-progress → done` in `sprint-status.yaml` and unblocking Epic 5 (5-1 currently `backlog`). This is the architectural acknowledgment that Epic 4's scope has landed (satisfies cross-epic references such as 4.22 AC12 that assume "story 4.28 has occurred"). **If the validator has not been named, complete every other row, leave the validator row unchecked, and do NOT flip `epic-4` to `done` — surface the blocker to Alex.**
3. **[was 4.28 AC6 clause 2] End-to-end ArchUnit proof.** All five deeper recovery methods are present on `RecoveryService` (verified 2026-07-19: `resume` L811, `pause` L1131, `reconcile` L1561, `classifyFailure` L1724, `rerunFromStep` L1875) plus the Epic-1 baseline `retry` + `describeFailure`. Confirm the full recovery surface compiles and the Failsafe architecture slice (`**/architecture/**/*Test`) is green with the scope lock gone — i.e. the methods "work end-to-end without triggering ArchUnit failures." The existing arch slice + `RecoveryServiceScopeLiftMetaTest` already prove this; add a focused assertion or IT only if it adds value beyond the arch slice being green.
4. **[was 4.28 AC7] Walkthrough cross-link — PRE-SATISFIED (verify only).** Story 4.27's recovery walkthrough `docs/failed-run-recovery-walkthrough.md` already references ADR 0033 in its "Background — why the deeper recovery surface exists" section (line ~45). Verify the anchor link resolves; no new edit needed unless it has rotted. (The pre-existing `docs/failure-recovery-walkthrough.md` and `docs/cli/workflow-commands.md` were separately updated by 4.28 — those are NOT the 4.27 increment.)
5. **ADR 0033 allow-list reconciliation + status flip.** Confirm ADR 0033 §(c)'s "what new scope is now allowed" table matches the *actual* merged `RecoveryService` public surface and REST endpoints (4.10–4.14). Verified 2026-07-19: the five method names + endpoints in §(c) match the built surface and all of 4.10–4.14 are `done`, so no allow-list drift is expected — but re-diff to be sure (per ADR 0033 §(e) step 5). Additionally, ADR 0033's header still reads `**Status:** Proposed (2026-07-08) — to be confirmed on merge of story 4-28`; since 4-28 is now `done`/merged, flip it to `Accepted` (keep the confirmation date/trail) so the governance record is not left perpetually "Proposed".

## Tasks / Subtasks

- [x] **Task 1 — Re-confirm the dependency gate (AC1)**
  - [x] Re-read `sprint-status.yaml`; confirm `4-5`…`4-9` (and 4.1–4.28 + 4.30) are all `done`. Record the confirmation. → Verified 2026-07-19: every Epic-4 row 4-1..4-30 (incl. 4-16a) is `done`; `epic-4: in-progress`; only `4-29` was in flight (now `in-progress`).
- [x] **Task 2 — End-to-end ArchUnit proof (AC3)**
  - [x] Run the Failsafe architecture slice (`**/architecture/**/*Test`, incl. `ArchitectureBoundaryTest` + `RecoveryServiceScopeLiftMetaTest`) in a clean env; confirm it is GREEN. → `mvnw failsafe:integration-test failsafe:verify -Dit.test=**/architecture/**/*Test` → **BUILD SUCCESS, 90 tests / 0 failures** (`ArchitectureBoundaryTest` 64/64, `RecoveryServiceScopeLiftMetaTest` 4/4, `AllowedActionRegistryPinTest` 17/17, `ArchitectureDiagnosticMetaTest` 5/5). Scope lock absent, all five deeper methods + baseline present (`retry`/`resume`/`reconcile`/`rerunFromStep`/`pause`/`classifyFailure`/`describeFailure`). No source change. `@{argLine}` direct-goal crash neutralized via explicit `-DargLine=` + `-Djacoco.skip=true`.
- [x] **Task 3 — ADR 0033 allow-list reconciliation + status flip (AC5)**
  - [x] Diff ADR 0033 §(c) against the merged `RecoveryService` public surface + REST endpoints 4.10–4.14. → Zero drift: (c) table matches the built 7-method surface; 4.10–4.14 all `done`. No allow-list edit.
  - [x] Flip the ADR header `Status: Proposed …` → `Accepted`. → Now `Accepted (proposed 2026-07-08; confirmed 2026-07-19 on the merge/close of story 4-28, verified by story 4-29's Epic-4 closure gate)`.
- [x] **Task 4 — 4.27 walkthrough cross-link (AC4, verify-only)**
  - [x] Verify `docs/failed-run-recovery-walkthrough.md` "Background" references ADR 0033 and the anchor resolves. → Confirmed present (Background L44–45: `[ADR 0033](adr/0033-recovery-service-scope-lift.md)`, resolves to the existing file). No edit. Walkthrough also reachable from the docs top level (root `README.md:18` operator entry).
- [x] **Task 5 — Epic-4 close gate (AC2)**
  - [x] Author `docs/epic-4-close-checklist.md` mirroring `docs/epic-1-close-checklist.md` (Checklist + How-to-use + Why-human-gate) with inline evidence notes. → Created; all mechanically-verifiable rows checked.
  - [x] ⚠️ **Human decision (Alex):** surfaced via AskUserQuestion 2026-07-19. → **Alex chose "Leave blocked (don't flip epic-4)"** — the hands-on operator-console validation has not been performed, so the placeholder at `docs/failed-run-recovery-walkthrough.md:3` is intentionally retained and the validator checklist row stays unchecked.
  - [ ] Once every checklist row is checked, flip `epic-4: in-progress → done` + unblock Epic 5. → ⚠️ **HUMAN-GATED, correctly NOT performed** per AC2's "else" branch + Alex's 2026-07-19 decision: the validator row is unchecked, so `epic-4` stays `in-progress` and `5-1` stays `backlog`. This is the specified terminal state, not a dev-incomplete item — the residual is a human gate for a later session once a named operator validates the console.
- [x] **Task 6 — Retire the deferral**
  - [x] Annotate the "Deferred from: dev-story of story-4-28" section in `deferred-work.md` as RESOLVED (2026-07-19), cross-referencing story 4.29. Left the separate "code review of story-4-28" defers untouched (out of scope).
- [x] **Logging instrumentation** — N/A (verification + docs only; no runtime code touched).

## Dev Notes

### Current-state verification (confirmed this session, 2026-07-19 — dev agent should re-run, not re-discover)

- **Gate:** `sprint-status.yaml` — every Epic-4 story `done`: 4.1–4.16, 4.16a, 4.17–4.28, 4.30. Only `4-29` is `backlog` (this story). `epic-4: in-progress`.
- **RecoveryService five methods present** in `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java`: `resume` L811, `pause` L1131, `reconcile` L1561, `classifyFailure` L1724, `rerunFromStep` L1875 (plus Epic-1 `retry` + `describeFailure`).
- **Scope lock genuinely removed:** `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` survives ONLY as (a) the absence-guard in `RecoveryServiceScopeLiftMetaTest.java:41` (`assertFalse(hasDeclaredField(...))`) and (b) explanatory comments in `ArchitectureRuleCatalog.java:946` + `ArchitectureBoundaryTest.java:195`. No live `@ArchTest` registration remains. Sibling `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` stays in place (4.28 AC8) — the meta-test guards it.
- **ADR 0033** exists at `docs/adr/0033-recovery-service-scope-lift.md`; §(c) allow-list matches the built surface. Header still `Status: Proposed` — flip to `Accepted` (Task 3).
- **4.27 walkthrough** `docs/failed-run-recovery-walkthrough.md` exists (30 KB); Background section references ADR 0033 (~L45) → AC4 pre-satisfied. **Operator-walkthrough validator still a placeholder** at L3 → AC2 human blocker.
- **Close-gate pattern:** `docs/epic-1-close-checklist.md` is the template for the `docs/epic-4-close-checklist.md` deliverable (Task 5). No `docs/index.md`/`docs/README.md` exists — docs are flat files under `docs/`; do not invent an index.

### Scope discipline

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
- [Source: `docs/epic-1-close-checklist.md` — the close-checklist template to mirror for `docs/epic-4-close-checklist.md` (AC2)]
- [Source: `docs/failed-run-recovery-walkthrough.md:3` — the unfilled Operator-walkthrough validator placeholder (AC2 human blocker)]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (bmad-dev-story, 2026-07-19)

### Debug Log References

- Architecture proof (AC3): `mvnw -f deliveryline-backend/pom.xml test-compile failsafe:integration-test failsafe:verify -Dit.test=**/architecture/**/*Test -DargLine= -Djacoco.skip=true` → BUILD SUCCESS, 90/0. (`@{argLine}` direct-goal crash trap avoided by overriding `argLine` empty + skipping jacoco.)

### Completion Notes List

Verification + docs-only closure story (no runtime/production code touched). All six tasks executed; the RecoveryService scope-lift (applied by 4.28) is formally verified and closed.

- **AC1 (merge-gate):** all Epic-4 stories `4-1`..`4-30` (incl. `4-16a`) confirmed `done` in `sprint-status.yaml`.
- **AC3 (end-to-end ArchUnit proof):** Failsafe architecture slice GREEN — 90 tests / 0 failures with the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` tripwire gone and all five deeper methods + Epic-1 baseline present. `RecoveryServiceScopeLiftMetaTest` 4/4 (rule-constant absent, `@ArchTest` registration absent, sibling `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` intact, ADR sections a–e present).
- **AC5 (ADR reconciliation + status flip):** §(c) allow-list matches the built surface + REST endpoints 4.10–4.14 (zero drift). ADR 0033 header flipped `Proposed → Accepted` (confirmation trail preserved).
- **AC4 (walkthrough cross-link):** verify-only — Background section references ADR 0033, anchor resolves; walkthrough reachable from root `README.md:18`. No edit needed.
- **AC2 (Epic-4 close gate):** `docs/epic-4-close-checklist.md` authored (mirrors epic-1 close checklist), all mechanically-verifiable rows checked.
- **AC6 (deferral retirement):** `deferred-work.md` "dev-story of story-4-28" section annotated RESOLVED (2026-07-19), cross-referencing 4.29.

⚠️ **RESIDUAL HUMAN GATE (by design, not a defect):** the Operator-walkthrough **validator name** (placeholder at `docs/failed-run-recovery-walkthrough.md:3`) requires a real operator to walk the console end-to-end. Surfaced to Alex on 2026-07-19; Alex chose **Leave blocked** (validation not yet performed). Consequently, per AC2's explicit instruction, `epic-4` was **NOT** flipped to `done` — it remains `in-progress`, and Epic 5 (`5-1`) remains `backlog`. When a named operator validates the console in a later session, complete the validator row + flip `epic-4 → done` per the close checklist. **Story 4.29's own dev scope is complete** — the gate flip is a separate human milestone the checklist now governs.

### File List

- `docs/epic-4-close-checklist.md` — **new**. Epic-4 operational close checklist (AC2), mirroring `docs/epic-1-close-checklist.md`.
- `docs/adr/0033-recovery-service-scope-lift.md` — **modified**. Status header flipped `Proposed → Accepted` (AC5).
- `_bmad-output/implementation-artifacts/deferred-work.md` — **modified**. "dev-story of story-4-28" deferral annotated RESOLVED (AC6).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — **modified**. `4-29` status `ready-for-dev → in-progress → review`. (`epic-4` intentionally NOT flipped — human gate open.)
- `_bmad-output/implementation-artifacts/4-29-epic-4-closure-gate-recovery-scope-lift-deferred-verification.md` — **modified**. Task checkboxes, Dev Agent Record, Change Log, Status.

## Change Log

| Date | Change |
|---|---|
| 2026-07-19 | Story 4.29 dev-story: verified Epic-4 closure (AC1 merge-gate, AC3 arch slice GREEN 90/0, AC5 ADR §(c) zero-drift + `Proposed→Accepted`, AC4 walkthrough cross-link). Authored `docs/epic-4-close-checklist.md` (AC2). Retired the story-4-28 dev-story deferral (AC6). Operator-walkthrough validator remains a **human gate** (Alex chose Leave blocked) → `epic-4` NOT flipped; stays `in-progress`. Status `ready-for-dev → review`. |
