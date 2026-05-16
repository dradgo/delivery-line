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
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.PersistedRegistryValues;

/**
 * JPA mapping for the V1 {@code recovery_actions} table (story 1.18 Task 2). Mirrors V1 column
 * types and CHECK constraints — the persistence adapter is responsible for inserting only values
 * that satisfy {@code ck_recovery_actions_action_type}, {@code ck_recovery_actions_actor_type},
 * and {@code ck_recovery_actions_result_status}.
 *
 * <p>FK columns ({@code triggering_event_id}, {@code resulting_event_id}) are nullable per V1 —
 * the adapter resolves them from public-id lookup and leaves them null when the caller passes
 * {@code null}.
 */
@Entity
@Table(name = "recovery_actions")
public class RecoveryActionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "public_id", nullable = false)
	private String publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "workflow_run_id", nullable = false)
	private WorkflowRunEntity workflowRun;

	@Column(name = "action_type", nullable = false)
	private String actionType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "triggering_event_id")
	private WorkflowEventEntity triggeringEvent;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "resulting_event_id")
	private WorkflowEventEntity resultingEvent;

	@Column(name = "actor_identity", nullable = false)
	private String actorIdentity;

	@Column(name = "actor_type", nullable = false)
	private String actorType;

	@Column(name = "reviewer_role")
	private String reviewerRole;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "result_status", nullable = false)
	private String resultStatus;

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

	public String getActionType() {
		return actionType;
	}

	public void setActionType(String actionType) {
		this.actionType = Objects.requireNonNull(actionType, "actionType");
	}

	public WorkflowEventEntity getTriggeringEvent() {
		return triggeringEvent;
	}

	public void setTriggeringEvent(WorkflowEventEntity triggeringEvent) {
		this.triggeringEvent = triggeringEvent;
	}

	public WorkflowEventEntity getResultingEvent() {
		return resultingEvent;
	}

	public void setResultingEvent(WorkflowEventEntity resultingEvent) {
		this.resultingEvent = resultingEvent;
	}

	public String getActorIdentity() {
		return actorIdentity;
	}

	public void setActorIdentity(String actorIdentity) {
		this.actorIdentity = actorIdentity;
	}

	public ActorType getActorType() {
		return PersistedRegistryValues.recoveryActorType(actorType);
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

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public String getResultStatus() {
		return resultStatus;
	}

	public void setResultStatus(String resultStatus) {
		this.resultStatus = Objects.requireNonNull(resultStatus, "resultStatus");
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
