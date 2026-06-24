---
stepsCompleted:
  - step-01-init
  - step-02-discovery
  - step-02b-vision
  - step-02c-executive-summary
  - step-03-success
  - step-04-journeys
  - step-05-domain
  - step-06-innovation
  - step-07-project-type
  - step-08-scoping
  - step-09-functional
  - step-10-nonfunctional
  - step-11-polish
  - step-12-complete
inputDocuments:
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\brainstorming\brainstorming-session-2026-04-18-210302.md
  - C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\implementation-spec-2026-04-20-agent-orchestration.md
workflowType: 'prd'
documentCounts:
  productBriefs: 0
  research: 0
  brainstorming: 1
  projectDocs: 0
  implementationSpecs: 1
classification:
  projectType: developer_tool
  domain: governed AI agent orchestration for software delivery teams
  bmadDomainBucket: general
  complexity: medium-high
  projectContext: brownfield
---

# Product Requirements Document - DeliveryLine

**Author:** Alex
**Date:** 2026-04-22

## Executive Summary

Software delivery teams want to use coding agents on real work in existing codebases, but current options break down in practice. Orchestration products are often too complex for everyday delivery, while direct agent usage is too opaque to manage reliably when work fails, changes hands, or needs review. Teams can experiment with agents, but they still lack a practical operating model for using them across shared delivery workflows.

This product provides a governed execution layer for engineering work in existing codebases. It gives teams a practical way to delegate agent-driven work without losing visibility or control, making execution observable, recoverable, and manageable when failures or handoffs occur.

The product is designed for software delivery teams working in brownfield environments where team-scale coordination overhead slows delivery. Its value is not generic automation. It replaces ad hoc agent usage and heavyweight orchestration with a more practical model for repeatable day-to-day execution.

The MVP thesis is that teams will trust and repeat a governed, recoverable workflow for low-risk delivery more than autonomous agent output they cannot inspect or safely recover.

The first release proves this model through one low-risk ticket workflow: intake, specification review, implementation output / PR artifact, developer review or takeover, visible run history, and merge-ready handoff.

## Project Classification

- **Project Type:** Developer tool
- **Domain:** Governed AI agent orchestration for software delivery teams
- **Complexity:** Medium-high
- **Project Context:** Brownfield

## Success Criteria

### User Success

- A pilot delivery team can complete a defined low-risk ticket workflow through the product from ticket intake to merge-ready PR with visible status at each stage and at least one human feedback loop before merge.
- Users can inspect every run and answer the operational questions that matter: what stage the ticket reached, who or what acted, what artifacts were produced, where failure occurred, and where intervention happened.
- Users can provide structured feedback on the generated specification and on the generated PR inside the governed flow.
- Low-risk tickets can be delegated through the workflow without losing stage visibility, intervention points, or recovery context.

**Working definition: low-risk ticket**
- bounded implementation scope
- no irreversible production data change
- no security-critical or compliance-critical change
- no cross-team dependency required for completion
- suitable for normal code review and merge in the existing team process

### Business Success

- Within the first 8 weeks of pilot use, one development team routes at least 20% of eligible low-risk tickets, or at least 5 low-risk tickets per sprint, through the workflow.
- Median cycle time for eligible low-risk tickets does not regress during pilot adoption, and shows measurable improvement versus baseline once the team has used the workflow across at least 2 full sprints.
- Requirement-related rework decreases versus baseline, using a tagged review taxonomy for missing scope, unclear specification, or misunderstood implementation intent.
- The pilot team uses the workflow for real delivery work across multiple sprints, not only for isolated demonstrations.

**Working definition: baseline**
- the same team’s current delivery process before adoption
- measured on eligible low-risk tickets
- compared over the previous 2 sprints or equivalent recent delivery window

### Technical Success

- All workflow runs in pilot scope store an inspectable audit record including:
  - workflow stages
  - timestamps
  - acting human or agent
  - produced artifacts
  - failure reason, if any
  - intervention points
  - final status
- All ticket runs in pilot scope record resource usage. For first release, this means at minimum wall-clock duration and agent execution metadata; token or cost data is included when technically available.
- Failed runs can be assigned to a defined failure taxonomy so the team can review patterns across tickets.

**Initial failure taxonomy**
- specification gap
- context gap
- agent execution failure
- review rejection
- integration or merge failure
- tooling or infrastructure failure

- Specification-stage and PR-stage feedback are captured, linked to the run record, and visible in the execution history for every pilot-scope ticket that enters review.

### Measurable Outcomes

- At least one pilot team uses the workflow for real low-risk tickets within the first 8 weeks.
- At least 20% of eligible low-risk tickets, or 5 per sprint, run through the workflow during pilot use.
- Median cycle time for eligible low-risk tickets improves versus baseline after stable pilot adoption.
- All pilot-scope runs have inspectable execution records and recorded resource usage.
- Failed runs are categorized consistently enough to identify repeated failure patterns across tickets.

## Product Scope

### MVP - Minimum Viable Product

- One governed workflow for low-risk tickets.
- Multiple governed projects can be configured, each with its own repository, ticket-source and repository-host connectors, credentials, and run options; every run is scoped to one project.
- Ticket moves through a visible flow from intake to specification, implementation output / PR artifact, review feedback, merge-ready handoff, and closure.
- The system orchestrates workflow state, agent execution, artifact visibility, and feedback capture.
- Humans remain responsible for approval, review feedback, and final merge decisions.
- Every run is inspectable and recoverable enough for real pilot use on low-risk tickets.

This PRD defines product outcomes, governance boundaries, handoff expectations, and trust requirements. It does not define runner internals, storage technology, orchestration implementation strategy, or agent execution mechanics.

### Growth Features (Post-MVP)

- Teams can define and operate their own delivery flow in the product.
- Additional team roles such as QA, tech leads, or release reviewers can join the governed workflow with structured feedback linked to ticket execution.
- Failures are categorized and analyzed across tickets and across team members.
- The product supports stronger team-level adaptation of flow, review, and intervention patterns.

### Vision (Future)

- Teams can monitor delivery performance across the workflow.
- The product can identify bottlenecks and recurring failure points.
- Teams can improve flow design, assignment, review, and recovery using workflow data.
- If resource-usage signals prove reliable, the system can support ticket prioritization and planning decisions.
- The product becomes a continuous improvement layer for agent-assisted software delivery, not only a ticket execution tool.

## User Journeys

**Release-stage boundary**
- `MVP` covers PM review, developer takeover, visible run history, structured feedback, and recoverable execution for low-risk tickets.
- `Growth` adds QA participation and broader quality analysis across tickets.

### MVP Journey 1: Product Manager

**Actor**
Alex, Product Manager

**Goal**
Submit a low-risk ticket, review generated scope, and decide whether the outcome is acceptable from a product perspective without losing visibility into the workflow.

**Steps**
- Alex submits a low-risk ticket into the governed workflow.
- The system generates a specification artifact.
- Alex reviews the specification and either approves it or rejects it with structured feedback.
- The workflow advances to implementation only after the specification is acceptable from a product perspective.
- Alex later reviews the implementation output and PR artifact from a product perspective.
- Alex either accepts the outcome or sends it back with structured feedback.

**Approval boundary**
- Alex has authority to accept or reject the product meaning of the specification and implementation outcome.
- Alex does not perform the technical merge or operational workflow repair.

**Failure and recovery**
- If the specification misses scope, Alex rejects it and provides structured feedback.
- If the implementation no longer reflects approved scope, Alex rejects the output and routes it back through the governed flow.
- If the work is rejected repeatedly with no useful progress, the workflow must support escalation to a human owner instead of endless looping.
- Alex must be able to see what changed after each feedback cycle and why.

**Outcome**
- Alex can approve or reject work with a clear decision point, visible history, and preserved context instead of relying on opaque agent behavior.

### MVP Journey 2: Developer

**Actor**
Nina, Developer

**Goal**
Review agent-produced work, decide whether it is technically acceptable, and take over directly when partial output is useful but not merge-ready.

**Steps**
- Nina opens the ticket workflow and reviews the approved specification, implementation context, and PR artifact.
- She checks whether the output aligns with the latest approved requirements and technical expectations.
- If the output is acceptable, she moves it forward through the team’s normal technical process.
- If the output is partially useful but incomplete, she takes over, edits or completes the work, and preserves workflow history.

**Takeover boundary**
- Takeover means a human can continue work without losing prior artifacts, feedback history, stage state, or failure context.
- The system must preserve the audit trail after human modification or completion.

**Failure and recovery**
- If the implementation plan misses an edge case, Nina records technical feedback and routes the work back.
- If review feedback was already given, Nina must see the latest approved state and prior changes before acting.
- If takeover is required, the workflow must preserve ownership transition and context rather than forcing a restart.

**Outcome**
- Nina can safely review, correct, or complete work inside a governed flow instead of choosing between blind approval and full manual reconstruction.

### MVP Journey 3: Operations / Workflow Owner

**Actor**
Oleg, Operations / Workflow Owner

**Goal**
Monitor workflow health, diagnose failed or stalled runs, and recover broken workflow or integration state in the first release.

**Steps**
- Oleg monitors active and failed runs.
- He inspects run records, including stage history, timestamps, actors, artifacts, failures, and intervention points.
- When a run fails or stalls, he determines whether the issue is workflow-state related, input/context related, agent-related, or integration/state-sync related.
- He recovers or reconciles the workflow state so the run can continue or be closed cleanly.

**Role boundary**
- In first release, this role combines workflow ownership and integration ownership.
- The role is operational: monitoring, diagnosis, recovery, and configuration-level correction.
- It does not imply a full analytics platform or future admin suite.

**Failure and recovery**
- If ticket ingestion creates incomplete workflow state, Oleg must be able to inspect and reconcile it.
- If state sync fails between systems, he must be able to determine what the correct current state is.
- If repeated failures occur, he must be able to classify them consistently enough to support recovery and operational learning.

**Outcome**
- Oleg can recover broken or stalled workflow runs with clear diagnostic evidence rather than relying on guesswork.

### Growth Journey 4: QA / Quality Participant

**Actor**
Irina, QA / Quality Participant

**Goal**
Review ticket outcomes with full execution context and provide structured quality feedback linked to the governed workflow.

**Steps**
- Irina enters the workflow after the engineering loop is functioning.
- She reviews the ticket history, approved specification, implementation artifact, and prior review context.
- She records structured QA feedback linked directly to the run.

**Growth boundary**
- QA participation is not MVP-critical.
- Broader cross-ticket quality pattern analysis belongs to Growth, not MVP.

**Failure and recovery**
- If QA finds an issue, the feedback must remain attached to the same governed history rather than being lost in a separate tool.
- If multiple similar issues emerge later, Growth-stage analysis can use categorized feedback across tickets.

**Outcome**
- QA becomes a structured participant in the governed workflow rather than a disconnected downstream checkpoint.

### Cross-Journey Capability Summary

**MVP-required capabilities**
- governed ticket initiation for low-risk work
- visible run state and artifact history
- explicit accept/reject decision points for PM review
- structured feedback at specification and PR stages
- preserved context across review cycles
- human takeover with preserved audit trail
- inspectable run records with stage, actor, artifacts, timestamps, failure reason, intervention point, and final status
- recoverable execution for failed or stalled runs
- operational diagnosis and state recovery for workflow/integration failures

**Growth-stage capabilities**
- QA participation inside the governed workflow
- structured QA feedback linked to run history
- categorized quality issues across tickets when Growth analytics are added

## Domain-Specific Requirements

This product must preserve workflow integrity, traceability, and recoverability across agent-driven delivery work in existing codebases. The domain-specific requirements focus on governance, inspectability, integration conflict handling, retention, and recovery from partial failure.

This section defines governance principles for the domain. Measurable quality gates are captured later in Non-Functional Requirements.

### Governance & Accountability

- Every run must produce an append-only audit history for human, agent, and system actions.
- Each audit entry must include at minimum:
  - actor identity
  - actor type (`human`, `agent`, `system`, or `service account`)
  - action type
  - timestamp
  - workflow context
  - prior state
  - resulting state
  - linked artifact or reference, when applicable
  - reason for intervention or override, when applicable
- Approval boundaries must be explicit:
  - product approval authority for specification acceptance
  - technical approval authority for implementation acceptance
  - operational authority for recovery and reconciliation actions
- The product must record whether an action was system-generated, agent-executed, human-approved, or human-overridden.
- Audit history must be queryable by ticket and by run.

### Technical Constraints

- Every agent-initiated change must create a linked run record with actor, stage, input artifact, output artifact, and outcome.
- Every run must expose enough information for an operator or reviewer to determine:
  - who acted
  - what changed
  - which stage is current
  - what the last successful step was
  - what failed, if anything
  - what the next safe action is
- Minimum inspection data must include actor, timestamp, workflow stage, linked artifact, prior state, resulting state, and failure or intervention marker when applicable.
- MVP recovery must support:
  - manual takeover
  - rerun of a failed or rejected step
  - state reconciliation after detected integration conflict
- Human takeover must preserve prior context, artifacts, feedback history, and audit history.
- The system must degrade safely during interruptions: it may pause or block progression, but must not lose run history or silently advance state.
- Failure taxonomy must be centrally governed and consistently applied.

### Integration Requirements

- The product must define source of truth by entity type, including:
  - ticket identity
  - workflow state
  - artifact linkage
  - PR status
  - review status
- The system must detect conflicts between internal workflow state and external system state.
- Silent overwrite of conflicting state is prohibited.
- On detected conflict, the system must surface the conflict to an operator or reviewer and support explicit reconciliation.
- Sync failures, link failures, and state conflicts must be distinguishable in the operational record.
- Integration failure handling must support:
  - retry when safe
  - pause when state is uncertain
  - manual repair or reconciliation when required
- Ticket-source and repository-host integrations are pluggable per project; Linear and GitHub are the first-release reference kinds and define the initial conflict model.
- Each project resolves its own ticket-source and repository-host connector and credentials; the same conflict, sync-failure, and reconciliation rules apply per project.

### Recovery & Failure Handling

- On partial failure, the system must preserve:
  - current state
  - last known good state
  - relevant artifacts
  - prior feedback
  - next safe action
- The product must make stalled, failed, and manually overridden runs visible to operators and reviewers.
- Escalation and recovery actions must be recorded in the same governed history as normal execution.
- The product must support classification of failed runs using a governed failure taxonomy.

### Retention & Governance Requirements

- The product must define artifact-retention and run-history-retention policies for the first release.
- Retention rules must support:
  - active review
  - recovery from failure
  - investigation across multiple tickets
  - pattern analysis across sprint windows
- Retention policy must define:
  - minimum retention duration
  - configurable retention window, if supported
  - policy owner
  - behavior for archived or deleted records
- Failure taxonomy governance must define:
  - ownership
  - allowed edits
  - change control
  - whether taxonomy changes are versioned
  - how historical runs remain interpretable when taxonomy evolves

## Differentiation & Operating Model Hypothesis

### Candidate Differentiation Areas

The product's strongest differentiating idea is its operating model: it governs delivery at the ticket level through explicit feedback, approval, and recovery loops rather than centering work around isolated agent sessions.

Its clearest differentiating emphasis is not raw autonomy, but visibility and recoverability. The product hypothesis is that software delivery teams working in existing codebases may adopt agent-assisted delivery more effectively when execution is visible, reviewable, and recoverable than when orchestration primarily optimizes for autonomy or power.

The product also appears differentiated in how it matches existing team behavior. Instead of asking teams to adopt a new agent-centric operating pattern, it fits agent work into ticket-driven delivery, human approval boundaries, and structured handoffs.

### Market Context & Competitive Landscape

The current working conclusion is that this is likely not a new category. The stronger and more defensible claim is that it may represent a better-fit operating model for a specific segment: software delivery teams in brownfield codebases that need governance, visibility, and recovery more than orchestration breadth.

The relevant market tradeoff remains:
- heavyweight orchestrators may offer power, but are too complex for day-to-day delivery
- direct agent usage may be flexible, but is too opaque to trust at team scale

The potential differentiation is therefore practical rather than category-creating:
- ticket-centric governance
- explicit human feedback and approval loops
- visible execution history
- recoverable failure handling
- low-risk delivery as the first operational wedge

### Validation Approach

This hypothesis should be validated by comparison and adoption behavior, not by novelty language.

Compare the product against:
- direct agent usage
- heavyweight orchestrators
- lightweight workflow wrappers

Evaluate each option on:
- setup overhead
- ticket-level governance
- traceability
- recovery quality
- handoff clarity
- team trust
- repeated adoption after pilot use

Run a low-risk ticket pilot and measure whether teams continue to use this model after initial trial, not just whether they prefer it in theory.

The central product hypothesis to test is:
- teams may value visibility, recoverability, and governed handoff more than maximum agent autonomy in real delivery workflows

The kill test for the stronger differentiation claim is:
- if existing tools already provide comparable ticket governance, traceability, and recovery with similar setup cost, the novelty claim should be dropped and the product should be positioned as execution quality in an under-served segment rather than as a distinct operating model.

### Risk Mitigation & Fallback Positioning

If the stronger differentiation claim proves weak, the product still has credible fallback positioning.

- Fallback 1: a governed, low-friction execution layer for brownfield delivery teams that need visibility and recovery more than orchestration breadth
- Fallback 2: a governed workflow wrapper around existing agent tooling
- Fallback 3: a focused low-risk ticket delivery product with strong inspectability and recovery, without broader operating-model claims

This keeps the PRD honest. The product does not need to win by proving a new category. It can still win by solving a real delivery problem more clearly and more safely than current alternatives.

## Developer Tool Specific Requirements

### Project-Type Overview

This product is a local-first developer tool for governed ticket delivery in existing codebases. In first release, local-first is the MVP deployment shape: the workflow runs from a pilot user's development environment, but still produces inspectable artifacts and run history that can be shared for team review.

### Technical Architecture Considerations

**Local execution model**
- The first-release workflow runner operates from a local development machine.
- The product must define what runs locally, what is persisted locally, and what can be exported or shared for team review.
- The product must support interruption-safe local execution so that runs can be inspected and resumed after interruption.

**Runner abstraction model**
- The product must support a common runner abstraction so familiar coding agents can operate inside the governed workflow without becoming the workflow model themselves.
- The first release must define a minimal runner contract for dispatching a workflow step, receiving normalized output, recording artifacts, and recording execution failure.
- Runner-specific raw output may be retained for troubleshooting, but workflow decisions must depend on normalized workflow events, artifacts, and review states.

**Context handoff model**
- The product must use explicit context bundles to hand work between workflow stages, agents, and humans.
- A context bundle must include the current workflow state, ticket summary, approved specification or latest review decision, relevant artifact references, prior feedback, and execution constraints needed for the next step.
- Context bundles must be inspectable enough for a human reviewer or developer to understand what context was given to the agent before acting.

**Team-visible review model**
- Local execution must still allow other team members to inspect workflow state, artifacts, and run history during pilot use.
- The first release must define a concrete sharing model for run history and artifacts generated from local execution.

**Local-tool constraints**
- Credential handling for Linear, GitHub, and agent providers must avoid committing secrets to the repository.
- Configuration must remain isolated by user and repository so local pilot usage does not leak state across projects.
- The tool must expose enough local logs and run metadata for troubleshooting without centralized operations tooling.
- The tool must define how persisted local artifacts, logs, and run state are stored and recovered.
- The product must define the pilot environment assumptions it supports in first release.

### Installation & Adoption Model

- The first-release installation path must optimize for pilot adoption, not enterprise deployment.
- A pilot user must be able to install the tool, configure credentials, connect the required integrations, run one low-risk ticket through the governed workflow, and inspect the resulting run history through a single guided onboarding path.
- The setup path must minimize dependencies and avoid requiring platform engineering support.
- First-run onboarding should be measurable by a clear pilot success path, not just by install completion.

### Integration Surface

- Linear intake is a required first-release integration.
- GitHub / PR linkage is a required first-release integration.
- The product must support governed ticket flow using these systems as the initial external anchors.
- Integration behavior in MVP should prioritize low setup overhead over real-time sophistication.
- Polling is acceptable in first release if it materially reduces setup burden, as long as sync freshness and failure detection remain understandable to the pilot team.
- Manual fallback must preserve workflow continuity when integration automation fails. In first release, this means preserving:
  - ticket linkage
  - PR or artifact linkage
  - run identity
  - recovery action history
- The product must define what happens when sync, linking, or network connectivity fails, including whether the system retries, pauses, or requires manual repair.

### Documentation & Adoption Requirements

- Documentation must support first-run completion by a pilot user without live assistance.
- Minimum documentation deliverables:
  - first-run quickstart
  - low-risk ticket tutorial
  - failure recovery walkthrough
- Documentation must explain:
  - local setup
  - credential handling
  - governed ticket workflow behavior
  - approval boundaries
  - visible execution and artifact history
  - recovery actions and fallback behavior

### Migration & Rollout Path

- The initial migration path is from direct ad hoc agent usage to a governed ticket workflow.
- Early adopters should be able to keep using familiar coding agents through the governed workflow rather than replacing them immediately.
- The migration path should preserve familiar ticket management and PR review habits wherever possible.
- The first rollout is a pilot constraint: one team, low-risk tickets, governed workflow.

### Implementation Considerations

- The MVP should optimize for learnability, inspectability, and pilot repeatability.
- The product should support repeatable workflow execution within the supported pilot environment.
- The product should make recovery, troubleshooting, and artifact inspection core behavior rather than secondary tooling.
- The MVP should avoid choices that block later hosting, but hosted platform concerns are not part of the first-release requirement set.

## Project Scoping & Phased Development

This section specifies MVP scope in detail. Growth and Vision items are directional expansion paths, not first-release commitments.

### MVP Strategy & Philosophy

**Single MVP proof point**
Phase 1 proves one thing: a low-risk ticket can move through a governed workflow from intake to merge-ready handoff with visible history, human feedback, developer takeover, and minimum recovery.

**What Phase 1 validates**
- teams can trust governed ticket delivery more than ad hoc agent usage for low-risk work
- teams can inspect what happened when work succeeds, fails, or changes hands
- teams will use the governed loop repeatedly, not just once as a demo

**Phase 1 is explicitly not**
- a general orchestration platform
- a hosted control plane
- a team-configurable workflow builder
- a cross-ticket analytics product
- a QA-centric workflow
- a deep recovery automation system
- a resource-based planning or prioritization product
- a multi-user product: configuring multiple projects does NOT introduce multi-user authentication, role-based access control, or tenant isolation — these remain post-MVP. Projects are configuration records operated by a single local operator, not access-control tenants.

**MVP Approach:** validated-learning MVP
The first release exists to validate governed ticket delivery as a better operating model for low-risk work. It does not need to prove the full long-term vision.

### MVP Feature Set (Phase 1)

**Core User Journeys Supported**
- Product Manager submits a low-risk ticket, reviews generated specification, gives feedback, and confirms product acceptability
- Developer reviews implementation output, takes over when needed, and gets to merge-ready handoff

**Must-Have Capabilities**
- low-risk ticket intake
- specification generation with PM feedback loop
- implementation output generation
- PR linkage to reviewed implementation output
- developer review and human takeover
- visible run history
- Linear linkage (first-release reference ticket-source connector)
- GitHub linkage (first-release reference repository-host connector)
- multi-project configuration (define, edit, disable governed projects)
- per-project selectable connector types (pluggable ticket-source / repo-host kinds)
- per-project credentials stored encrypted at rest, never exposed or exported

**Phase 1 trust floor**
- every failed run must show where it stopped
- preserved run history must remain visible after failure or intervention
- every manual intervention must be recorded
- manual retry or rerun of the current step must not erase prior state
- human takeover must preserve artifact lineage and context

**Phase 1 scope rules**
- the MVP remains successful if some transitions require manual operator action
- allowed manual transitions in Phase 1 are:
  - rerun of the current failed or rejected step
  - manual continuation after takeover
  - manual reconciliation of visible integration failure
- Phase 1 may succeed with implementation output plus PR linkage even if deeper PR automation is deferred
- if a capability is not required for ticket intake, specification feedback, developer takeover, visible history, or minimum recovery, it is out of Phase 1

### Post-MVP Features

**Phase 2: Reliability and Adoption Expansion**
- richer recovery from failed or stalled runs
- QA participation in the governed workflow
- configurable team-defined flows
- additional participant views for QA, technical reviewers, and workflow owners
- limited cross-ticket analysis for recurring workflow and failure patterns

**Phase 3: Analytics and Optimization Expansion**
- workflow performance monitoring
- bottleneck and failure-point detection
- workflow improvement based on accumulated run data
- resource-based prioritization if the signal quality proves reliable

### Risk Mitigation Strategy

**Technical Risks**
- local-first execution may make team-visible review harder than expected
- Linear, GitHub, and workflow linkage may drift or fail in ways that reduce trust
- teams may need more recovery depth earlier than planned

**Mitigation Approach**
- keep Phase 1 narrow and centered on one governed workflow
- require visible failed state, preserved history, and manual intervention from the start
- if local-first sharing is too hard, keep the pilot narrower and rely on exported/shared run history rather than expanding architecture
- if integration linkage becomes the bottleneck, reduce the workflow to the smallest governed loop that still preserves traceability

**Market Risks**
- teams may still prefer raw autonomy over governed visibility
- one workflow may feel too narrow to justify adoption

**Validation Approach**
- test one complete PM -> spec -> dev review -> merge-ready handoff loop on low-risk tickets
- compare pilot behavior against direct ad hoc agent usage
- measure whether teams actually repeat usage after the first successful run

**Resource Risks**
- Phase 1 may drift into platform scope through recovery, analytics, or multi-role support

**Contingency Approach**
- if Phase 1 becomes too broad, narrow it to the smallest governed ticket flow with:
  - intake
  - specification feedback
  - implementation output
  - developer takeover
  - visible history
  - minimum recovery

## Functional Requirements

The following functional requirements translate the scoped MVP workflow into a capability contract for downstream design, architecture, and story creation.

These functional requirements define the Phase 1 capability contract for one governed low-risk ticket workflow. They intentionally exclude workflow configurability, cross-ticket analytics, QA-stage participation, deep recovery automation, and hosted control-plane capabilities. Their purpose is to support visible execution, structured feedback, developer takeover, minimum recovery, and traceable handoff to a merge-ready result.

### Workflow Initiation & Ticket Governance

- **FR1:** Product Managers can initiate a governed workflow from a low-risk ticket reference.
- **FR2:** Product Managers can associate a governed workflow run with a source ticket reference.
- **FR3:** The system supports one governed low-risk ticket workflow in Phase 1.
- **FR4:** Product Managers can see the current workflow stage for each governed ticket.
- **FR5:** Authorized users can view the linkage between a ticket, its workflow run, and its related artifacts.
- **FR6:** Authorized users can see the current pending action required to move a governed ticket forward.

### Specification Capture & Product Approval

- **FR7:** Product Managers can capture or review a specification for a governed ticket.
- **FR8:** Product Managers can approve a specification for progression to implementation.
- **FR9:** Product Managers can reject a specification and provide structured feedback.
- **FR10:** Authorized users can see the currently approved specification state for a governed ticket.
- **FR11:** Authorized users can review prior specification states and changes before approving a revision.
- **FR12:** The workflow can prevent implementation progression until a specification has been accepted from a product perspective.
- **FR13:** The workflow can expose unresolved specification loops for human escalation.

### Implementation Output & Developer Review

- **FR14:** Developers can access the approved specification and related workflow context before reviewing implementation output.
- **FR15:** Developers can review implementation output associated with a governed ticket.
- **FR16:** Developers can accept implementation output as technically ready for merge-ready handoff.
- **FR17:** Developers can reject implementation output and provide structured technical feedback.
- **FR18:** Developers can take over a governed ticket after agent-produced work without losing prior workflow context.
- **FR19:** The workflow can preserve artifact lineage and run history across developer takeover.
- **FR20:** Authorized users can see the relationship between implementation output, PR linkage, and review outcome.
- **FR21:** Authorized users can see separate product acceptance and technical acceptance states for a governed ticket.

### Run History, Visibility & Inspectability

- **FR22:** Authorized users can inspect the stage-by-stage history of a governed run.
- **FR23:** Authorized users can see who or what acted at each workflow step.
- **FR24:** Authorized users can see what artifacts were produced or changed during a run.
- **FR25:** Authorized users can see prior state, resulting state, and intervention markers for workflow actions.
- **FR26:** Workflow Owners can inspect active, failed, stalled, and manually overridden runs.
- **FR27:** Authorized users can determine what changed after each feedback cycle.
- **FR28:** Authorized users can see why a workflow step changed state after feedback, intervention, or recovery action.
- **FR29:** Workflow Owners can query audit history by ticket and by run.

### Failure Handling, Recovery & Reconciliation

- **FR30:** Authorized users can see when a run has failed or stalled and where it stopped.
- **FR31:** Workflow Owners can rerun a failed or rejected workflow step without erasing prior history.
- **FR32:** Workflow Owners can record retry or rerun actions as recovery actions linked to the failed step.
- **FR33:** Developers can continue a workflow manually after takeover while preserving prior run context.
- **FR34:** Workflow Owners can record recovery actions in the same governed history as normal execution.
- **FR35:** Workflow Owners can reconcile workflow state when an integration conflict is detected.
- **FR36:** Authorized users can see the current state, last known good state, and next safe action during recovery.
- **FR37:** Workflow Owners can apply a failure category to each failed pilot-scope run.
- **FR38:** Workflow Owners can apply and review a governed failure taxonomy for failed runs.

### Integration & State Integrity

- **FR39:** The workflow can link governed tickets to Linear ticket references.
- **FR40:** The workflow can link governed implementation output to GitHub / PR references.
- **FR41:** Workflow Owners can detect disagreement between internal workflow state and external integration state.
- **FR42:** Workflow Owners can review integration conflicts without silent overwrite of conflicting state.
- **FR43:** Workflow Owners can distinguish sync failures, link failures, and state conflicts in the operational record.
- **FR44:** Workflow Owners can manually preserve ticket linkage, artifact linkage, and recovery history when automated integration behavior fails.

### Governance, Accountability & Approval Boundaries

- **FR45:** The system can record whether an action was system-generated, agent-executed, human-approved, or human-overridden.
- **FR46:** Authorized users can see which role approved a specification, which role approved implementation output, and which role performed recovery actions.
- **FR47:** Authorized users can inspect an append-only history of human, agent, and system actions for each run.

### Local-First Pilot Use, Runner Abstraction & Context Handoff

- **FR48:** Pilot users can operate the governed workflow from a local-first environment in Phase 1.
- **FR49:** The workflow can preserve run state and history across local interruptions.
- **FR50:** Team members can access shared run history and artifacts generated from local-first workflow execution in a form suitable for review.
- **FR51:** Pilot users can use familiar coding agents within the governed workflow rather than replacing them with a new agent interface.
- **FR52:** Workflow Owners can inspect local-first run records and exported history without requiring centralized operations tooling.
- **FR53:** The workflow can dispatch agent work through a common runner abstraction that records normalized output, artifacts, and failure state.
- **FR54:** The workflow can create context bundles from ticket data, approved specifications, prior feedback, artifact references, and workflow state for use by later workflow steps.
- **FR55:** Authorized users can inspect the context bundle used for an agent step when reviewing output, diagnosing failure, or taking over work.

### Multi-Project Configuration & Connector Management

- **FR56:** Operators can define and manage multiple governed projects (create, edit, disable).
- **FR57:** Each project carries its own repository binding, ticket-source connector, repository-host connector, and run options (including OpenSpec authoring mode).
- **FR58:** Operators can select the connector type per project; the ticket source and repository host are pluggable kinds, with Linear and GitHub as the first-release reference kinds.
- **FR59:** The system can store per-project connector credentials securely (encrypted at rest) and never exposes or exports credential values.
- **FR60:** Every governed workflow run is scoped to exactly one project; its repository, connectors, and run options are resolved from that project.
- **FR61:** Operators can validate a project's connectivity — repository reachable, ticket-source and repository-host authentication — before running governed work against it.
- **FR62:** Existing single-project configuration migrates transparently to a default project so prior governed flows continue unchanged.
- **FR63:** Authorized users can see which project a governed run, ticket, and artifact belong to.

### Per-Step Execution Control, Observability & Manual Execution

- **FR64:** Operators can configure a per-project reviewer model so each workflow step's
  output is reviewed by a different LLM than produced it; the reviewer's verdict is advisory
  (surfaced to the human reviewer) and the configuration supports per-project gating later.
- **FR65:** Operators can view a step's container execution logs both while the step is
  running and after it has finished.
- **FR66:** Operators can execute a workflow step manually — the system emits the step's
  context bundle, parks the run awaiting manual execution, and accepts the operator-produced
  result back through the same validation and review pipeline as automated runners.
- **FR67:** Operators can hide or archive obsolete executions (for example, when the source
  ticket is removed) without erasing append-only audit history.
- **FR68:** Operators can open a read-only diagnostic console into a running runner container;
  console activity is recorded in governed history.
- **FR69:** After a step executes, operators can see the agent provider's usage/limit status
  (for example, the 5-hour rolling window and weekly limits) where the provider exposes it.

### Complex Ticket Flow (Split, Dependencies & Portfolio Visibility)

- **FR70:** Operators can split a governed run into multiple smaller governed subtasks at the
  specification-approval or implementation-review gate. An LLM proposes the decomposition; the
  operator can approve it, continue as a single ticket, or re-propose with feedback. On approval
  the system creates one child run per subtask — creating a source sub-ticket where the ticket
  connector supports it, otherwise proceeding internal-only — and preserves parent→child lineage
  in the governed history. Subtasks may themselves be split recursively, subject to a configurable
  depth limit; a split run is treated as decomposed and pending until all of its descendant runs
  complete, at which point it rolls up to a completed state — preserving full multi-level lineage.
- **FR71:** Operators can declare execution dependencies between governed runs so a dependent run
  does not start until all its prerequisite runs complete; the dependency graph is acyclic and a
  blocked run is held in an explicit waiting state until released. A split prerequisite run
  satisfies its dependents once all of its descendant runs complete (rollup); a failed descendant
  holds the rollup, leaving dependents blocked and operator-visible with no cascade.
- **FR72:** Authorized users can see each run's project attribution in the run review queue and
  filter the queue by project.

## Non-Functional Requirements

The following non-functional requirements define the trust floor for the MVP: inspectability, recovery, identity integrity, safe local operation, and clear handoff context.

### Reliability, Recovery & Inspectability

- **NFR1:** A run must use explicit states: `running`, `paused`, `failed`, `taken_over`, `reconciled`, and `completed`.
- **NFR2:** A workflow run must preserve current state, last safe checkpoint, last durable event, produced artifacts, and audit history after interruption, restart, or agent failure.
- **NFR3:** A failed or stalled run must expose failed stage, last successful stage, failure category, last activity time, and next safe action.
- **NFR4:** Retry, rerun, manual takeover, and reconciliation actions must append history and must never erase or mutate prior history.
- **NFR5:** When workflow or integration state is uncertain, the system must pause progression and require explicit human recovery.
- **NFR6:** Durable workflow events must be written so interruption does not leave the run unreadable or partially corrupted.
- **NFR7:** A reviewer must be able to answer what happened, what changed, who acted, what failed, and what is next from the inspection view without reading raw agent logs first.

### Security, Redaction & Share Boundaries

- **NFR8:** Linear, GitHub, and agent-provider credentials must not be committed to the repository or stored in generated artifacts.
- **NFR9:** Local configuration and credentials must be scoped by user and repository.
- **NFR10:** Shared/exported run artifacts must redact secrets, private tokens, and unnecessary local machine paths by default.
- **NFR11:** The local store must distinguish private working data from shareable/exported review data.
- **NFR12:** Human, agent, and system actions must be attributable to an actor identity or service identity in the audit trail.
- **NFR13:** The MVP must define which data is safe to share with teammates and which data remains local-only.
- **NFR14:** Context bundles prepared for agent execution must avoid including credentials, private tokens, or unrelated local-only data.

### Integration & Identity Integrity

- **NFR15:** The system must define the system of record for run state, ticket identity, repository identity, branch/commit lineage, artifact linkage, and PR linkage.
- **NFR16:** One governed run must map to one ticket, one repository context, and one implementation lineage unless a human explicitly reconciles the record.
- **NFR17:** Linear ticket linkage and GitHub/PR linkage must be durable enough that a reviewer can reconstruct which ticket, repo, branch/commit lineage, artifacts, and PR belong to a run.
- **NFR18:** Integration writes and sync operations must be idempotent where practical and must detect conflicts before changing workflow state.
- **NFR19:** The system must not silently overwrite conflicting internal and external state.
- **NFR20:** The system must prevent or clearly flag attempts to attach implementation output, artifacts, or PR references to the wrong ticket or wrong run.
- **NFR21:** When ticket, run, repository, artifact, or PR identity is ambiguous, the system must pause and require human confirmation.
- **NFR22:** Integration failures must be classified as sync failure, link failure, state conflict, or network/API failure.
- **NFR23:** Integration freshness expectations must be explicit, including whether status depends on polling, manual refresh, or direct API reads.
- **NFR24:** Runner execution records must link normalized runner output, raw output reference when retained, produced artifacts, and the context bundle used for the step.

### Performance & Freshness

- **NFR25:** Local inspection of a single run's current status should return within 2 seconds for normal pilot-size run histories.
- **NFR26:** Local inspection of a single run's stage history should return within 5 seconds for normal pilot-size run histories.
- **NFR27:** For MVP measurement, a normal pilot-size run history means up to 100 durable workflow events and up to 25 linked artifacts for one ticket run.
- **NFR28:** Performance targets apply to inspection/read paths, not to agent implementation execution time.
- **NFR29:** Workflow status must be available without waiting for agent execution to complete.
- **NFR30:** Long-running agent work must expose current stage, last activity time, latest durable event, and freshness/staleness indicator.

### Data Retention & Auditability

- **NFR31:** MVP run history and artifacts must be retained for at least 60 days by default unless manually archived or deleted.
- **NFR32:** Audit history must be append-only from the product perspective; corrections must be represented as new events rather than mutation of prior events.
- **NFR33:** Failure taxonomy values used on historical runs must remain interpretable if the taxonomy changes later.
- **NFR34:** Run records must be inspectable by ticket reference and run identifier.
- **NFR35:** The system must define what happens to run history when a ticket is closed, archived, or removed from the source system, including whether tombstone records are preserved.

### Local-First Operability

- **NFR36:** The MVP must run from a supported local development environment without requiring a hosted control plane.
- **NFR37:** The MVP must define supported pilot environment assumptions, including operating system, shell, Git repository access, Linear access, GitHub access, and agent tool availability.
- **NFR38:** Local persisted state must survive normal interruption and allow the user to inspect, resume, or take over a run.
- **NFR39:** The system must define where local state, logs, artifacts, private working data, and exported review history are stored.
- **NFR40:** The first-release setup path must avoid platform-engineering support and should be completable by a pilot developer or workflow owner.
- **NFR41:** Exported or shared run history must include enough context for a teammate to inspect status, artifacts, decisions, failures, and next action without access to the originating local machine.

### Usability & Adoption

- **NFR42:** A pilot user must be able to run one low-risk ticket through the guided workflow using documented setup and tutorial material.
- **NFR43:** The product should minimize new workflow concepts beyond ticket, spec, run, artifact, review, failure, and recovery action.
- **NFR44:** The MVP should optimize for understandable recovery and inspection over maximum automation.
- **NFR45:** First-run documentation must include a happy-path tutorial and at least one failed-run recovery walkthrough.
