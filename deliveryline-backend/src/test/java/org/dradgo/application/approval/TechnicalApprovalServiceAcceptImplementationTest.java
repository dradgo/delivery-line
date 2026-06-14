package org.dradgo.application.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.approval.spi.ApprovalWritePort.NewApproval;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Story 3.20 unit-level coverage for {@link TechnicalApprovalService#acceptImplementation} with all
 * dependencies mocked (the technical-approval twin of {@code ApprovalServiceApproveSpecTest}). Pins
 * AC13 cases plus the Logback {@link ListAppender} log-level pins required by the cross-cutting
 * logging task: the success line and the WARN rejection branches.
 *
 * <p>Idempotent-replay (AC10) and idempotency conflict are pinned at the {@code
 * WorkflowCommandService} layer (the surrounding {@code executeIdempotent} pipeline owns
 * reservation/replay) and end-to-end by the contract IT.
 */
class TechnicalApprovalServiceAcceptImplementationTest {

  private static final String RUN_ID = "run_accept9999";
  private static final String ARTIFACT_ID = "art_impl9999";
  private static final String RUNNER_EXECUTION_ID = "rex_exe0001";
  private static final OffsetDateTime FIXED_NOW =
      OffsetDateTime.of(2026, 6, 14, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);

  private ArtifactRecordPort artifactRecordPort;
  private ArtifactService artifactService;
  private ApprovalWritePort approvalWritePort;
  private WorkflowEventWritePort workflowEventWritePort;
  private WorkflowTransitionService workflowTransitionService;
  private WorkflowOrchestrationService workflowOrchestrationService;
  private IntegrationLinkService integrationLinkService;
  private RunnerExecutionRecordPort runnerExecutionRecordPort;

  private TechnicalApprovalService technicalApprovalService;

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    artifactRecordPort = Mockito.mock(ArtifactRecordPort.class);
    artifactService = Mockito.mock(ArtifactService.class);
    approvalWritePort = Mockito.mock(ApprovalWritePort.class);
    workflowEventWritePort = Mockito.mock(WorkflowEventWritePort.class);
    workflowTransitionService = Mockito.mock(WorkflowTransitionService.class);
    workflowOrchestrationService = Mockito.mock(WorkflowOrchestrationService.class);
    integrationLinkService = Mockito.mock(IntegrationLinkService.class);
    runnerExecutionRecordPort = Mockito.mock(RunnerExecutionRecordPort.class);
    technicalApprovalService =
        new TechnicalApprovalService(
            artifactRecordPort,
            artifactService,
            approvalWritePort,
            workflowEventWritePort,
            workflowTransitionService,
            workflowOrchestrationService,
            integrationLinkService,
            new ApprovalVersionBinder(artifactRecordPort, runnerExecutionRecordPort),
            FIXED_CLOCK);

    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(TechnicalApprovalService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(TechnicalApprovalService.class)).detachAppender(appender);
  }

  @Test
  void happyPathImplementationPlanTransitionsToExecutingAndDispatches() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(true);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);

    ApprovalResult result =
        technicalApprovalService.acceptImplementation(commandWithVersions(3, 2));

    assertThat(result.resultingState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(result.reviewerRole()).isEqualTo("developer");
    assertThat(result.artifactVersion()).isEqualTo(3);
    assertThat(result.contextBundleVersion()).isEqualTo(2);
    assertThat(result.approvalId()).startsWith("apr_");

    ArgumentCaptor<NewApproval> newApprovalCaptor = ArgumentCaptor.forClass(NewApproval.class);
    verify(approvalWritePort).insert(newApprovalCaptor.capture());
    NewApproval newApproval = newApprovalCaptor.getValue();
    assertThat(newApproval.decision()).isEqualTo(ApprovalSnapshot.DECISION_APPROVED);
    assertThat(newApproval.rejectionTaxonomy()).isNull();
    assertThat(newApproval.reviewerRole()).isEqualTo("developer");
    assertThat(newApproval.decidedAt()).isEqualTo(FIXED_NOW);

    // (AC9) attribution: the approval.approved event carries reviewerRole=developer matching the
    // row.
    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(eventCaptor.capture());
    WorkflowEventRecord event = eventCaptor.getValue();
    assertThat(event.eventType()).isEqualTo(WorkflowEventType.APPROVAL_APPROVED);
    assertThat(event.details().get("reviewerRole")).isEqualTo("developer");
    assertThat(event.details().get("approvalId")).isEqualTo(newApproval.publicId());

    // (AC8) transition -> EXECUTING then dispatchImplementation, and the event appended BEFORE the
    // transition (Trap T5 ordering).
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.EXECUTING),
            any(TransitionActor.class),
            eq("accept implementation"),
            eq("idem-accept-1234567890"),
            anyMap());
    verify(workflowOrchestrationService).dispatchImplementation(RUN_ID, "corr-1");
    InOrder inOrder = inOrder(workflowEventWritePort, workflowTransitionService);
    inOrder.verify(workflowEventWritePort).append(any(WorkflowEventRecord.class));
    inOrder
        .verify(workflowTransitionService)
        .transition(
            eq(RUN_ID), eq(WorkflowState.EXECUTING), any(), anyString(), anyString(), anyMap());

    assertSuccessLogEmitted();
  }

  @Test
  void happyPathPrOutputTransitionsToCompletedWithoutDispatch() {
    seedArtifact(ArtifactType.PR_OUTPUT, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(true);
    when(integrationLinkService.findActiveGitHubPrLink(RUN_ID))
        .thenReturn(Optional.of(githubPrLink("owner/repo#1")));
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);

    ApprovalResult result =
        technicalApprovalService.acceptImplementation(commandWithVersions(3, 2));

    assertThat(result.resultingState()).isEqualTo(WorkflowState.COMPLETED);
    // PR-link gate ran through the canonical method (self-match against the active link).
    verify(integrationLinkService).assertArtifactPrLinkMatches(RUN_ID, "owner/repo#1");
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.COMPLETED),
            any(TransitionActor.class),
            eq("accept implementation"),
            eq("idem-accept-1234567890"),
            anyMap());
    // prOutput approval must NOT dispatch and must NOT call Linear directly (Trap T6 — the 3.16
    // post-commit hook fires the sync).
    verify(workflowOrchestrationService, never()).dispatchImplementation(anyString(), anyString());
    assertSuccessLogEmitted();
  }

  @Test
  void specArtifactIsRejectedWithInvalidCommandPayloadAndNoMutations() {
    seedArtifact(ArtifactType.SPEC, 3);

    DomainException error =
        catchDomainException(
            () -> technicalApprovalService.acceptImplementation(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    assertThat(error.details())
        .containsEntry("artifactType", "spec")
        .containsEntry("reason", "technical_approval_requires_implementation_artifact");
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowEventWritePort);
    verifyNoInteractions(workflowTransitionService);
    verifyNoInteractions(workflowOrchestrationService);
  }

  @Test
  void versionMismatchRejectsBeforeAnyWrite() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 4); // current artifact version differs
    seedRunnerExecutionContextBundleVersion(2);

    DomainException error =
        catchDomainException(
            () -> technicalApprovalService.acceptImplementation(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.APPROVAL_VERSION_MISMATCH);
    assertThat(error.details())
        .containsEntry("expectedArtifactVersion", 3)
        .containsEntry("currentArtifactVersion", 4)
        .containsEntry("expectedContextBundleVersion", 2)
        .containsEntry("currentContextBundleVersion", 2);
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowEventWritePort);
    verifyNoInteractions(workflowTransitionService);
    verify(artifactService, never()).isApprovalEligible(anyString());
    assertWarnLogContains("APPROVAL_VERSION_MISMATCH");
  }

  @Test
  void unavailableArtifactPayloadRejectsAfterVersionCheck() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(false);

    DomainException error =
        catchDomainException(
            () -> technicalApprovalService.acceptImplementation(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE);
    assertThat(error.details()).containsEntry("reason", "not_approval_eligible");
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowTransitionService);
    assertWarnLogContains("ARTIFACT_PAYLOAD_UNAVAILABLE");
  }

  @Test
  void prOutputWithNoActiveGitHubLinkFailsClosed() {
    seedArtifact(ArtifactType.PR_OUTPUT, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(true);
    when(integrationLinkService.findActiveGitHubPrLink(RUN_ID)).thenReturn(Optional.empty());

    DomainException error =
        catchDomainException(
            () -> technicalApprovalService.acceptImplementation(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH);
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowEventWritePort);
    verifyNoInteractions(workflowTransitionService);
    assertWarnLogContains("ARTIFACT_PR_LINK_MISMATCH");
  }

  @Test
  void illegalTransitionFromTransitionServicePropagatesAfterRowAndEvent() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(true);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
    doThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "Illegal transition Investigating -> Executing",
                Map.of("runId", RUN_ID)))
        .when(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.EXECUTING),
            any(TransitionActor.class),
            anyString(),
            anyString(),
            anyMap());

    DomainException error =
        catchDomainException(
            () -> technicalApprovalService.acceptImplementation(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);
    // The row insert + event append already happened (same outer transaction, rolled back by the
    // caller); the point is the call order + the WARN signal.
    verify(approvalWritePort).insert(any(NewApproval.class));
    verify(workflowEventWritePort).append(any(WorkflowEventRecord.class));
    assertWarnLogContains("ILLEGAL_TRANSITION");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private AcceptImplementationCommand commandWithVersions(int artifactVersion, int contextVersion) {
    return new AcceptImplementationCommand(
        RUN_ID,
        ARTIFACT_ID,
        artifactVersion,
        contextVersion,
        "dev-alex",
        ActorType.HUMAN,
        "idem-accept-1234567890",
        "corr-1",
        "developer",
        null);
  }

  private void seedArtifact(ArtifactType type, int currentVersion) {
    when(artifactRecordPort.findByPublicId(ARTIFACT_ID))
        .thenReturn(
            Optional.of(
                new ArtifactRecordSnapshot(
                    ARTIFACT_ID,
                    RUN_ID,
                    type,
                    currentVersion,
                    null,
                    DataClassification.LOCAL_ONLY,
                    "scratch://impl/" + ARTIFACT_ID,
                    "sha-256",
                    "abc123def456",
                    null,
                    null,
                    ArtifactStatus.AVAILABLE,
                    null,
                    false,
                    FIXED_NOW)));
  }

  private void seedRunnerExecutionContextBundleVersion(int version) {
    when(artifactRecordPort.findRunnerExecutionIdForArtifact(ARTIFACT_ID))
        .thenReturn(Optional.of(RUNNER_EXECUTION_ID));
    when(runnerExecutionRecordPort.findByPublicId(RUNNER_EXECUTION_ID))
        .thenReturn(
            Optional.of(
                new RunnerExecutionSnapshot(
                    RUNNER_EXECUTION_ID,
                    RUN_ID,
                    RunnerStage.EXECUTION,
                    RunnerExecutionStatus.COMPLETED,
                    version,
                    FIXED_NOW,
                    FIXED_NOW.plusMinutes(10),
                    null,
                    FIXED_NOW.plusMinutes(5),
                    FIXED_NOW.minusMinutes(1),
                    null)));
  }

  private IntegrationLink githubPrLink(String externalRef) {
    return new IntegrationLink(
        "ilk_github0001",
        RUN_ID,
        "github_pr",
        externalRef,
        IntegrationSyncStatus.LINKED,
        Instant.parse("2026-06-14T11:00:00Z"),
        null,
        null);
  }

  private ApprovalSnapshot persistedFromNew(org.mockito.invocation.InvocationOnMock invocation) {
    NewApproval newApproval = invocation.getArgument(0);
    return new ApprovalSnapshot(
        newApproval.publicId(),
        newApproval.workflowRunPublicId(),
        newApproval.artifactPublicId(),
        newApproval.artifactVersion(),
        newApproval.contextBundleVersion(),
        newApproval.actorIdentity(),
        newApproval.actorType(),
        newApproval.reviewerRole(),
        newApproval.decision(),
        newApproval.reason(),
        newApproval.rejectionTaxonomy(),
        newApproval.decidedAt());
  }

  private static DomainException catchDomainException(Runnable runnable) {
    Throwable thrown = null;
    try {
      runnable.run();
    } catch (Throwable error) {
      thrown = error;
    }
    assertThat(thrown).isInstanceOf(DomainException.class);
    return (DomainException) thrown;
  }

  private void assertSuccessLogEmitted() {
    boolean found =
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("acceptImplementation success"));
    assertThat(found).as("expected an INFO 'acceptImplementation success' log line").isTrue();
  }

  private void assertWarnLogContains(String token) {
    boolean found =
        appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(token));
    assertThat(found).as("expected a WARN log line containing '" + token + "'").isTrue();
  }
}
