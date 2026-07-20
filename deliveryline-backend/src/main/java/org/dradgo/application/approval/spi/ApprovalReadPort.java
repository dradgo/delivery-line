package org.dradgo.application.approval.spi;

import java.util.List;
import java.util.Optional;
import org.dradgo.application.approval.ApprovalSnapshot;

/**
 * Application-owned read SPI over the {@code approvals} table (story 2.8). Adapter implementations
 * MUST filter {@code archived_at IS NULL} on every method — tombstoned rows are invisible to read
 * callers.
 *
 * <p>The writer ({@code ApprovalService.approveSpec / rejectSpec}) is intentionally out of scope
 * for 2.8; it arrives with stories 2.9 + 2.10. The read port stands on its own because the
 * underlying V1 schema is already provisioned and tests can seed rows via direct repository
 * inserts.
 */
public interface ApprovalReadPort {

  /**
   * Most recent {@code decision='approved'} row whose joined artifact has the requested {@code
   * artifact_type} within the supplied workflow run, ordered by {@code decided_at} descending.
   *
   * <p>Backs {@code WorkflowInspectionService.getCurrentApprovedSpec} (FR10) — returns {@code
   * Optional.empty()} when no spec has been approved yet in this run.
   *
   * @param workflowRunPublicId run scope ({@code run_…})
   * @param artifactType registry value (e.g. {@code "spec"})
   */
  Optional<ApprovalSnapshot> findLatestApprovedForArtifactLineage(
      String workflowRunPublicId, String artifactType);

  /**
   * Story 4.16a [Review D3] — the currently-valid approved row for ONE specific artifact ({@code
   * art_…} public id, which pins exactly one version), or {@code Optional.empty()} when that
   * artifact has no live approval. Backs {@code ApprovalService.invalidateApprovalForArtifact} —
   * version-specific so terminating an ambiguous lineage version never invalidates a healthy
   * sibling version's approval (contrast {@link #findLatestApprovedForArtifactLineage}, which is
   * run+type keyed and returns the highest version).
   *
   * @param artifactPublicId the approved/rejected artifact's public id ({@code art_…})
   */
  Optional<ApprovalSnapshot> findLatestApprovedForArtifact(String artifactPublicId);

  /**
   * All decisions ({@code approved} or {@code rejected}) for the run + artifact-type filter, in
   * chronological order ({@code decided_at} ascending). Backs {@code
   * WorkflowInspectionService.getSpecHistory} (FR11) — the caller joins each row onto the matching
   * spec artifact version via {@code (artifactId, artifactVersion)}.
   */
  List<ApprovalSnapshot> listByWorkflowRunAndArtifactType(
      String workflowRunPublicId, String artifactType);

  /**
   * All {@code decision='rejected'} rows for the run + artifact-type filter, in chronological order
   * ({@code decided_at} ascending). Backs spec-investigation context-bundle composition (story 2.8
   * AC3) — each row contributes a {@code priorFeedbackReferences[]} entry of the form {@code
   * {referenceId: apr_…, kind: "spec.rejection"}}.
   */
  List<ApprovalSnapshot> listRejectionsByWorkflowRunAndArtifactType(
      String workflowRunPublicId, String artifactType);
}
