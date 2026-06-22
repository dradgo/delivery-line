package org.dradgo.adapters.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.dradgo.adapters.persistence.entity.StepReviewEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.StepReviewEntityMapper;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.RunnerExecutionRepository;
import org.dradgo.adapters.persistence.repository.StepReviewRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.review.StepReviewSnapshot;
import org.dradgo.application.review.spi.DuplicateStepReviewException;
import org.dradgo.application.review.spi.StepReviewWritePort;
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
 * Story 3d-2 (AC2/AC4) — JPA-backed {@link StepReviewWritePort}. Mirrors {@code
 * ApprovalWritePersistenceAdapter}. {@code insert} is {@code Propagation.REQUIRED}: the REVIEW
 * harvest invokes it inside its own programmatic transaction (so the verdict insert + the
 * reviewer-execution finalize commit atomically), which REQUIRED joins; with no ambient transaction
 * it opens its own. A duplicate-verdict UNIQUE hit (V21) is surfaced as the benign {@link
 * org.dradgo.application.review.spi.DuplicateStepReviewException} (an idempotent re-delivery, not a
 * fault); the public-id collision / otherwise-unmapped integrity violation maps to a typed domain
 * exception so an upstream bug surfaces as a Problem Details response rather than a raw 500.
 *
 * <p>Logging discipline (AC1/Task 9): INFO entry/exit carries the {@code rev_} id + the FK public
 * ids + the {@code outcome} enum ONLY — NEVER the rationale text, reviewed-artifact payload, or any
 * reviewer credential.
 */
@Component
public class StepReviewWritePersistenceAdapter implements StepReviewWritePort {

  private static final Logger log =
      LoggerFactory.getLogger(StepReviewWritePersistenceAdapter.class);

  /** Partial-unique index (V21) forbidding a duplicate verdict per reviewer execution. */
  private static final String UQ_RUNNER_EXECUTION_CONSTRAINT = "uq_step_reviews_runner_execution";

  /** Public-id collision constraint (V19) — astronomically unlikely but mapped explicitly. */
  private static final String UQ_PUBLIC_ID_CONSTRAINT = "uq_step_reviews_public_id";

  private final StepReviewRepository stepReviewRepository;
  private final StepReviewEntityMapper stepReviewEntityMapper;
  private final WorkflowRunRepository workflowRunRepository;
  private final RunnerExecutionRepository runnerExecutionRepository;
  private final ArtifactRepository artifactRepository;

  public StepReviewWritePersistenceAdapter(
      StepReviewRepository stepReviewRepository,
      StepReviewEntityMapper stepReviewEntityMapper,
      WorkflowRunRepository workflowRunRepository,
      RunnerExecutionRepository runnerExecutionRepository,
      ArtifactRepository artifactRepository) {
    this.stepReviewRepository = stepReviewRepository;
    this.stepReviewEntityMapper = stepReviewEntityMapper;
    this.workflowRunRepository = workflowRunRepository;
    this.runnerExecutionRepository = runnerExecutionRepository;
    this.artifactRepository = artifactRepository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public StepReviewSnapshot insert(NewStepReview newStepReview) {
    String priorReviewId = MdcKeys.beginScope(MdcKeys.STEP_REVIEW_ID, newStepReview.publicId());
    try {
      log.info(
          "persisting step_review {} workflowRunId={} reviewerExec={} reviewedArtifactId={}"
              + " reviewedArtifactVersion={} outcome={}",
          newStepReview.publicId(),
          newStepReview.workflowRunPublicId(),
          newStepReview.runnerExecutionPublicId(),
          newStepReview.reviewedArtifactPublicId(),
          newStepReview.reviewedArtifactVersion(),
          newStepReview.outcome().value());

      WorkflowRunEntity workflowRun =
          workflowRunRepository
              .findByPublicId(newStepReview.workflowRunPublicId())
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.RUN_NOT_FOUND,
                          "Workflow run not found: " + newStepReview.workflowRunPublicId(),
                          detailMap("runId", newStepReview.workflowRunPublicId())));

      RunnerExecutionEntity runnerExecution =
          runnerExecutionRepository
              .findByPublicId(newStepReview.runnerExecutionPublicId())
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
                          "Runner execution not found: " + newStepReview.runnerExecutionPublicId(),
                          detailMap("runnerExecutionId", newStepReview.runnerExecutionPublicId())));

      ArtifactEntity reviewedArtifact =
          artifactRepository
              .findByPublicId(newStepReview.reviewedArtifactPublicId())
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                          "Artifact not found: " + newStepReview.reviewedArtifactPublicId(),
                          detailMap("artifactId", newStepReview.reviewedArtifactPublicId())));

      StepReviewEntity entity = new StepReviewEntity();
      entity.setPublicId(newStepReview.publicId());
      entity.setWorkflowRun(workflowRun);
      entity.setRunnerExecution(runnerExecution);
      entity.setReviewedArtifact(reviewedArtifact);
      entity.setReviewedArtifactVersion(newStepReview.reviewedArtifactVersion());
      entity.setOutcome(newStepReview.outcome());
      // rationale is ALREADY post-redaction (the harvest redacts before handing it here).
      entity.setRationale(newStepReview.rationale());
      entity.setReviewerModelIdentity(newStepReview.reviewerModelIdentity());
      entity.setProducerModelIdentity(newStepReview.producerModelIdentity());

      StepReviewEntity saved;
      try {
        saved = stepReviewRepository.saveAndFlush(entity);
      } catch (DataIntegrityViolationException violation) {
        String constraintName = resolveConstraintName(violation);
        if (constraintName != null && constraintName.contains(UQ_RUNNER_EXECUTION_CONSTRAINT)) {
          // A duplicate/late harvest of the SAME reviewer execution (recovery scratch-replay or a
          // concurrent harvest) is explicitly anticipated — NOT a fault. Signal it as a benign
          // idempotent no-op (the first delivery's verdict stands) instead of an INTERNAL_ERROR 500
          // that the harvest would falsely degrade as a contract violation (code-review hardening).
          log.info(
              "step_review duplicate-verdict idempotent no-op reviewId={} reviewerExec={}"
                  + " constraint={}",
              newStepReview.publicId(),
              newStepReview.runnerExecutionPublicId(),
              constraintName);
          throw new DuplicateStepReviewException(newStepReview.runnerExecutionPublicId());
        }
        if (constraintName != null && constraintName.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
          log.error(
              "step_review public-id collision reviewId={} constraint={}",
              newStepReview.publicId(),
              constraintName);
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("source", "db_unique_constraint");
          details.put("constraintName", constraintName);
          throw new DomainException(
              DomainErrorCode.INTERNAL_ERROR, "Step-review public-id collision", details);
        }
        log.error(
            "step_review integrity-violation reviewId={} constraint={} cause={}",
            newStepReview.publicId(),
            constraintName,
            violation.getMostSpecificCause() == null
                ? violation.getClass().getSimpleName()
                : violation.getMostSpecificCause().getClass().getSimpleName());
        throw violation;
      }

      StepReviewSnapshot snapshot = stepReviewEntityMapper.toSnapshot(saved);
      log.info(
          "persisted step_review {} outcome={} reviewerExec={} selfReview={}",
          snapshot.publicId(),
          snapshot.outcome().value(),
          snapshot.runnerExecutionId(),
          snapshot.selfReview());
      return snapshot;
    } finally {
      MdcKeys.endScope(MdcKeys.STEP_REVIEW_ID, priorReviewId);
    }
  }

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
        if (message.contains(UQ_RUNNER_EXECUTION_CONSTRAINT)) {
          return UQ_RUNNER_EXECUTION_CONSTRAINT;
        }
        if (message.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
          return UQ_PUBLIC_ID_CONSTRAINT;
        }
      }
      cursor = cursor.getCause();
    }
    return null;
  }

  private static Map<String, Object> detailMap(String key, String value) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(key, value);
    return details;
  }
}
