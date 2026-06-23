package org.dradgo.domain.registry;

import java.util.Map;

public enum WorkflowEventType implements RegistryValue {
  WORKFLOW_STATE_CHANGED("workflow.stateChanged"),
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
  ARTIFACT_LINEAGE_RECOVERED("artifact.lineageRecovered"),
  INTEGRATION_LINKED("integration.linked"),
  EXPORT_CREATED("export.created"),
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
  WORKFLOW_UNARCHIVED("workflow.unarchived");

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
