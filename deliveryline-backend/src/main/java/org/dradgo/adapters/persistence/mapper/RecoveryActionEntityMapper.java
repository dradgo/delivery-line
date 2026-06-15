package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.RecoveryActionEntity;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.springframework.stereotype.Component;

/**
 * Entity ↔ application-shape translation for {@code recovery_actions} (story 1.18 Task 2). Explicit
 * (no MapStruct) to match the convention established by sibling persistence mappers.
 */
@Component
public class RecoveryActionEntityMapper {

  public RecoveryActionSnapshot toSnapshot(RecoveryActionEntity entity) {
    return new RecoveryActionSnapshot(
        entity.getPublicId(),
        entity.getId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getActionType(),
        entity.getTriggeringEvent() == null ? null : entity.getTriggeringEvent().getPublicId(),
        entity.getResultingEvent() == null ? null : entity.getResultingEvent().getPublicId(),
        entity.getActorIdentity(),
        entity.getActorType(),
        entity.getIdempotencyKey(),
        entity.getResultStatus(),
        entity.getCreatedAt(),
        entity.getReviewerRole());
  }
}
