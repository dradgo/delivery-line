package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Intentionally lossy application-facing view of a workflow run.
 *
 * <p>The application layer needs the public id, state, archival marker, optimistic-lock version,
 * and the two spec-rejection loop tracking fields ({@code specRejectionLoopCount} + {@code
 * escalationMarkerSet}) that drive the {@code WorkflowInspectionService} surface introduced by
 * story 2.10.
 */
public record WorkflowRunSnapshot(
    String publicId,
    WorkflowState currentState,
    OffsetDateTime archivedAt,
    Long version,
    int specRejectionLoopCount,
    boolean escalationMarkerSet,
    String projectId) {

  public WorkflowRunSnapshot(
      String publicId,
      WorkflowState currentState,
      OffsetDateTime archivedAt,
      Long version,
      int specRejectionLoopCount,
      boolean escalationMarkerSet) {
    this(
        publicId,
        currentState,
        archivedAt,
        version,
        specRejectionLoopCount,
        escalationMarkerSet,
        null);
  }

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
