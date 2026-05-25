package org.dradgo.application.clarification.spi;

import java.util.List;
import java.util.Optional;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;

/**
 * Application-owned read SPI over the {@code clarifications} table (story 2.11). Adapter
 * implementations MUST filter {@code archived_at IS NULL} on every method — tombstoned rows are
 * invisible to read callers. Mirror of {@link
 * org.dradgo.application.approval.spi.ApprovalReadPort}'s archived-row contract.
 *
 * <p>Lists are returned <strong>status-grouped</strong> (open first, then answered, then accepted,
 * then terminal {@code incorporated|superseded|rejected_invalid} collapsed last); within each
 * group, rows are ordered by {@code created_at ASC} with an {@code id} tiebreak for deterministic
 * ordering (story 2.11 trap T11).
 */
public interface ClarificationReadPort {

  /**
   * Single clarification lookup by public id. Returns {@code Optional.empty()} when the row is
   * missing OR when it is archived ({@code archived_at IS NOT NULL}).
   */
  Optional<Clarification> findByPublicId(String clarificationPublicId);

  /**
   * P30/D2 — pessimistic-lock variant of {@link #findByPublicId}. Emits {@code SELECT ... FOR
   * UPDATE} so concurrent lifecycle transitions on the same clarification row serialize. Caller
   * MUST be inside a transaction (the lifecycle service runs under the spec-rebuild outer
   * {@code @Transactional}). Used exclusively by {@code
   * ClarificationLifecycleService.loadAndGuardRun}.
   */
  Optional<Clarification> findByPublicIdForUpdate(
      String workflowRunPublicId, String clarificationPublicId);

  /**
   * All non-archived clarifications attached to the supplied workflow run, status-grouped then
   * {@code created_at ASC}. Backs {@code WorkflowInspectionService.getClarifications}.
   */
  List<Clarification> listByWorkflowRunId(String workflowRunPublicId);

  /**
   * All non-archived clarifications attached to the supplied artifact, status-grouped then {@code
   * created_at ASC}. Backs {@code WorkflowInspectionService.getClarificationsForArtifact}.
   */
  List<Clarification> listByArtifactId(String artifactPublicId);

  /**
   * Story 2.12 AC9: count of clarifications for the supplied workflow run that are NOT in {@code
   * incorporated} or {@code rejected_invalid} status (i.e. {@code open + answered + accepted +
   * superseded}). Filters {@code archived_at IS NULL} mirroring the existing list methods. {@code
   * superseded} IS counted as pending — see OQ-2 deep dive in story 2.12 Dev Notes. Story 2.14
   * (allowed-actions endpoint) reads this to gate {@code approve_spec}.
   */
  int countPendingByWorkflowRun(String workflowRunPublicId);

  /**
   * Story 2.12 — return the V9-rich lifecycle snapshot for the single clarification. Backs {@code
   * WorkflowInspectionService.getClarificationStatus}. Trap T1 — does NOT widen {@link
   * Clarification}; the snapshot is a separate richer projection.
   */
  Optional<ClarificationLifecycleSnapshot> findLifecycleSnapshotByPublicId(
      String clarificationPublicId);
}
