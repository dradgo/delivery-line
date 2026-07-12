package org.dradgo.application.recovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pure, deterministic safety-ranking policy for recovery recommendations (story 4.4 AC2/AC9).
 *
 * <p>This service holds ZERO ports, no DB access, and no {@code @Transactional} boundary — it is a
 * pure function of {@code (currentState, failureCategory, linearDrift, githubDrift,
 * nextSafeAction)} → a safety-ranked {@link RecommendedAction} list. Keeping the ranking logic here
 * (not in an adapter) is what ArchUnit rule {@code
 * RECOMMENDATION_LOGIC_LIVES_IN_RECOMMENDATION_SERVICE} enforces (AC9). The former ArchUnit
 * scope-lock on {@link RecoveryService} was lifted by story 4.28 (ADR 0033); that service now also
 * exposes {@code resume} (4.5), {@code reconcile} (4.6), {@code rerunFromStep} (4.7), {@code pause}
 * (4.8), and {@code classifyFailure} (4.9) — so the {@code pause} recommendation below points at a
 * real, invocable method ({@code RecoveryService.pause}; {@code Failed} is in its pausable-source
 * allow-list, pinned by {@code RecommendedPauseIsInvocableTest}), and so does the {@code
 * classify_failure} recommendation ({@code RecoveryService.classifyFailure} gates on {@code Failed}
 * only).
 *
 * <p><strong>Advisory only.</strong> The returned list DISPLAYS ranked guidance. Only {@code retry}
 * has a wired one-click invocation today (Reconciliation 10) — the other verbs surface as ranked
 * guidance until their recovery endpoints ship (stories 4.10–4.14/4.22). {@code actionType} values
 * stay within the {@code recovery_actions} CHECK vocabulary {@code
 * retry|rerun|resume|takeover|pause|reconcile|classify_failure} (V44 widened the CHECK, story 4.9).
 */
@Service
public class RecommendationService {

  static final String SAFE = "safe";
  static final String CAUTION = "caution";
  static final String RISKY = "risky";

  static final String ACTION_RETRY = "retry";
  static final String ACTION_PAUSE = "pause";
  static final String ACTION_RECONCILE = "reconcile";
  static final String ACTION_CLASSIFY_FAILURE = "classify_failure";

  private static final String NEXT_SAFE_ACTION_AWAIT_MANUAL_RECONCILIATION =
      "await_manual_reconciliation";

  private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

  /** Orders {@code safe} before {@code caution} before {@code risky}; unknown levels sort last. */
  private static int safetyRank(String safetyLevel) {
    return switch (safetyLevel) {
      case SAFE -> 0;
      case CAUTION -> 1;
      case RISKY -> 2;
      default -> 3;
    };
  }

  /**
   * Ranks the recovery actions advisable for a failed run.
   *
   * <p>Non-{@code Failed} runs return an empty list (nothing to recover). For a {@code Failed} run
   * the ranking is derived from the failure category crossed with integration drift and the
   * pre-computed {@code nextSafeAction} from {@link RecoveryService#describeFailure}:
   *
   * <ul>
   *   <li>{@code await_manual_reconciliation} (non-retryable git failure / failed-orphan artifact):
   *       {@code retry} is {@code risky}; surface {@code pause} as the {@code safe} intervention.
   *   <li>{@code runner_timeout}/{@code runner_crash}/… (generic): {@code retry} is {@code safe}
   *       (downgraded to {@code caution} when there is integration drift).
   *   <li>{@code runner_contract_violation}/{@code runner_malformed_output}/{@code
   *       runner_secret_leak}: {@code retry} is {@code risky}; add {@code pause} as {@code safe}.
   *   <li>{@code runner_build_failed}: {@code retry} is {@code caution}.
   *   <li>{@code orphan}: {@code retry} is {@code caution}; add a {@code reconcile} guidance
   *       action.
   *   <li>Any integration drift ({@code stale}/{@code failed} on Linear or GitHub): add {@code
   *       reconcile} as {@code safe} and downgrade every other {@code safe} action to {@code
   *       caution} until the drift is resolved.
   * </ul>
   *
   * @param currentState the run's current workflow state (empty list unless {@code FAILED})
   * @param failureCategory normalized failure category value, or null when unknown
   * @param linearDrift whether the Linear integration link is {@code stale}/{@code failed}
   * @param githubDrift whether the GitHub PR integration link is {@code stale}/{@code failed}
   * @param nextSafeAction the {@code describeFailure} next-safe-action hint, nullable
   * @return the recovery actions ranked {@code safe} → {@code caution} → {@code risky}
   */
  public List<RecommendedAction> recommend(
      WorkflowState currentState,
      String failureCategory,
      boolean linearDrift,
      boolean githubDrift,
      String nextSafeAction) {
    if (currentState != WorkflowState.FAILED) {
      log.debug(
          "recommend skipped currentState={} (non-Failed run yields no recommendations)",
          currentState == null ? "null" : currentState.value());
      return List.of();
    }

    boolean drift = linearDrift || githubDrift;
    boolean manualReconciliation =
        NEXT_SAFE_ACTION_AWAIT_MANUAL_RECONCILIATION.equals(nextSafeAction);
    List<RecommendedAction> actions = new ArrayList<>();

    // --- retry (always surfaced) ---
    String retrySafety;
    String retryReason;
    if (manualReconciliation) {
      retrySafety = RISKY;
      retryReason =
          "Automatic retry is blocked — this run needs manual reconciliation before it can safely"
              + " re-run.";
    } else if (isRiskyRetryCategory(failureCategory)) {
      retrySafety = RISKY;
      retryReason =
          "Retrying a "
              + describeCategory(failureCategory)
              + " failure risks repeating the same violation; investigate before retrying.";
    } else if (isCautionRetryCategory(failureCategory)) {
      retrySafety = CAUTION;
      retryReason =
          "Retry may succeed after a "
              + describeCategory(failureCategory)
              + " failure, but confirm the underlying cause first.";
    } else {
      retrySafety = SAFE;
      retryReason = "Retry from the failed stage is expected to succeed on a transient failure.";
    }
    actions.add(new RecommendedAction(ACTION_RETRY, retrySafety, retryReason, "workspace intact"));

    // --- pause (manual-reconciliation or violation categories) ---
    if (manualReconciliation || isRiskyRetryCategory(failureCategory)) {
      actions.add(
          new RecommendedAction(
              ACTION_PAUSE,
              SAFE,
              "Pause the run for operator intervention while the failure is investigated.",
              "no automated recovery in flight"));
    }

    // --- reconcile (integration drift, or orphan guidance) ---
    if (drift) {
      actions.add(
          new RecommendedAction(
              ACTION_RECONCILE,
              SAFE,
              "Integration links have drifted ("
                  + driftDescription(linearDrift, githubDrift)
                  + "); reconcile them before any other recovery.",
              "integration link marked stale or failed"));
    } else if (FailureCategory.ORPHAN.value().equals(failureCategory)) {
      actions.add(
          new RecommendedAction(
              ACTION_RECONCILE,
              CAUTION,
              "The run produced an orphaned artifact; reconcile ownership before retrying.",
              "verify orphaned artifact ownership"));
    }

    // --- classify_failure (always surfaced, always safe — story 4.9 AC10) ---
    // A pure metadata operation: no state transition, no runner re-dispatch, no integration
    // side-effect, so nothing about the run's condition (including drift) can make it unsafe.
    actions.add(
        new RecommendedAction(
            ACTION_CLASSIFY_FAILURE,
            SAFE,
            "Classifying the failure records operator triage for cross-run analysis; it changes"
                + " no workflow state.",
            "run is in a terminal-failure state"));

    // Drift makes reconcile the single safe MUTATING move — every other safe action becomes
    // caution, EXCEPT classify_failure, which mutates nothing and stays safe under drift (story
    // 4.9 AC10's explicit exemption).
    if (drift) {
      for (int i = 0; i < actions.size(); i++) {
        RecommendedAction a = actions.get(i);
        if (!ACTION_RECONCILE.equals(a.actionType())
            && !ACTION_CLASSIFY_FAILURE.equals(a.actionType())
            && SAFE.equals(a.safetyLevel())) {
          actions.set(
              i,
              new RecommendedAction(
                  a.actionType(),
                  CAUTION,
                  a.reason() + " Hold until integration drift is reconciled.",
                  a.precondition()));
        }
      }
    }

    actions.sort(Comparator.comparingInt(a -> safetyRank(a.safetyLevel())));
    log.debug(
        "recommend resolved currentState=failed failureCategory={} linearDrift={} githubDrift={}"
            + " nextSafeAction={} recommendationCount={}",
        failureCategory,
        linearDrift,
        githubDrift,
        nextSafeAction,
        actions.size());
    return List.copyOf(actions);
  }

  private static boolean isRiskyRetryCategory(String failureCategory) {
    return FailureCategory.RUNNER_CONTRACT_VIOLATION.value().equals(failureCategory)
        || FailureCategory.RUNNER_MALFORMED_OUTPUT.value().equals(failureCategory)
        || FailureCategory.RUNNER_SECRET_LEAK.value().equals(failureCategory);
  }

  private static boolean isCautionRetryCategory(String failureCategory) {
    return FailureCategory.RUNNER_BUILD_FAILED.value().equals(failureCategory)
        || FailureCategory.ORPHAN.value().equals(failureCategory)
        || FailureCategory.TESTCONTAINERS_INFRA_FAILED.value().equals(failureCategory);
  }

  private static String describeCategory(String failureCategory) {
    return failureCategory == null ? "runner" : failureCategory.replace('_', ' ');
  }

  private static String driftDescription(boolean linearDrift, boolean githubDrift) {
    if (linearDrift && githubDrift) {
      return "Linear and GitHub";
    }
    if (linearDrift) {
      return "Linear";
    }
    return "GitHub";
  }
}
