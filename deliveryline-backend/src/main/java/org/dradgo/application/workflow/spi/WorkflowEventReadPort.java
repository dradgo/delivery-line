package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-only SPI for {@code workflow_events} consumed by
 * {@link org.dradgo.application.workflow.WorkflowInspectionService} (story 1-15 Task 1). The
 * persistence adapter implementation lives in
 * {@code adapters.persistence.WorkflowEventPersistenceAdapter} (extended in story 1-15).
 *
 * <p>Implementations MUST return application-shaped {@link WorkflowEventRecord} instances so the
 * application layer never depends on JPA entities. The mapper inside the adapter handles
 * {@code WorkflowEventEntity → WorkflowEventRecord}.
 */
public interface WorkflowEventReadPort {

	/**
	 * Latest event for the given workflow run (highest {@code created_at}; tiebreak on row id).
	 * Empty when no events exist for the run.
	 */
	Optional<WorkflowEventRecord> findLatestByWorkflowRunPublicId(String workflowRunPublicId);

	/**
	 * Events for the given workflow run, ordered ascending by {@code (created_at, id)}. When
	 * {@code sinceInclusive} is non-null, only events with {@code created_at >= sinceInclusive}
	 * are returned. Bounded internally at {@link #HISTORY_CEILING} rows; when the bound is hit,
	 * implementations MUST throw
	 * {@code DomainException(HISTORY_TOO_LARGE, "Run history exceeds 1000 events; pass --since to narrow.")}.
	 */
	List<WorkflowEventRecord> listByWorkflowRunPublicId(String workflowRunPublicId, OffsetDateTime sinceInclusive);

	/** Hard ceiling on history page size (NFR26/27). */
	int HISTORY_CEILING = 1000;
}
