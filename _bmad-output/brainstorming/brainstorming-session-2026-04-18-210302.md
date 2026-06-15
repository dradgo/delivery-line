---
stepsCompleted: [1, 2]
inputDocuments: []
session_topic: 'Agent orchestration platform for structured software-development workflows with a hackathon-ready demo'
session_goals: 'Define desired platform features, MVP scope, demo story, build-vs-fork decision, key risks, and implementation effort'
selected_approach: 'progressive-flow'
techniques_used: ['What If Scenarios', 'Mind Mapping', 'Morphological Analysis', 'Decision Tree Mapping']
ideas_generated: []
context_file: ''
---

# Brainstorming Session Results

**Facilitator:** Alex
**Date:** 2026-04-18 21:03:02

## Session Overview

**Topic:** Agent orchestration platform for structured software-development workflows with a hackathon-ready demo
**Goals:** Define desired platform features, MVP scope, demo story, build-vs-fork decision, key risks, and implementation effort

### Session Setup

We are evaluating how to demonstrate an agent orchestration solution at a hackathon in a couple of weeks. The session will explore solution shapes broadly, then narrow toward an MVP, a credible demo narrative, and a practical implementation path.

## Technique Selection

**Approach:** Progressive Technique Flow
**Journey Design:** Systematic development from exploration to action

**Progressive Techniques:**

- **Phase 1 - Exploration:** What If Scenarios for maximum idea generation
- **Phase 2 - Pattern Recognition:** Mind Mapping for organizing insights
- **Phase 3 - Development:** Morphological Analysis for refining concepts
- **Phase 4 - Action Planning:** Decision Tree Mapping for implementation planning

**Journey Rationale:** This sequence starts by breaking assumptions around build, fork, or adopt, then clusters emerging patterns, combines platform design options into MVP-ready concepts, and finally maps the decision path toward a credible hackathon implementation and demo.

## Technique Execution Results

**What If Scenarios:**

- **Interactive Focus:** Reframing the solution away from "build the full platform" toward demo-first solution shapes
- **Key Breakthroughs:** Three promising directions emerged immediately: a demo-first facade with only the critical loop implemented, a thin orchestration veneer over existing tools, and an agent director pattern with scripted sub-agents
- **Emerging MVP Feature Signals:** Task decomposition, workflow states, human-in-the-loop checkpoints, audit trail, artifacts, multi-runner support for Codex and Claude, Linear integration, failure recovery, and memory/context handoff
- **Feature Prioritization Cut:** Fully real features are agent execution, Linear task intake, and workflow states. Partial features are audit trail, human checkpoints, and artifacts. Demo-simulated features are failure recovery, memory/context handoff, and task decomposition.
- **MVP Decomposition Decision:** Task decomposition can be template-driven by work type for the hackathon MVP, preserving demo credibility while reducing implementation risk.
- **Demo Value Priorities:** The most important proof points are an end-to-end story from task intake to completed artifact, and real integrations with tools like Linear and multiple runners.
- **Canonical Demo Workflow:** The primary vertical slice for the hackathon demo is feature delivery.
- **Demo Narrative Spine:** 1) Linear ticket appears in inbox, 2) workflow template is selected and task enters planned, 3) director dispatches work to agent runners, 4) execution trace shows state changes and produced artifacts, 5) human review checkpoint approves or redirects, 6) task is completed and synced back to Linear.
- **Differentiator Signals:** The strongest remembered value propositions are that humans remain in control without slowing delivery, and that AI delivery feels like a real operational system rather than chat.
- **Moat Hypothesis:** The defensible value is a unified workflow state model combined with consistent human checkpointing across agent-driven delivery.
- **Forking Rule:** Only fork an existing solution if it already behaves like a delivery workflow engine with explicit states and checkpoint primitives.
- **Phase Transition:** Move from divergent exploration into pattern recognition to organize product, workflow, integration, architecture, and risk themes around the feature-delivery control-plane concept.

**Mind Mapping:**

- **Branch - Core Product Promise:** Governed AI delivery and human control without drag are the first anchor themes for the product identity.
- **Branch - Core Product Promise Expansion:** Additional anchor themes are operational visibility, real task-to-artifact flow, and a unified workflow state model.
- **Branch - Canonical Workflow States:** Inbox -> Planned -> Investigating -> Waiting for spec approval -> Executing -> Waiting for Review -> Completed.
- **Branch - MVP Features:** Current position is that all targeted MVP capabilities should be real in the hackathon demo rather than partial or simulated, increasing implementation pressure and raising the bar for any fork candidate.
- **MVP Scope Rule:** All targeted capabilities should be narrow but real. Each feature can be implemented in its smallest legitimate form as long as the end-to-end vertical slice is genuinely operational.
- **Branch - Architecture Options Ranking:** Preferred direction is 1) fork a workflow-native OSS base, 2) build a thin orchestration layer, 3) compose existing tools behind a unified shell.
- **Branch - Ideal Fork Candidate Requirements:** Explicit workflow/state machine, easy Linear integration points, clean agent-runner abstraction, artifact/event logging model, and approval support are the hard requirements for any OSS base to be worth forking.
- **Branch - Risks / Unknowns:** Two immediate risks are that no OSS candidate actually fits the fork criteria, and that approvals plus state transitions become messy or unclear in the UI.
- **Branch - Risks / Unknowns Expansion:** Additional major risks are integration complexity, weak or unconvincing artifact quality, and runner inconsistency between supported agent backends.
- **Technique Transition:** Completed the initial divergent exploration and moved into Mind Mapping to structure the concept into product promise, workflow, architecture, integration, and risk branches.
- **Branch - Integrations:** Required systems and surfaces for the real vertical slice are GitHub or repo access, a human approval UI, a prompt/context store, and artifact storage/view in addition to Linear and agent runners.
- **Mind Mapping Summary:** The concept has crystallized around a governed feature-delivery control plane with explicit workflow states, narrow-but-real capabilities, workflow-native architecture requirements, and a risk-managed preference for forking only if the OSS base already behaves like a delivery workflow engine.
- **Technique Transition:** Moving from Mind Mapping into Morphological Analysis to combine system dimensions into concrete MVP solution concepts.

**Morphological Analysis:**

- **Dimension - Workflow engine options:** Queue/job engine with a workflow layer on top, and a scripted orchestration service with explicit states, are the current viable engine candidates for the MVP.
- **Dimension - Linear intake / sync options:** Poll Linear for selected tickets is the simpler intake option, while full bidirectional sync during every workflow state transition is the more ambitious operational option.
- **Dimension - Task decomposition option:** Fixed workflow template by task type is the selected decomposition model for the MVP, keeping the flow narrow, real, and predictable.
- **Dimension - Agent runner abstraction option:** A single common runner interface with provider adapters is the selected abstraction model for supporting Codex and Claude behind one workflow.
- **Dimension - Approval / checkpoint option:** Spec approval plus final review approval is the selected checkpoint model for the MVP.
- **Dimension - State and event store options:** A Git-backed workflow folder is an attractive option for visibility and auditability, while a conventional application storage layer remains an acceptable pragmatic fallback for runtime workflow state and events.
- **Dimension - Artifact model:** The selected first-class artifacts are a proposed spec, an implementation diff or PR artifact, and a completion summary synced back to Linear.
- **Dimension - UI surface options:** Linear can remain the ticket dashboard, while the orchestration product can be mostly CLI/backend with a thin web review surface for approvals, state visibility, and artifact inspection.
- **Dimension - Failure recovery options:** Retry from the last valid state and resume after fixing context or input are the selected narrow-but-real recovery mechanisms.
- **Dimension - Context handoff options:** Prompt bundles generated from previous artifacts and metadata, plus state summaries with artifact references passed to the next step, define the narrow-but-real context handoff model.
- **Current Direction (2026-04-20):** Best default path is a scripted control plane (`A`). A workflow-native OSS fork (`C`) is worth only a tightly timeboxed spike against candidates that already offer stateful workflows and human approval primitives.
- **Current OSS Readout (2026-04-20):** Kestra appears closest to a workflow-native base because it supports pause/resume approvals, audit trails, and Git-centric workflow management. n8n is the fastest demo accelerator due to built-in Linear and GitHub nodes plus human-review tooling, but its node-based model is a weaker match for a domain-specific workflow state machine. LangGraph is strong on checkpoints, HITL, memory, replay, and failure recovery, but behaves more like a low-level orchestration runtime than a ready-made delivery workflow engine.
- **Decision Checkpoint (2026-04-20):** Recommended strategy is `A by default, C only as a short spike`. Timebox OSS evaluation to one day maximum, beginning with Kestra. If no candidate can express the feature-delivery state model and approval flow by the end of that spike, proceed with the scripted control plane implementation immediately.

## Planning Recommendations

### Architecture Sketch for A

- **Core shape:** A scripted control plane centered on an explicit workflow state machine for `feature-delivery`.
- **Workflow states:** `Inbox -> Planned -> Investigating -> Waiting for spec approval -> Executing -> Waiting for Review -> Completed`.
- **Ingress/Egress:** Poll selected Linear tickets into the workflow engine and sync completion summaries back to Linear.
- **Workflow model:** Fixed template by task type with one initial supported template: `feature-delivery`.
- **Runner layer:** A common runner interface with provider adapters for Codex and Claude.
- **Approvals:** Two mandatory checkpoints: spec approval and final review approval.
- **Artifacts:** Proposed spec, implementation diff or PR artifact, completion summary.
- **Recovery:** Retry from last valid state and resume after fixing context or input.
- **Context handoff:** Prompt bundles built from prior artifacts and workflow metadata plus a compact state summary.
- **Persistence:** Conventional application storage for runtime state/events, with optional Git-backed artifact folders if useful for demo visibility.
- **UI shape:** Linear remains the ticket dashboard; a thin review surface exposes approvals, current state, event history, and artifacts.

### Suggested 10-Day Delivery Sequence

1. **Day 1:** Run the OSS spike against Kestra only. Kill the fork path by end of day if it cannot express the workflow and approvals cleanly.
2. **Day 2:** Finalize workflow schema, event model, state transitions, and artifact contracts.
3. **Day 3:** Implement workflow instance storage, event log, and state transition engine.
4. **Day 4:** Implement Linear polling/import and completion sync-back.
5. **Day 5:** Implement the common runner interface and one provider adapter.
6. **Day 6:** Add the second provider adapter and context handoff bundle generation.
7. **Day 7:** Implement the `Investigating -> spec artifact -> spec approval` path.
8. **Day 8:** Implement the `Executing -> diff/PR artifact -> final review` path plus completion summary generation.
9. **Day 9:** Build the thin approval/review UI and add retry/resume from last valid state.
10. **Day 10:** Rehearse the demo, polish artifacts, harden the happy path, and remove nonessential scope.

## Concrete Implementation Spec

### Goal

Deliver a hackathon-ready orchestration MVP that turns a Linear feature ticket into a governed end-to-end delivery workflow with real state transitions, real agent execution, two human approval gates, visible artifacts, and completion sync back to Linear.

### Scope

- Support exactly one workflow template: `feature-delivery`
- Support exactly two runner adapters: `codex` and `claude`
- Support exactly one intake path: polling selected Linear tickets
- Support exactly one completion path: completion summary synced back to Linear
- Support exactly two approval gates: spec approval and final review approval

### Core User Flow

1. System polls Linear and imports a selected feature ticket into `Inbox`
2. Workflow instance is created and moved to `Planned`
3. Investigation runner step executes and produces a `proposed-spec` artifact
4. Workflow moves to `Waiting for spec approval`
5. Human approves or requests correction
6. Execution runner step runs and produces an `implementation-diff` or `pr-artifact`
7. Workflow moves to `Waiting for Review`
8. Human approves or requests correction
9. System creates a `completion-summary` artifact
10. Workflow moves to `Completed` and syncs summary/state back to Linear

### State Machine

#### States

- `Inbox`
- `Planned`
- `Investigating`
- `WaitingForSpecApproval`
- `Executing`
- `WaitingForReview`
- `Completed`
- `Failed`

#### Allowed Transitions

- `Inbox -> Planned`
- `Planned -> Investigating`
- `Investigating -> WaitingForSpecApproval`
- `WaitingForSpecApproval -> Executing`
- `WaitingForSpecApproval -> Investigating`
- `Executing -> WaitingForReview`
- `WaitingForReview -> Completed`
- `WaitingForReview -> Executing`
- `Investigating -> Failed`
- `Executing -> Failed`
- `Failed -> Investigating`
- `Failed -> Executing`

### Domain Objects

#### WorkflowInstance

- `id`
- `linearIssueId`
- `workflowType` = `feature-delivery`
- `currentState`
- `assignedRunnerPlan`
- `createdAt`
- `updatedAt`
- `lastError`

#### WorkflowEvent

- `id`
- `workflowId`
- `type`
- `fromState`
- `toState`
- `actorType` (`system`, `runner`, `human`)
- `actorId`
- `timestamp`
- `metadata`

#### Artifact

- `id`
- `workflowId`
- `type` (`proposed-spec`, `implementation-diff`, `pr-artifact`, `completion-summary`)
- `uri` or inline payload reference
- `createdBy`
- `createdAt`
- `metadata`

#### ApprovalDecision

- `id`
- `workflowId`
- `approvalType` (`spec`, `review`)
- `decision` (`approved`, `changes_requested`)
- `comment`
- `decidedBy`
- `decidedAt`

#### ContextBundle

- `workflowId`
- `state`
- `ticketSummary`
- `artifactRefs`
- `priorDecisionRefs`
- `promptTemplateId`
- `providerConfig`

### Required Services

#### Workflow Engine

Responsibilities:

- create workflow instances
- enforce state transitions
- dispatch steps based on current state
- emit workflow events
- coordinate recovery from `Failed`

#### Linear Adapter

Responsibilities:

- poll selected tickets
- map Linear issue to workflow input
- sync completion summary and terminal status back to Linear

#### Runner Broker

Responsibilities:

- expose a common interface for `codex` and `claude`
- build execution request from `ContextBundle`
- normalize provider output into artifact payloads and status updates

#### Approval Service

Responsibilities:

- expose pending approvals to UI
- persist approval decisions
- trigger legal next transition after decision

#### Artifact Service

Responsibilities:

- persist artifact metadata and payload references
- expose artifact list per workflow
- build completion summary from prior artifacts

### Runner Interface

Minimal contract:

- `runStep(stepType, contextBundle) -> StepResult`

#### Supported Step Types

- `investigate_feature`
- `implement_feature`

#### StepResult

- `status` (`success`, `failed`)
- `artifacts`
- `summary`
- `rawOutputRef`
- `error`

### Persistence

Minimum required storage:

- workflow instances table or collection
- workflow events table or collection
- artifacts table or collection
- approvals table or collection

Optional:

- Git-backed artifact folder for demo visibility and traceability

### Thin Review UI

The UI only needs three screens:

1. `Pending Approvals`
2. `Workflow Detail`
3. `Artifact Viewer`

#### Pending Approvals

- list workflows waiting for human action
- show approval type, ticket title, runner used, latest artifact

#### Workflow Detail

- show current state
- show ordered event timeline
- show recovery action when in `Failed`
- allow approve or request changes

#### Artifact Viewer

- show proposed spec
- show implementation diff or PR reference
- show completion summary

### Recovery Behavior

#### Retry

- allowed only from `Failed`
- retry returns workflow to last executable state (`Investigating` or `Executing`)

#### Resume After Fix

- human updates input or context
- system records a recovery event
- workflow resumes from the failed executable state

### Non-Goals

- no autonomous workflow planning
- no multiple workflow templates in MVP
- no Slack integration
- no analytics dashboard
- no general-purpose orchestration designer
- no long-term memory system beyond explicit context bundles

### API Definitions

Base path:

- `/api/v1`

#### Workflow APIs

##### Create workflow from Linear issue

- `POST /api/v1/workflows`

Request:

```json
{
  "linearIssueId": "ENG-123",
  "workflowType": "feature-delivery",
  "providerPlan": {
    "investigate": "claude",
    "implement": "codex"
  }
}
```

Response:

```json
{
  "workflowId": "wf_001",
  "state": "Inbox",
  "linearIssueId": "ENG-123",
  "workflowType": "feature-delivery",
  "createdAt": "2026-04-20T10:00:00Z"
}
```

##### List workflows

- `GET /api/v1/workflows?state=WaitingForReview&limit=20`

Response:

```json
{
  "items": [
    {
      "workflowId": "wf_001",
      "linearIssueId": "ENG-123",
      "title": "Add orchestration approval surface",
      "state": "WaitingForReview",
      "workflowType": "feature-delivery",
      "updatedAt": "2026-04-20T12:00:00Z"
    }
  ]
}
```

##### Get workflow detail

- `GET /api/v1/workflows/{workflowId}`

Response:

```json
{
  "workflowId": "wf_001",
  "linearIssueId": "ENG-123",
  "workflowType": "feature-delivery",
  "state": "WaitingForSpecApproval",
  "providerPlan": {
    "investigate": "claude",
    "implement": "codex"
  },
  "lastError": null,
  "createdAt": "2026-04-20T10:00:00Z",
  "updatedAt": "2026-04-20T10:25:00Z"
}
```

##### Start planning transition

- `POST /api/v1/workflows/{workflowId}/plan`

Behavior:

- validates current state is `Inbox`
- transitions workflow to `Planned`
- emits `workflow.planned`

##### Dispatch next step

- `POST /api/v1/workflows/{workflowId}/dispatch`

Behavior:

- if `Planned`, dispatches `investigate_feature`
- if `WaitingForSpecApproval` and latest decision is `approved`, dispatches `implement_feature`
- if `Failed`, rejects unless explicit recovery action was recorded

Response:

```json
{
  "workflowId": "wf_001",
  "dispatchedStep": "investigate_feature",
  "runner": "claude",
  "state": "Investigating"
}
```

##### Retry failed workflow step

- `POST /api/v1/workflows/{workflowId}/retry`

Request:

```json
{
  "reason": "Transient provider error resolved"
}
```

Response:

```json
{
  "workflowId": "wf_001",
  "state": "Investigating",
  "recoveredFrom": "Failed"
}
```

##### Resume after context fix

- `POST /api/v1/workflows/{workflowId}/resume`

Request:

```json
{
  "reason": "Updated implementation constraints",
  "contextPatch": {
    "notes": "Target only backend service layer"
  }
}
```

Response:

```json
{
  "workflowId": "wf_001",
  "state": "Executing",
  "resumeFrom": "Failed"
}
```

#### Approval APIs

##### List pending approvals

- `GET /api/v1/approvals?status=pending`

Response:

```json
{
  "items": [
    {
      "workflowId": "wf_001",
      "approvalType": "spec",
      "state": "WaitingForSpecApproval",
      "ticketTitle": "Add orchestration approval surface",
      "latestArtifactId": "art_001",
      "createdAt": "2026-04-20T10:20:00Z"
    }
  ]
}
```

##### Submit approval decision

- `POST /api/v1/workflows/{workflowId}/approvals`

Request:

```json
{
  "approvalType": "spec",
  "decision": "approved",
  "comment": "Proceed with backend-first implementation",
  "decidedBy": "alex"
}
```

Response:

```json
{
  "workflowId": "wf_001",
  "approvalType": "spec",
  "decision": "approved",
  "nextState": "Executing"
}
```

Rules:

- `spec` approval valid only in `WaitingForSpecApproval`
- `review` approval valid only in `WaitingForReview`
- `changes_requested` sends workflow back to `Investigating` or `Executing`

#### Artifact APIs

##### List artifacts for workflow

- `GET /api/v1/workflows/{workflowId}/artifacts`

Response:

```json
{
  "items": [
    {
      "artifactId": "art_001",
      "type": "proposed-spec",
      "createdAt": "2026-04-20T10:18:00Z",
      "createdBy": "claude",
      "status": "active"
    }
  ]
}
```

##### Get artifact detail

- `GET /api/v1/artifacts/{artifactId}`

Response:

```json
{
  "artifactId": "art_001",
  "workflowId": "wf_001",
  "type": "proposed-spec",
  "payloadRef": "store://artifacts/wf_001/proposed-spec.md",
  "metadata": {
    "runner": "claude",
    "stepType": "investigate_feature"
  },
  "createdAt": "2026-04-20T10:18:00Z"
}
```

#### Event APIs

##### List workflow events

- `GET /api/v1/workflows/{workflowId}/events`

Response:

```json
{
  "items": [
    {
      "eventId": "evt_001",
      "type": "workflow.state_changed",
      "fromState": "Planned",
      "toState": "Investigating",
      "actorType": "system",
      "timestamp": "2026-04-20T10:05:00Z"
    }
  ]
}
```

#### Linear Sync APIs

##### Trigger manual Linear sync

- `POST /api/v1/linear/sync`

Request:

```json
{
  "issueIds": ["ENG-123"]
}
```

Response:

```json
{
  "synced": 1,
  "importedWorkflowIds": ["wf_001"]
}
```

### API Error Model

All endpoints return:

```json
{
  "error": {
    "code": "INVALID_STATE_TRANSITION",
    "message": "Workflow wf_001 cannot transition from WaitingForReview to Planned",
    "details": {
      "workflowId": "wf_001",
      "fromState": "WaitingForReview",
      "attemptedState": "Planned"
    }
  }
}
```

Suggested error codes:

- `INVALID_STATE_TRANSITION`
- `APPROVAL_NOT_ALLOWED`
- `WORKFLOW_NOT_FOUND`
- `ARTIFACT_NOT_FOUND`
- `RUNNER_EXECUTION_FAILED`
- `LINEAR_SYNC_FAILED`
- `VALIDATION_ERROR`

### Data Model Definitions

#### WorkflowInstance Model

```json
{
  "id": "wf_001",
  "linearIssueId": "ENG-123",
  "workflowType": "feature-delivery",
  "state": "WaitingForSpecApproval",
  "providerPlan": {
    "investigate": "claude",
    "implement": "codex"
  },
  "currentStepType": "investigate_feature",
  "lastExecutableState": "Investigating",
  "lastError": null,
  "createdAt": "2026-04-20T10:00:00Z",
  "updatedAt": "2026-04-20T10:20:00Z"
}
```

#### WorkflowEvent Model

```json
{
  "id": "evt_001",
  "workflowId": "wf_001",
  "type": "workflow.state_changed",
  "fromState": "Investigating",
  "toState": "WaitingForSpecApproval",
  "actorType": "system",
  "actorId": "workflow-engine",
  "timestamp": "2026-04-20T10:18:00Z",
  "metadata": {
    "trigger": "artifact_created",
    "artifactId": "art_001"
  }
}
```

#### Artifact Model

```json
{
  "id": "art_001",
  "workflowId": "wf_001",
  "type": "proposed-spec",
  "payloadRef": "store://artifacts/wf_001/proposed-spec.md",
  "createdBy": "claude",
  "createdAt": "2026-04-20T10:18:00Z",
  "metadata": {
    "runner": "claude",
    "stepType": "investigate_feature",
    "contentType": "text/markdown"
  }
}
```

#### ApprovalDecision Model

```json
{
  "id": "apr_001",
  "workflowId": "wf_001",
  "approvalType": "spec",
  "decision": "approved",
  "comment": "Proceed with backend-first scope",
  "decidedBy": "alex",
  "decidedAt": "2026-04-20T10:25:00Z"
}
```

#### ContextBundle Model

```json
{
  "workflowId": "wf_001",
  "state": "Executing",
  "ticketSummary": {
    "id": "ENG-123",
    "title": "Add orchestration approval surface",
    "description": "Build the thin approval UI for workflow checkpoints"
  },
  "artifactRefs": [
    {
      "artifactId": "art_001",
      "type": "proposed-spec"
    }
  ],
  "priorDecisionRefs": [
    {
      "approvalId": "apr_001",
      "approvalType": "spec",
      "decision": "approved"
    }
  ],
  "promptTemplateId": "implement_feature_v1",
  "providerConfig": {
    "provider": "codex"
  }
}
```

### Suggested Event Types

- `workflow.created`
- `workflow.state_changed`
- `workflow.failed`
- `workflow.retried`
- `workflow.resumed`
- `runner.step_dispatched`
- `runner.step_completed`
- `runner.step_failed`
- `artifact.created`
- `approval.requested`
- `approval.recorded`
- `linear.synced`
