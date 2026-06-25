package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.review.StepReviewSnapshot;
import org.dradgo.application.review.spi.StepReviewReadPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.ReviewerVerdictView;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.ReviewOutcome;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;

/**
 * Story 3d-2 (AC3/AC5, Task 10) — server-side state derivation for {@link
 * WorkflowInspectionService#getReviewerVerdict}: a persisted verdict ⇒ {@code available}; a
 * queued/running reviewer execution ⇒ {@code pending}; a failed reviewer execution with no verdict
 * ⇒ {@code unavailable} + reason (AC6); NO reviewer execution at all ⇒ {@code unavailable} + {@code
 * no_reviewer_configured} so the frontend renders nothing (AC5, no-binding parity).
 */
class WorkflowInspectionServiceReviewerVerdictTest {

  private static final String RUN_ID = "run_verdict0001";

  private final WorkflowRunReadPort workflowRunReadPort = mock(WorkflowRunReadPort.class);
  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final StepReviewReadPort stepReviewReadPort = mock(StepReviewReadPort.class);
  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          workflowRunReadPort,
          mock(WorkflowEventReadPort.class),
          mock(ArtifactRecordPort.class),
          mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class),
          mock(ApprovalReadPort.class),
          mock(IntegrationLinkService.class),
          mock(RedactionPolicyService.class),
          mock(RecoveryService.class),
          runnerExecutions,
          mock(RunnerScratchStore.class),
          mock(ClarificationReadPort.class),
          mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults());

  {
    service.setStepReviewReadPort(stepReviewReadPort);
    when(workflowRunReadPort.findByPublicId(RUN_ID))
        .thenReturn(Optional.of(mock(WorkflowRunSnapshot.class)));
  }

  @Test
  void availableWhenVerdictPersisted() {
    when(stepReviewReadPort.findLatestForRun(RUN_ID))
        .thenReturn(
            Optional.of(
                new StepReviewSnapshot(
                    "rev_v0001",
                    RUN_ID,
                    "rex_v0001",
                    "art_v0001",
                    2,
                    ReviewOutcome.CONCERN,
                    "[redacted]",
                    "claude:it",
                    "codex:it",
                    OffsetDateTime.parse("2026-06-22T10:00:00Z"))));

    ReviewerVerdictView view = service.getReviewerVerdict(RUN_ID);

    assertThat(view.state()).isEqualTo("available");
    assertThat(view.outcome()).isEqualTo("concern");
    assertThat(view.rationale()).isEqualTo("[redacted]");
    assertThat(view.selfReview()).isFalse();
    assertThat(view.reviewerModelIdentity()).isEqualTo("claude:it");
  }

  @Test
  void availableForSpecPhaseRunIsStageAgnostic() {
    // Story 3e-3 (AC9) — getReviewerVerdict is run-scoped, NOT state-bound: it serves a spec-phase
    // verdict for a run parked at WaitingForSpecApproval exactly as it serves an execution-phase
    // verdict, with no endpoint/contract change. The reviewed artifact is the SPEC.
    when(workflowRunReadPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN_ID,
                    org.dradgo.domain.registry.WorkflowState.WAITING_FOR_SPEC_APPROVAL,
                    null,
                    1L,
                    0,
                    false)));
    when(stepReviewReadPort.findLatestForRun(RUN_ID))
        .thenReturn(
            Optional.of(
                new StepReviewSnapshot(
                    "rev_spec0001",
                    RUN_ID,
                    "rex_spec0001",
                    "art_spec0001",
                    1,
                    ReviewOutcome.CONCERN,
                    "[redacted]",
                    "claude:it",
                    "codex:it",
                    OffsetDateTime.parse("2026-06-25T10:00:00Z"))));

    ReviewerVerdictView view = service.getReviewerVerdict(RUN_ID);

    assertThat(view.state()).isEqualTo("available");
    assertThat(view.outcome()).isEqualTo("concern");
  }

  @Test
  void pendingWhenReviewerExecutionStillRunningAndNoVerdict() {
    when(stepReviewReadPort.findLatestForRun(RUN_ID)).thenReturn(Optional.empty());
    when(runnerExecutions.findLatestByWorkflowRunPublicIdAndStage(RUN_ID, RunnerStage.REVIEW))
        .thenReturn(Optional.of(reviewerExec(RunnerExecutionStatus.RUNNING, null)));

    assertThat(service.getReviewerVerdict(RUN_ID).state()).isEqualTo("pending");
  }

  @Test
  void pendingWhenReReviewIsInFlightEvenThoughAnOlderVerdictExists() {
    // Code-review 2026-06-22 (re-review reconciliation): a re-review over a newer artifact version
    // enqueued a fresh reviewer execution (still RUNNING) after the prior verdict persisted. The
    // panel must surface `pending`, NOT the now-stale prior `available` verdict — the verdict is
    // tied to the latest reviewer execution.
    when(stepReviewReadPort.findLatestForRun(RUN_ID))
        .thenReturn(
            Optional.of(
                new StepReviewSnapshot(
                    "rev_old0001",
                    RUN_ID,
                    "rex_old0001",
                    "art_v0001",
                    2,
                    ReviewOutcome.PASS,
                    "[redacted]",
                    "claude:it",
                    "codex:it",
                    OffsetDateTime.parse("2026-06-22T09:00:00Z"))));
    when(runnerExecutions.findLatestByWorkflowRunPublicIdAndStage(RUN_ID, RunnerStage.REVIEW))
        .thenReturn(Optional.of(reviewerExec(RunnerExecutionStatus.RUNNING, null)));

    assertThat(service.getReviewerVerdict(RUN_ID).state()).isEqualTo("pending");
  }

  @Test
  void availableWhenVerdictPresentAndLatestReviewerExecutionTerminal() {
    // The normal settled case still short-circuits to `available` — a verdict beside a terminal
    // (non-in-flight) latest reviewer execution is the current opinion, not stale.
    when(stepReviewReadPort.findLatestForRun(RUN_ID))
        .thenReturn(
            Optional.of(
                new StepReviewSnapshot(
                    "rev_cur0001",
                    RUN_ID,
                    "rex_v0001",
                    "art_v0001",
                    2,
                    ReviewOutcome.PASS,
                    "[redacted]",
                    "claude:it",
                    "codex:it",
                    OffsetDateTime.parse("2026-06-22T10:00:00Z"))));
    when(runnerExecutions.findLatestByWorkflowRunPublicIdAndStage(RUN_ID, RunnerStage.REVIEW))
        .thenReturn(Optional.of(reviewerExec(RunnerExecutionStatus.COMPLETED, null)));

    assertThat(service.getReviewerVerdict(RUN_ID).state()).isEqualTo("available");
  }

  @Test
  void unavailableWhenLatestReReviewTerminallyFailedEvenThoughAnOlderVerdictExists() {
    // Code-review 2026-06-22 (3rd round): a re-review over a newer artifact (e.g. prOutput after a
    // passed plan review) enqueued a fresh reviewer execution that then terminally FAILED — NOT
    // in-flight. The prior verdict (from the OLDER execution) must not be surfaced as a settled
    // `available` beside a current artifact whose review failed; the panel reflects the latest
    // execution's `unavailable` reason instead. The verdict is tied to the latest reviewer
    // execution by id.
    when(stepReviewReadPort.findLatestForRun(RUN_ID))
        .thenReturn(
            Optional.of(
                new StepReviewSnapshot(
                    "rev_old0001",
                    RUN_ID,
                    "rex_old0001",
                    "art_v0001",
                    2,
                    ReviewOutcome.PASS,
                    "[redacted]",
                    "claude:it",
                    "codex:it",
                    OffsetDateTime.parse("2026-06-22T09:00:00Z"))));
    when(runnerExecutions.findLatestByWorkflowRunPublicIdAndStage(RUN_ID, RunnerStage.REVIEW))
        .thenReturn(
            Optional.of(reviewerExec(RunnerExecutionStatus.FAILED, FailureCategory.RUNNER_CRASH)));

    ReviewerVerdictView view = service.getReviewerVerdict(RUN_ID);
    assertThat(view.state()).isEqualTo("unavailable");
    assertThat(view.unavailableReason()).isEqualTo("runner_crash");
    assertThat(view.outcome()).isNull();
  }

  @Test
  void unavailableWithReasonWhenReviewerExecutionFailed() {
    when(stepReviewReadPort.findLatestForRun(RUN_ID)).thenReturn(Optional.empty());
    when(runnerExecutions.findLatestByWorkflowRunPublicIdAndStage(RUN_ID, RunnerStage.REVIEW))
        .thenReturn(
            Optional.of(reviewerExec(RunnerExecutionStatus.FAILED, FailureCategory.RUNNER_CRASH)));

    ReviewerVerdictView view = service.getReviewerVerdict(RUN_ID);
    assertThat(view.state()).isEqualTo("unavailable");
    assertThat(view.unavailableReason()).isEqualTo("runner_crash");
  }

  @Test
  void unavailableNoReviewerConfiguredWhenNoReviewerExecution() {
    when(stepReviewReadPort.findLatestForRun(RUN_ID)).thenReturn(Optional.empty());
    when(runnerExecutions.findLatestByWorkflowRunPublicIdAndStage(RUN_ID, RunnerStage.REVIEW))
        .thenReturn(Optional.empty());

    ReviewerVerdictView view = service.getReviewerVerdict(RUN_ID);
    assertThat(view.state()).isEqualTo("unavailable");
    assertThat(view.unavailableReason()).isEqualTo("no_reviewer_configured");
  }

  private static RunnerExecutionSnapshot reviewerExec(
      RunnerExecutionStatus status, FailureCategory failureCategory) {
    return new RunnerExecutionSnapshot(
        "rex_v0001",
        RUN_ID,
        RunnerStage.REVIEW,
        status,
        1,
        OffsetDateTime.parse("2026-06-22T09:00:00Z"),
        OffsetDateTime.parse("2026-06-22T09:10:00Z"),
        failureCategory,
        null,
        OffsetDateTime.parse("2026-06-22T09:00:00Z"),
        null,
        null);
  }
}
