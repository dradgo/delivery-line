# Epic 4 Close Checklist

Authority: this checklist is the source of authority for closing Epic 4 (Failure Handling,
Recovery & Reconciliation). It is owned by story 4.29 AC2
(`_bmad-output/implementation-artifacts/4-29-epic-4-closure-gate-recovery-scope-lift-deferred-verification.md`);
the checklist's `epic-4` status flip is the manual gate that follows the automated closure work
(the RecoveryService scope-lift applied by story 4.28 and verified end-to-end by story 4.29).
It mirrors the Epic-1 close gate (`docs/epic-1-close-checklist.md`) and the 2.29 / 3.36 close
gates.

## Checklist

- [x] All Epic 4 stories merged / `done` (4.1-4.16, 4.16a, 4.17-4.28, 4.30) - verify via
      `_bmad-output/implementation-artifacts/sprint-status.yaml` `development_status` block.
      Evidence: verified 2026-07-19 - every row 4-1..4-30 (incl. 4-16a) reads `done`; only `4-29`
      (this closure story) is in flight.
- [x] The 4.28 RecoveryService scope-lift is applied: the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED`
      ArchUnit rule + its `@ArchTest` registration are removed, ADR 0033
      (`docs/adr/0033-recovery-service-scope-lift.md`) is present and governs the surface, and the
      sibling `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` lock stays in place.
      Evidence: verified 2026-07-19 - `RecoveryServiceScopeLiftMetaTest` 4/4 green (rule-constant
      absent, registration absent, sibling present, ADR sections a-e present).
- [x] End-to-end proof: all five deeper recovery methods plus the Epic-1 baseline are present on
      `RecoveryService` (`retry`, `resume`, `reconcile`, `rerunFromStep`, `pause`,
      `classifyFailure`, `describeFailure`) and the Failsafe architecture slice
      (`**/architecture/**/*Test`) is GREEN with the scope lock gone.
      Evidence: verified 2026-07-19 - `mvnw failsafe:integration-test failsafe:verify
      -Dit.test=**/architecture/**/*Test` -> BUILD SUCCESS, 90 tests, 0 failures
      (`ArchitectureBoundaryTest` 64/64, `RecoveryServiceScopeLiftMetaTest` 4/4).
- [x] ADR 0033 (c) allow-list matches the merged `RecoveryService` public surface and REST
      endpoints (stories 4.10-4.14), and the ADR header status is `Accepted` (no longer
      `Proposed`).
      Evidence: verified 2026-07-19 - (c) table (resume/4.5/4.10, reconcile/4.6/4.11,
      rerunFromStep/4.7/4.12, pause/4.8/4.13, classifyFailure/4.9/4.14 + retry + describeFailure)
      matches the built surface with zero drift; header flipped Proposed -> Accepted by story 4.29.
- [x] The Epic-4 recovery walkthrough (`docs/failed-run-recovery-walkthrough.md`, story 4.27) is
      published, references ADR 0033 in its Background section, and is reachable from the docs
      top level.
      Evidence: verified 2026-07-19 - Background section links `[ADR 0033](adr/0033-recovery-service-scope-lift.md)`
      (anchor resolves); linked from root `README.md` operator entry (README.md:18).
- [ ] Operator-walkthrough validated by a **named human** (per story 4.27 AC12). Replace the
      placeholder at `docs/failed-run-recovery-walkthrough.md:3`
      (`_____ (to be named before Epic 4 close)`) with the validator's name.
      Status: **BLOCKED - human decision required (Alex).** The placeholder is still unfilled as of
      2026-07-19; Alex confirmed on 2026-07-19 to leave this blocked (the hands-on console
      validation has not yet been performed). Do not check this row until a real operator has walked
      the console end-to-end and is named in the doc.
- [ ] `sprint-status.yaml` `epic-4` field flipped from `in-progress` to `done`.
      Gated on the validator row above.
- [ ] Epic 5 unblocked - story 5-1 status flipped from `backlog` to `ready-for-dev` (or kept at
      `backlog` pending Epic 5 sprint planning, at Alex's direction).
      Gated on the `epic-4` flip above.

## How to use this checklist

1. **Verify each item by inspection.** Each row corresponds to a concrete artifact or repository
   state. Do not check a row until you have confirmed the artifact exists / the state is set.
2. **Check the box and add a brief evidence note** beside each item - commit SHA, PR link, or
   "verified by `<reviewer>` on `<date>`". The note lives inline (extend the row) so the audit
   trail is co-located with the checklist.
3. **Flip `epic-4` to `done`** in `_bmad-output/implementation-artifacts/sprint-status.yaml` only
   after every box above is checked - including the human-validated operator walkthrough. The flip
   is irreversible by convention: closing Epic 4 is the structural signal to start Epic 5 sprint
   planning and satisfies cross-epic references (e.g. 4.22 AC12) that assume the scope-lift story
   has landed.
4. **Announce Epic 4 close** to the team (whatever channel the team uses for milestone signal).

## Why this is a human gate

Story 4.29's automated verification (the green architecture slice, the ADR reconciliation, the
merged-status confirmation) is the structural close: it proves the recovery surface landed and the
scope-lift is durable. This checklist is the **operational close**: a human confirms the operator
can actually triage a failed run in the console end-to-end (which no automated test exercises - it
requires hands-on console interaction on a real failed run), names themselves as the validator,
then flips the sprint-status field. The two gates together prevent Epic 5 from starting before
either the recovery contracts or the operator experience are durable.
