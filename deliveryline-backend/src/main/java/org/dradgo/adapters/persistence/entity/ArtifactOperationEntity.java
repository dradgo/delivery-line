package org.dradgo.adapters.persistence.entity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.PersistedRegistryValues;

@Entity
@Table(name = "artifact_operations")
public class ArtifactOperationEntity {

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

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "linked_event_id", nullable = false)
	private WorkflowEventEntity linkedEvent;

	@Column(name = "operation_type", nullable = false)
	private String operationType;

	@Column(name = "artifact_type", nullable = false)
	private String artifactType;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "failure_category")
	private String failureCategory;

	@Column(name = "reason")
	private String reason;

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

	public ArtifactEntity getArtifact() {
		return artifact;
	}

	public void setArtifact(ArtifactEntity artifact) {
		this.artifact = artifact;
	}

	public WorkflowEventEntity getLinkedEvent() {
		return linkedEvent;
	}

	public void setLinkedEvent(WorkflowEventEntity linkedEvent) {
		this.linkedEvent = linkedEvent;
	}

	public String getOperationType() {
		return operationType;
	}

	public void setOperationType(String operationType) {
		this.operationType = operationType;
	}

	public ArtifactType getArtifactType() {
		return PersistedRegistryValues.artifactType(artifactType);
	}

	public void setArtifactType(ArtifactType artifactType) {
		this.artifactType = Objects.requireNonNull(artifactType, "artifactType").value();
	}

	public ArtifactOperationStatus getStatus() {
		return PersistedRegistryValues.artifactOperationStatus(status);
	}

	public void setStatus(ArtifactOperationStatus status) {
		this.status = Objects.requireNonNull(status, "status").value();
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public FailureCategory getFailureCategory() {
		return PersistedRegistryValues.artifactOperationFailureCategory(failureCategory);
	}

	public void setFailureCategory(FailureCategory failureCategory) {
		this.failureCategory = failureCategory == null ? null : failureCategory.value();
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
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
