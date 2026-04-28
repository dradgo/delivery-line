package org.dradgo.domain.registry;

public final class PersistedRegistryValues {

	private PersistedRegistryValues() {
	}

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

	public static ArtifactOperationStatus artifactOperationStatus(String rawValue) {
		return ArtifactOperationStatus.fromValue(rawValue, "artifact_operations.status");
	}

	public static ActorType approvalActorType(String rawValue) {
		return ActorType.fromValue(rawValue, "approvals.actor_type");
	}

	public static RunnerExecutionStatus runnerExecutionStatus(String rawValue) {
		return RunnerExecutionStatus.fromValue(rawValue, "runner_executions.status");
	}

	public static IntegrationSyncStatus integrationSyncStatus(String rawValue) {
		return IntegrationSyncStatus.fromValue(rawValue, "integration_links.sync_status");
	}

	public static ActorType recoveryActorType(String rawValue) {
		return ActorType.fromValue(rawValue, "recovery_actions.actor_type");
	}
}
