package org.dradgo.application.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.approval.spi.ApprovalWritePort.NewApproval;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
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
 * Story 2.9 unit-level coverage for {@link ApprovalService#approveSpec} with all dependencies
 * mocked. Pins (a)-(g) of AC10 plus the Logback {@link ListAppender} log-level pins required by the
 * cross-cutting logging instrumentation task.
 *
 * <p>Idempotent-replay (AC10d) and idempotency-key conflict (AC10e) are pinned at the {@link
 * org.dradgo.application.workflow.WorkflowCommandService} layer (the surrounding {@code
 * executeIdempotent} pipeline owns reservation/replay) — those scenarios are exercised by the
 * separate Testcontainers contract test {@code WorkflowCommandServiceContractTest} that lives
 * alongside the existing approveSpec coverage.
 */
class ApprovalServiceApproveSpecTest {

  private static final String RUN_ID = "run_approve9999";
  private static final String ARTIFACT_ID = "art_spec9999";
  private static final String RUNNER_EXECUTION_ID = "rex_inv0001";
  private static final OffsetDateTime FIXED_NOW =
      OffsetDateTime.of(2026, 5, 23, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);

  private ArtifactRecordPort artifactRecordPort;
  private ArtifactService artifactService;
  private ApprovalWritePort approvalWritePort;
  private WorkflowEventWritePort workflowEventWritePort;
  private WorkflowTransitionService workflowTransitionService;
  private RunnerExecutionRecordPort runnerExecutionRecordPort;
  private org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort
      workflowRunRejectionLoopPort;
  private org.dradgo.application.workflow.SpecRejectionEscalationThresholdProvider
      escalationThresholdProvider;

  private ApprovalService approvalService;

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    artifactRecordPort = Mockito.mock(ArtifactRecordPort.class);
    artifactService = Mockito.mock(ArtifactService.class);
    approvalWritePort = Mockito.mock(ApprovalWritePort.class);
    workflowEventWritePort = Mockito.mock(WorkflowEventWritePort.class);
    workflowTransitionService = Mockito.mock(WorkflowTransitionService.class);
    runnerExecutionRecordPort = Mockito.mock(RunnerExecutionRecordPort.class);
    workflowRunRejectionLoopPort =
        Mockito.mock(org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort.class);
    escalationThresholdProvider =
        new org.dradgo.application.workflow.SpecRejectionEscalationThresholdProvider(3);
    approvalService =
        new ApprovalService(
            artifactRecordPort,
            artifactService,
            approvalWritePort,
            workflowEventWritePort,
            workflowTransitionService,
            runnerExecutionRecordPort,
            workflowRunRejectionLoopPort,
            escalationThresholdProvider,
            FIXED_CLOCK);

    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ApprovalService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(ApprovalService.class)).detachAppender(appender);
  }

  @Test
  void happyPathInsertsApprovalAppendsEventAndTransitionsToExecuting() {
    // (a) — happy path
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(true);
    when(approvalWritePort.insert(any())).thenAnswer(invocation -> persistedFromNew(invocation));

    ApprovalResult result = approvalService.approveSpec(commandWithVersions(3, 2));

    assertThat(result.workflowRunId()).isEqualTo(RUN_ID);
    assertThat(result.artifactId()).isEqualTo(ARTIFACT_ID);
    assertThat(result.artifactVersion()).isEqualTo(3);
    assertThat(result.contextBundleVersion()).isEqualTo(2);
    assertThat(result.reviewerRole()).isEqualTo("product_reviewer");
    assertThat(result.resultingState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(result.correlationId()).isEqualTo("corr-1");
    assertThat(result.approvalId()).startsWith("apr_");

    ArgumentCaptor<NewApproval> newApprovalCaptor = ArgumentCaptor.forClass(NewApproval.class);
    verify(approvalWritePort).insert(newApprovalCaptor.capture());
    NewApproval newApproval = newApprovalCaptor.getValue();
    assertThat(newApproval.publicId()).startsWith("apr_");
    assertThat(newApproval.decision()).isEqualTo(ApprovalSnapshot.DECISION_APPROVED);
    assertThat(newApproval.rejectionTaxonomy()).isNull();
    assertThat(newApproval.actorType()).isEqualTo(ActorType.HUMAN);
    assertThat(newApproval.reviewerRole()).isEqualTo("product_reviewer");
    assertThat(newApproval.decidedAt()).isEqualTo(FIXED_NOW);

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(eventCaptor.capture());
    WorkflowEventRecord approvalEvent = eventCaptor.getValue();
    assertThat(approvalEvent.eventType()).isEqualTo(WorkflowEventType.APPROVAL_APPROVED);
    assertThat(approvalEvent.workflowRunPublicId()).isEqualTo(RUN_ID);
    // (g) attribution end-to-end — reviewerRole on the event matches the persisted reviewerRole.
    assertThat(approvalEvent.details().get("reviewerRole")).isEqualTo("product_reviewer");
    assertThat(approvalEvent.details().get("approvalId")).isEqualTo(newApproval.publicId());
    assertThat(approvalEvent.details().get("artifactVersion")).isEqualTo(3);
    assertThat(approvalEvent.details().get("contextBundleVersion")).isEqualTo(2);
    // Review batch 1 P-correlationId-pin: AC6 says details = { …, correlationId? }; the
    // production code emits the key only when non-null. Pin its presence so a future change that
    // drops the conditional branch cannot slip through.
    assertThat(approvalEvent.details().get("correlationId")).isEqualTo("corr-1");
    assertThat(approvalEvent.details().get("idempotencyKey")).isEqualTo("idem-approve-1234567890");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> transitionDetails = ArgumentCaptor.forClass(Map.class);
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.EXECUTING),
            any(TransitionActor.class),
            eq("approve specification"),
            eq("idem-approve-1234567890"),
            transitionDetails.capture());
    assertThat(transitionDetails.getValue())
        .containsEntry("approvalId", newApproval.publicId())
        .containsEntry("reviewerRole", "product_reviewer")
        .containsEntry("artifactId", ARTIFACT_ID)
        .containsEntry("artifactVersion", 3)
        .containsEntry("contextBundleVersion", 2);

    assertSuccessLogEmitted();
  }

  @Test
  void versionMismatchRejectsBeforeAnyWrite() {
    // (b) — version-mismatch (artifact version differs)
    seedArtifact(4); // current
    seedRunnerExecutionContextBundleVersion(2);

    DomainException error =
        catchDomainException(() -> approvalService.approveSpec(commandWithVersions(3, 2)));

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
  void versionMismatchRejectsBeforeEligibilityCheckEvenWhenContextBundleDiffers() {
    // (b) — context-bundle version differs while artifact version matches; trap T3 ordering.
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(3);

    DomainException error =
        catchDomainException(() -> approvalService.approveSpec(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.APPROVAL_VERSION_MISMATCH);
    assertThat(error.details())
        .containsEntry("expectedContextBundleVersion", 2)
        .containsEntry("currentContextBundleVersion", 3);
    verify(artifactService, never()).isApprovalEligible(anyString());
  }

  @Test
  void unavailableArtifactPayloadRejectsAfterVersionCheck() {
    // (c) — eligibility check fails after version-binding passes
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(false);

    DomainException error =
        catchDomainException(() -> approvalService.approveSpec(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE);
    assertThat(error.details())
        .containsEntry("artifactId", ARTIFACT_ID)
        .containsEntry("reason", "not_approval_eligible");
    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowEventWritePort);
    verifyNoInteractions(workflowTransitionService);

    assertWarnLogContains("ARTIFACT_PAYLOAD_UNAVAILABLE");
  }

  @Test
  void illegalTransitionFromTransitionServicePropagatesAndIsLoggedAsWarn() {
    // (f) — illegal-state-transition. The transition service throws after the row+event landed in
    // the same transaction; ApprovalService re-raises, the outer @Transactional rolls back.
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(true);
    when(approvalWritePort.insert(any())).thenAnswer(invocation -> persistedFromNew(invocation));
    doThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "Illegal transition Inbox -> Executing",
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
        catchDomainException(() -> approvalService.approveSpec(commandWithVersions(3, 2)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);
    // The row insert + event append already happened (they're inside the same outer transaction
    // that the test does not actually commit). The point of this test is to assert the call order
    // and the WARN signal — rollback is pinned by the persistence-level contract test.
    verify(approvalWritePort).insert(any(NewApproval.class));
    verify(workflowEventWritePort).append(any(WorkflowEventRecord.class));
    assertWarnLogContains("ILLEGAL_TRANSITION");
  }

  @Test
  void bootstrapBundleVersionIsOneWhenArtifactHasNoRunnerExecution() {
    // (OQ-2) — bootstrap path: no runner_execution row -> current bundle version = 1.
    seedArtifact(1);
    when(artifactRecordPort.findRunnerExecutionIdForArtifact(ARTIFACT_ID))
        .thenReturn(Optional.empty());
    when(artifactService.isApprovalEligible(ARTIFACT_ID)).thenReturn(true);
    when(approvalWritePort.insert(any())).thenAnswer(invocation -> persistedFromNew(invocation));

    ApprovalResult result = approvalService.approveSpec(commandWithVersions(1, 1));

    assertThat(result.contextBundleVersion()).isEqualTo(1);
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.EXECUTING),
            any(TransitionActor.class),
            anyString(),
            anyString(),
            anyMap());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ApproveSpecCommand commandWithVersions(int artifactVersion, int contextVersion) {
    return new ApproveSpecCommand(
        RUN_ID,
        ARTIFACT_ID,
        artifactVersion,
        contextVersion,
        "alex",
        ActorType.HUMAN,
        "idem-approve-1234567890",
        "corr-1",
        "product_reviewer",
        null);
  }

  private void seedArtifact(int currentVersion) {
    when(artifactRecordPort.findByPublicId(ARTIFACT_ID))
        .thenReturn(
            Optional.of(
                new ArtifactRecordSnapshot(
                    ARTIFACT_ID,
                    RUN_ID,
                    ArtifactType.SPEC,
                    currentVersion,
                    null,
                    DataClassification.LOCAL_ONLY,
                    "scratch://spec/" + ARTIFACT_ID,
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
                    RunnerStage.INVESTIGATION,
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
                        && e.getFormattedMessage().contains("approveSpec success"));
    assertThat(found).as("expected an INFO 'approveSpec success' log line").isTrue();
  }

  private void assertWarnLogContains(String token) {
    boolean found =
        appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(token));
    assertThat(found).as("expected a WARN log line containing '" + token + "'").isTrue();
  }
}
