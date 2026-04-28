package org.dradgo.domain.registry;

import java.util.Map;

public enum WorkflowEventType implements RegistryValue {
	WORKFLOW_STATE_CHANGED("workflow.stateChanged"),
	APPROVAL_REQUESTED("approval.requested"),
	APPROVAL_APPROVED("approval.approved"),
	APPROVAL_REJECTED("approval.rejected"),
	ARTIFACT_AVAILABLE("artifact.available"),
	ARTIFACT_VERSION_CREATED("artifact.versionCreated"),
	RUNNER_STARTED("runner.started"),
	RUNNER_FAILED("runner.failed"),
	RECOVERY_RETRIED("recovery.retried"),
	RECOVERY_RECONCILED("recovery.reconciled"),
	INTEGRATION_LINKED("integration.linked"),
	EXPORT_CREATED("export.created"),
	CLARIFICATION_ANSWERED("clarification.answered"),
	CLARIFICATION_ACCEPTED("clarification.accepted"),
	CLARIFICATION_INCORPORATED("clarification.incorporated"),
	CLARIFICATION_SUPERSEDED("clarification.superseded"),
	CLARIFICATION_REJECTED_INVALID("clarification.rejectedInvalid"),
	CLARIFICATION_NO_EFFECT_REASON("clarification.noEffectReason");

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
