package org.dradgo.application.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.approval.spi.ApprovalWritePort.NewApproval;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.ImplementationRejectionEscalationThresholdProvider;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RejectionTaxonomy;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Story 3.21 unit-level coverage for {@link TechnicalApprovalService#rejectImplementation} with all
 * dependencies mocked (the technical-rejection twin of {@code
 * TechnicalApprovalServiceAcceptImplementationTest} / {@code ApprovalServiceRejectSpecTest}). Pins
 * AC10 cases plus the Logback {@link ListAppender} log-level pins required by the cross-cutting
 * logging task: the success line, the escalation WARN, and the WARN rejection branches.
 *
 * <p>Idempotent-replay (AC10) and idempotency conflict are pinned at the {@code
 * WorkflowCommandService} layer + end-to-end by the contract IT.
 */
class TechnicalApprovalServiceRejectImplementationTest {

  private static final String RUN_ID = "run_reject9999";
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
  private WorkflowRunRejectionLoopPort workflowRunRejectionLoopPort;

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
    workflowRunRejectionLoopPort = Mockito.mock(WorkflowRunRejectionLoopPort.class);
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
            workflowRunRejectionLoopPort,
            new ImplementationRejectionEscalationThresholdProvider(3),
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
  void happyPathImplementationPlanTransitionsToExecutingAndRedispatchesPlanGeneration() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(workflowRunRejectionLoopPort.incrementAndReadImplementationLoopCount(RUN_ID))
        .thenReturn(1);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);

    ApprovalResult result =
        technicalApprovalService.rejectImplementation(
            command(3, 2, RejectionTaxonomy.INCORRECT_APPROACH));

    assertThat(result.resultingState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(result.reviewerRole()).isEqualTo("developer");
    assertThat(result.approvalId()).startsWith("apr_");

    ArgumentCaptor<NewApproval> newApprovalCaptor = ArgumentCaptor.forClass(NewApproval.class);
    verify(approvalWritePort).insert(newApprovalCaptor.capture());
    NewApproval newApproval = newApprovalCaptor.getValue();
    assertThat(newApproval.decision()).isEqualTo(ApprovalSnapshot.DECISION_REJECTED);
    assertThat(newApproval.rejectionTaxonomy()).isEqualTo("incorrect_approach");
    assertThat(newApproval.reviewerRole()).isEqualTo("developer");
    assertThat(newApproval.reason()).isEqualTo("Approach is wrong");

    // AR34a: the approval.rejected event carries reviewerRole=developer + taggedFeedback.
    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(eventCaptor.capture());
    WorkflowEventRecord event = eventCaptor.getValue();
    assertThat(event.eventType()).isEqualTo(WorkflowEventType.APPROVAL_REJECTED);
    assertThat(event.details().get("reviewerRole")).isEqualTo("developer");
    assertThat(event.details().get("taggedFeedback")).isEqualTo("incorrect_approach");
    assertThat(event.details().get("implementationRejectionLoopCount")).isEqualTo(1);

    verify(workflowRunRejectionLoopPort).incrementAndReadImplementationLoopCount(RUN_ID);
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.EXECUTING),
            any(TransitionActor.class),
            eq("reject implementation"),
            eq("idem-reject-1234567890"),
            anyMap());
    verify(workflowOrchestrationService).retryPlanGeneration(RUN_ID, "corr-1");
    verify(workflowOrchestrationService, never()).retryImplementation(anyString(), anyString());
    assertSuccessLogEmitted();
  }

  @Test
  void happyPathPrOutputTransitionsToExecutingAndRedispatchesImplementation() {
    seedArtifact(ArtifactType.PR_OUTPUT, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(workflowRunRejectionLoopPort.incrementAndReadImplementationLoopCount(RUN_ID))
        .thenReturn(1);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);

    ApprovalResult result =
        technicalApprovalService.rejectImplementation(
            command(3, 2, RejectionTaxonomy.BREAKS_EXISTING_FUNCTIONALITY));

    assertThat(result.resultingState()).isEqualTo(WorkflowState.EXECUTING);
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.EXECUTING),
            any(TransitionActor.class),
            eq("reject implementation"),
            eq("idem-reject-1234567890"),
            anyMap());
    verify(workflowOrchestrationService).retryImplementation(RUN_ID, "corr-1");
    verify(workflowOrchestrationService, never()).retryPlanGeneration(anyString(), anyString());
    // Rejection never consults eligibility or the PR-link gate (Decision D3/OQ-1).
    verifyNoInteractions(artifactService);
    verifyNoInteractions(integrationLinkService);
    assertSuccessLogEmitted();
  }

  @Test
  void specArtifactIsRejectedWithInvalidCommandPayloadAndNoMutations() {
    seedArtifact(ArtifactType.SPEC, 3);

    DomainException error =
        catchDomainException(
            () ->
                technicalApprovalService.rejectImplementation(
                    command(3, 2, RejectionTaxonomy.INCORRECT_APPROACH)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    assertThat(error.details())
        .containsEntry("artifactType", "spec")
        .containsEntry("reason", "technical_rejection_requires_implementation_artifact");
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowEventWritePort);
    verifyNoInteractions(workflowTransitionService);
    verifyNoInteractions(workflowOrchestrationService);
    verifyNoInteractions(workflowRunRejectionLoopPort);
  }

  @Test
  void versionMismatchRejectsBeforeAnyWrite() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 4); // current artifact version differs
    seedRunnerExecutionContextBundleVersion(2);

    DomainException error =
        catchDomainException(
            () ->
                technicalApprovalService.rejectImplementation(
                    command(3, 2, RejectionTaxonomy.INCORRECT_APPROACH)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.APPROVAL_VERSION_MISMATCH);
    assertThat(error.details())
        .containsEntry("expectedArtifactVersion", 3)
        .containsEntry("currentArtifactVersion", 4)
        .containsEntry("expectedContextBundleVersion", 2)
        .containsEntry("currentContextBundleVersion", 2);
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowEventWritePort);
    verifyNoInteractions(workflowTransitionService);
    verifyNoInteractions(workflowRunRejectionLoopPort);
    assertWarnLogContains("APPROVAL_VERSION_MISMATCH");
  }

  @Test
  void missingTaxonomyRejectsAfterVersionCheckWithNoWrite() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);

    DomainException error =
        catchDomainException(
            () -> technicalApprovalService.rejectImplementation(command(3, 2, null)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.MISSING_REJECTION_TAXONOMY);
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowTransitionService);
    verifyNoInteractions(workflowRunRejectionLoopPort);
    assertWarnLogContains("MISSING_REJECTION_TAXONOMY");
  }

  @Test
  void developerRoleWithProductTaxonomyIsRejectedAsInvalidPayload() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);

    DomainException error =
        catchDomainException(
            () ->
                technicalApprovalService.rejectImplementation(
                    command(3, 2, RejectionTaxonomy.MISSING_SCOPE)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    assertThat(error.details())
        .containsEntry("reason", "developer_role_requires_developer_taxonomy")
        .containsEntry("taggedFeedback", "missing_scope");
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowTransitionService);
  }

  @Test
  void thresholdNotExceededDoesNotEmitEscalationEvent() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(workflowRunRejectionLoopPort.incrementAndReadImplementationLoopCount(RUN_ID))
        .thenReturn(2);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);

    technicalApprovalService.rejectImplementation(command(3, 2, RejectionTaxonomy.QUALITY_ISSUE));

    // Only the approval.rejected event — no escalation.required (loopCount 2 < threshold 3).
    verify(workflowEventWritePort, times(1)).append(any(WorkflowEventRecord.class));
    verify(workflowRunRejectionLoopPort, never()).markEscalationOnce(anyString());
  }

  @Test
  void thresholdExceededEmitsEscalationEventOnce() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(workflowRunRejectionLoopPort.incrementAndReadImplementationLoopCount(RUN_ID))
        .thenReturn(3);
    when(workflowRunRejectionLoopPort.isEscalationMarkerSet(RUN_ID)).thenReturn(false);
    when(workflowRunRejectionLoopPort.markEscalationOnce(RUN_ID)).thenReturn(1);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);

    technicalApprovalService.rejectImplementation(command(3, 2, RejectionTaxonomy.OUT_OF_SCOPE));

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort, times(2)).append(eventCaptor.capture());
    List<WorkflowEventRecord> events = eventCaptor.getAllValues();
    assertThat(events)
        .extracting(WorkflowEventRecord::eventType)
        .containsExactly(
            WorkflowEventType.APPROVAL_REJECTED, WorkflowEventType.ESCALATION_REQUIRED);
    WorkflowEventRecord escalation = events.get(1);
    assertThat(escalation.details())
        .containsEntry("reason", "implementation_rejection_loop_threshold_exceeded")
        .containsEntry("threshold", 3)
        .containsEntry("implementationRejectionLoopCount", 3);
    verify(workflowRunRejectionLoopPort).markEscalationOnce(RUN_ID);
    assertWarnLogContains("escalation marker raised");
  }

  @Test
  void escalationIdempotentWhenMarkerAlreadySet() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(workflowRunRejectionLoopPort.incrementAndReadImplementationLoopCount(RUN_ID))
        .thenReturn(4);
    when(workflowRunRejectionLoopPort.isEscalationMarkerSet(RUN_ID)).thenReturn(true);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);

    technicalApprovalService.rejectImplementation(command(3, 2, RejectionTaxonomy.QUALITY_ISSUE));

    // Marker already set: counter still advanced, but no second escalation event + no flip attempt.
    verify(workflowEventWritePort, times(1)).append(any(WorkflowEventRecord.class));
    verify(workflowRunRejectionLoopPort, never()).markEscalationOnce(anyString());
  }

  @Test
  void illegalTransitionFromTransitionServicePropagatesAfterRowAndEvents() {
    seedArtifact(ArtifactType.IMPLEMENTATION_PLAN, 3);
    seedRunnerExecutionContextBundleVersion(2);
    when(workflowRunRejectionLoopPort.incrementAndReadImplementationLoopCount(RUN_ID))
        .thenReturn(1);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
    doThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "Illegal transition WaitingForReview -> Executing",
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
            () ->
                technicalApprovalService.rejectImplementation(
                    command(3, 2, RejectionTaxonomy.INCORRECT_APPROACH)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);
    verify(approvalWritePort).insert(any(NewApproval.class));
    verify(workflowEventWritePort).append(any(WorkflowEventRecord.class));
    verify(workflowOrchestrationService, never()).retryPlanGeneration(anyString(), anyString());
    assertWarnLogContains("ILLEGAL_TRANSITION");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private RejectImplementationCommand command(
      int artifactVersion, int contextVersion, RejectionTaxonomy taggedFeedback) {
    return new RejectImplementationCommand(
        RUN_ID,
        ARTIFACT_ID,
        artifactVersion,
        contextVersion,
        "dev-alex",
        ActorType.HUMAN,
        "idem-reject-1234567890",
        "corr-1",
        "developer",
        taggedFeedback,
        "Approach is wrong");
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
                        && e.getFormattedMessage().contains("rejectImplementation success"));
    assertThat(found).as("expected an INFO 'rejectImplementation success' log line").isTrue();
  }

  private void assertWarnLogContains(String token) {
    boolean found =
        appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(token));
    assertThat(found).as("expected a WARN log line containing '" + token + "'").isTrue();
  }
}
