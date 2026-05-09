package org.dradgo.application.artifact;

import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;

public record ArtifactEventRecord(
	String workflowRunId,
	WorkflowEventType eventType,
	String actorIdentity,
	ActorType actorType,
	String reason,
	FailureCategory failureCategory,
	OffsetDateTime createdAt,
	Map<String, Object> details
) {
	public ArtifactEventRecord {
		details = details == null ? Map.of() : Map.copyOf(details);
	}
}
