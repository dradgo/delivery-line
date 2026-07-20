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
 *
 * <p>Story 3m-2 (AC6/AC10) appends the two nullable definition-cursor fields ({@code
 * workflowDefinitionId} + {@code currentStepIndex}, ADR 0036). Both are {@code null} for a legacy
 * run — a run with a null definition is byte-identical to pre-3m (null-binding parity). They are
 * dormant in 3m-2; the write path is 3m-3.
 */
public record WorkflowRunSnapshot(
    String publicId,
    WorkflowState currentState,
    OffsetDateTime archivedAt,
    Long version,
    int specRejectionLoopCount,
    boolean escalationMarkerSet,
    String projectId,
    String parentRunId,
    Long workflowDefinitionId,
    Integer currentStepIndex) {

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
        null,
        null);
  }

  public WorkflowRunSnapshot(
      String publicId,
      WorkflowState currentState,
      OffsetDateTime archivedAt,
      Long version,
      int specRejectionLoopCount,
      boolean escalationMarkerSet,
      String projectId) {
    this(
        publicId,
        currentState,
        archivedAt,
        version,
        specRejectionLoopCount,
        escalationMarkerSet,
        projectId,
        null);
  }

  /**
   * Story 3m-2 back-compat constructor for the pre-3m 8-arg shape (canonical through {@code
   * parentRunId}) — defaults the two definition-cursor fields to {@code null} (legacy pipeline, no
   * cursor). Keeps every existing {@code new WorkflowRunSnapshot(...)} call site compiling
   * unchanged; only {@code WorkflowRunEntityMapper.toSnapshot} passes the real cursor values.
   */
  public WorkflowRunSnapshot(
      String publicId,
      WorkflowState currentState,
      OffsetDateTime archivedAt,
      Long version,
      int specRejectionLoopCount,
      boolean escalationMarkerSet,
      String projectId,
      String parentRunId) {
    this(
        publicId,
        currentState,
        archivedAt,
        version,
        specRejectionLoopCount,
        escalationMarkerSet,
        projectId,
        parentRunId,
        null,
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
