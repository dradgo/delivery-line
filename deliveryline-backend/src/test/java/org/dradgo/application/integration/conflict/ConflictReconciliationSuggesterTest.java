package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dradgo.application.integration.conflict.ConflictReconciliationSuggester.SuggestedDecision;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.ReconciliationDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Story 4.18 (AC3) — the deterministic per-category safety ranking. */
class ConflictReconciliationSuggesterTest {

  private final ConflictReconciliationSuggester suggester = new ConflictReconciliationSuggester();

  @ParameterizedTest
  @EnumSource(IntegrationConflictCategory.class)
  void everyCategoryHasANonEmptyRankingCoveringAllFourDecisions(
      IntegrationConflictCategory category) {
    List<SuggestedDecision> ranking = suggester.suggestFor(category);
    assertThat(ranking).isNotEmpty();
    // Every ranking covers all four decisions exactly once (a complete, deterministic menu).
    assertThat(ranking.stream().map(SuggestedDecision::decision))
        .containsExactlyInAnyOrder(ReconciliationDecision.values());
    // Safe options are ranked before risky ones.
    int firstRisky = -1;
    for (int i = 0; i < ranking.size(); i++) {
      boolean risky = ConflictReconciliationSuggester.SAFETY_RISKY.equals(ranking.get(i).safety());
      if (risky && firstRisky < 0) {
        firstRisky = i;
      }
      if (firstRisky >= 0 && !risky) {
        // a safe option appeared AFTER a risky one → ordering violated
        throw new AssertionError("safe option ranked after risky for category " + category);
      }
      assertThat(ranking.get(i).safety())
          .isIn(
              ConflictReconciliationSuggester.SAFETY_SAFE,
              ConflictReconciliationSuggester.SAFETY_RISKY);
    }
    // Every category has at least one SAFE option to recommend.
    assertThat(ranking)
        .anySatisfy(
            d -> assertThat(d.safety()).isEqualTo(ConflictReconciliationSuggester.SAFETY_SAFE));
  }

  @Test
  void externalStateAdvancedRanksAcceptExternalSafeFirst() {
    List<SuggestedDecision> ranking =
        suggester.suggestFor(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED);
    assertThat(ranking.get(0))
        .isEqualTo(
            new SuggestedDecision(
                ReconciliationDecision.ACCEPT_EXTERNAL_STATE,
                ConflictReconciliationSuggester.SAFETY_SAFE));
    assertThat(safetyOf(ranking, ReconciliationDecision.ACCEPT_INTERNAL_STATE))
        .isEqualTo(ConflictReconciliationSuggester.SAFETY_RISKY);
  }

  @Test
  void resourceRemovedMarksFailedExternallySafe() {
    List<SuggestedDecision> ranking =
        suggester.suggestFor(IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED);
    assertThat(safetyOf(ranking, ReconciliationDecision.MARK_FAILED_EXTERNALLY))
        .isEqualTo(ConflictReconciliationSuggester.SAFETY_SAFE);
  }

  @Test
  void linkBrokenMarksFailedExternallySafe() {
    List<SuggestedDecision> ranking = suggester.suggestFor(IntegrationConflictCategory.LINK_BROKEN);
    assertThat(ranking.get(0).decision()).isEqualTo(ReconciliationDecision.MARK_FAILED_EXTERNALLY);
    assertThat(ranking.get(0).safety()).isEqualTo(ConflictReconciliationSuggester.SAFETY_SAFE);
  }

  @Test
  void nullCategoryYieldsEmptyRanking() {
    assertThat(suggester.suggestFor(null)).isEmpty();
  }

  private static String safetyOf(List<SuggestedDecision> ranking, ReconciliationDecision decision) {
    return ranking.stream()
        .filter(d -> d.decision() == decision)
        .map(SuggestedDecision::safety)
        .findFirst()
        .orElseThrow();
  }
}
