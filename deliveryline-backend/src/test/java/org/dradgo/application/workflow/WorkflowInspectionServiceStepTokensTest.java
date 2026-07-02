package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.StepExecutionView;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;

/**
 * Story 3g-4 (FR74, AC1/AC2) — per-step token read + run-level rollup unit coverage. {@link
 * WorkflowInspectionService#getStepExecutions} projects every runner execution oldest-first with
 * its nullable token counts and guards run-not-found / bad-prefix; {@link
 * WorkflowInspectionService#rollupTotalTokens} sums only the non-null step totals, returns {@code
 * null} when none reported (never {@code 0}), and clamps int32 overflow at {@code
 * Integer.MAX_VALUE}.
 */
class WorkflowInspectionServiceStepTokensTest {

  private static final String RUN_ID = "run_tokens0001";

  private final WorkflowRunReadPort workflowRunReadPort = mock(WorkflowRunReadPort.class);
  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          workflowRunReadPort,
          mock(WorkflowEventReadPort.class),
          mock(ArtifactRecordPort.class),
          mock(ArtifactPayloadStore.class),
          mock(ApprovalReadPort.class),
          mock(IntegrationLinkService.class),
          mock(RedactionPolicyService.class),
          mock(RecoveryService.class),
          runnerExecutions,
          mock(RunnerScratchStore.class),
          mock(ClarificationReadPort.class),
          mock(RecoveryActionRecordPort.class),
          RunnerProperties.defaults(),
          RunnerWorkerPoolProperties.defaults(),
          mock(SplitProposalReadPort.class));

  {
    when(workflowRunReadPort.findByPublicId(RUN_ID))
        .thenReturn(Optional.of(mock(WorkflowRunSnapshot.class)));
  }

  // ---- getStepExecutions ----

  @Test
  void stepsReturnedOldestFirstWithTokensMapped() {
    // Seeded out of createdAt order — the port applies no ordering, the service sorts.
    when(runnerExecutions.findByWorkflowRunPublicIdAndStatusIn(
            org.mockito.ArgumentMatchers.eq(RUN_ID), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            List.of(
                step(
                    "rex_newer",
                    RunnerStage.REVIEW,
                    RunnerExecutionStatus.COMPLETED,
                    OffsetDateTime.parse("2026-07-02T11:00:00Z"),
                    5,
                    6,
                    11),
                step(
                    "rex_older",
                    RunnerStage.EXECUTION,
                    RunnerExecutionStatus.COMPLETED,
                    OffsetDateTime.parse("2026-07-02T10:00:00Z"),
                    100,
                    200,
                    300)));

    List<StepExecutionView> steps = service.getStepExecutions(RUN_ID);

    assertThat(steps).extracting(StepExecutionView::runnerExecutionId)
        .containsExactly("rex_older", "rex_newer");
    StepExecutionView older = steps.get(0);
    assertThat(older.stage()).isEqualTo("execution");
    assertThat(older.status()).isEqualTo("completed");
    assertThat(older.inputTokens()).isEqualTo(100);
    assertThat(older.outputTokens()).isEqualTo(200);
    assertThat(older.totalTokens()).isEqualTo(300);
  }

  @Test
  void stepWithNullTokensPassesThroughAsNullNotZero() {
    when(runnerExecutions.findByWorkflowRunPublicIdAndStatusIn(
            org.mockito.ArgumentMatchers.eq(RUN_ID), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            List.of(
                step(
                    "rex_noreport",
                    RunnerStage.EXECUTION,
                    RunnerExecutionStatus.RUNNING,
                    OffsetDateTime.parse("2026-07-02T10:00:00Z"),
                    null,
                    null,
                    null)));

    StepExecutionView only = service.getStepExecutions(RUN_ID).get(0);
    assertThat(only.inputTokens()).isNull();
    assertThat(only.outputTokens()).isNull();
    assertThat(only.totalTokens()).isNull();
  }

  @Test
  void stepsEmptyWhenNoExecutions() {
    when(runnerExecutions.findByWorkflowRunPublicIdAndStatusIn(
            org.mockito.ArgumentMatchers.eq(RUN_ID), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());

    assertThat(service.getStepExecutions(RUN_ID)).isEmpty();
  }

  @Test
  void getStepExecutionsThrowsRunNotFoundForMissingRun() {
    when(workflowRunReadPort.findByPublicId("run_missing00")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getStepExecutions("run_missing00"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.RUN_NOT_FOUND);
  }

  @Test
  void getStepExecutionsThrowsInvalidPrefixForBadId() {
    assertThatThrownBy(() -> service.getStepExecutions("bogus_0001"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_ID_PREFIX);
  }

  // ---- rollupTotalTokens ----

  @Test
  void rollupSumsNonNullStepTotals() {
    Integer sum =
        WorkflowInspectionService.rollupTotalTokens(
            List.of(
                stepTotal(300),
                stepTotal(11),
                stepTotal(0))); // 0 = "reported zero", contributes to the sum
    assertThat(sum).isEqualTo(311);
  }

  @Test
  void rollupReturnsNullWhenNoStepReportedTokens() {
    Integer sum =
        WorkflowInspectionService.rollupTotalTokens(List.of(stepTotal(null), stepTotal(null)));
    assertThat(sum).isNull();
  }

  @Test
  void rollupSkipsNullsAndSumsOnlyReported() {
    Integer sum =
        WorkflowInspectionService.rollupTotalTokens(
            List.of(stepTotal(null), stepTotal(50), stepTotal(null), stepTotal(25)));
    assertThat(sum).isEqualTo(75);
  }

  @Test
  void rollupClampsInt32OverflowAtMaxValue() {
    Integer sum =
        WorkflowInspectionService.rollupTotalTokens(
            List.of(
                stepTotal(Integer.MAX_VALUE), stepTotal(Integer.MAX_VALUE), stepTotal(1)));
    assertThat(sum).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void rollupEmptyListIsNull() {
    assertThat(WorkflowInspectionService.rollupTotalTokens(List.of())).isNull();
  }

  // ---- helpers ----

  private static RunnerExecutionSnapshot step(
      String publicId,
      RunnerStage stage,
      RunnerExecutionStatus status,
      OffsetDateTime createdAt,
      Integer inputTokens,
      Integer outputTokens,
      Integer totalTokens) {
    return new RunnerExecutionSnapshot(
        publicId,
        RUN_ID,
        stage,
        status,
        1,
        createdAt,
        createdAt,
        null,
        null,
        createdAt,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        100,
        0,
        null,
        null,
        null,
        null,
        inputTokens,
        outputTokens,
        totalTokens);
  }

  private static RunnerExecutionSnapshot stepTotal(Integer totalTokens) {
    return step(
        "rex_x",
        RunnerStage.EXECUTION,
        RunnerExecutionStatus.COMPLETED,
        OffsetDateTime.parse("2026-07-02T10:00:00Z"),
        null,
        null,
        totalTokens);
  }
}
