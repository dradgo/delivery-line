package org.dradgo.domain.registry;

import java.util.Map;

public enum WorkflowEventType implements RegistryValue {
  WORKFLOW_STATE_CHANGED("workflow.stateChanged"),
  SPLIT("workflow.split"),
  APPROVAL_REQUESTED("approval.requested"),
  APPROVAL_APPROVED("approval.approved"),
  APPROVAL_REJECTED("approval.rejected"),
  ESCALATION_REQUIRED("escalation.required"),
  ARTIFACT_DRAFT_CREATED("artifact.draftCreated"),
  ARTIFACT_AVAILABLE("artifact.available"),
  ARTIFACT_FAILED("artifact.failed"),
  ARTIFACT_VERSION_CREATED("artifact.versionCreated"),
  // Story 3.17a — appended by RunnerExecutionQueue.enqueue when a row is placed in the queue
  // (pre-dispatch). Built dormant: no production code enqueues yet (story 3.17b activates it).
  RUNNER_QUEUED("runner.queued"),
  RUNNER_STARTED("runner.started"),
  RUNNER_FAILED("runner.failed"),
  RUNNER_DISPATCHED("runner.dispatched"),
  RUNNER_HEARTBEAT_STALE("runner.heartbeatStale"),
  RUNNER_TIMEOUT("runner.timeout"),
  RUNNER_ORPHANED("runner.orphaned"),
  RUNNER_COMPLETED("runner.completed"),
  RECOVERY_RETRIED("recovery.retried"),
  RECOVERY_DISPATCH_FAILED("recovery.dispatchFailed"),
  RECOVERY_RECONCILED("recovery.reconciled"),
  // Story 4.5 (AC7) — appended in the same REQUIRES_NEW prep transaction as the Paused → prior
  // executing-state transition + the recovery_actions insert when an operator resumes a paused run.
  // Recovery namespace (lowerCamel), mirroring recovery.retried / recovery.reconciled (NOT
  // workflow.resumed). It preserves who resumed + when (FR47 append-only): priorState = Paused,
  // resultingState = the recovered prior executing state, interventionMarker = true. Detail keys
  // (triggeringEventId = the Paused transition event, idempotencyKey, reason?, correlationId?) are
  // already allow-listed. NO Flyway migration — event_type is an un-CHECKed text column and the
  // registry set is fixture-asserted, not DB-derived.
  RECOVERY_RESUMED("recovery.resumed"),
  ARTIFACT_LINEAGE_RECOVERED("artifact.lineageRecovered"),
  INTEGRATION_LINKED("integration.linked"),
  EXPORT_CREATED("export.created"),
  // Story 3e-1 (FR10) — appended once per OPEN clarification the spec-runner question handler
  // (ClarificationIngestService) creates from a spec result's specArtifact.questions, inside the
  // broker's best-effort post-completion seam. Carries the already-allow-listed clarificationId /
  // artifactId / questionId detail keys. NOT a workflow-state change (priorState ==
  // resultingState == null), like clarification.answered. The CREATE half of the clarification loop
  // (the answer/accept/incorporate transitions already existed; the create caller never did).
  CLARIFICATION_RAISED("clarification.raised"),
  CLARIFICATION_ANSWERED("clarification.answered"),
  CLARIFICATION_ACCEPTED("clarification.accepted"),
  CLARIFICATION_INCORPORATED("clarification.incorporated"),
  CLARIFICATION_SUPERSEDED("clarification.superseded"),
  CLARIFICATION_REJECTED_INVALID("clarification.rejectedInvalid"),
  CLARIFICATION_NO_EFFECT_REASON("clarification.noEffectReason"),
  // Story 3.16 (AC4) — emitted (best-effort, in its own post-commit transaction) when the Linear
  // completion-sync write-back fails. NEVER rolls back the Completed transition; the integration
  // failure category rides the `failureCategory` detail key (the event record's typed
  // failureCategory field is runner-scoped FailureCategory, so the integration-scoped category is
  // carried as a detail, mirroring INTEGRATION_LINKED).
  LINEAR_COMPLETION_SYNC_FAILED("linear.completionSyncFailed"),
  // Story 3d-3 (AC4, ADR 0024 D3) — appended when a step dispatched under the `manual` runner kind
  // is parked in WaitingForManualExecution (no container launched, no queue row). Carries the
  // existing allow-listed detail keys runnerExecutionId / workflowRunId / runnerKind (= "manual").
  // The submission-side `manual.artifactSubmitted` event belongs to story 3d-4, NOT here.
  MANUAL_EXECUTION_REQUESTED("manual.executionRequested"),
  // Story 3d-4 (AC4, ADR 0024 D4) — appended when an operator submits a manually-produced artifact
  // for a parked WaitingForManualExecution run. It carries the resolved OPERATOR ActorContext (not
  // SYSTEM) and the existing allow-listed runnerExecutionId / workflowRunId detail keys; the run
  // transitions out of WaitingForManualExecution as a side effect. The dispatch-side twin
  // `manual.executionRequested` belongs to story 3d-3.
  MANUAL_ARTIFACT_SUBMITTED("manual.artifactSubmitted"),
  // Story 3d-6 (AC3, ADR 0025 D2) — governed read-only diagnostic-console session history. Appended
  // when a console is opened against a LIVE runner execution (console.opened) and when the session
  // closes (console.closed). Console I/O is NOT durably stored — only this session metadata. Both
  // carry ONLY the already-allow-listed runnerExecutionId / workflowRunId detail keys (DD-4); they
  // have no prior/resulting state (a console session is not a workflow-state change). They do NOT
  // belong in any scenario stream fixture (happy-path etc.) — only the registry + fixture mirrors.
  CONSOLE_OPENED("console.opened"),
  CONSOLE_CLOSED("console.closed"),
  // Story 3d-8 (FR67, AC3/AC4, ADR 0027) — governed soft-hide of an obsolete run. Appended when an
  // operator (or, behind the default-off auto-scan, a SYSTEM actor) hides a run from the default
  // queue and when it is later un-hidden. Archiving is orthogonal to the lifecycle: it does NOT
  // change current_state, so the event carries priorState == resultingState == the run's current
  // state and interventionMarker = true (a human/governed triage action). Detail keys are the
  // already-allow-listed idempotencyKey / correlationId / reason. No row is ever deleted (FR47).
  WORKFLOW_ARCHIVED("workflow.archived"),
  WORKFLOW_UNARCHIVED("workflow.unarchived"),
  // Story 3f-7 (AC5) — appended (in the same transaction as the split request) when an operator
  // bypasses the recursive-split depth cap via the allowDeepSplit/--allow-deep-split override. It
  // is a governance action, NOT a workflow-state change (priorState == resultingState == null, like
  // ESCALATION_REQUIRED) and carries interventionMarker = true so the deliberate safety-cap bypass
  // is auditable in the governed history. Detail keys: deepSplitOverride / splitDepth /
  // maxSplitDepth. NFR43 ("justify each new concept") is satisfied — an operator deliberately
  // overriding a safety cap is exactly what the governed history exists to record.
  SPLIT_DEPTH_OVERRIDE("workflow.splitDepthOverride"),
  // Story 4.4 (AC5) — appended (best-effort, in the download request's own transaction) when an
  // operator downloads a run's already-redacted runner log via GET
  // /api/v1/runner-executions/{rexId}/logs/download. It is a governed read-access audit record, NOT
  // a workflow-state change: priorState == resultingState == the run's current state and
  // interventionMarker = true (mirrors workflow.archived). Detail key is the already-allow-listed
  // runnerExecutionId. The wire value is lowerCamel (audit.logDownloaded, NOT audit.log_downloaded)
  // so it satisfies the RegistryContractTest dot-separated-lowerCamel pattern. NO Flyway migration
  // —
  // event_type is an un-CHECKed text column and the registry set is fixture-asserted, not
  // DB-derived.
  AUDIT_LOG_DOWNLOADED("audit.logDownloaded"),
  // Story 4.17 (AC4) — appended (once per first-inserted (link, category) conflict) by the
  // IntegrationConflictDetectionService sweep when internal workflow state disagrees with the
  // cached-vs-fresh external Linear/GitHub state. NON-lifecycle event like integration.linked
  // (priorState == resultingState == null, actorType = SYSTEM); the insert-or-skip dedup prevents
  // per-tick spam. Detail keys reuse failureCategory/linearTicketReference/githubPrReference/
  // prState/reason/correlationId/workflowRunId + the new allow-listed conflictId/conflictCategory.
  // NOT in any scenario stream fixture (non-lifecycle, like console.opened). NO Flyway migration —
  // event_type is un-CHECKed text and the registry set is fixture-asserted, not DB-derived.
  INTEGRATION_CONFLICT_DETECTED("integration.conflictDetected"),
  // Story 3h-4 (AC4, ADR 0030) — appended when an operator records an out-of-band delivery for a
  // run parked at WaitingForDelivery under the `manual` push mode (approve_delivery). The backend
  // pushes NOTHING (Decision 4, ingestManualResult precedent): the operator pushed by hand, so this
  // event records that the delivery happened out-of-band and the run advanced to WaitingForReview.
  // It rides the WaitingForDelivery -> WaitingForReview transition (appended in the same command
  // tx)
  // but is NOT itself a workflow-state change — priorState == resultingState == the run's state at
  // append time (WaitingForReview), interventionMarker = true (a governed operator action). Detail
  // keys reuse the already-allow-listed runnerExecutionId / workflowRunId / correlationId. NOT in
  // any scenario stream fixture (like console.opened / integration.conflictDetected). NO Flyway —
  // event_type is un-CHECKed text and the registry set is fixture-asserted, not DB-derived.
  DELIVERY_RECORDED_MANUALLY("delivery.recordedManually"),
  // Story 4.7 (AC5) — appended in the same REQUIRES_NEW prep transaction as the → targetStep
  // transition + the recovery_actions insert + the prior-approval invalidation when an operator
  // reruns a run from a safe step boundary (Investigating/Executing). Recovery namespace
  // (lowerCamel), mirroring recovery.retried / recovery.resumed / recovery.reconciled (NOT
  // workflow.rerunFromStep as the epic AC5 text says — same reconciliation applied to
  // workflow.resumed → recovery.resumed). priorState = the run's state at rerun time,
  // resultingState
  // = the caller-chosen targetStep, interventionMarker = true. Detail keys: targetStep +
  // supersededArtifactIds (both NEW, allow-listed here) + invalidatedApprovalIds? +
  // triggeringEventId/idempotencyKey/reason?/correlationId? (already allow-listed). NO Flyway
  // migration — event_type is an un-CHECKed text column and the registry set is fixture-asserted.
  RECOVERY_RERUN_FROM_STEP("recovery.rerunFromStep"),
  // Story 4.7 (AC7) — appended (best-effort, in its own post-commit transaction) when a rerun-from-
  // step reopens a run and the reverse Linear notification is posted to the source ticket. Mirrors
  // the forward linear.completionSyncFailed shape: NEVER rolls back the committed rerun; a missing
  // link / absent comment capability / adapter failure logs + skips. NO Flyway migration —
  // event_type is un-CHECKed text and the registry set is fixture-asserted, not DB-derived.
  LINEAR_RUN_REOPENED_NOTIFICATION("linear.runReopenedNotification"),
  // Story 4.8 (AC5/AC6) — appended in the same REQUIRES_NEW prep transaction as the → Paused
  // transition + the runner cancel-flips + the recovery_actions insert when an operator manually
  // pauses a run. Recovery namespace (lowerCamel), mirroring recovery.retried / recovery.resumed /
  // recovery.reconciled (NOT workflow.paused as the epic AC5 text says — the same reconciliation
  // applied to workflow.resumed → recovery.resumed). It exists as the non-null
  // recovery_actions.resulting_event_id FK anchor the concurrent-replay guard depends on; the prior
  // state that resume (4.5) reads stays on the TYPED priorState() of the transition's own
  // WORKFLOW_STATE_CHANGED → Paused event (NO priorState detail key here). priorState = the
  // pausable
  // source state, resultingState = Paused, interventionMarker = true. Detail keys
  // (triggeringEventId?, idempotencyKey, reason, correlationId?) are already allow-listed. NO
  // Flyway
  // migration — event_type is an un-CHECKed text column and the registry set is fixture-asserted.
  RECOVERY_PAUSED("recovery.paused"),
  // Story 4.9 (AC8) — appended in the same REQUIRES_NEW prep transaction as the workflow_runs
  // failure-classification column update + the recovery_actions insert when a workflow owner
  // classifies a failed run against the governed failure taxonomy (FR37/FR38). Recovery namespace
  // (lowerCamel), mirroring recovery.retried / recovery.resumed / recovery.reconciled (NOT
  // workflow.failureClassified as the epic AC8 text says — the same reconciliation applied to
  // workflow.resumed → recovery.resumed). classify is a PURE METADATA operation: no transition, so
  // the event is a non-transition audit record exactly like audit.logDownloaded — priorState ==
  // resultingState == the run's current state (Failed), failureCategory = null (the taxonomy is
  // the orthogonal HUMAN axis; see FailureTaxonomyValue), interventionMarker = true. Detail keys:
  // taxonomyValue + priorTaxonomyValue? (both NEW, allow-listed by this story) +
  // triggeringEventId?/idempotencyKey/reason?/correlationId? (already allow-listed). Re-classifying
  // appends a NEW event — prior classifications live in this event chain, never on the row (AC9).
  // NO Flyway migration for the event itself — event_type is an un-CHECKed text column and the
  // registry set is fixture-asserted, not DB-derived.
  RECOVERY_FAILURE_CLASSIFIED("recovery.failureClassified"),
  // Story 4.15 (AC4) — appended (once per first-inserted (category, artifact/operation) drift) by
  // the ArtifactDriftDetectionService sweep when it detects an orphan operation / missing payload /
  // checksum mismatch. DETECTION-ONLY, NON-lifecycle event like artifact.available / integration.
  // conflictDetected (priorState == resultingState == null, actorType = SYSTEM); the insert-or-skip
  // dedup prevents per-tick spam. Detail keys reuse artifactId / correlationId / reason /
  // failureCategory + the new allow-listed driftCategory. NOT in any scenario stream fixture
  // (non-lifecycle, like console.opened). NO Flyway migration for the event itself — event_type is
  // an un-CHECKed text column and the registry set is fixture-asserted, not DB-derived.
  ARTIFACT_DRIFT_DETECTED("artifact.driftDetected");

  private static final Map<String, WorkflowEventType> LOOKUP = RegistryParsers.index(values());

  private final String value;

  WorkflowEventType(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static WorkflowEventType fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static WorkflowEventType fromValue(String rawValue, String field) {
    return RegistryParsers.parse("WorkflowEventType", rawValue, field, LOOKUP);
  }
}
