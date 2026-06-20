package org.dradgo.domain.registry;

public final class PersistedRegistryValues {

  private PersistedRegistryValues() {}

  public static WorkflowState workflowRunState(String rawValue) {
    return WorkflowState.fromValue(rawValue, "workflow_runs.current_state");
  }

  public static WorkflowState workflowEventPriorState(String rawValue) {
    return WorkflowState.fromNullableValue(rawValue, "workflow_events.prior_state");
  }

  public static WorkflowState workflowEventResultingState(String rawValue) {
    return WorkflowState.fromNullableValue(rawValue, "workflow_events.resulting_state");
  }

  public static ActorType workflowEventActorType(String rawValue) {
    return ActorType.fromValue(rawValue, "workflow_events.actor_type");
  }

  public static WorkflowEventType workflowEventType(String rawValue) {
    return WorkflowEventType.fromValue(rawValue, "workflow_events.event_type");
  }

  public static FailureCategory workflowEventFailureCategory(String rawValue) {
    return FailureCategory.fromNullableValue(rawValue, "workflow_events.failure_category");
  }

  public static ArtifactType artifactType(String rawValue) {
    return ArtifactType.fromValue(rawValue, "artifacts.artifact_type");
  }

  public static DataClassification artifactClassification(String rawValue) {
    return DataClassification.fromValue(rawValue, "artifacts.classification");
  }

  public static ArtifactStatus artifactStatus(String rawValue) {
    return ArtifactStatus.fromValue(rawValue, "artifacts.status");
  }

  public static FailureCategory artifactFailureCategory(String rawValue) {
    return FailureCategory.fromNullableValue(rawValue, "artifacts.failure_category");
  }

  public static ArtifactOperationStatus artifactOperationStatus(String rawValue) {
    return ArtifactOperationStatus.fromValue(rawValue, "artifact_operations.status");
  }

  public static ArtifactOperationType artifactOperationType(String rawValue) {
    return ArtifactOperationType.fromValue(rawValue, "artifact_operations.operation_type");
  }

  public static FailureCategory artifactOperationFailureCategory(String rawValue) {
    return FailureCategory.fromNullableValue(rawValue, "artifact_operations.failure_category");
  }

  public static ActorType approvalActorType(String rawValue) {
    return ActorType.fromValue(rawValue, "approvals.actor_type");
  }

  public static RejectionTaxonomy approvalRejectionTaxonomy(String rawValue) {
    return RejectionTaxonomy.fromValue(rawValue, "approvals.rejection_taxonomy");
  }

  public static RunnerExecutionStatus runnerExecutionStatus(String rawValue) {
    return RunnerExecutionStatus.fromValue(rawValue, "runner_executions.status");
  }

  public static RunnerStage runnerExecutionStage(String rawValue) {
    return RunnerStage.fromValue(rawValue, "runner_executions.stage");
  }

  public static RunnerKind runnerKind(String rawValue) {
    return RunnerKind.fromValue(rawValue, "runner_kind");
  }

  public static IdempotencyRecordStatus idempotencyRecordStatus(String rawValue) {
    return IdempotencyRecordStatus.fromValue(rawValue, "idempotency_records.status");
  }

  public static IntegrationSyncStatus integrationSyncStatus(String rawValue) {
    return IntegrationSyncStatus.fromValue(rawValue, "integration_links.sync_status");
  }

  public static ActorType recoveryActorType(String rawValue) {
    return ActorType.fromValue(rawValue, "recovery_actions.actor_type");
  }

  // Story 3c-6 (AC7) — the three project persistence boundaries 3c-2 (R7) deferred to "when rows
  // are
  // first read/written through the app", which is now: ProjectEntity stores
  // status/ticket_source_kind
  // /repo_host_kind as raw text and parses each at the getter through these wrappers, exactly like
  // WorkflowRunEntity.getCurrentState(). Unknown DB values fail fast with UNKNOWN_REGISTRY_VALUE.
  public static ProjectStatus projectStatus(String rawValue) {
    return ProjectStatus.fromValue(rawValue, "projects.status");
  }

  public static ConnectorKind projectTicketSourceKind(String rawValue) {
    return ConnectorKind.fromValue(rawValue, "projects.ticket_source_kind");
  }

  public static ConnectorKind projectRepoHostKind(String rawValue) {
    return ConnectorKind.fromValue(rawValue, "projects.repo_host_kind");
  }

  // Story 3c-5 (AC7) — the project_credentials.connector_role persistence boundary that 3c-2 R1 /
  // 3c-6 explicitly deferred to this story ("the {ticket_source,repo_host} role set is a 3c-5
  // credential concern"). ProjectCredentialEntity stores connector_role as raw text and parses it
  // at the getter through this wrapper; an unknown DB value fails fast with UNKNOWN_REGISTRY_VALUE.
  public static ConnectorRole projectCredentialConnectorRole(String rawValue) {
    return ConnectorRole.fromValue(rawValue, "project_credentials.connector_role");
  }
}
