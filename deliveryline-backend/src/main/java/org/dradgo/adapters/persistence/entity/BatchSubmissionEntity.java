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
 * JPA entity mapping for the V15 {@code batch_submissions} table (story 3.18). One row per batch
 * submission; the per-ticket outcomes (including rejected tickets, which have no {@code
 * runner_executions} row) are stored as a JSON document in {@code result_json} so an idempotent
 * replay reconstructs the full {@code BatchSubmissionResult} (Decision D-PERSIST, result_json
 * variant).
 */
@Entity
@Table(name = "batch_submissions")
public class BatchSubmissionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "actor_identity", nullable = false)
  private String actorIdentity;

  @Column(name = "actor_type", nullable = false)
  private String actorType;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(name = "total", nullable = false)
  private int total;

  @Column(name = "queued_count", nullable = false)
  private int queuedCount;

  @Column(name = "rejected_count", nullable = false)
  private int rejectedCount;

  @Column(name = "result_json", nullable = false)
  private String resultJson;

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

  public String getActorIdentity() {
    return actorIdentity;
  }

  public void setActorIdentity(String actorIdentity) {
    this.actorIdentity = actorIdentity;
  }

  public String getActorType() {
    return actorType;
  }

  public void setActorType(String actorType) {
    this.actorType = actorType;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public int getTotal() {
    return total;
  }

  public void setTotal(int total) {
    this.total = total;
  }

  public int getQueuedCount() {
    return queuedCount;
  }

  public void setQueuedCount(int queuedCount) {
    this.queuedCount = queuedCount;
  }

  public int getRejectedCount() {
    return rejectedCount;
  }

  public void setRejectedCount(int rejectedCount) {
    this.rejectedCount = rejectedCount;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
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
      // Truncate to microseconds (Postgres timestamptz precision) so the in-memory value returned
      // by the first submit is byte-identical to the value reconstructed from the row on an
      // idempotent replay (story 3.18 AC9 — submittedAt must match across the DB round-trip).
      createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
  }
}
