package org.dradgo.application.integration.conflict;

import java.util.List;
import java.util.Map;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.ReconciliationDecision;
import org.springframework.stereotype.Component;

/**
 * Story 4.18 (AC3 / Reconciliation 12) — a pure, deterministic per-category ranking of the {@link
 * ReconciliationDecision} options an operator can take on an unresolved conflict, each tagged with
 * a coarse {@code safety} label ({@code safe} / {@code risky}). The story-4.23 reconciliation
 * dialog consumes this (via the {@code GET /api/v1/integration-conflicts/{conflictId}} detail) to
 * present the least-destructive decision first.
 *
 * <p>The ranking is stateless and side-effect-free (no DB, no external I/O) so it is trivially
 * unit-testable and can be evaluated inside the read path. It reflects the semantics of each drift
 * category:
 *
 * <ul>
 *   <li>{@code external_state_advanced} (e.g. a PR merged/closed externally ahead of the run) —
 *       accepting the external state is <em>safe</em> (acknowledge what already happened);
 *       re-asserting the internal state (re-opening the PR) is <em>risky</em>.
 *   <li>{@code external_state_reverted} (e.g. a PR reopened after we treated it terminal) — mirrors
 *       advanced: accept the external (reverted) state is <em>safe</em>, re-asserting internal is
 *       <em>risky</em>.
 *   <li>{@code external_resource_removed} (the PR/ticket is gone) — marking the run failed
 *       externally or accepting the (absent) external state are <em>safe</em>; the completion /
 *       internal-state decisions are <em>risky</em>.
 *   <li>{@code metadata_drift} (branch/repo rename) — adopt the external metadata (accept external)
 *       is <em>safe</em>; the rest are <em>risky</em>.
 *   <li>{@code link_broken} (permanent access failure) — marking the run failed externally is
 *       <em>safe</em>; the rest are <em>risky</em>.
 * </ul>
 */
@Component
public class ConflictReconciliationSuggester {

  /** The lower-risk decision(s) an operator can take without further destructive side effects. */
  public static final String SAFETY_SAFE = "safe";

  /** A decision that may re-open, re-run, or otherwise diverge from the external reality. */
  public static final String SAFETY_RISKY = "risky";

  private static final List<SuggestedDecision> ADVANCED =
      List.of(
          safe(ReconciliationDecision.ACCEPT_EXTERNAL_STATE),
          safe(ReconciliationDecision.MARK_COMPLETED_EXTERNALLY),
          risky(ReconciliationDecision.MARK_FAILED_EXTERNALLY),
          risky(ReconciliationDecision.ACCEPT_INTERNAL_STATE));

  private static final List<SuggestedDecision> REVERTED =
      List.of(
          safe(ReconciliationDecision.ACCEPT_EXTERNAL_STATE),
          risky(ReconciliationDecision.ACCEPT_INTERNAL_STATE),
          risky(ReconciliationDecision.MARK_FAILED_EXTERNALLY),
          risky(ReconciliationDecision.MARK_COMPLETED_EXTERNALLY));

  private static final List<SuggestedDecision> RESOURCE_REMOVED =
      List.of(
          safe(ReconciliationDecision.MARK_FAILED_EXTERNALLY),
          safe(ReconciliationDecision.ACCEPT_EXTERNAL_STATE),
          risky(ReconciliationDecision.ACCEPT_INTERNAL_STATE),
          risky(ReconciliationDecision.MARK_COMPLETED_EXTERNALLY));

  private static final List<SuggestedDecision> METADATA_DRIFT =
      List.of(
          safe(ReconciliationDecision.ACCEPT_EXTERNAL_STATE),
          risky(ReconciliationDecision.ACCEPT_INTERNAL_STATE),
          risky(ReconciliationDecision.MARK_COMPLETED_EXTERNALLY),
          risky(ReconciliationDecision.MARK_FAILED_EXTERNALLY));

  private static final List<SuggestedDecision> LINK_BROKEN =
      List.of(
          safe(ReconciliationDecision.MARK_FAILED_EXTERNALLY),
          risky(ReconciliationDecision.ACCEPT_INTERNAL_STATE),
          risky(ReconciliationDecision.ACCEPT_EXTERNAL_STATE),
          risky(ReconciliationDecision.MARK_COMPLETED_EXTERNALLY));

  private static final Map<IntegrationConflictCategory, List<SuggestedDecision>> BY_CATEGORY =
      Map.of(
          IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED, ADVANCED,
          IntegrationConflictCategory.EXTERNAL_STATE_REVERTED, REVERTED,
          IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED, RESOURCE_REMOVED,
          IntegrationConflictCategory.METADATA_DRIFT, METADATA_DRIFT,
          IntegrationConflictCategory.LINK_BROKEN, LINK_BROKEN);

  /**
   * The safety-ranked reconciliation options for {@code category} (safe options first). Returns an
   * empty list for a {@code null} category (defensive — every persisted category is mapped, and a
   * drift test pins that every {@link IntegrationConflictCategory} has a ranking).
   */
  public List<SuggestedDecision> suggestFor(IntegrationConflictCategory category) {
    if (category == null) {
      return List.of();
    }
    return BY_CATEGORY.getOrDefault(category, List.of());
  }

  private static SuggestedDecision safe(ReconciliationDecision decision) {
    return new SuggestedDecision(decision, SAFETY_SAFE);
  }

  private static SuggestedDecision risky(ReconciliationDecision decision) {
    return new SuggestedDecision(decision, SAFETY_RISKY);
  }

  /**
   * One safety-ranked reconciliation option: the {@link ReconciliationDecision} and its coarse
   * {@code safety} label ({@link #SAFETY_SAFE} / {@link #SAFETY_RISKY}).
   */
  public record SuggestedDecision(ReconciliationDecision decision, String safety) {}
}
