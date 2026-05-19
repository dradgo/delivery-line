# Fixture: spec-rejection-and-resubmit

## Scenario

A governed run produces a spec v1 that fails review. The human reviewer rejects with structured
feedback; the run reverts to `Investigating`, a runner produces spec v2 that addresses the
feedback, and the spec is approved on the second pass. Run reaches `Completed`.

## What it covers

- The `WaitingForSpecApproval` → `Investigating` reversion path (legal transition exercised).
- `approval.rejected` event with `details.feedback` carrying a placeholder structured-feedback
  string (no PII, no secrets — fixture-safe).
- Spec artifact versioning: `artifactVersion: 1` then `artifactVersion: 2` on the same
  `artifactId` (`art_spec_rej_001`).
- Multiple human intervention markers (rejection + approval + final review pass).

## What it does NOT cover

- Runner failure (see `execution-failure-with-retry.md`).
- The `implementationPlan` and `prOutput` artifact variants — covered by the happy-path and
  failure-retry fixtures respectively.

## Recommended Epic 2 consumers

`2.10` Backend spec rejection (structured feedback wire shape), `2.18` Clarification region,
`2.17` Artifact Review Panel (spec variant in `superseded-by-new-version` state),
`2.20` Queue Shell States (rejected-and-returned state row).
