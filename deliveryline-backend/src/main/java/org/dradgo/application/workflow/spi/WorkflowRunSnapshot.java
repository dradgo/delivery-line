package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Intentionally lossy application-facing view of a workflow run.
 *
 * <p>The application layer currently needs only the public id, state, archival marker, and optimistic-lock version.
 */
public record WorkflowRunSnapshot(
	String publicId,
	WorkflowState currentState,
	OffsetDateTime archivedAt,
	Long version
) {

	public Long requiredVersion() {
		if (version != null) {
			return version;
		}
		throw new DomainException(
			DomainErrorCode.INTERNAL_ERROR,
			"Workflow run is missing its optimistic-lock version: " + publicId,
			Map.of("runId", publicId, "reason", "missing_optimistic_lock_version"));
	}
}
