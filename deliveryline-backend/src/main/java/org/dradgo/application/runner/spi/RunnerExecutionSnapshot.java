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
    Integer redactionCount,
    // Story 3.17a — runner execution queue substrate columns (V12). dispatchedAt/workerId carry the
    // dequeue() lease; queuePriority/queueAttemptCount are the dequeue ORDER BY key + lease
    // counter;
    // correlationId is the originating story-1.19 id persisted at enqueue (AC5/AC8). All default to
    // null / the V12 column defaults (priority 100, attempts 0) for rows the legacy synchronous
    // dispatch path created (it never enqueues).
    OffsetDateTime dispatchedAt,
    String workerId,
    int queuePriority,
    int queueAttemptCount,
    String correlationId,
    // Story 3.17b — dispatch carriage (V14). idempotencyKey + actorIdentity + actorType are
    // persisted at enqueue so the worker pool can reconstruct the idempotency reservation + the
    // event ActorContext when it runs the relocated dispatch body off a dequeued row. All null for
    // rows the legacy synchronous dispatch path created (it never enqueues).
    String idempotencyKey,
    String actorIdentity,
    String actorType,
    // Story 3g-3 (FR74) V31 — per-execution agent token accounting. All nullable: populated only by
    // the recordTokenUsage metadata write at result ingest when the agent reported counts; null for
    // pre-3g rows and any no-usage / command-only execution (null = "not reported", never 0). Each
    // count is carried independently as reported (total is NOT synthesized from input+output).
    Integer inputTokens,
    Integer outputTokens,
    Integer totalTokens) {

  /**
   * Pre-3g-3 callsite shim: derives a snapshot whose three token-accounting fields are null.
   * Carries today's (through-3.17b) 24-arg shape so every existing {@code new
   * RunnerExecutionSnapshot(...)} call site (the broker / recovery / cleanup tests + the
   * persistence mapper before it was widened) compiles unchanged — only the persistence mapper
   * populates the full 27-field shape with the real token columns.
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
      OffsetDateTime heartbeatStaleEmittedAt,
      String rawOutputReference,
      DataClassification rawOutputClassification,
      Long rawOutputByteSize,
      Integer redactionCount,
      OffsetDateTime dispatchedAt,
      String workerId,
      int queuePriority,
      int queueAttemptCount,
      String correlationId,
      String idempotencyKey,
      String actorIdentity,
      String actorType) {
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
        rawOutputReference,
        rawOutputClassification,
        rawOutputByteSize,
        redactionCount,
        dispatchedAt,
        workerId,
        queuePriority,
        queueAttemptCount,
        correlationId,
        idempotencyKey,
        actorIdentity,
        actorType,
        null,
        null,
        null);
  }

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
   * unchanged — only the persistence mapper populates the full shape.
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

  /**
   * Pre-3.17a callsite shim: derives a snapshot whose queue-substrate + dispatch-carriage fields
   * carry the V12/V14 column defaults (no lease, priority 100, zero attempts, no correlationId, no
   * carriage). Keeps every existing caller that builds the 16-field (through-3.6) shape compiling
   * unchanged — only the persistence mapper populates the full shape with the real queue columns.
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
      OffsetDateTime heartbeatStaleEmittedAt,
      String rawOutputReference,
      DataClassification rawOutputClassification,
      Long rawOutputByteSize,
      Integer redactionCount) {
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
        rawOutputReference,
        rawOutputClassification,
        rawOutputByteSize,
        redactionCount,
        null,
        null,
        100,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
