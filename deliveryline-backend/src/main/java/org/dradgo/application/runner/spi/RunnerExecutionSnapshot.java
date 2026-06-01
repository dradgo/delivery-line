package org.dradgo.application.runner.spi;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;

public record RunnerExecutionSnapshot(
    String publicId,
    String workflowRunPublicId,
    RunnerStage stage,
    RunnerExecutionStatus status,
    int contextBundleVersion,
    OffsetDateTime lastActivityAt,
    OffsetDateTime timeoutAt,
    FailureCategory failureCategory,
    OffsetDateTime completedAt,
    OffsetDateTime createdAt,
    OffsetDateTime archivedAt,
    OffsetDateTime heartbeatStaleEmittedAt,
    // Story 3.6 AC3/AC7 — durable redacted-log capture reference + metrics (all nullable until
    // recordRawOutput runs for the row). Read by WorkflowInspectionService.getRunnerLogReference.
    String rawOutputReference,
    DataClassification rawOutputClassification,
    Long rawOutputByteSize,
    Integer redactionCount) {

  /** Pre-3.2 callsite shim: derives a snapshot whose {@code heartbeatStaleEmittedAt} is null. */
  public RunnerExecutionSnapshot(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      RunnerExecutionStatus status,
      int contextBundleVersion,
      OffsetDateTime lastActivityAt,
      OffsetDateTime timeoutAt,
      FailureCategory failureCategory,
      OffsetDateTime completedAt,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt) {
    this(
        publicId,
        workflowRunPublicId,
        stage,
        status,
        contextBundleVersion,
        lastActivityAt,
        timeoutAt,
        failureCategory,
        completedAt,
        createdAt,
        archivedAt,
        null);
  }

  /**
   * Pre-3.6 callsite shim: derives a snapshot whose four {@code raw_output_*} capture fields are
   * null. Keeps the broker / cleanup / recovery tests that build snapshots directly compiling
   * unchanged — only the persistence mapper populates the full 16-field shape.
   */
  public RunnerExecutionSnapshot(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      RunnerExecutionStatus status,
      int contextBundleVersion,
      OffsetDateTime lastActivityAt,
      OffsetDateTime timeoutAt,
      FailureCategory failureCategory,
      OffsetDateTime completedAt,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt,
      OffsetDateTime heartbeatStaleEmittedAt) {
    this(
        publicId,
        workflowRunPublicId,
        stage,
        status,
        contextBundleVersion,
        lastActivityAt,
        timeoutAt,
        failureCategory,
        completedAt,
        createdAt,
        archivedAt,
        heartbeatStaleEmittedAt,
        null,
        null,
        null,
        null);
  }
}
