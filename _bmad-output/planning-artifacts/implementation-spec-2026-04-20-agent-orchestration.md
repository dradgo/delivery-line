# Implementation Spec: Agent-Orchestrated Feature Delivery Control Plane

**Date:** 2026-04-20
**Owner:** Alex
**Mode:** Hackathon MVP
**Strategy:** Build `A` by default. Fork `C` only if a workflow-native OSS base proves itself in a one-day spike.

## 1. Goal

Build a narrow but real orchestration system that turns a Linear feature ticket into a governed delivery workflow across multiple agent runners, with explicit workflow states, human approvals, artifacts, and completion sync.

The MVP must demonstrate:

- real task intake from Linear
- real workflow state progression
- real agent execution through a common runner interface
- real spec approval and final review checkpoints
- real artifacts at key steps
- real recovery via retry/resume from a valid workflow state

## 2. Demo Slice

### Canonical Workflow

One workflow type only: `feature-delivery`.

### Happy Path

1. A selected Linear issue enters the system as `Inbox`.
2. The system creates a workflow instance and moves it to `Planned`.
3. The `Investigating` step gathers repo/context information and produces a `proposed-spec.md` artifact.
4. The workflow pauses in `Waiting for spec approval`.
5. A human approves the spec in the thin review UI.
6. The workflow enters `Executing` and dispatches one or more runner steps.
7. Execution produces a `diff` or PR-like artifact bundle.
8. The workflow pauses in `Waiting for Review`.
9. A human approves final review.
10. The workflow moves to `Completed` and syncs a completion summary back to Linear.

## 3. In Scope

- one workflow template: `feature-delivery`
- one ticket source: Linear
- two runner adapters: Codex and Claude
- one repo integration path: GitHub or local repo access
- two human approval checkpoints
- three artifact types
- one thin web review UI
- one retry/resume recovery model

## 4. Out of Scope

- general workflow builder
- autonomous decomposition/planning engine
- multi-project RBAC
- analytics/reporting dashboard
- full bidirectional state sync for every external system
- broad notification matrix
- production-grade auth hardening beyond hackathon necessity

## 5. State Machine

### States

- `Inbox`
- `Planned`
- `Investigating`
- `WaitingForSpecApproval`
- `Executing`
- `WaitingForReview`
- `Completed`
- `Failed`

### Allowed Transitions

- `Inbox -> Planned`
- `Planned -> Investigating`
- `Investigating -> WaitingForSpecApproval`
- `WaitingForSpecApproval -> Executing`
- `WaitingForSpecApproval -> Investigating`
- `Executing -> WaitingForReview`
- `Executing -> Failed`
- `Failed -> Executing`
- `Failed -> Investigating`
- `WaitingForReview -> Completed`
- `WaitingForReview -> Executing`

### Transition Rules

- Every transition must emit an event record.
- Approval transitions must record actor, decision, timestamp, and reason.
- Retry/resume must restart from the last successful committed state, not from the beginning.

## 6. Core Components

### 6.1 Workflow Engine

Responsibilities:

- create workflow instances from Linear issues
- apply fixed `feature-delivery` template
- enforce state transitions
- schedule next step execution
- persist workflow state and event log
- pause/resume for approval checkpoints

Implementation shape:

- scripted orchestration service
- explicit state transition table
- step handlers per workflow state

### 6.2 Linear Adapter

Responsibilities:

- poll selected Linear issues
- fetch issue metadata
- map Linear issue to workflow instance
- write completion summary back to issue

Initial behavior:

- polling-based intake
- completion sync-back only

### 6.3 Runner Adapter Layer

Responsibilities:

- expose one common runner contract
- route execution to Codex or Claude adapter
- normalize outputs into workflow artifacts
- normalize failures into workflow events

Common contract:

```ts
interface RunnerAdapter {
  provider: "codex" | "claude";
  execute(input: RunnerExecutionInput): Promise<RunnerExecutionResult>;
}

interface RunnerExecutionInput {
  workflowId: string;
  stepId: string;
  promptBundle: PromptBundle;
  repoContext: RepoContext;
}

interface RunnerExecutionResult {
  status: "success" | "failed";
  outputText: string;
  artifactRefs: string[];
  rawMetadata: Record<string, unknown>;
  errorMessage?: string;
}
```

### 6.4 Artifact Service

Responsibilities:

- create and version workflow artifacts
- resolve artifact references for later steps
- expose artifact metadata to the UI

Artifact types:

- `proposed-spec`
- `implementation-diff`
- `completion-summary`

### 6.5 Approval Service

Responsibilities:

- open approval requests
- store decisions and reasons
- resume paused workflows
- reject and redirect workflows

Approval types:

- `spec-approval`
- `final-review`

### 6.6 Review UI

Responsibilities:

- list workflow instances needing review
- show current state and event history
- display artifacts
- collect approval or redirect action
- trigger resume/retry actions

## 7. Persistence Model

Use conventional app storage for runtime state and events.

### Tables / Collections

#### `workflow_instances`

- `id`
- `linear_issue_id`
- `workflow_type`
- `current_state`
- `status`
- `active_runner`
- `created_at`
- `updated_at`

#### `workflow_events`

- `id`
- `workflow_id`
- `event_type`
- `from_state`
- `to_state`
- `actor_type` (`system`, `human`, `runner`)
- `actor_id`
- `payload_json`
- `created_at`

#### `workflow_artifacts`

- `id`
- `workflow_id`
- `artifact_type`
- `title`
- `storage_path`
- `metadata_json`
- `created_at`

#### `approval_requests`

- `id`
- `workflow_id`
- `approval_type`
- `status` (`pending`, `approved`, `rejected`)
- `requested_at`
- `resolved_at`
- `resolved_by`
- `decision_reason`

Optional:

- mirror artifact files into a Git-backed folder for demo transparency

## 8. Context Handoff Model

Each step receives a generated prompt bundle assembled from:

- workflow metadata
- current state summary
- selected Linear fields
- previous approved artifact content
- relevant repo context
- explicit step objective

### Prompt Bundle Shape

```json
{
  "workflowId": "wf_123",
  "workflowType": "feature-delivery",
  "state": "Executing",
  "linearIssue": {
    "id": "LIN-123",
    "title": "Add approval flow to onboarding"
  },
  "stateSummary": "Spec approved. Execute implementation.",
  "artifactRefs": [
    "artifact_spec_001"
  ],
  "instructions": [
    "Implement only the approved scope",
    "Produce a diff artifact"
  ]
}
```

## 9. Repo Integration

Initial implementation can support either:

- local repo access from the orchestration service
- GitHub integration for artifact association and optional PR metadata

MVP requirement:

- enough repo access to let investigation and execution steps inspect or reference code context

## 10. APIs

### Internal Service Actions

- `POST /workflows/import-from-linear`
- `GET /workflows`
- `GET /workflows/:id`
- `GET /workflows/:id/events`
- `GET /workflows/:id/artifacts`
- `POST /workflows/:id/approve-spec`
- `POST /workflows/:id/reject-spec`
- `POST /workflows/:id/approve-review`
- `POST /workflows/:id/reject-review`
- `POST /workflows/:id/retry`
- `POST /workflows/:id/resume`

### Event Types

- `workflow.created`
- `state.changed`
- `artifact.created`
- `runner.started`
- `runner.completed`
- `runner.failed`
- `approval.requested`
- `approval.approved`
- `approval.rejected`
- `workflow.completed`

## 11. UI Spec

### View 1: Review Queue

Shows:

- workflow id
- Linear issue id/title
- current state
- pending approval type
- last updated timestamp

### View 2: Workflow Detail

Shows:

- state timeline
- current step
- artifact list
- event log
- approval actions
- retry/resume controls if failed or paused

### UI Constraint

Do not build a broad dashboard. The UI exists only to make:

- approvals
- state visibility
- artifact inspection

clear in the demo.

## 12. Step Execution Logic

### Step: Investigating

Input:

- Linear issue
- repo context
- workflow metadata

Output:

- `proposed-spec` artifact
- transition to `WaitingForSpecApproval`

### Step: Executing

Input:

- approved spec artifact
- prompt bundle
- runner selection

Output:

- `implementation-diff` artifact
- transition to `WaitingForReview`

### Step: Completing

Input:

- approved final review
- collected artifact refs

Output:

- `completion-summary` artifact
- Linear update
- transition to `Completed`

## 13. Recovery Model

### Supported

- retry the failed step from the last valid state
- resume after human fixes context or input

### Not Supported

- arbitrary branching replay
- multiple parallel recovery paths
- automatic semantic conflict resolution

## 14. Demo Data and Script Requirements

Prepare one canonical Linear feature ticket with:

- clear title
- short description
- repo target
- enough context to generate a believable proposed spec

Prepare one happy-path demo script:

1. import ticket
2. show state creation
3. show proposed spec
4. approve spec
5. show runner execution
6. show diff/PR artifact
7. approve review
8. show completion summary synced to Linear

Prepare one recovery-path mini-demo:

- show failed execution
- edit context/input
- resume from last valid state

## 15. Acceptance Criteria

The MVP is acceptable if all are true:

- a real Linear issue can enter the workflow
- the workflow visibly progresses through the defined states
- spec approval and final review are both real pauses
- at least one runner can execute a real step end-to-end
- the second runner can execute through the same interface
- artifacts are visible in the UI
- retry/resume works from a valid state
- completion summary is written back to Linear
- the full demo can be completed reliably in under 7 minutes

## 16. Build Order

1. workflow schema and state transition engine
2. storage model and event log
3. Linear adapter
4. first runner adapter
5. investigation step and spec artifact
6. spec approval UI
7. execution step and diff artifact
8. final review UI
9. completion sync
10. second runner adapter
11. retry/resume
12. demo polish

## 17. Open Questions

- Should runtime state live only in app storage, or should selected artifacts also be mirrored into Git for better demo visibility?
- Should the first runner be Codex or Claude, based on whichever is easier to wire in your current environment?
- Is GitHub PR creation required for the demo, or is a diff artifact sufficient?
