package org.dradgo.adapters.persistence.entity;

import java.time.OffsetDateTime;
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
import jakarta.persistence.Table;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.PersistedRegistryValues;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_events")
public class WorkflowEventEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "public_id", nullable = false)
	private String publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workflow_run_id", nullable = false)
	private WorkflowRunEntity workflowRun;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "prior_state")
	private String priorState;

	@Column(name = "resulting_state")
	private String resultingState;

	@Column(name = "actor_identity", nullable = false)
	private String actorIdentity;

	@Column(name = "actor_type", nullable = false)
	private String actorType;

	@Column(name = "reviewer_role")
	private String reviewerRole;

	@Column(name = "reason")
	private String reason;

	@Column(name = "failure_category")
	private String failureCategory;

	@Column(name = "intervention_marker", nullable = false)
	private boolean interventionMarker;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "details", nullable = false)
	private Map<String, Object> details = new LinkedHashMap<>();

	@Column(name = "stage_duration_ms")
	private Long stageDurationMs;

	@Column(name = "rejection_taxonomy")
	private String rejectionTaxonomy;

	@Column(name = "created_at", nullable = false)
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

	public WorkflowEventType getEventType() {
		return PersistedRegistryValues.workflowEventType(eventType);
	}

	public void setEventType(WorkflowEventType eventType) {
		this.eventType = Objects.requireNonNull(eventType, "eventType").value();
	}

	public WorkflowState getPriorState() {
		return PersistedRegistryValues.workflowEventPriorState(priorState);
	}

	public void setPriorState(WorkflowState priorState) {
		this.priorState = priorState == null ? null : priorState.value();
	}

	public WorkflowState getResultingState() {
		return PersistedRegistryValues.workflowEventResultingState(resultingState);
	}

	public void setResultingState(WorkflowState resultingState) {
		this.resultingState = resultingState == null ? null : resultingState.value();
	}

	public String getActorIdentity() {
		return actorIdentity;
	}

	public void setActorIdentity(String actorIdentity) {
		this.actorIdentity = actorIdentity;
	}

	public ActorType getActorType() {
		return PersistedRegistryValues.workflowEventActorType(actorType);
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

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public FailureCategory getFailureCategory() {
		return PersistedRegistryValues.workflowEventFailureCategory(failureCategory);
	}

	public void setFailureCategory(FailureCategory failureCategory) {
		this.failureCategory = failureCategory == null ? null : failureCategory.value();
	}

	public boolean isInterventionMarker() {
		return interventionMarker;
	}

	public void setInterventionMarker(boolean interventionMarker) {
		this.interventionMarker = interventionMarker;
	}

	public Map<String, Object> getDetails() {
		return details;
	}

	public void setDetails(Map<String, Object> details) {
		this.details = Objects.requireNonNull(details, "details");
	}

	public Long getStageDurationMs() {
		return stageDurationMs;
	}

	public void setStageDurationMs(Long stageDurationMs) {
		this.stageDurationMs = stageDurationMs;
	}

	public String getRejectionTaxonomy() {
		return rejectionTaxonomy;
	}

	public void setRejectionTaxonomy(String rejectionTaxonomy) {
		this.rejectionTaxonomy = rejectionTaxonomy;
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
}
