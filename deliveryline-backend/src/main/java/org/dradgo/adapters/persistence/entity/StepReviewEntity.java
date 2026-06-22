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
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.ReviewOutcome;

/**
 * Story 3d-2 (AC2/AC4) — JPA entity for the V19 {@code step_reviews} advisory-verdict table (3d-1).
 * Mirrors {@code ApprovalEntity}: the composite FK {@code (reviewed_artifact_id,
 * reviewed_artifact_version) → artifacts (id, version)} is enforced at the schema level; this
 * entity holds the {@code ManyToOne} on {@code reviewed_artifact_id} and preserves the version pin
 * via the {@code reviewed_artifact_version} column so a verdict pins to the exact artifact version
 * it reviewed even if the lineage advances afterwards.
 *
 * <p>{@code outcome} is stored as the wire string and parsed on read via {@link
 * PersistedRegistryValues#stepReviewOutcome}. {@code archived_at} stays null (retention is Epic 5).
 * All FK associations are {@code LAZY}; read paths on the worker pool (no OSIV) MUST {@code join
 * fetch} them — see {@code StepReviewRepository}.
 */
@Entity
@Table(name = "step_reviews")
public class StepReviewEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workflow_run_id", nullable = false)
  private WorkflowRunEntity workflowRun;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "runner_execution_id", nullable = false)
  private RunnerExecutionEntity runnerExecution;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reviewed_artifact_id", nullable = false)
  private ArtifactEntity reviewedArtifact;

  @Column(name = "reviewed_artifact_version", nullable = false)
  private int reviewedArtifactVersion;

  @Column(name = "outcome", nullable = false)
  private String outcome;

  @Column(name = "rationale")
  private String rationale;

  @Column(name = "reviewer_model_identity")
  private String reviewerModelIdentity;

  @Column(name = "producer_model_identity")
  private String producerModelIdentity;

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

  public WorkflowRunEntity getWorkflowRun() {
    return workflowRun;
  }

  public void setWorkflowRun(WorkflowRunEntity workflowRun) {
    this.workflowRun = workflowRun;
  }

  public RunnerExecutionEntity getRunnerExecution() {
    return runnerExecution;
  }

  public void setRunnerExecution(RunnerExecutionEntity runnerExecution) {
    this.runnerExecution = runnerExecution;
  }

  public ArtifactEntity getReviewedArtifact() {
    return reviewedArtifact;
  }

  public void setReviewedArtifact(ArtifactEntity reviewedArtifact) {
    this.reviewedArtifact = reviewedArtifact;
  }

  public int getReviewedArtifactVersion() {
    return reviewedArtifactVersion;
  }

  public void setReviewedArtifactVersion(int reviewedArtifactVersion) {
    this.reviewedArtifactVersion = reviewedArtifactVersion;
  }

  public ReviewOutcome getOutcome() {
    return PersistedRegistryValues.stepReviewOutcome(outcome);
  }

  public void setOutcome(ReviewOutcome outcome) {
    this.outcome = Objects.requireNonNull(outcome, "outcome").value();
  }

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String rationale) {
    this.rationale = rationale;
  }

  public String getReviewerModelIdentity() {
    return reviewerModelIdentity;
  }

  public void setReviewerModelIdentity(String reviewerModelIdentity) {
    this.reviewerModelIdentity = reviewerModelIdentity;
  }

  public String getProducerModelIdentity() {
    return producerModelIdentity;
  }

  public void setProducerModelIdentity(String producerModelIdentity) {
    this.producerModelIdentity = producerModelIdentity;
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
      createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
  }
}
