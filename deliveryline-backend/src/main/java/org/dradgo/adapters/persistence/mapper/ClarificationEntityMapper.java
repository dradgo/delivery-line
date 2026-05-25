package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.ClarificationEntity;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
import org.springframework.stereotype.Component;

/**
 * Maps {@link ClarificationEntity} → {@link Clarification} for application-layer reads (story
 * 2.11). Mirror of {@link ApprovalEntityMapper}. Eagerly dereferences {@code workflowRun.publicId}
 * and {@code artifact.publicId} — callers MUST invoke this mapper inside a transaction (read path
 * is {@code @Transactional(readOnly = true)} on the adapter) or supply rows fetched with the
 * repository's {@code join fetch} queries to keep the dereference inside the persistence context.
 */
@Component
public class ClarificationEntityMapper {

  private final WorkflowEventRepository workflowEventRepository;
  private final ArtifactRepository artifactRepository;

  public ClarificationEntityMapper(
      WorkflowEventRepository workflowEventRepository, ArtifactRepository artifactRepository) {
    this.workflowEventRepository = workflowEventRepository;
    this.artifactRepository = artifactRepository;
  }

  public Clarification toProjection(ClarificationEntity entity) {
    return new Clarification(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getArtifact().getPublicId(),
        entity.getArtifactVersion(),
        entity.getQuestionId(),
        entity.getQuestionText(),
        entity.getStatus(),
        entity.getAnswerText(),
        entity.getAnsweredByActor(),
        entity.getAnsweredByActorType(),
        entity.getAnsweredAt(),
        entity.getCreatedAt());
  }

  /**
   * Story 2.12 — V9-rich lifecycle snapshot. {@code incorporationEventPublicId} is resolved by
   * walking {@code incorporation_event_id} → {@code workflow_events.public_id}; {@code
   * supersededByArtifactPublicId} is resolved by walking {@code superseded_by_artifact_id} →
   * {@code artifacts.public_id}. Both lookups stay inside the read transaction so the dereference
   * cannot escape the persistence context. N+1 acceptable for a single-row inspection call.
   */
  public ClarificationLifecycleSnapshot toLifecycleSnapshot(ClarificationEntity entity) {
    String incorporationEventPublicId = null;
    String incorporatedIntoArtifactPublicId = null;
    if (entity.getIncorporationEventId() != null) {
      java.util.Optional<WorkflowEventEntity> eventRow =
          workflowEventRepository.findById(entity.getIncorporationEventId());
      if (eventRow.isPresent()) {
        WorkflowEventEntity event = eventRow.get();
        incorporationEventPublicId = event.getPublicId();
        // AC6 / AC7: resolve incorporatedIntoArtifactId from the event details map. The orchestrator
        // stamps this key on the clarification.incorporated event (Trap-aware: kept in the
        // allow-list).
        java.util.Map<String, Object> details = event.getDetails();
        if (details != null) {
          Object value = details.get("incorporatedIntoArtifactId");
          if (value instanceof String s && !s.isBlank()) {
            incorporatedIntoArtifactPublicId = s;
          }
        }
      }
    }
    String supersededByArtifactPublicId = null;
    if (entity.getSupersededByArtifactId() != null) {
      supersededByArtifactPublicId =
          artifactRepository
              .findById(entity.getSupersededByArtifactId())
              .map(a -> a.getPublicId())
              .orElse(null);
    }
    return new ClarificationLifecycleSnapshot(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getArtifact().getPublicId(),
        entity.getArtifactVersion(),
        entity.getQuestionId(),
        entity.getQuestionText(),
        entity.getStatus(),
        entity.getAnswerText(),
        entity.getAnsweredByActor(),
        entity.getAnsweredByActorType(),
        entity.getAnsweredAt(),
        entity.getAcceptedAt(),
        entity.getIncorporatedAt(),
        incorporationEventPublicId,
        incorporatedIntoArtifactPublicId,
        supersededByArtifactPublicId,
        entity.getSupersededByArtifactVersion(),
        entity.getNoEffectReason(),
        entity.getCreatedAt());
  }
}
