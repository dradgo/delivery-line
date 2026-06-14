package org.dradgo.adapters.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;

@Entity
@Table(name = "runner_executions")
public class RunnerExecutionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workflow_run_id", nullable = false)
  private WorkflowRunEntity workflowRun;

  @Column(name = "stage", nullable = false)
  private String stage;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "context_bundle_version", nullable = false)
  private int contextBundleVersion;

  @Column(name = "last_activity_at", nullable = false)
  private OffsetDateTime lastActivityAt;

  @Column(name = "timeout_at", nullable = false)
  private OffsetDateTime timeoutAt;

  @Column(name = "failure_category")
  private String failureCategory;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;

  @Column(name = "heartbeat_stale_emitted_at")
  private OffsetDateTime heartbeatStaleEmittedAt;

  // Story 3.17a — runner execution queue substrate columns (V12). Populated by the
  // RunnerExecutionQueue.enqueue/dequeue path; null/default for rows created by the legacy
  // synchronous dispatch path (which never enqueues). queuePriority/queueAttemptCount carry the
  // V12 NOT NULL DEFAULT (100 / 0) so pre-3.17a rows migrate without a backfill.
  @Column(name = "dispatched_at")
  private OffsetDateTime dispatchedAt;

  @Column(name = "worker_id")
  private String workerId;

  @Column(name = "queue_priority", nullable = false)
  private int queuePriority = 100;

  @Column(name = "queue_attempt_count", nullable = false)
  private int queueAttemptCount;

  @Column(name = "correlation_id")
  private String correlationId;

  // Story 3.6 AC3 — capture reference + metrics for the durable redacted runner-logs store. All
  // nullable: populated by a metadata-only recordRawOutput update after log capture (Trap T10).
  @Column(name = "raw_output_reference")
  private String rawOutputReference;

  @Column(name = "raw_output_classification")
  private String rawOutputClassification;

  @Column(name = "raw_output_byte_size")
  private Long rawOutputByteSize;

  @Column(name = "redaction_count")
  private Integer redactionCount;

  public Long getId() {
    return id;
  }

  public String getPublicId() {
    return publicId;
  }

  public void setPublicId(String publicId) {
    this.publicId = publicId;
  }

  public WorkflowRunEntity getWorkflowRun() {
    return workflowRun;
  }

  public void setWorkflowRun(WorkflowRunEntity workflowRun) {
    this.workflowRun = workflowRun;
  }

  public RunnerStage getStage() {
    return PersistedRegistryValues.runnerExecutionStage(stage);
  }

  public void setStage(RunnerStage stage) {
    this.stage = Objects.requireNonNull(stage, "stage").value();
  }

  public RunnerExecutionStatus getStatus() {
    return PersistedRegistryValues.runnerExecutionStatus(status);
  }

  public void setStatus(RunnerExecutionStatus status) {
    this.status = Objects.requireNonNull(status, "status").value();
  }

  public int getContextBundleVersion() {
    return contextBundleVersion;
  }

  public void setContextBundleVersion(int contextBundleVersion) {
    this.contextBundleVersion = contextBundleVersion;
  }

  public OffsetDateTime getLastActivityAt() {
    return lastActivityAt;
  }

  public void setLastActivityAt(OffsetDateTime lastActivityAt) {
    this.lastActivityAt = lastActivityAt;
  }

  public OffsetDateTime getTimeoutAt() {
    return timeoutAt;
  }

  public void setTimeoutAt(OffsetDateTime timeoutAt) {
    this.timeoutAt = timeoutAt;
  }

  public FailureCategory getFailureCategory() {
    return failureCategory == null
        ? null
        : PersistedRegistryValues.workflowEventFailureCategory(failureCategory);
  }

  public void setFailureCategory(FailureCategory failureCategory) {
    this.failureCategory = failureCategory == null ? null : failureCategory.value();
  }

  public OffsetDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(OffsetDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(OffsetDateTime archivedAt) {
    this.archivedAt = archivedAt;
  }

  public OffsetDateTime getHeartbeatStaleEmittedAt() {
    return heartbeatStaleEmittedAt;
  }

  public void setHeartbeatStaleEmittedAt(OffsetDateTime heartbeatStaleEmittedAt) {
    this.heartbeatStaleEmittedAt = heartbeatStaleEmittedAt;
  }

  public OffsetDateTime getDispatchedAt() {
    return dispatchedAt;
  }

  public void setDispatchedAt(OffsetDateTime dispatchedAt) {
    this.dispatchedAt = dispatchedAt;
  }

  public String getWorkerId() {
    return workerId;
  }

  public void setWorkerId(String workerId) {
    this.workerId = workerId;
  }

  public int getQueuePriority() {
    return queuePriority;
  }

  public void setQueuePriority(int queuePriority) {
    this.queuePriority = queuePriority;
  }

  public int getQueueAttemptCount() {
    return queueAttemptCount;
  }

  public void setQueueAttemptCount(int queueAttemptCount) {
    this.queueAttemptCount = queueAttemptCount;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }

  public String getRawOutputReference() {
    return rawOutputReference;
  }

  public void setRawOutputReference(String rawOutputReference) {
    this.rawOutputReference = rawOutputReference;
  }

  public String getRawOutputClassification() {
    return rawOutputClassification;
  }

  public void setRawOutputClassification(String rawOutputClassification) {
    this.rawOutputClassification = rawOutputClassification;
  }

  public Long getRawOutputByteSize() {
    return rawOutputByteSize;
  }

  public void setRawOutputByteSize(Long rawOutputByteSize) {
    this.rawOutputByteSize = rawOutputByteSize;
  }

  public Integer getRedactionCount() {
    return redactionCount;
  }

  public void setRedactionCount(Integer redactionCount) {
    this.redactionCount = redactionCount;
  }

  @PrePersist
  void initializeCreatedAt() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
  }
}
