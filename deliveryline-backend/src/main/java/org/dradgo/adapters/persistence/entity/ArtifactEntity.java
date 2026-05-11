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
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.PersistedRegistryValues;

@Entity
@Table(name = "artifacts")
public class ArtifactEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "public_id", nullable = false)
	private String publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workflow_run_id", nullable = false)
	private WorkflowRunEntity workflowRun;

	@Column(name = "artifact_type", nullable = false)
	private String artifactType;

	@Column(name = "version", nullable = false)
	private int version;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_artifact_id")
	private ArtifactEntity parentArtifact;

	@Column(name = "classification", nullable = false)
	private String classification;

	@Column(name = "storage_ref")
	private String storageRef;

	@Column(name = "checksum_algorithm")
	private String checksumAlgorithm;

	@Column(name = "checksum_value")
	private String checksumValue;

	@Column(name = "failure_category")
	private String failureCategory;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(name = "status", nullable = false)
	private String status;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "linked_event_id", nullable = false)
	private WorkflowEventEntity linkedEvent;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "archived_at")
	private OffsetDateTime archivedAt;

	@Column(name = "lineage_recovery", nullable = false)
	private boolean lineageRecovery;

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

	public ArtifactType getArtifactType() {
		return PersistedRegistryValues.artifactType(artifactType);
	}

	public void setArtifactType(ArtifactType artifactType) {
		this.artifactType = Objects.requireNonNull(artifactType, "artifactType").value();
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public ArtifactEntity getParentArtifact() {
		return parentArtifact;
	}

	public void setParentArtifact(ArtifactEntity parentArtifact) {
		this.parentArtifact = parentArtifact;
	}

	public DataClassification getClassification() {
		return PersistedRegistryValues.artifactClassification(classification);
	}

	public void setClassification(DataClassification classification) {
		this.classification = Objects.requireNonNull(classification, "classification").value();
	}

	public String getStorageRef() {
		return storageRef;
	}

	public void setStorageRef(String storageRef) {
		this.storageRef = storageRef;
	}

	public String getChecksumAlgorithm() {
		return checksumAlgorithm;
	}

	public void setChecksumAlgorithm(String checksumAlgorithm) {
		this.checksumAlgorithm = checksumAlgorithm;
	}

	public String getChecksumValue() {
		return checksumValue;
	}

	public void setChecksumValue(String checksumValue) {
		this.checksumValue = checksumValue;
	}

	public FailureCategory getFailureCategory() {
		return PersistedRegistryValues.artifactFailureCategory(failureCategory);
	}

	public void setFailureCategory(FailureCategory failureCategory) {
		this.failureCategory = failureCategory == null ? null : failureCategory.value();
	}

	public String getFailureReason() {
		return failureReason;
	}

	public void setFailureReason(String failureReason) {
		this.failureReason = failureReason;
	}

	public ArtifactStatus getStatus() {
		return PersistedRegistryValues.artifactStatus(status);
	}

	public void setStatus(ArtifactStatus status) {
		this.status = Objects.requireNonNull(status, "status").value();
	}

	public WorkflowEventEntity getLinkedEvent() {
		return linkedEvent;
	}

	public void setLinkedEvent(WorkflowEventEntity linkedEvent) {
		this.linkedEvent = linkedEvent;
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

	public boolean isLineageRecovery() {
		return lineageRecovery;
	}

	public void setLineageRecovery(boolean lineageRecovery) {
		this.lineageRecovery = lineageRecovery;
	}

	@PrePersist
	void initializeCreatedAt() {
		if (createdAt == null) {
			createdAt = OffsetDateTime.now(ZoneOffset.UTC);
		}
	}
}
