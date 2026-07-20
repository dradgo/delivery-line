package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.FailureDescription;
import org.dradgo.application.recovery.RecommendationService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.runner.RedactedRunnerLog;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerLogStore;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureDiagnostics;
import org.dradgo.application.workflow.WorkflowInspectionService.RedactedRunnerLogView;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.4 (AC1/AC7/AC10) — assembly + NFR7 five-questions + reason redaction coverage for {@link
 * WorkflowInspectionService#getFailureDiagnostics}, plus the {@link
 * WorkflowInspectionService#getRedactedRunnerLog} seam. Pure unit test (mocked ports + the real
 * {@link RecommendationService} and {@link RedactionPolicyService}).
 */
class WorkflowInspectionServiceFailureDiagnosticsTest {

  private static final String RUN = "run_diag12345678";
  private static final String REX = "rex_diag12345678";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-07T10:00:00Z");

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final IntegrationLinkService links = mock(IntegrationLinkService.class);
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final RunnerLogStore runnerLogStore = mock(RunnerLogStore.class);
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());

  private final WorkflowInspectionService service = buildService();

  private WorkflowInspectionService buildService() {
    WorkflowInspectionService svc =
        new WorkflowInspectionService(
            runs,
            events,
            mock(ArtifactRecordPort.class),
            mock(ArtifactPayloadStore.class),
            mock(ApprovalReadPort.class),
            links,
            redaction,
            recovery,
            runnerExecutions,
            mock(RunnerScratchStore.class),
            mock(ClarificationReadPort.class),
            mock(RecoveryActionRecordPort.class),
            RunnerProperties.defaults(),
            RunnerWorkerPoolProperties.defaults(),
            mock(SplitProposalReadPort.class));
    svc.setRecommendationService(new RecommendationService());
    svc.setRunnerLogStore(runnerLogStore);
    return svc;
  }

  private static WorkflowRunSnapshot failedRun() {
    return new WorkflowRunSnapshot(
        RUN, WorkflowState.FAILED, null, 3L, 0, false, "prj_default", null);
  }

  private void stubFailed(String reason) {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.of(failedRun()));
    when(recovery.describeFailure(RUN))
        .thenReturn(
            new FailureDescription(
                RUN,
                WorkflowState.FAILED,
                "execution",
                "Executing",
                NOW,
                "runner_timeout",
                NOW,
                "retry",
                "corr_diag_1"));
    when(events.findLatestFailureEvent(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_1",
                    RUN,
                    WorkflowEventType.RUNNER_FAILED,
                    WorkflowState.EXECUTING,
                    WorkflowState.FAILED,
                    "codex-runner",
                    ActorType.AGENT,
                    reason,
                    null,
                    false,
                    NOW,
                    Map.of())));
    // NFR7 "who acted" resolves from the latest RECOVERY/TAKEOVER-attributing event only — a later
    // audit.logDownloaded (or archive) event must NOT mask the real actor. The service therefore
    // reads via findLatestByWorkflowRunPublicIdAndEventTypeIn(RECOVERY_ACTOR_EVENT_TYPES), not the
    // run's latest event of any type.
    when(events.findLatestByWorkflowRunPublicIdAndEventTypeIn(
            eq(RUN), org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_recovery",
                    RUN,
                    WorkflowEventType.RECOVERY_RETRIED,
                    WorkflowState.FAILED,
                    WorkflowState.FAILED,
                    "operator-jane",
                    ActorType.HUMAN,
                    reason,
                    null,
                    true,
                    NOW,
                    Map.of())));
    when(runnerExecutions.findByWorkflowRunPublicIdAndStatusIn(
            eq(RUN), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(List.of());
    when(links.findActiveLinearLinkReadOnly(RUN))
        .thenReturn(
            Optional.of(
                new IntegrationLink(
                    "iln_1",
                    RUN,
                    "linear",
                    "LIN-101",
                    IntegrationSyncStatus.SYNCED,
                    Instant.parse("2026-07-07T09:00:00Z"),
                    Instant.parse("2026-07-07T09:30:00Z"),
                    null)));
    when(links.findActiveGitHubPrLinkReadOnly(RUN)).thenReturn(Optional.empty());
  }

  @Test
  void assemblesFailureDiagnosticsReusingDescribeFailure() {
    stubFailed("boom");

    FailureDiagnostics diagnostics = service.getFailureDiagnostics(RUN);

    assertThat(diagnostics.currentState()).isEqualTo(WorkflowState.FAILED);
    assertThat(diagnostics.failedStage()).isEqualTo("execution");
    assertThat(diagnostics.lastSuccessfulStage()).isEqualTo("Executing");
    assertThat(diagnostics.lastGoodState()).isEqualTo("Executing");
    assertThat(diagnostics.failureCategory()).isEqualTo("runner_timeout");
    assertThat(diagnostics.correlationId()).isEqualTo("corr_diag_1");
    assertThat(diagnostics.nextSafeAction()).isEqualTo("retry");
    // who acted — the recovery actor, not a later intervention actor.
    assertThat(diagnostics.lastActorIdentity()).isEqualTo("operator-jane");
    assertThat(diagnostics.linearSyncStatus()).isNotNull();
    assertThat(diagnostics.linearSyncStatus().syncStatus()).isEqualTo("synced");
    assertThat(diagnostics.linearSyncStatus().lastSyncAt()).isNotNull();
    assertThat(diagnostics.githubSyncStatus()).isNull();
    // runner_timeout + no drift → a top-ranked safe retry.
    assertThat(diagnostics.recommendedRecoveryActions()).isNotEmpty();
    assertThat(diagnostics.recommendedRecoveryActions().get(0).actionType()).isEqualTo("retry");
    assertThat(diagnostics.recommendedRecoveryActions().get(0).safetyLevel()).isEqualTo("safe");
  }

  @Test
  void nfr7FiveQuestionFieldsAreNonEmptyForAFailedRun() {
    stubFailed("container exited");

    FailureDiagnostics d = service.getFailureDiagnostics(RUN);

    // what happened
    assertThat(d.failureReason()).isNotBlank();
    assertThat(d.failureCategory()).isNotBlank();
    // what changed
    assertThat(d.lastSuccessfulStage()).isNotBlank();
    assertThat(d.failedStage()).isNotBlank();
    // who acted
    assertThat(d.lastActorIdentity()).isNotBlank();
    // what is next
    assertThat(d.nextSafeAction()).isNotBlank();
    assertThat(d.recommendedRecoveryActions()).isNotEmpty();
  }

  @Test
  void failureReasonIsRedactedAndControlCharStripped() {
    stubFailed("crash line1\nInjected line2\ttabbed");

    FailureDiagnostics d = service.getFailureDiagnostics(RUN);

    assertThat(d.failureReason()).doesNotContain("\n").doesNotContain("\t");
    assertThat(d.failureReason()).contains("crash line1");
  }

  @Test
  void missingRunThrowsRunNotFound() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getFailureDiagnostics(RUN))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.RUN_NOT_FOUND);
  }

  @Test
  void getRedactedRunnerLogServesAlreadyRedactedBytesWhenAvailable() {
    when(runnerExecutions.findByPublicId(REX))
        .thenReturn(Optional.of(capturedRex(DataClassification.SHAREABLE_REDACTED)));
    when(runnerLogStore.readRedacted(REX))
        .thenReturn(Optional.of(new RedactedRunnerLog("out", "err", false)));

    RedactedRunnerLogView view = service.getRedactedRunnerLog(REX);

    assertThat(view.available()).isTrue();
    assertThat(view.workflowRunId()).isEqualTo(RUN);
    assertThat(view.stdout()).isEqualTo("out");
    assertThat(view.stderr()).isEqualTo("err");
    assertThat(view.classification()).isEqualTo("shareable-redacted");
  }

  @Test
  void getRedactedRunnerLogUnavailableWhenRexMissing() {
    when(runnerExecutions.findByPublicId(REX)).thenReturn(Optional.empty());

    RedactedRunnerLogView view = service.getRedactedRunnerLog(REX);

    assertThat(view.available()).isFalse();
    assertThat(view.reason()).isEqualTo("runnerExecutionNotFound");
  }

  @Test
  void getRedactedRunnerLogUnavailableWhenLogsNotCaptured() {
    when(runnerExecutions.findByPublicId(REX))
        .thenReturn(Optional.of(capturedRex(DataClassification.SHAREABLE_REDACTED)));
    when(runnerLogStore.readRedacted(REX)).thenReturn(Optional.empty());

    RedactedRunnerLogView view = service.getRedactedRunnerLog(REX);

    assertThat(view.available()).isFalse();
    assertThat(view.reason()).isEqualTo("logsNotCaptured");
  }

  @Test
  void getRedactedRunnerLogRefusesNonServableClassification() {
    when(runnerExecutions.findByPublicId(REX))
        .thenReturn(Optional.of(capturedRex(DataClassification.SHAREABLE_FULL)));
    when(runnerLogStore.readRedacted(REX))
        .thenReturn(Optional.of(new RedactedRunnerLog("out", "err", false)));

    RedactedRunnerLogView view = service.getRedactedRunnerLog(REX);

    assertThat(view.available()).isFalse();
    assertThat(view.reason()).isEqualTo("classificationNotServable");
  }

  private static RunnerExecutionSnapshot capturedRex(DataClassification classification) {
    return new RunnerExecutionSnapshot(
        REX,
        RUN,
        RunnerStage.EXECUTION,
        RunnerExecutionStatus.FAILED,
        1,
        NOW,
        NOW,
        null,
        NOW,
        NOW,
        null,
        null,
        "/runner-logs/" + REX,
        classification,
        128L,
        2);
  }
}
