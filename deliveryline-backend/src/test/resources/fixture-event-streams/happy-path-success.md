# Fixture: happy-path-success

## Scenario

A governed run flows from Inbox to Completed without intervention. Each Epic-2 artifact variant
appears as a draft, then `available`, then is approved or linked into the outcome.

## What it covers

- All 11 `WorkflowState` values that participate in the happy path: `Inbox`, `Planned`,
  `Investigating`, `WaitingForSpecApproval`, `Executing`, `WaitingForReview`, `Completed`.
- `WorkflowEventType` values: `workflow.stateChanged`, `artifact.draftCreated`,
  `artifact.available`, `approval.requested`, `approval.approved`, `runner.started`,
  `integration.linked`.
- Artifact variants (`details.artifactVariant`): `spec`, `implementationPlan`, `prOutput`.
- `ActorType`: `system`, `agent`, `human`.

## What it does NOT cover

- Rejection paths, runner failure, retry. See `spec-rejection-and-resubmit.md` and
  `execution-failure-with-retry.md` for those.

## Recommended Epic 2 consumers

`2.15` Queue Item, `2.16` Context Strip, `2.17` Artifact Review Panel (all three variants),
`2.19` Decision Bar (positive-path approval mode).
