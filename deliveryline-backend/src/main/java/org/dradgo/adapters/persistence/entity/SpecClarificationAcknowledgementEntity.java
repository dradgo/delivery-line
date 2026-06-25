package org.dradgo.adapters.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * JPA entity for the V25 {@code spec_clarification_acknowledgements} side-store (story 3e-2). One
 * row per (spec artifact, questionId) structured acknowledgement the spec runner emitted, persisted
 * at broker ingest and read by {@code ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild}
 * (the sweep sees only the artifact, not the runner result). No secret content — only the
 * questionId and an {@code addressed} boolean.
 */
@Entity
@Table(name = "spec_clarification_acknowledgements")
public class SpecClarificationAcknowledgementEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "spec_artifact_id", nullable = false)
  private String specArtifactId;

  @Column(name = "question_id", nullable = false)
  private String questionId;

  @Column(name = "addressed", nullable = false)
  private boolean addressed;

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

  public String getSpecArtifactId() {
    return specArtifactId;
  }

  public void setSpecArtifactId(String specArtifactId) {
    this.specArtifactId = specArtifactId;
  }

  public String getQuestionId() {
    return questionId;
  }

  public void setQuestionId(String questionId) {
    this.questionId = questionId;
  }

  public boolean isAddressed() {
    return addressed;
  }

  public void setAddressed(boolean addressed) {
    this.addressed = addressed;
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
      // the row reconstructed on a read round-trip (mirrors ProviderUsageSnapshotEntity).
      createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
  }
}
