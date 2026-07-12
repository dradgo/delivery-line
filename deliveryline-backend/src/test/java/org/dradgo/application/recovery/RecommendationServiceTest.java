package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit coverage for the pure safety-ranking policy (story 4.4 AC2/AC10). Deterministic —
 * category×drift matrix + the non-Failed short-circuit; no ports, no DB.
 */
class RecommendationServiceTest {

  private final RecommendationService service = new RecommendationService();

  private static RecommendedAction actionOf(List<RecommendedAction> actions, String type) {
    return actions.stream()
        .filter(a -> a.actionType().equals(type))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type + " action in " + actions));
  }

  @ParameterizedTest
  @EnumSource(WorkflowState.class)
  void nonFailedStatesYieldEmptyRecommendations(WorkflowState state) {
    if (state == WorkflowState.FAILED) {
      return;
    }
    assertThat(service.recommend(state, "runner_timeout", false, false, "await_outcome")).isEmpty();
  }

  @Test
  void nullCurrentStateYieldsEmpty() {
    assertThat(service.recommend(null, "runner_timeout", false, false, null)).isEmpty();
  }

  @Test
  void timeoutNoDriftMakesRetrySafeAndTopRanked() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_timeout", false, false, "retry");

    // Story 4.9 (AC10) — classify_failure is always present alongside retry.
    assertThat(actions).hasSize(2);
    RecommendedAction retry = actions.get(0);
    assertThat(retry.actionType()).isEqualTo("retry");
    assertThat(retry.safetyLevel()).isEqualTo("safe");
    assertThat(retry.precondition()).isNotBlank();
    assertThat(retry.reason()).isNotBlank();
    assertThat(actionOf(actions, "classify_failure").safetyLevel()).isEqualTo("safe");
  }

  @Test
  void timeoutWithDriftDowngradesRetryToCautionAndAddsSafeReconcile() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_timeout", true, false, "retry");

    // reconcile is the single safe MUTATING move; retry is downgraded to caution and ranked below
    // it. classify_failure stays safe under drift (story 4.9 AC10 — it mutates nothing).
    assertThat(actions)
        .extracting(RecommendedAction::actionType)
        .containsExactly("reconcile", "classify_failure", "retry");
    assertThat(actionOf(actions, "reconcile").safetyLevel()).isEqualTo("safe");
    assertThat(actionOf(actions, "classify_failure").safetyLevel()).isEqualTo("safe");
    assertThat(actionOf(actions, "retry").safetyLevel()).isEqualTo("caution");
  }

  @Test
  void contractViolationMakesRetryRiskyAndPauseSafe() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_contract_violation", false, false, "retry");

    assertThat(actionOf(actions, "retry").safetyLevel()).isEqualTo("risky");
    assertThat(actionOf(actions, "pause").safetyLevel()).isEqualTo("safe");
    // safe pause outranks risky retry (classify_failure is also safe — story 4.9 AC10).
    assertThat(actions.get(0).actionType()).isEqualTo("pause");
    assertThat(actions)
        .extracting(RecommendedAction::safetyLevel)
        .containsExactly("safe", "safe", "risky");
  }

  @Test
  void malformedOutputMirrorsContractViolationRanking() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_malformed_output", false, false, "retry");

    assertThat(actionOf(actions, "retry").safetyLevel()).isEqualTo("risky");
    assertThat(actionOf(actions, "pause").safetyLevel()).isEqualTo("safe");
  }

  @Test
  void secretLeakMakesRetryRiskyAndPauseSafe() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_secret_leak", false, false, "retry");

    assertThat(actionOf(actions, "retry").safetyLevel()).isEqualTo("risky");
    assertThat(actionOf(actions, "pause").safetyLevel()).isEqualTo("safe");
  }

  @Test
  void buildFailedMakesRetryCaution() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_build_failed", false, false, "retry");

    // Story 4.9 (AC10) — classify_failure (safe) joins and outranks the caution retry.
    assertThat(actions).hasSize(2);
    assertThat(actionOf(actions, "retry").safetyLevel()).isEqualTo("caution");
    assertThat(actions.get(0).actionType()).isEqualTo("classify_failure");
  }

  @Test
  void orphanAddsReconcileGuidanceEvenWithoutDrift() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "orphan", false, false, "retry");

    assertThat(actionOf(actions, "retry").safetyLevel()).isEqualTo("caution");
    assertThat(actionOf(actions, "reconcile").safetyLevel()).isEqualTo("caution");
  }

  @Test
  void manualReconciliationMakesRetryRiskyAndPauseSafe() {
    List<RecommendedAction> actions =
        service.recommend(
            WorkflowState.FAILED, "runner_crash", false, false, "await_manual_reconciliation");

    assertThat(actionOf(actions, "retry").safetyLevel()).isEqualTo("risky");
    assertThat(actionOf(actions, "pause").safetyLevel()).isEqualTo("safe");
  }

  @Test
  void githubDriftAloneStillAddsSafeReconcile() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_crash", false, true, "retry");

    assertThat(actionOf(actions, "reconcile").safetyLevel()).isEqualTo("safe");
    assertThat(actionOf(actions, "reconcile").reason()).contains("GitHub");
  }

  @Test
  void bothDriftsDescribedTogether() {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, "runner_timeout", true, true, "retry");

    assertThat(actionOf(actions, "reconcile").reason()).contains("Linear and GitHub");
  }

  @ParameterizedTest
  @EnumSource(FailureCategory.class)
  void everyCategoryAlwaysRecommendsRetryFirstOfItsVerbAndIsRankSorted(FailureCategory category) {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, category.value(), false, false, "retry");

    assertThat(actions).anyMatch(a -> a.actionType().equals("retry"));
    // ranked safe -> caution -> risky (monotonic non-decreasing).
    List<Integer> ranks =
        actions.stream()
            .map(
                a ->
                    switch (a.safetyLevel()) {
                      case "safe" -> 0;
                      case "caution" -> 1;
                      default -> 2;
                    })
            .toList();
    assertThat(ranks).isSorted();
    // actionType stays within the recovery_actions CHECK vocabulary (V44 added
    // classify_failure).
    assertThat(actions)
        .allMatch(
            a ->
                List.of(
                        "retry",
                        "rerun",
                        "resume",
                        "takeover",
                        "pause",
                        "reconcile",
                        "classify_failure")
                    .contains(a.actionType()));
  }

  // ---------------------------------------------------------------------------
  // Story 4.9 (AC10) — classify_failure is always present and ALWAYS safe
  // ---------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(FailureCategory.class)
  void classifyFailureIsAlwaysPresentAndSafeForEveryCategory(FailureCategory category) {
    List<RecommendedAction> actions =
        service.recommend(WorkflowState.FAILED, category.value(), false, false, "retry");

    RecommendedAction classify = actionOf(actions, "classify_failure");
    assertThat(classify.safetyLevel()).isEqualTo("safe");
    assertThat(classify.reason()).isNotBlank();
    assertThat(classify.precondition()).isNotBlank();
  }

  @Test
  void classifyFailureStaysSafeUnderIntegrationDrift() {
    // The drift-downgrade loop rewrites every non-reconcile SAFE action to CAUTION; classify is
    // explicitly exempt — it mutates nothing (story 4.9 AC10).
    List<RecommendedAction> linearDrift =
        service.recommend(WorkflowState.FAILED, "runner_timeout", true, false, "retry");
    List<RecommendedAction> bothDrift =
        service.recommend(WorkflowState.FAILED, "runner_crash", true, true, "retry");

    assertThat(actionOf(linearDrift, "classify_failure").safetyLevel()).isEqualTo("safe");
    assertThat(actionOf(bothDrift, "classify_failure").safetyLevel()).isEqualTo("safe");
  }

  @Test
  void classifyFailureAbsentForNonFailedRuns() {
    assertThat(service.recommend(WorkflowState.EXECUTING, null, false, false, null)).isEmpty();
    assertThat(service.recommend(WorkflowState.COMPLETED, null, false, false, null)).isEmpty();
  }
}
