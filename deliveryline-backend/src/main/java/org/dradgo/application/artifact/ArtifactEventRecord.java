package org.dradgo.application.artifact;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
		// Map.copyOf rejects null values; filter them out defensively so a null payloadRef
		// or other optional field reaching the detail map does not crash event construction.
		if (details == null || details.isEmpty()) {
			details = Map.of();
		} else {
			Map<String, Object> filtered = new LinkedHashMap<>(details);
			filtered.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
			details = Map.copyOf(filtered);
		}
	}
}
