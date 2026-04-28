package org.dradgo.adapters.persistence.entity;

import java.time.OffsetDateTime;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.WorkflowState;

@Entity
@Table(name = "workflow_runs")
public class WorkflowRunEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "public_id", nullable = false)
	private String publicId;

	@Column(name = "current_state", nullable = false)
	private String currentState;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "archived_at")
	private OffsetDateTime archivedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version = 0L;

	public Long getId() {
		return id;
	}

	public String getPublicId() {
		return publicId;
	}

	public void setPublicId(String publicId) {
		this.publicId = publicId;
	}

	public WorkflowState getCurrentState() {
		return PersistedRegistryValues.workflowRunState(currentState);
	}

	public void setCurrentState(WorkflowState currentState) {
		this.currentState = Objects.requireNonNull(currentState, "currentState").value();
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

	public Long getVersion() {
		return version;
	}
}
