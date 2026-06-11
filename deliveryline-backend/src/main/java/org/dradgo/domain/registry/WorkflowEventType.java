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
  LINEAR_COMPLETION_SYNC_FAILED("linear.completionSyncFailed");

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
