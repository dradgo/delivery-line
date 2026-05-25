package org.dradgo.adapters.persistence.entity;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.PersistedRegistryValues;

/**
 * JPA entity mapping for the V8 {@code clarifications} table (story 2.11).
 *
 * <p>The composite FK {@code (artifact_id, artifact_version) → artifacts (id, version)} is enforced
 * at the schema level (V8 {@code fk_clarifications_artifacts}). This entity stores both columns and
 * holds the {@code ManyToOne} on {@code artifact_id} only — the version pin is preserved via the
 * {@code artifact_version} column so a row pins to its exact artifact version even if the artifact
 * lineage advances afterwards. Same pattern as {@link ApprovalEntity}.
 */
@Entity
@Table(name = "clarifications")
public class ClarificationEntity {

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

  @Column(name = "artifact_version", nullable = false)
  private int artifactVersion;

  @Column(name = "question_id", nullable = false)
  private String questionId;

  @Column(name = "question_text", nullable = false)
  private String questionText;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "answer_text")
  private String answerText;

  @Column(name = "answered_by_actor")
  private String answeredByActor;

  @Column(name = "answered_by_actor_type")
  private String answeredByActorType;

  @Column(name = "answered_at")
  private OffsetDateTime answeredAt;

  @Column(name = "incorporation_event_id")
  private Long incorporationEventId;

  // V9 (story 2.12): lifecycle metadata columns.
  @Column(name = "accepted_at")
  private OffsetDateTime acceptedAt;

  @Column(name = "incorporated_at")
  private OffsetDateTime incorporatedAt;

  @Column(name = "superseded_by_artifact_id")
  private Long supersededByArtifactId;

  @Column(name = "superseded_by_artifact_version")
  private Integer supersededByArtifactVersion;

  @Column(name = "no_effect_reason")
  private String noEffectReason;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

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

  public int getArtifactVersion() {
    return artifactVersion;
  }

  public void setArtifactVersion(int artifactVersion) {
    this.artifactVersion = artifactVersion;
  }

  public String getQuestionId() {
    return questionId;
  }

  public void setQuestionId(String questionId) {
    this.questionId = questionId;
  }

  public String getQuestionText() {
    return questionText;
  }

  public void setQuestionText(String questionText) {
    this.questionText = questionText;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getAnswerText() {
    return answerText;
  }

  public void setAnswerText(String answerText) {
    this.answerText = answerText;
  }

  public String getAnsweredByActor() {
    return answeredByActor;
  }

  public void setAnsweredByActor(String answeredByActor) {
    this.answeredByActor = answeredByActor;
  }

  /**
   * Returns the persisted actor type as the typed registry value. Uses {@link
   * PersistedRegistryValues#approvalActorType} because clarifications mirror approvals' actor-type
   * CHECK ({@code human | agent | system | service_account}); the persistence-boundary parsing
   * helper is shared. Returns {@code null} only when {@code answered_by_actor_type IS NULL} (status
   * = open invariant — Trap T9).
   */
  public ActorType getAnsweredByActorType() {
    return answeredByActorType == null
        ? null
        : PersistedRegistryValues.approvalActorType(answeredByActorType);
  }

  public void setAnsweredByActorType(ActorType answeredByActorType) {
    this.answeredByActorType =
        answeredByActorType == null
            ? null
            : Objects.requireNonNull(answeredByActorType, "answeredByActorType").value();
  }

  public OffsetDateTime getAnsweredAt() {
    return answeredAt;
  }

  public void setAnsweredAt(OffsetDateTime answeredAt) {
    this.answeredAt = answeredAt;
  }

  public Long getIncorporationEventId() {
    return incorporationEventId;
  }

  public void setIncorporationEventId(Long incorporationEventId) {
    this.incorporationEventId = incorporationEventId;
  }

  public OffsetDateTime getAcceptedAt() {
    return acceptedAt;
  }

  public void setAcceptedAt(OffsetDateTime acceptedAt) {
    this.acceptedAt = acceptedAt;
  }

  public OffsetDateTime getIncorporatedAt() {
    return incorporatedAt;
  }

  public void setIncorporatedAt(OffsetDateTime incorporatedAt) {
    this.incorporatedAt = incorporatedAt;
  }

  public Long getSupersededByArtifactId() {
    return supersededByArtifactId;
  }

  public void setSupersededByArtifactId(Long supersededByArtifactId) {
    this.supersededByArtifactId = supersededByArtifactId;
  }

  public Integer getSupersededByArtifactVersion() {
    return supersededByArtifactVersion;
  }

  public void setSupersededByArtifactVersion(Integer supersededByArtifactVersion) {
    this.supersededByArtifactVersion = supersededByArtifactVersion;
  }

  public String getNoEffectReason() {
    return noEffectReason;
  }

  public void setNoEffectReason(String noEffectReason) {
    this.noEffectReason = noEffectReason;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
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
