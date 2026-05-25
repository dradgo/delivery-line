package org.dradgo.adapters.persistence;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.adapters.persistence.entity.ClarificationEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.ClarificationEntityMapper;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.ClarificationRepository;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link ClarificationWritePort} (story 2.11). Participates in the caller's transaction
 * so {@code insertOpen} / {@code recordAnswer} commits with the appended {@code clarification.*}
 * event in the surrounding {@code WorkflowCommandService.answerClarification @Transactional}
 * boundary.
 *
 * <p>Defense-in-depth maps the {@code uq_clarifications_idempotency_key} UNIQUE constraint to
 * {@link DomainErrorCode#IDEMPOTENCY_KEY_CONFLICT} (mirror of {@link
 * ApprovalWritePersistenceAdapter} story 2.9 review patch P9 — sanitized details that do NOT echo
 * the caller's idempotency key).
 */
@Component
public class ClarificationWritePersistenceAdapter implements ClarificationWritePort {

  private static final Logger log =
      LoggerFactory.getLogger(ClarificationWritePersistenceAdapter.class);

  private static final String UQ_IDEMPOTENCY_CONSTRAINT = "uq_clarifications_idempotency_key";
  private static final String UQ_PUBLIC_ID_CONSTRAINT = "uq_clarifications_public_id";
  private static final String POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505";

  private final ClarificationRepository clarificationRepository;
  private final ClarificationEntityMapper clarificationEntityMapper;
  private final WorkflowRunRepository workflowRunRepository;
  private final ArtifactRepository artifactRepository;
  private final WorkflowEventRepository workflowEventRepository;

  public ClarificationWritePersistenceAdapter(
      ClarificationRepository clarificationRepository,
      ClarificationEntityMapper clarificationEntityMapper,
      WorkflowRunRepository workflowRunRepository,
      ArtifactRepository artifactRepository,
      WorkflowEventRepository workflowEventRepository) {
    this.clarificationRepository = clarificationRepository;
    this.clarificationEntityMapper = clarificationEntityMapper;
    this.workflowRunRepository = workflowRunRepository;
    this.artifactRepository = artifactRepository;
    this.workflowEventRepository = workflowEventRepository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Clarification insertOpen(NewClarification newClarification) {
    log.info(
        "clarification write insertOpen entry clarificationId={} workflowRunId={} artifactId={} artifactVersion={} questionId={}",
        newClarification.publicId(),
        newClarification.workflowRunPublicId(),
        newClarification.artifactPublicId(),
        newClarification.artifactVersion(),
        newClarification.questionId());

    WorkflowRunEntity workflowRun =
        workflowRunRepository
            .findByPublicId(newClarification.workflowRunPublicId())
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.RUN_NOT_FOUND,
                        "Workflow run not found: " + newClarification.workflowRunPublicId(),
                        detailMap("runId", newClarification.workflowRunPublicId())));

    ArtifactEntity artifact =
        artifactRepository
            .findByPublicId(newClarification.artifactPublicId())
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                        "Artifact not found: " + newClarification.artifactPublicId(),
                        detailMap("artifactId", newClarification.artifactPublicId())));
    String artifactWorkflowRunId = artifact.getWorkflowRun().getPublicId();
    if (!newClarification.workflowRunPublicId().equals(artifactWorkflowRunId)) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", newClarification.workflowRunPublicId());
      details.put("artifactId", newClarification.artifactPublicId());
      details.put("artifactWorkflowRunId", artifactWorkflowRunId);
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "Clarification artifact/run mismatch", details);
    }

    ClarificationEntity entity = new ClarificationEntity();
    entity.setPublicId(newClarification.publicId());
    entity.setWorkflowRun(workflowRun);
    entity.setArtifact(artifact);
    entity.setArtifactVersion(newClarification.artifactVersion());
    entity.setQuestionId(newClarification.questionId());
    entity.setQuestionText(newClarification.questionText());
    entity.setStatus(Clarification.STATUS_OPEN);
    entity.setIdempotencyKey(newClarification.idempotencyKey());

    ClarificationEntity saved;
    try {
      saved = clarificationRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException violation) {
      String constraintName = resolveConstraintName(violation);
      if (matchesIdempotencyConstraint(violation, constraintName)) {
        log.warn(
            "clarification write idempotency-key conflict clarificationId={} source=db_unique_constraint constraint={}",
            newClarification.publicId(),
            constraintName == null ? UQ_IDEMPOTENCY_CONSTRAINT : constraintName);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "db_unique_constraint");
        details.put("conflictDetected", true);
        throw new DomainException(
            DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            "Clarification idempotency key already used",
            details);
      }
      if (constraintName != null && constraintName.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
        log.error(
            "clarification write public-id collision clarificationId={} constraint={} cause={}",
            newClarification.publicId(),
            constraintName,
            violation.getMostSpecificCause() == null
                ? violation.getClass().getSimpleName()
                : violation.getMostSpecificCause().getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "db_unique_constraint");
        details.put("constraintName", constraintName);
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR, "Clarification public-id collision", details);
      }
      log.error(
          "clarification write integrity-violation clarificationId={} constraint={} cause={}",
          newClarification.publicId(),
          constraintName,
          violation.getMostSpecificCause() == null
              ? violation.getClass().getSimpleName()
              : violation.getMostSpecificCause().getClass().getSimpleName());
      throw violation;
    }

    Clarification projection = clarificationEntityMapper.toProjection(saved);
    log.info(
        "clarification write insertOpen success clarificationId={} workflowRunId={} artifactId={} artifactVersion={}",
        projection.publicId(),
        projection.workflowRunId(),
        projection.artifactId(),
        projection.artifactVersion());
    return projection;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Clarification recordAnswer(RecordAnswer recordAnswer) {
    log.info(
        "clarification write recordAnswer entry clarificationId={} answeredByActor={} answeredByActorType={} answerText.length={}",
        recordAnswer.clarificationPublicId(),
        recordAnswer.answeredByActor(),
        recordAnswer.answeredByActorType().value(),
        recordAnswer.answerText() == null ? 0 : recordAnswer.answerText().length());

    ClarificationEntity entity =
        clarificationRepository
            .findByPublicIdAndArchivedAtIsNull(recordAnswer.clarificationPublicId())
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.CLARIFICATION_NOT_FOUND,
                        "Clarification not found: " + recordAnswer.clarificationPublicId(),
                        detailMap("clarificationId", recordAnswer.clarificationPublicId())));

    // Trap T9: write all three answer fields in ONE UPDATE so the DB CHECK
    // ck_clarifications_answered_fields_paired never trips. Status transitions to 'answered' only
    // when the row was previously 'open'; for re-answer paths (status was already 'answered' or
    // 'accepted' per AC8) the status is preserved.
    if (Clarification.STATUS_OPEN.equals(entity.getStatus())) {
      entity.setStatus(Clarification.STATUS_ANSWERED);
    }
    entity.setAnswerText(recordAnswer.answerText());
    entity.setAnsweredByActor(recordAnswer.answeredByActor());
    entity.setAnsweredByActorType(recordAnswer.answeredByActorType());
    entity.setAnsweredAt(recordAnswer.answeredAt());

    ClarificationEntity saved = clarificationRepository.saveAndFlush(entity);
    Clarification projection = clarificationEntityMapper.toProjection(saved);
    log.info(
        "clarification write recordAnswer success clarificationId={} workflowRunId={} status={}",
        projection.publicId(),
        projection.workflowRunId(),
        projection.status());
    return projection;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Clarification markAccepted(MarkAccepted markAccepted) {
    log.info(
        "clarification write markAccepted entry clarificationId={} acceptedAt={}",
        markAccepted.clarificationPublicId(),
        markAccepted.acceptedAt());
    ClarificationEntity entity = requireRow(markAccepted.clarificationPublicId());
    entity.setStatus(Clarification.STATUS_ACCEPTED);
    entity.setAcceptedAt(markAccepted.acceptedAt());
    ClarificationEntity saved = clarificationRepository.saveAndFlush(entity);
    Clarification projection = clarificationEntityMapper.toProjection(saved);
    log.info(
        "clarification write markAccepted success clarificationId={} workflowRunId={} status={}",
        projection.publicId(),
        projection.workflowRunId(),
        projection.status());
    return projection;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Clarification markIncorporated(MarkIncorporated markIncorporated) {
    log.info(
        "clarification write markIncorporated entry clarificationId={} newSpecArtifactId={} newSpecArtifactVersion={} incorporationEventId={}",
        markIncorporated.clarificationPublicId(),
        markIncorporated.incorporatedIntoArtifactPublicId(),
        markIncorporated.incorporatedIntoArtifactVersion(),
        markIncorporated.incorporationEventPublicId());
    ClarificationEntity entity = requireRow(markIncorporated.clarificationPublicId());
    // Resolve the event row's id via the workflow_events table. Trap T13: the lifecycle service
    // appended the event before this call so the row exists; if findIdByPublicId returns empty,
    // surface as INTERNAL_ERROR (caller invariant violation, not user error).
    Long eventInternalId =
        workflowEventRepository
            .findIdByPublicId(markIncorporated.incorporationEventPublicId())
            .orElseThrow(
                () -> {
                  Map<String, Object> details = new LinkedHashMap<>();
                  details.put("clarificationId", markIncorporated.clarificationPublicId());
                  details.put(
                      "incorporationEventPublicId",
                      markIncorporated.incorporationEventPublicId());
                  log.error(
                      "clarification write markIncorporated event-resolve-failed clarificationId={} incorporationEventId={}",
                      markIncorporated.clarificationPublicId(),
                      markIncorporated.incorporationEventPublicId());
                  return new DomainException(
                      DomainErrorCode.INTERNAL_ERROR,
                      "Incorporation event row not found by public id",
                      details);
                });
    entity.setStatus(Clarification.STATUS_INCORPORATED);
    entity.setIncorporatedAt(markIncorporated.incorporatedAt());
    entity.setIncorporationEventId(eventInternalId);
    ClarificationEntity saved = clarificationRepository.saveAndFlush(entity);
    Clarification projection = clarificationEntityMapper.toProjection(saved);
    log.info(
        "clarification write markIncorporated success clarificationId={} workflowRunId={} status={}",
        projection.publicId(),
        projection.workflowRunId(),
        projection.status());
    return projection;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Clarification markSuperseded(MarkSuperseded markSuperseded) {
    log.info(
        "clarification write markSuperseded entry clarificationId={} supersededBy={} noEffectReason={}",
        markSuperseded.clarificationPublicId(),
        markSuperseded.supersededByArtifactPublicId(),
        markSuperseded.noEffectReason());
    ClarificationEntity entity = requireRow(markSuperseded.clarificationPublicId());
    ArtifactEntity supersedingArtifact =
        artifactRepository
            .findByPublicId(markSuperseded.supersededByArtifactPublicId())
            .orElseThrow(
                () -> {
                  Map<String, Object> details = new LinkedHashMap<>();
                  details.put("clarificationId", markSuperseded.clarificationPublicId());
                  details.put(
                      "supersededByArtifactId", markSuperseded.supersededByArtifactPublicId());
                  log.error(
                      "clarification write markSuperseded artifact-resolve-failed clarificationId={} supersededBy={}",
                      markSuperseded.clarificationPublicId(),
                      markSuperseded.supersededByArtifactPublicId());
                  return new DomainException(
                      DomainErrorCode.INTERNAL_ERROR,
                      "Superseding artifact not found by public id",
                      details);
                });
    entity.setStatus(Clarification.STATUS_SUPERSEDED);
    entity.setSupersededByArtifactId(supersedingArtifact.getId());
    entity.setSupersededByArtifactVersion(markSuperseded.supersededByArtifactVersion());
    entity.setNoEffectReason(markSuperseded.noEffectReason());
    ClarificationEntity saved = clarificationRepository.saveAndFlush(entity);
    Clarification projection = clarificationEntityMapper.toProjection(saved);
    log.info(
        "clarification write markSuperseded success clarificationId={} workflowRunId={} status={}",
        projection.publicId(),
        projection.workflowRunId(),
        projection.status());
    return projection;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Clarification markRejectedInvalid(MarkRejectedInvalid markRejectedInvalid) {
    log.info(
        "clarification write markRejectedInvalid entry clarificationId={} noEffectReason={}",
        markRejectedInvalid.clarificationPublicId(),
        markRejectedInvalid.noEffectReason());
    ClarificationEntity entity = requireRow(markRejectedInvalid.clarificationPublicId());
    entity.setStatus(Clarification.STATUS_REJECTED_INVALID);
    entity.setNoEffectReason(markRejectedInvalid.noEffectReason());
    ClarificationEntity saved = clarificationRepository.saveAndFlush(entity);
    Clarification projection = clarificationEntityMapper.toProjection(saved);
    log.info(
        "clarification write markRejectedInvalid success clarificationId={} workflowRunId={} status={}",
        projection.publicId(),
        projection.workflowRunId(),
        projection.status());
    return projection;
  }

  private ClarificationEntity requireRow(String clarificationPublicId) {
    return clarificationRepository
        .findByPublicIdAndArchivedAtIsNull(clarificationPublicId)
        .orElseThrow(
            () ->
                new DomainException(
                    DomainErrorCode.CLARIFICATION_NOT_FOUND,
                    "Clarification not found: " + clarificationPublicId,
                    detailMap("clarificationId", clarificationPublicId)));
  }

  /**
   * Resolve the constraint name from the exception chain. Mirror of {@link
   * ApprovalWritePersistenceAdapter#resolveConstraintName}.
   */
  private static String resolveConstraintName(DataIntegrityViolationException violation) {
    Throwable cursor = violation;
    while (cursor != null) {
      if (cursor instanceof ConstraintViolationException cve) {
        String constraint = cve.getConstraintName();
        if (constraint != null && !constraint.isBlank()) {
          return constraint;
        }
      }
      cursor = cursor.getCause();
    }
    cursor = violation;
    while (cursor != null) {
      String message = cursor.getMessage();
      if (message != null) {
        if (message.contains(UQ_IDEMPOTENCY_CONSTRAINT)) {
          return UQ_IDEMPOTENCY_CONSTRAINT;
        }
        if (message.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
          return UQ_PUBLIC_ID_CONSTRAINT;
        }
      }
      cursor = cursor.getCause();
    }
    return null;
  }

  private static boolean matchesIdempotencyConstraint(
      DataIntegrityViolationException violation, String resolvedConstraintName) {
    if (resolvedConstraintName != null
        && resolvedConstraintName.contains(UQ_IDEMPOTENCY_CONSTRAINT)) {
      return true;
    }
    if (resolvedConstraintName != null) {
      return false;
    }
    Throwable cursor = violation;
    while (cursor != null) {
      if (cursor instanceof SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        if (POSTGRES_UNIQUE_VIOLATION_SQLSTATE.equals(sqlState)) {
          return true;
        }
      }
      cursor = cursor.getCause();
    }
    return false;
  }

  private static Map<String, Object> detailMap(String key, String value) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(key, value);
    return details;
  }
}
