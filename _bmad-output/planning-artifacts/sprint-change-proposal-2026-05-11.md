# Sprint Change Proposal - 2026-05-11

**Project:** DeliveryLine
**Prepared by:** Codex using `bmad-correct-course`
**User:** Alex
**Language:** English
**Mode Assumed:** Batch

## 1. Issue Summary

### Trigger

- **Triggering story:** `1.12 - artifact-operations-skeleton`
- **Current status:** `in-progress` after repeated return from review

### Problem Type

- **Primary category:** Failed approach requiring a different solution shape
- **Secondary category:** Technical limitation discovered during implementation

### Core Problem Statement

Story `1.12` has drifted beyond a foundation "artifact operations skeleton" into unresolved design work about ambiguous lineage recovery, replay semantics after partial failure, and operator-driven reconciliation. Those concerns are real, but they belong to later recovery/reconciliation scope. Keeping them inside `1.12` is causing review churn, expanding the story contract, and creating pressure to change architecture-level behavior mid-sprint.

### Evidence

- [sprint-status.yaml](/C:/Users/pc/Documents/Personal/ai-hackaton-1/_bmad-output/implementation-artifacts/sprint-status.yaml) records `1.12` returning to `in-progress` on 2026-05-10 after high-severity review findings and multiple "big-stakes decisions".
- [1-12-artifact-operations-skeleton.md](/C:/Users/pc/Documents/Personal/ai-hackaton-1/_bmad-output/implementation-artifacts/1-12-artifact-operations-skeleton.md) shows the story accumulating large review-driven contract changes instead of converging on the original skeleton.
- The review notes identify four structural issues, not just local bugs:
  - race-replay transaction semantics
  - replay of failed outcomes as success
  - trust in caller-supplied `storageRef`
  - duplicate artifacts when `replace`/`update` meets empty or partially failed lineage
- The same review notes introduce decisions that materially widen scope:
  - explicit fork / lineage recovery behavior
  - new domain conflict codes
  - new event types
  - new Flyway migration work
  - broader locking semantics

## 2. Checklist Findings

### Section 1 - Understand the Trigger and Context

- `[x] 1.1` Trigger identified: story `1.12`.
- `[x] 1.2` Core problem defined: story-scope drift from foundation artifact skeleton into recovery/reconciliation design.
- `[x] 1.3` Evidence gathered from story notes, sprint status, and architecture/epic contracts.

### Section 2 - Epic Impact Assessment

- `[x] 2.1` **Current epic impact:** Epic 1 can still complete, but story `1.12` must be narrowed back to foundation scope.
- `[x] 2.2` **Epic-level change needed:** yes. Epic 1 should stop owning ambiguous lineage repair and manual fork governance.
- `[x] 2.3` **Future epic review:** Epic 4 is affected because it already owns deeper artifact reconciliation and operator recovery.
- `[!] 2.4` **New scope owner needed:** explicit lineage-repair/fork-governance coverage is currently under-specified in future work.
- `[!] 2.5` **Sequencing impact:** recovery semantics should remain in Epic 4; only minimal conflict surfacing should remain in Epic 1.

### Section 3 - Artifact Conflict and Impact Analysis

- `[x] 3.1` **PRD conflict check:** no PRD conflict. MVP goals remain valid.
- `[!] 3.2` **Architecture impact:** one clarification is needed so ambiguous lineage is fail-closed and routed to explicit reconciliation rather than silently auto-forked.
- `[x] 3.3` **UX impact:** no immediate Epic 2 UX spec change is required.
- `[!] 3.4` **Other artifacts impacted:** Epic 1 story text, Epic 4 recovery backlog, and later sprint tracking.

### Section 4 - Path Forward Evaluation

#### Option 1: Direct Adjustment

- **Viable:** Yes
- **Effort:** Medium
- **Risk:** Medium
- **Assessment:** Best path if `1.12` is narrowed and future recovery work is moved out of the story contract.

#### Option 2: Potential Rollback

- **Viable:** No
- **Effort:** High
- **Risk:** High
- **Assessment:** Rolling back completed foundation work would cost momentum and does not solve the planning mistake. The issue is misplaced scope, not invalid overall direction.

#### Option 3: PRD MVP Review

- **Viable:** No
- **Effort:** Low
- **Risk:** Low
- **Assessment:** PRD/MVP is not the problem. This is not a product-scope failure.

#### Recommended Path

- **Selected approach:** Hybrid, centered on Option 1
- **Definition:** Narrow `1.12`, clarify architecture fail-closed behavior for ambiguous lineage, and assign explicit lineage repair/fork governance to Epic 4 instead of continuing to expand Epic 1.

## 3. Impact Analysis

### Epic Impact

#### Epic 1

- Story `1.12` must return to a true foundation slice:
  - artifact intent persistence
  - approval gating
  - stale/late/orphan detection
  - deterministic replay where outcome is unambiguous
- Story `1.12` should **not** absorb:
  - explicit lineage fork governance
  - operator-driven lineage repair
  - broad recovery semantics beyond skeleton detection and fail-closed conflict signaling

#### Epic 4

- Epic 4 already owns deeper reconciliation.
- The backlog needs one explicit place for:
  - ambiguous lineage repair
  - explicit fork/reattach decisions
  - audit/event semantics around lineage recovery
  - operator-driven resolution of `ARTIFACT_OPERATION_INTENT_CONFLICT`-type cases

### Story Impact

- **Current story directly affected:** `1.12`
- **Likely future stories affected:** `1.18`, `4.15`, `4.16`, `4.18`

### Artifact Conflicts

#### PRD

- No change required.
- MVP scope, success criteria, and product thesis remain intact.

#### Architecture

- Clarification required in the artifact atomicity / recovery semantics area:
  - ambiguous lineage must fail closed
  - manual reconciliation owns fork decisions
  - foundation stories should not silently create second lineages under replay or partial failure

#### UX

- No direct UX spec conflict now.
- Epic 4 operator recovery UX will need to surface lineage-recovery decisions if the proposed backlog change is accepted.

### Technical Impact

- Fewer architecture-changing decisions inside Epic 1
- Cleaner separation between:
  - foundation artifact bookkeeping
  - deeper recovery and repair semantics
- Lower risk of encoding unstable artifact-lineage rules into early backend and later UI/API contracts

## 4. Recommended Approach

### Summary

Keep Epic 1 focused on the minimum safe artifact skeleton. Move ambiguous lineage repair and explicit fork governance into Epic 4, where operator-driven recovery already belongs.

### Why This Path

- It preserves momentum on the current sprint.
- It avoids turning one foundation story into a mini-epic.
- It aligns with the architecture and PRD trust model:
  - no silent overwrite
  - no silent reconciliation under ambiguity
  - explicit human recovery when identity or state is uncertain
- It keeps the MVP intact while reducing technical churn.

### Risk Assessment

- **Main risk if accepted:** Epic 4 backlog grows slightly.
- **Main risk if rejected:** `1.12` continues expanding, review churn continues, and future stories inherit unstable lineage semantics.

### Timeline Impact

- **Short-term:** positive. `1.12` becomes finishable.
- **Medium-term:** neutral to mildly negative. Epic 4 gains one more explicit responsibility.
- **MVP impact:** none.

## 5. Detailed Change Proposals

### A. Story Change Proposal - Narrow Story 1.12

**Artifact:** [epics.md](/C:/Users/pc/Documents/Personal/ai-hackaton-1/_bmad-output/planning-artifacts/epics.md) and the working story file [1-12-artifact-operations-skeleton.md](/C:/Users/pc/Documents/Personal/ai-hackaton-1/_bmad-output/implementation-artifacts/1-12-artifact-operations-skeleton.md)

#### Proposal A1 - Clarify lineage scope

**Story:** `1.12 - Artifact Operations Skeleton`  
**Section:** Acceptance Criteria 7

**OLD**

> Given artifact versioning, when `newVersion(parentArtifactId, payloadRef)` is called, a new artifact row is created with `parent_artifact_id` pointing to the superseded version and `version` incremented. Multiple independent lineages of the same `artifact_type` inside one workflow run are out of scope for this story.

**NEW**

> Given artifact versioning, when `newVersion(lineageMemberArtifactId, payloadRef)` is called, the service continues only the existing active lineage for that `(workflow_run_id, artifact_type)` pair and creates the next version on that lineage. If the requested operation would require creating a second lineage, healing an ambiguous lineage, or deciding between multiple possible parents after partial failure or replay, the service fails closed with a typed conflict (`ARTIFACT_OPERATION_INTENT_CONFLICT`) and leaves explicit repair/fork decisions to Epic 4 recovery work. This story does not create or heal forks.

**Rationale**

The current story text says multi-lineage is out of scope, but it does not say what happens when implementation encounters that case. The proposal makes the boundary executable instead of rhetorical.

#### Proposal A2 - Tighten replay semantics for pre-artifact ambiguity

**Story:** `1.12 - Artifact Operations Skeleton`  
**Section:** Acceptance Criteria 9

**OLD**

> Given idempotency on artifact operations, a unique constraint on (`idempotency_key`, `operation_type`, `artifact_id`) prevents duplicate operations; repeated submissions replay the prior outcome.

**NEW**

> Given idempotency on artifact operations, duplicate submissions replay only when the prior outcome can be reconstructed unambiguously for the same workflow run, artifact type, and operation intent. When replay would otherwise create a new artifact, attach to the wrong lineage, or convert a prior failed outcome into an apparent success, the service returns `ARTIFACT_OPERATION_INTENT_CONFLICT` and requires later explicit recovery handling instead of silently guessing.

**Rationale**

The current AC is too optimistic for partial-failure and pre-artifact collision cases. The implementation needs an explicit fail-closed rule.

#### Proposal A3 - State the skeleton non-goal directly

**Story:** `1.12 - Artifact Operations Skeleton`  
**Section:** New "Non-goals / Scope Guardrail" note after Acceptance Criteria

**OLD**

> No explicit non-goal section exists.

**NEW**

> **Scope guardrail:** Story `1.12` owns artifact intent persistence, availability gating, late/stale/orphan detection, and deterministic replay where outcome is unambiguous. It does **not** own operator-driven artifact lineage repair, explicit fork governance, or deep reconciliation of ambiguous artifact history. Those behaviors are planned follow-up recovery work.

**Rationale**

This makes future code reviews easier to triage and prevents the story from re-expanding after each defect found.

### B. Architecture Change Proposal - Clarify fail-closed lineage behavior

**Artifact:** [architecture.md](/C:/Users/pc/Documents/Personal/ai-hackaton-1/_bmad-output/planning-artifacts/architecture.md)

#### Proposal B1 - Add fail-closed clause to artifact atomicity/recovery

**Section:** Data architecture / artifact atomicity / recovery semantics

**OLD**

> Artifact operations must have both an execution path and a reconciliation path. Recording an operation without a way to complete, retry, fail, or reconcile it is not sufficient.

**NEW**

> Artifact operations must have both an execution path and a reconciliation path. Recording an operation without a way to complete, retry, fail, or reconcile it is not sufficient. When partial failure, replay, or stale callback makes artifact lineage ambiguous, the system must fail closed and require explicit reconciliation; it must not silently create a new lineage or attach output to a guessed parent.

**Rationale**

This aligns the architecture with existing trust requirements:
- no silent overwrite
- no silent reconciliation under ambiguity
- explicit human recovery when identity/state is uncertain

### C. Epic Backlog Change Proposal - Add explicit Epic 4 owner for lineage repair

**Artifact:** [epic-04-recovery.md](/C:/Users/pc/Documents/Personal/ai-hackaton-1/_bmad-output/planning-artifacts/epic-04-recovery.md)

#### Proposal C1 - Add a new recovery story for artifact lineage reconciliation

**Story:** new story inserted after `4.16`

**OLD**

> No explicit story owns ambiguous artifact-lineage repair or operator-approved fork governance.

**NEW**

> **Story 4.16a: Artifact lineage reconciliation and fork governance**
>
> As a Workflow Owner repairing ambiguous artifact history after partial failure or replay conflict,  
> I want explicit lineage-recovery actions that let me reattach an orphan payload, mark a dead lineage terminal, or approve creation of a new lineage branch with recorded rationale,  
> so that ambiguous artifact history is resolved through auditable recovery rather than hidden automatic behavior.
>
> **Suggested acceptance outline:**
> - typed recovery command for lineage reconciliation
> - explicit operator choice between reattach / terminate / fork
> - persisted recovery action and append-only workflow event
> - new lineage-recovery discriminator in persisted metadata
> - resolution path for `ARTIFACT_OPERATION_INTENT_CONFLICT`
> - contract tests proving no silent fork occurs before operator decision

**Rationale**

Epic 4 already owns deep reconciliation. This proposal makes the missing owner explicit instead of letting Epic 1 absorb it by accident.

### D. Optional Baseline Recovery Adjustment

**Artifact:** [epics.md](/C:/Users/pc/Documents/Personal/ai-hackaton-1/_bmad-output/planning-artifacts/epics.md)

#### Proposal D1 - Surface manual-recovery-needed in Epic 1 baseline diagnostics

**OLD**

> Story `1.18` covers baseline retry and failure description.

**NEW**

> Extend baseline failure description to surface artifact-operation conflicts that are **not** safe to auto-retry, including a stable indication that manual reconciliation is required.

**Rationale**

If `1.12` fails closed under ambiguity, the CLI baseline should at least describe that state clearly before Epic 4 adds the richer repair surface.

## 6. PRD / MVP Impact

- **PRD change required:** No
- **MVP scope change required:** No
- **Success criteria affected:** No

This is a planning and sequencing correction, not a product-direction change.

## 7. Scope Classification and Handoff

### Classification

- **Scope:** Moderate

### Why Moderate

- No PRD rewrite is required.
- No architecture reset is required.
- Backlog and story contracts do need adjustment before implementation continues cleanly.

### Recommended Handoff

- **Primary recipients:** Product Owner / Developer
- **Secondary recipient:** Architect, only for the architecture clarification and Epic 4 story ownership

### Responsibilities

- **PO/Developer**
  - approve the narrowed `1.12` scope
  - update the affected story text in planning artifacts
  - keep sprint tracking aligned after approval
- **Architect**
  - approve the fail-closed lineage clarification
  - bless the Epic 4 ownership of explicit lineage repair/fork governance

## 8. High-Level Action Plan

1. Approve this Sprint Change Proposal.
2. Update `epics.md` and the `1.12` working story file to narrow the story contract.
3. Add the architecture clarification.
4. Add the Epic 4 follow-up story for explicit lineage reconciliation/fork governance.
5. Update `sprint-status.yaml` notes only after the planning artifacts are revised.
6. Return to `bmad-dev-story` for `1.12` under the corrected scope.

## 9. Final Recommendation

Approve the hybrid direct-adjustment path.

The problem is not the MVP and not Epic 1 as a whole. The problem is that story `1.12` is currently acting as both a foundation artifact skeleton and a future recovery-design story. Splitting those responsibilities at the planning level is the cleanest way to restore momentum without hiding the real technical risks.
