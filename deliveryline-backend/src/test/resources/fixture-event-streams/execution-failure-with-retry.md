# Fixture: execution-failure-with-retry

## Scenario

A governed run reaches `Executing`, then a runner crashes (`failureCategory: runner_crash`). The
run transitions to `Failed`. A human invokes recovery; `recovery.retried` fires, and the run
transitions back to `Executing` for a second attempt. The retry succeeds and the run reaches
`Completed`.

## What it covers

- The `Executing` → `Failed` legal transition with a non-null `failureCategory` (the
  `WorkflowTransitionTable` invariant — only `RUNNER_TIMEOUT`, `RUNNER_CRASH`,
  `RUNNER_CONTRACT_VIOLATION`, `RUNNER_NON_ZERO_EXIT` are accepted).
- The `Failed` → `Executing` recovery transition.
- `runner.failed` event with `errorClass` + `errorCode` + `failedStage` in `details`.
- `recovery.retried` event with `triggeringEventId` linking back to the runner failure event,
  and `recoveryActionId` linking to a synthetic `recovery_actions` row id.
- `implementationPlan` and `prOutput` artifact variants on the successful retry.

## What it does NOT cover

- `recovery.dispatchFailed` (the second-event audit case from story 1.18 review).
- Rejection paths (see `spec-rejection-and-resubmit.md`).
- Reconciliation / `Reconciled` terminal state, or `TakenOver` — those are downstream-epic
  fixture territory.

## Recommended Epic 2 consumers

`2.15` Queue Item (failure-and-retry status display), `2.16` Context Strip (recovery-action
identifier surfacing), `2.19` Decision Bar (retry-mode CTA),
`2.21` Feedback Patterns (recoverable-error toast/inline boundaries).
