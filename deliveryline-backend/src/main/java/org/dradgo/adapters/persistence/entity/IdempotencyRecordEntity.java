package org.dradgo.adapters.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.PersistedRegistryValues;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecordEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "key", nullable = false)
  private String key;

  @Column(name = "command_type", nullable = false)
  private String commandType;

  @Column(name = "actor_identity", nullable = false)
  private String actorIdentity;

  @Column(name = "command_fingerprint", nullable = false)
  private String commandFingerprint;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "result_ref")
  private String resultRef;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  @Column(name = "expires_at")
  private OffsetDateTime expiresAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
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

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getCommandType() {
    return commandType;
  }

  public void setCommandType(String commandType) {
    this.commandType = commandType;
  }

  public String getActorIdentity() {
    return actorIdentity;
  }

  public void setActorIdentity(String actorIdentity) {
    this.actorIdentity = actorIdentity;
  }

  public String getCommandFingerprint() {
    return commandFingerprint;
  }

  public void setCommandFingerprint(String commandFingerprint) {
    this.commandFingerprint = commandFingerprint;
  }

  public IdempotencyRecordStatus getStatus() {
    return PersistedRegistryValues.idempotencyRecordStatus(status);
  }

  public void setStatus(IdempotencyRecordStatus status) {
    this.status = Objects.requireNonNull(status, "status").value();
  }

  public String getResultRef() {
    return resultRef;
  }

  public void setResultRef(String resultRef) {
    this.resultRef = resultRef;
  }

  public OffsetDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(OffsetDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(OffsetDateTime expiresAt) {
    this.expiresAt = expiresAt;
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
}
