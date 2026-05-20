# Epic 1 Close Checklist

Authority: this checklist is the source of authority for closing Epic 1 (Foundation & First
Governed Run — CLI). It is owned by story 1.23 AC10 (`_bmad-output/implementation-artifacts/1-23-foundation-gate-ci-verification-and-deterministic-fixture-event-stream.md`);
the checklist's `epic-1` status flip is the manual gate that follows story 1.23's automated
foundation-gate verification.

## Checklist

- [ ] All Epic 1 stories merged (1.1–1.22, 1.12c, story 1.23) — verify via
      `_bmad-output/implementation-artifacts/sprint-status.yaml` `development_status` block.
- [ ] `foundation-gate` CI job is a required status check on `main` (run
      `scripts/ci/configure-branch-protection.sh` or follow `docs/ci-branch-protection.md`).
- [ ] Fixture event stream published in
      `deliveryline-backend/src/test/resources/fixture-event-streams/` and the three contract
      tests (`FixtureEventStreamSchemaConformanceContractTest`,
      `FixtureEventStreamTransitionIntegrityContractTest`,
      `FixtureEventStreamArtifactVariantCoverageContractTest`) pass.
- [ ] Documentation increments merged (story 1.22 artifacts: `docs/quickstart.md`,
      `docs/setup-local.md`, `docs/glossary.md`, `docs/failure-recovery-walkthrough.md`, root
      `README.md`).
- [ ] Pilot-installer cold-run walkthrough validated by a **named human** (per story 1.22 AC7).
      Replace the placeholder in `docs/quickstart.md`, `docs/setup-local.md`, and
      `docs/failure-recovery-walkthrough.md` with the validator's name.
- [ ] `sprint-status.yaml` `epic-1` field flipped from `in-progress` to `done`.
- [ ] Epic 2 unblocked — story 2.1 status flipped from `backlog` to `ready-for-dev` (or kept at
      `backlog` pending Epic 2 sprint planning).

## How to use this checklist

1. **Verify each item by inspection.** Each row corresponds to a concrete artifact or repository
   state. Do not check a row until you have confirmed the artifact exists / the state is set.
2. **Check the box and add a brief evidence note** beside each item — commit SHA, PR link, or
   "verified by `<reviewer>` on `<date>`". The note lives inline (extend the row) so the
   audit trail is co-located with the checklist.
3. **Flip `epic-1` to `done`** in `_bmad-output/implementation-artifacts/sprint-status.yaml` only
   after every box above is checked. The flip is irreversible by convention — closing Epic 1 is
   the structural signal to start Epic 2 sprint planning.
4. **Announce Epic 1 close** to the team (whatever channel the team uses for milestone signal).

## Why this is a human gate

The `foundation-gate` job (story 1.23 AC1–AC2) is the structural close. This checklist is the
**operational close**: a human verifies the pilot-installer walkthrough (which `foundation-gate`
cannot exercise — it requires hands-on platform setup on a fresh machine), then flips the
sprint-status field. The two gates together prevent Epic 2 from starting before either the
contracts or the operator experience are durable.
