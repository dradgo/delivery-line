package org.dradgo.application.approval;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Rich result type returned by {@link ApprovalService#approveSpec}. Carries everything REST/CLI
 * surfaces need to render a "you approved version N of artifact X" success line — story 2.13 will
 * eventually expose this directly through a new REST DTO.
 *
 * <p>Intentionally NOT a member of the {@code DomainResult} sealed interface in {@code
 * org.dradgo.application.workflow.DomainResult}: widening that interface would force the {@code
 * executeIdempotent} replay loaders (typed to {@code DomainResult}) and all serialization paths to
 * understand {@code ApprovalResult} for zero gain — the legacy REST contract still returns {@code
 * WorkflowStateChangeResult} (built from this record inside {@code
 * WorkflowCommandService.approveSpec}).
 *
 * @param approvalId persisted approval public id ({@code apr_…})
 * @param workflowRunId run public id ({@code run_…})
 * @param artifactId approved artifact public id ({@code art_…})
 * @param artifactVersion exact version pinned by the composite FK
 * @param contextBundleVersion bundle version the reviewer reviewed against
 * @param reviewerRole asserted reviewer role (e.g. {@code product_reviewer})
 * @param decidedAt server-stamped decision timestamp
 * @param resultingState always {@link WorkflowState#EXECUTING} for {@code approveSpec}
 * @param correlationId echoed from the originating command (nullable)
 */
public record ApprovalResult(
    String approvalId,
    String workflowRunId,
    String artifactId,
    int artifactVersion,
    int contextBundleVersion,
    String reviewerRole,
    OffsetDateTime decidedAt,
    WorkflowState resultingState,
    String correlationId) {

  public ApprovalResult {
    Objects.requireNonNull(approvalId, "approvalId");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(artifactId, "artifactId");
    Objects.requireNonNull(reviewerRole, "reviewerRole");
    Objects.requireNonNull(decidedAt, "decidedAt");
    Objects.requireNonNull(resultingState, "resultingState");
    if (artifactVersion <= 0) {
      throw new IllegalArgumentException("artifactVersion must be positive: " + artifactVersion);
    }
    if (contextBundleVersion <= 0) {
      throw new IllegalArgumentException(
          "contextBundleVersion must be positive: " + contextBundleVersion);
    }
  }
}
