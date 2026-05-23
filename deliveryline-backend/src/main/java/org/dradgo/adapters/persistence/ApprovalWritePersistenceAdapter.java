package org.dradgo.adapters.persistence;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.adapters.persistence.entity.ApprovalEntity;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.ApprovalEntityMapper;
import org.dradgo.adapters.persistence.repository.ApprovalRepository;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.observability.MdcKeys;
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
 * JPA-backed {@link ApprovalWritePort} (story 2.9). Participates in the caller's transaction so the
 * row insert commits with the {@code approval.approved} event append + the workflow state
 * transition; defense-in-depth maps the {@code uq_approvals_idempotency_key} UNIQUE constraint hit
 * (T5) to a typed domain exception so a downstream {@code IdempotencyService} bug surfaces as a
 * normal Problem Details response rather than HTTP 500.
 */
@Component
public class ApprovalWritePersistenceAdapter implements ApprovalWritePort {

  private static final Logger log = LoggerFactory.getLogger(ApprovalWritePersistenceAdapter.class);

  /** Substring match against the constraint name surfaced by Postgres / Hibernate. */
  private static final String UQ_IDEMPOTENCY_CONSTRAINT = "uq_approvals_idempotency_key";

  /**
   * Postgres unique-violation SQLSTATE — used as defense-in-depth when the message lacks the
   * constraint name.
   */
  private static final String POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505";

  /**
   * Public-id collision constraint — astronomically unlikely (UUIDv7) but mapped explicitly to
   * avoid raw-DAE leakage.
   */
  private static final String UQ_PUBLIC_ID_CONSTRAINT = "uq_approvals_public_id";

  private final ApprovalRepository approvalRepository;
  private final ApprovalEntityMapper approvalEntityMapper;
  private final WorkflowRunRepository workflowRunRepository;
  private final ArtifactRepository artifactRepository;

  public ApprovalWritePersistenceAdapter(
      ApprovalRepository approvalRepository,
      ApprovalEntityMapper approvalEntityMapper,
      WorkflowRunRepository workflowRunRepository,
      ArtifactRepository artifactRepository) {
    this.approvalRepository = approvalRepository;
    this.approvalEntityMapper = approvalEntityMapper;
    this.workflowRunRepository = workflowRunRepository;
    this.artifactRepository = artifactRepository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public ApprovalSnapshot insert(NewApproval newApproval) {
    String priorApprovalId = MdcKeys.beginScope(MdcKeys.APPROVAL_ID, newApproval.publicId());
    try {
      log.info(
          "approval write entry approvalId={} workflowRunId={} artifactId={} artifactVersion={} contextBundleVersion={} decision={}",
          newApproval.publicId(),
          newApproval.workflowRunPublicId(),
          newApproval.artifactPublicId(),
          newApproval.artifactVersion(),
          newApproval.contextBundleVersion(),
          newApproval.decision());

      WorkflowRunEntity workflowRun =
          workflowRunRepository
              .findByPublicId(newApproval.workflowRunPublicId())
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.RUN_NOT_FOUND,
                          "Workflow run not found: " + newApproval.workflowRunPublicId(),
                          detailMap("runId", newApproval.workflowRunPublicId())));

      ArtifactEntity artifact =
          artifactRepository
              .findByPublicId(newApproval.artifactPublicId())
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                          "Artifact not found: " + newApproval.artifactPublicId(),
                          detailMap("artifactId", newApproval.artifactPublicId())));

      ApprovalEntity entity = new ApprovalEntity();
      entity.setPublicId(newApproval.publicId());
      entity.setWorkflowRun(workflowRun);
      entity.setArtifact(artifact);
      entity.setArtifactVersion(newApproval.artifactVersion());
      entity.setContextBundleVersion(newApproval.contextBundleVersion());
      entity.setActorIdentity(newApproval.actorIdentity());
      entity.setActorType(newApproval.actorType());
      entity.setReviewerRole(newApproval.reviewerRole());
      entity.setDecision(newApproval.decision());
      entity.setReason(newApproval.reason());
      entity.setRejectionTaxonomy(newApproval.rejectionTaxonomy());
      entity.setDecidedAt(newApproval.decidedAt());
      entity.setIdempotencyKey(newApproval.idempotencyKey());

      ApprovalEntity saved;
      try {
        saved = approvalRepository.saveAndFlush(entity);
      } catch (DataIntegrityViolationException violation) {
        String constraintName = resolveConstraintName(violation);
        if (matchesIdempotencyConstraint(violation, constraintName)) {
          log.warn(
              "approval write idempotency-key conflict approvalId={} source=db_unique_constraint constraint={}",
              newApproval.publicId(),
              constraintName == null ? UQ_IDEMPOTENCY_CONSTRAINT : constraintName);
          // Review batch 1 P9: do NOT echo idempotencyKey in error details. Server-side logs
          // already carry it (above + via MDC); reflecting it in the Problem Details response body
          // makes the caller-private token discoverable in any error-aggregation pipeline.
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("source", "db_unique_constraint");
          details.put("conflictDetected", true);
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Approval idempotency key already used",
              details);
        }
        if (constraintName != null && constraintName.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
          // Review batch 1 P8: map public-id collision as INTERNAL_ERROR (UUIDv7 collision is
          // astronomically unlikely but possible via fixture reuse) so callers never see raw DAE.
          log.error(
              "approval write public-id collision approvalId={} constraint={} cause={}",
              newApproval.publicId(),
              constraintName,
              violation.getMostSpecificCause() == null
                  ? violation.getClass().getSimpleName()
                  : violation.getMostSpecificCause().getClass().getSimpleName());
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("source", "db_unique_constraint");
          details.put("constraintName", constraintName);
          throw new DomainException(
              DomainErrorCode.INTERNAL_ERROR, "Approval public-id collision", details);
        }
        log.error(
            "approval write integrity-violation approvalId={} constraint={} cause={}",
            newApproval.publicId(),
            constraintName,
            violation.getMostSpecificCause() == null
                ? violation.getClass().getSimpleName()
                : violation.getMostSpecificCause().getClass().getSimpleName());
        throw violation;
      }

      ApprovalSnapshot snapshot = approvalEntityMapper.toSnapshot(saved);
      log.info(
          "approval write success approvalId={} artifactVersion={} contextBundleVersion={} reviewerRole={}",
          snapshot.publicId(),
          snapshot.artifactVersion(),
          snapshot.contextBundleVersion(),
          snapshot.reviewerRole());
      return snapshot;
    } finally {
      MdcKeys.endScope(MdcKeys.APPROVAL_ID, priorApprovalId);
    }
  }

  /**
   * Resolve the constraint name from the exception chain. Preferred path: the Hibernate-wrapped
   * {@link ConstraintViolationException} exposes {@code getConstraintName()} directly. Fallback:
   * walk the cause chain and check each message for the known constraint substring (legacy path;
   * survives if Hibernate ever stops wrapping). Returns {@code null} if the constraint name cannot
   * be determined.
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

  /**
   * Idempotency-key conflict detection layered:
   *
   * <ol>
   *   <li>Constraint name resolved by {@link #resolveConstraintName} (preferred).
   *   <li>SQLSTATE 23505 ({@code POSTGRES_UNIQUE_VIOLATION_SQLSTATE}) — defense-in-depth when the
   *       constraint name is unavailable. Note: this branch will return true for ANY unique
   *       constraint, not just idempotency-key — callers should resolve the constraint name first
   *       and only fall back to SQLSTATE when the name is missing.
   * </ol>
   */
  private static boolean matchesIdempotencyConstraint(
      DataIntegrityViolationException violation, String resolvedConstraintName) {
    if (resolvedConstraintName != null
        && resolvedConstraintName.contains(UQ_IDEMPOTENCY_CONSTRAINT)) {
      return true;
    }
    if (resolvedConstraintName != null) {
      // We resolved a constraint name and it WASN'T idempotency-key — definitely not a match.
      return false;
    }
    // Constraint name unavailable — fall back to SQLSTATE. Only triggers when there are no other
    // unique constraints on the table whose violation could be misclassified; today only
    // uq_approvals_idempotency_key and uq_approvals_public_id satisfy that, and the latter is
    // routed by the caller via resolved constraint name when available.
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
