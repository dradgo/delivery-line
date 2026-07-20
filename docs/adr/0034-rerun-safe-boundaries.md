# ADR 0034 — Rerun-from-Step Restricted to Safe Step Boundaries

**Status:** Proposed (2026-07-11) — to be confirmed on merge of story 4-7
**Driver:** Epic 4 (Failure Handling, Recovery & Reconciliation), story 4.7 (`RecoveryService.rerunFromStep`). Operators need a deeper-than-retry rerun path — re-spec a run whose spec missed scope, or re-implement after fixing a runner config — without the unbounded "rerun from any step" risk that could re-execute already-approved decisions or lose a run entirely. `rerunFromStep` is pre-sanctioned on ADR [0033](0033-recovery-service-scope-lift.md)'s (c) allow-list; this ADR records the safe-boundary decision that governs it.

## Context

`retry` (story 1.18) re-dispatches the last failed runner in place from `Failed`. It cannot help an operator who realizes the *spec* was wrong (the run must go back to `Investigating` and re-run the spec stage) or who wants to re-run the *implementation* from an approved plan (`Executing`). Story 4.7 adds `rerunFromStep(workflowRunId, targetStep, idempotencyKey, actor, reasonText)` for exactly those two cases.

The epic's original framing (§"Story 4.7" AC4) described rerun as legal "from any non-terminal state" via a `*→{targetStep}` pattern extending the `*→TakenOver`/`*→Reconciled` edges. Two facts about the live codebase make an unbounded rerun both unsafe and unnecessary:

1. **There is no transition-table wildcard.** `WorkflowTransitionTable` hand-enumerates every edge per source. A genuinely-unbounded `*→{Investigating|Executing}` would be a large state-machine expansion touching every source state — out of scope for a single backend recovery story, and a standing invitation to transition a run out of a gate that exists for a reason.
2. **Rerunning from an arbitrary earlier step destroys governed state.** Rerunning from `Inbox` would orphan the run's entire lineage; rerunning from `WaitingForSpecApproval` would silently discard the PM's pending approval decision. The value of a rerun is re-running a *stage*, not rewinding the lifecycle.

## Decision

`rerunFromStep`'s `targetStep` is constrained to a two-value registry enum, **`SafeRerunStep`** = { `investigating`, `executing` }. An out-of-range or blank value is rejected with `INVALID_RERUN_TARGET_STEP` (400) before any state is consumed; a missing `reasonText` is rejected with `MISSING_REASON_TEXT` (400).

**Safe boundaries only.** `investigating` (re-spec) and `executing` (re-implement) are the only rerun targets. They map to `WorkflowState.INVESTIGATING` / `WorkflowState.EXECUTING`.

**No new transition edges.** `rerunFromStep` leans on the edges that already exist and that the two canonical flows exercise: `FAILED→INVESTIGATING`, `FAILED→EXECUTING`, and `WAITING_FOR_REVIEW→EXECUTING`. It maps the safe step to its `WorkflowState` and routes the transition through `WorkflowCommandService.rerunFromStepWorkflow`. An illegal (source→targetStep) combination — a terminal run, or an unwired pair such as `WAITING_FOR_REVIEW→INVESTIGATING` — surfaces the transition table's `ILLEGAL_TRANSITION` (409). No transition-table wildcard is added.

**Approval invalidation is the enforced supersession.** Rerunning to `investigating` invalidates the run's current `spec` approval; rerunning to `executing` invalidates the current `implementationPlan` approval (`approvals.invalidated_at` / `invalidated_reason='superseded_by_rerun_from_step'`, added by Flyway `V42` — the next free number after sibling stories claimed V38–V41). The current-approved read filters `invalidated_at IS NULL`, so `getCurrentApprovedSpec` returns null until the re-run stage produces a new artifact version that is re-approved — future approvals cannot ride the stale decision.

**Artifact supersession is lineage-graft, not a column flip.** There is no artifact-supersession write. Prior artifacts at and beyond the target step are "superseded" by the existing lineage-graft mechanism: when the re-run runner produces its next artifact version, `createNextVersion` advances the active leaf (version N→N+1, `parent_artifact_id`=old leaf) and the prior version is preserved for audit / Compare Mode. `rerunFromStep` only *reads* the current active-leaf artifact ids at/beyond the target step and records them in the `recovery.rerunFromStep` event's `details.supersededArtifactIds` for audit.

## Alternatives Considered

### Alt 1 — Unbounded rerun from any non-terminal state (the epic's literal AC4)
**Rejected.** Requires either a transition-table wildcard or a per-source edge expansion touching the whole state machine, and lets an operator rewind a run out of a governance gate (losing a pending approval) or from `Inbox` (orphaning the lineage). The safe-boundary enum delivers the operator value (re-spec / re-implement) with none of that blast radius.

### Alt 2 — A real `artifacts.superseded_at` column + `ArtifactRecordPort.markSuperseded`
**Rejected / out of scope.** The codebase already expresses artifact obsolescence via the lineage chain; a parallel supersession column would duplicate that mechanism and overlaps the artifact-reconciliation stories (4.15/4.16/4.16a). The enforced obsolescence gate is the approval invalidation, not an artifact flip.

### Alt 3 — Allow rerun without a reason (fall back to a synthetic reason like retry/resume)
**Rejected.** A rerun invalidates a prior human approval and re-runs a stage — a materially heavier action than a retry. Requiring an explicit `reasonText` keeps the governed audit trail honest about *why* an approved decision was thrown away.

## Consequences

- Operators get a deeper-than-retry rerun path bounded to two safe steps; the REST endpoint + `SafeRerunStep` OpenAPI enum (story 4.12) and the Decision Bar dropdown (story 4.22) surface the sub-step choice.
- The concrete supersession that gates a re-run against a stale decision is the approval invalidation (`V42`), the only real migration in the story.
- `SafeRerunStep`'s two values are the sanctioned rerun targets; adding a third safe step in a future version is a change to this ADR + the enum + the transition edges it needs.
- No transition-table wildcard exists, so an operator who requests an unwired (source→step) pair gets a clean `ILLEGAL_TRANSITION` rather than an unexpected state jump.
