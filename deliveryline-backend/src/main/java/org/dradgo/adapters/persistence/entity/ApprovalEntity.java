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
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.PersistedRegistryValues;

/**
 * JPA entity mapping for the V1 {@code approvals} table. Read-side use only in story 2.8; stories
 * 2.9 (approveSpec writer) and 2.10 (rejectSpec writer + escalation) will introduce writer paths.
 *
 * <p>The composite FK {@code (artifact_id, artifact_version) → artifacts (id, version)} is enforced
 * at the schema level (V1 migration line 188–190). This entity stores both columns and holds the
 * {@code ManyToOne} on {@code artifact_id} only — the version pin is preserved via the {@code
 * artifact_version} column so a row pins to its exact artifact version even if the artifact lineage
 * advances afterwards.
 */
@Entity
@Table(name = "approvals")
public class ApprovalEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "workflow_run_id", nullable = false)
  private WorkflowRunEntity workflowRun;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "artifact_id", nullable = false)
  private ArtifactEntity artifact;

  @Column(name = "artifact_version", nullable = false)
  private int artifactVersion;

  @Column(name = "context_bundle_version", nullable = false)
  private int contextBundleVersion;

  @Column(name = "actor_identity", nullable = false)
  private String actorIdentity;

  @Column(name = "actor_type", nullable = false)
  private String actorType;

  @Column(name = "reviewer_role", nullable = false)
  private String reviewerRole;

  @Column(name = "decision", nullable = false)
  private String decision;

  @Column(name = "reason")
  private String reason;

  @Column(name = "rejection_taxonomy")
  private String rejectionTaxonomy;

  @Column(name = "decided_at", nullable = false)
  private OffsetDateTime decidedAt;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "archived_at")
  private OffsetDateTime archivedAt;

  // Story 4.7 (V42) — approval invalidation. Set when a rerun-from-step supersedes this approval's
  // stage decision; `invalidated_at IS NULL` means the approval is still current. Mirrors the
  // archived_at nullable-timestamp shape.
  @Column(name = "invalidated_at")
  private OffsetDateTime invalidatedAt;

  @Column(name = "invalidated_reason")
  private String invalidatedReason;

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

  public ArtifactEntity getArtifact() {
    return artifact;
  }

  public void setArtifact(ArtifactEntity artifact) {
    this.artifact = artifact;
  }

  public int getArtifactVersion() {
    return artifactVersion;
  }

  public void setArtifactVersion(int artifactVersion) {
    this.artifactVersion = artifactVersion;
  }

  public int getContextBundleVersion() {
    return contextBundleVersion;
  }

  public void setContextBundleVersion(int contextBundleVersion) {
    this.contextBundleVersion = contextBundleVersion;
  }

  public String getActorIdentity() {
    return actorIdentity;
  }

  public void setActorIdentity(String actorIdentity) {
    this.actorIdentity = actorIdentity;
  }

  public ActorType getActorType() {
    return PersistedRegistryValues.approvalActorType(actorType);
  }

  public void setActorType(ActorType actorType) {
    this.actorType = Objects.requireNonNull(actorType, "actorType").value();
  }

  public String getReviewerRole() {
    return reviewerRole;
  }

  public void setReviewerRole(String reviewerRole) {
    this.reviewerRole = reviewerRole;
  }

  public String getDecision() {
    return decision;
  }

  public void setDecision(String decision) {
    this.decision = decision;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getRejectionTaxonomy() {
    return rejectionTaxonomy;
  }

  public void setRejectionTaxonomy(String rejectionTaxonomy) {
    this.rejectionTaxonomy = rejectionTaxonomy;
  }

  public OffsetDateTime getDecidedAt() {
    return decidedAt;
  }

  public void setDecidedAt(OffsetDateTime decidedAt) {
    this.decidedAt = decidedAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
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

  public OffsetDateTime getInvalidatedAt() {
    return invalidatedAt;
  }

  public void setInvalidatedAt(OffsetDateTime invalidatedAt) {
    this.invalidatedAt = invalidatedAt;
  }

  public String getInvalidatedReason() {
    return invalidatedReason;
  }

  public void setInvalidatedReason(String invalidatedReason) {
    this.invalidatedReason = invalidatedReason;
  }

  @PrePersist
  void initializeCreatedAt() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
  }
}
