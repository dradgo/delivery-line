package org.dradgo.application.runner.spi;

import java.time.OffsetDateTime;
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
    OffsetDateTime heartbeatStaleEmittedAt) {

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
}
