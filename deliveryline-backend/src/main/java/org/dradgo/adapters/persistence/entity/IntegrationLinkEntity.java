package org.dradgo.adapters.persistence.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mapping for the V1 {@code integration_links} table. Mirrors V1 column types and CHECK
 * constraints; the persistence adapter is responsible for never inserting a value that violates
 * a CHECK (state machine guards {@code sync_status}; the SPI's {@code byte[] externalMetadata}
 * has its 64KB ceiling validated at the application boundary before reaching this entity).
 *
 * <p>{@code externalMetadata} is mapped as a Hibernate-managed {@code Map} over a {@code jsonb}
 * column (matches the {@code workflow_events.details} pattern); the persistence adapter
 * translates raw {@code byte[]} from the SPI to/from the map via Jackson.
 */
@Entity
@Table(name = "integration_links")
public class IntegrationLinkEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "public_id", nullable = false)
	private String publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workflow_run_id", nullable = false)
	private WorkflowRunEntity workflowRun;

	@Column(name = "integration_type", nullable = false)
	private String integrationType;

	@Column(name = "external_ref", nullable = false)
	private String externalRef;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "external_metadata", nullable = false)
	private Map<String, Object> externalMetadata = new LinkedHashMap<>();

	@Column(name = "last_sync_at")
	private OffsetDateTime lastSyncAt;

	@Column(name = "sync_status", nullable = false)
	private String syncStatus;

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

	public String getIntegrationType() {
		return integrationType;
	}

	public void setIntegrationType(String integrationType) {
		this.integrationType = integrationType;
	}

	public String getExternalRef() {
		return externalRef;
	}

	public void setExternalRef(String externalRef) {
		this.externalRef = externalRef;
	}

	public Map<String, Object> getExternalMetadata() {
		return externalMetadata;
	}

	public void setExternalMetadata(Map<String, Object> externalMetadata) {
		this.externalMetadata = externalMetadata == null ? new LinkedHashMap<>() : externalMetadata;
	}

	public OffsetDateTime getLastSyncAt() {
		return lastSyncAt;
	}

	public void setLastSyncAt(OffsetDateTime lastSyncAt) {
		this.lastSyncAt = lastSyncAt;
	}

	public IntegrationSyncStatus getSyncStatus() {
		return PersistedRegistryValues.integrationSyncStatus(syncStatus);
	}

	public void setSyncStatus(IntegrationSyncStatus syncStatus) {
		this.syncStatus = Objects.requireNonNull(syncStatus, "syncStatus").value();
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
