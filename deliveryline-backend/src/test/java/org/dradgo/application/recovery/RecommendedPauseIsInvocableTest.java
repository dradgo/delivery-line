package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowTransitionTable;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.AllowedAction;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.8 (Reconciliation 3) — the recommender's advice is TRUTHFUL. Story 4.4's {@link
 * RecommendationService} shipped {@code pause} as a {@code safe} recommendation on {@code Failed}
 * runs while no pause method or {@code Failed → Paused} edge existed. Now that 4.8 ships both, this
 * contract pins the legs that keep the advice invocable in ONE place: (1) the recommender still
 * emits {@code pause} for the risky/manual-reconciliation categories on a Failed run; (2) {@code
 * FAILED} is in {@code RecoveryService.PAUSABLE_SOURCE_STATES} (the executor gate accepts it); (3)
 * the {@code Failed → Paused} transition edge is legal; and (4) {@code pause_workflow} is offered
 * in the {@code workflow_owner} allowed-actions for {@code FAILED} (queried through {@link
 * WorkflowInspectionService#getAllowedActions} — the matrix's single home — so the exhaustive
 * matrix pin in {@code WorkflowInspectionServiceAllowedActionsTest} stays authoritative while this
 * test can never pass vacuously if the FAILED arm drops the action).
 */
class RecommendedPauseIsInvocableTest {

  private final RecommendationService recommendationService = new RecommendationService();

  @Test
  void pauseIsRecommendedSafeOnFailedRunWithRiskyCategoryAndIsActuallyInvocable() {
    List<RecommendedAction> actions =
        recommendationService.recommend(
            WorkflowState.FAILED,
            FailureCategory.RUNNER_CONTRACT_VIOLATION.value(),
            false,
            false,
            null);

    RecommendedAction pause =
        actions.stream()
            .filter(a -> RecommendationService.ACTION_PAUSE.equals(a.actionType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("pause recommendation missing"));
    assertThat(pause.safetyLevel()).isEqualTo(RecommendationService.SAFE);

    // Leg 2 — the executor gate accepts FAILED (RecoveryService.pause would not 409 this run).
    assertThat(RecoveryService.PAUSABLE_SOURCE_STATES).contains(WorkflowState.FAILED);

    // Leg 3 — the transition edge the executor drives is legal.
    assertThat(WorkflowTransitionTable.defaultTable().allowedTargetsFrom(WorkflowState.FAILED))
        .contains(WorkflowState.PAUSED);

    // Leg 4 — the operator is actually OFFERED the action: pause_workflow is in the
    // workflow_owner allowed-actions arm for a Failed run.
    assertThat(allowedActionsForFailedWorkflowOwner()).contains(AllowedAction.PAUSE_WORKFLOW);
  }

  @Test
  void pauseIsRecommendedSafeOnManualReconciliationFailure() {
    List<RecommendedAction> actions =
        recommendationService.recommend(
            WorkflowState.FAILED,
            FailureCategory.RUNNER_TIMEOUT.value(),
            false,
            false,
            "await_manual_reconciliation");

    assertThat(
            actions.stream()
                .anyMatch(
                    a ->
                        RecommendationService.ACTION_PAUSE.equals(a.actionType())
                            && RecommendationService.SAFE.equals(a.safetyLevel())))
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Minimal replica of the {@code WorkflowInspectionServiceAllowedActionsTest} fixture: the FAILED
   * workflow_owner arm needs only the run snapshot stub — every other collaborator resolves via
   * Mockito's empty-Optional/empty-List defaults (no spec, no events, no split dispatch).
   */
  private static List<AllowedAction> allowedActionsForFailedWorkflowOwner() {
    String run = "run_recpauseinv1";
    WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
    org.dradgo.application.clarification.spi.ClarificationReadPort clarifications =
        mock(org.dradgo.application.clarification.spi.ClarificationReadPort.class);
    WorkflowInspectionService inspectionService =
        new WorkflowInspectionService(
            runs,
            mock(org.dradgo.application.workflow.spi.WorkflowEventReadPort.class),
            mock(org.dradgo.application.artifact.spi.ArtifactRecordPort.class),
            mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class),
            mock(org.dradgo.application.approval.spi.ApprovalReadPort.class),
            mock(org.dradgo.application.integration.IntegrationLinkService.class),
            new org.dradgo.application.security.RedactionPolicyService(
                new org.dradgo.application.security.DataClassificationService()),
            mock(RecoveryService.class),
            mock(org.dradgo.application.runner.spi.RunnerExecutionRecordPort.class),
            mock(org.dradgo.application.runner.spi.RunnerScratchStore.class),
            clarifications,
            mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
            org.dradgo.application.runner.RunnerProperties.defaults(),
            org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults(),
            mock(org.dradgo.application.workflow.spi.SplitProposalReadPort.class));
    when(runs.findByPublicId(run))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(run, WorkflowState.FAILED, null, 1L, 0, false)));
    when(clarifications.countPendingByWorkflowRun(run)).thenReturn(0);
    return inspectionService.getAllowedActions(run, "workflow_owner").actions();
  }
}
