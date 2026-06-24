package org.dradgo.adapters.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * JPA entity for the V24 {@code provider_usage_snapshots} table (story 3d-7). One row per captured
 * provider usage/limit snapshot, keyed to the workflow run and the NON-SECRET {@code
 * account_reference} that produced it (Trap T1). NO secret/token column exists — only window
 * numbers, timestamps, and the non-secret label.
 */
@Entity
@Table(name = "provider_usage_snapshots")
public class ProviderUsageSnapshotEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "workflow_run_id", nullable = false)
  private String workflowRunId;

  @Column(name = "runner_execution_id")
  private String runnerExecutionId;

  @Column(name = "account_reference", nullable = false)
  private String accountReference;

  @Column(name = "signal_state", nullable = false)
  private String signalState;

  @Column(name = "five_hour_used_fraction")
  private BigDecimal fiveHourUsedFraction;

  @Column(name = "five_hour_used")
  private Integer fiveHourUsed;

  @Column(name = "five_hour_limit")
  private Integer fiveHourLimit;

  @Column(name = "five_hour_resets_at")
  private OffsetDateTime fiveHourResetsAt;

  @Column(name = "weekly_used_fraction")
  private BigDecimal weeklyUsedFraction;

  @Column(name = "weekly_used")
  private Integer weeklyUsed;

  @Column(name = "weekly_limit")
  private Integer weeklyLimit;

  @Column(name = "weekly_resets_at")
  private OffsetDateTime weeklyResetsAt;

  @Column(name = "as_of")
  private OffsetDateTime asOf;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;

  public Long getId() {
    return id;
  }

  public String getPublicId() {
    return publicId;
  }

  public void setPublicId(String publicId) {
    this.publicId = publicId;
  }

  public String getWorkflowRunId() {
    return workflowRunId;
  }

  public void setWorkflowRunId(String workflowRunId) {
    this.workflowRunId = workflowRunId;
  }

  public String getRunnerExecutionId() {
    return runnerExecutionId;
  }

  public void setRunnerExecutionId(String runnerExecutionId) {
    this.runnerExecutionId = runnerExecutionId;
  }

  public String getAccountReference() {
    return accountReference;
  }

  public void setAccountReference(String accountReference) {
    this.accountReference = accountReference;
  }

  public String getSignalState() {
    return signalState;
  }

  public void setSignalState(String signalState) {
    this.signalState = signalState;
  }

  public BigDecimal getFiveHourUsedFraction() {
    return fiveHourUsedFraction;
  }

  public void setFiveHourUsedFraction(BigDecimal fiveHourUsedFraction) {
    this.fiveHourUsedFraction = fiveHourUsedFraction;
  }

  public Integer getFiveHourUsed() {
    return fiveHourUsed;
  }

  public void setFiveHourUsed(Integer fiveHourUsed) {
    this.fiveHourUsed = fiveHourUsed;
  }

  public Integer getFiveHourLimit() {
    return fiveHourLimit;
  }

  public void setFiveHourLimit(Integer fiveHourLimit) {
    this.fiveHourLimit = fiveHourLimit;
  }

  public OffsetDateTime getFiveHourResetsAt() {
    return fiveHourResetsAt;
  }

  public void setFiveHourResetsAt(OffsetDateTime fiveHourResetsAt) {
    this.fiveHourResetsAt = fiveHourResetsAt;
  }

  public BigDecimal getWeeklyUsedFraction() {
    return weeklyUsedFraction;
  }

  public void setWeeklyUsedFraction(BigDecimal weeklyUsedFraction) {
    this.weeklyUsedFraction = weeklyUsedFraction;
  }

  public Integer getWeeklyUsed() {
    return weeklyUsed;
  }

  public void setWeeklyUsed(Integer weeklyUsed) {
    this.weeklyUsed = weeklyUsed;
  }

  public Integer getWeeklyLimit() {
    return weeklyLimit;
  }

  public void setWeeklyLimit(Integer weeklyLimit) {
    this.weeklyLimit = weeklyLimit;
  }

  public OffsetDateTime getWeeklyResetsAt() {
    return weeklyResetsAt;
  }

  public void setWeeklyResetsAt(OffsetDateTime weeklyResetsAt) {
    this.weeklyResetsAt = weeklyResetsAt;
  }

  public OffsetDateTime getAsOf() {
    return asOf;
  }

  public void setAsOf(OffsetDateTime asOf) {
    this.asOf = asOf;
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

  @PrePersist
  void initializeCreatedAt() {
    if (createdAt == null) {
      // Truncate to microseconds (Postgres timestamptz precision) so the in-memory value matches
      // the row reconstructed on a read round-trip (mirrors BatchSubmissionEntity).
      createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
  }
}
