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
import java.util.Objects;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.PersistedRegistryValues;

/**
 * Story 3c-5 — JPA mapping for the V17 {@code project_credentials} table. Mirrors the live {@code
 * IntegrationLinkEntity} convention:
 *
 * <ul>
 *   <li>{@code id} is a {@code bigserial} surrogate; the {@code cred_} prefix lives on {@code
 *       public_id}, never on the PK.
 *   <li>{@code project_id} is a plain {@code text} column (the V17 DB FK enforces referential
 *       integrity) — deliberately <strong>NOT</strong> a {@code @ManyToOne ProjectEntity} (R2: stay
 *       decoupled from the 3c-6 project read-side; the FK-violation&rarr;{@code PROJECT_NOT_FOUND}
 *       mapping at the adapter is the existence check).
 *   <li>{@code connector_role} is stored raw {@code text} and parsed at the getter through {@link
 *       PersistedRegistryValues#projectCredentialConnectorRole(String)} (fail fast on an unknown DB
 *       value), exactly like {@link IntegrationLinkEntity#getSyncStatus()}.
 *   <li>{@code created_at} is app-stamped via {@code @PrePersist} (mirrors {@code
 *       IntegrationLinkEntity}); the V17 {@code default now()} stays a fallback for raw-SQL
 *       inserts.
 * </ul>
 *
 * <p>Secret-bearing: {@code ciphertext} carries no Jackson annotations and is never projected onto
 * a DTO (the credential REST surface is 3c-8). This entity exposes no read path that serializes the
 * ciphertext to a client (AC2).
 */
@Entity
@Table(name = "project_credentials")
public class ProjectCredentialEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private String publicId;

  @Column(name = "project_id", nullable = false)
  private String projectId;

  @Column(name = "connector_role", nullable = false)
  private String connectorRole;

  @Column(name = "ciphertext", nullable = false)
  private byte[] ciphertext;

  @Column(name = "key_id", nullable = false)
  private String keyId;

  @Column(name = "algo", nullable = false)
  private String algo;

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

  public String getProjectId() {
    return projectId;
  }

  public void setProjectId(String projectId) {
    this.projectId = projectId;
  }

  public ConnectorRole getConnectorRole() {
    return PersistedRegistryValues.projectCredentialConnectorRole(connectorRole);
  }

  public void setConnectorRole(ConnectorRole connectorRole) {
    this.connectorRole = Objects.requireNonNull(connectorRole, "connectorRole").value();
  }

  public byte[] getCiphertext() {
    return ciphertext;
  }

  public void setCiphertext(byte[] ciphertext) {
    this.ciphertext = ciphertext;
  }

  public String getKeyId() {
    return keyId;
  }

  public void setKeyId(String keyId) {
    this.keyId = keyId;
  }

  public String getAlgo() {
    return algo;
  }

  public void setAlgo(String algo) {
    this.algo = algo;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
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
