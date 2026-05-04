package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;

public record WorkflowEventRecord(
	String publicId,
	String workflowRunPublicId,
	WorkflowEventType eventType,
	WorkflowState priorState,
	WorkflowState resultingState,
	String actorIdentity,
	ActorType actorType,
	String reason,
	FailureCategory failureCategory,
	boolean interventionMarker,
	OffsetDateTime createdAt,
	Map<String, Object> details
) {
}
