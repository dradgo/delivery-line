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
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.SpecRejectionEscalationThresholdProvider;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
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
 * Story 2.10 unit-level coverage for {@link ApprovalService#rejectSpec} with all dependencies
 * mocked. Pins AC9 cases (a), (c), (d), (e), (f), (j), (k) plus the Logback log-level pins required
 * by the cross-cutting logging instrumentation task.
 *
 * <p>Idempotent-replay (AC9h) and idempotency-key conflict (AC9i) are pinned at the {@code
 * WorkflowCommandService} layer because the surrounding {@code executeIdempotent} pipeline owns
 * reservation/replay behavior.
 */
class ApprovalServiceRejectSpecTest {

  private static final String RUN_ID = "run_reject9999";
  private static final String ARTIFACT_ID = "art_spec9999";
  private static final String RUNNER_EXECUTION_ID = "rex_inv0001";
  private static final OffsetDateTime FIXED_NOW =
      OffsetDateTime.of(2026, 5, 24, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);

  private ArtifactRecordPort artifactRecordPort;
  private ArtifactService artifactService;
  private ApprovalWritePort approvalWritePort;
  private WorkflowEventWritePort workflowEventWritePort;
  private WorkflowTransitionService workflowTransitionService;
  private RunnerExecutionRecordPort runnerExecutionRecordPort;
  private WorkflowRunRejectionLoopPort workflowRunRejectionLoopPort;
  private SpecRejectionEscalationThresholdProvider escalationThresholdProvider;

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
    workflowRunRejectionLoopPort = Mockito.mock(WorkflowRunRejectionLoopPort.class);
    escalationThresholdProvider = new SpecRejectionEscalationThresholdProvider(3);
    approvalService =
        new ApprovalService(
            artifactRecordPort,
            artifactService,
            approvalWritePort,
            workflowEventWritePort,
            workflowTransitionService,
            new ApprovalVersionBinder(artifactRecordPort, runnerExecutionRecordPort),
            workflowRunRejectionLoopPort,
            escalationThresholdProvider,
            org.mockito.Mockito.mock(
                org.dradgo.application.workflow.WorkflowOrchestrationService.class),
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
  void happyPathInsertsRejectionAppendsEventIncrementsCounterAndTransitionsToInvestigating() {
    // AC9(a) — happy path: row written, approval.rejected event appended, counter 0->1,
    // WaitingForSpecApproval -> Investigating. Threshold=3 so no escalation event.
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
    when(workflowRunRejectionLoopPort.incrementAndReadLoopCount(RUN_ID)).thenReturn(1);

    ApprovalResult result =
        approvalService.rejectSpec(commandWithVersions(3, 2, RejectionTaxonomy.MISSING_SCOPE));

    assertThat(result.resultingState()).isEqualTo(WorkflowState.INVESTIGATING);
    assertThat(result.correlationId()).isEqualTo("corr-r-1");

    ArgumentCaptor<NewApproval> approvalCaptor = ArgumentCaptor.forClass(NewApproval.class);
    verify(approvalWritePort).insert(approvalCaptor.capture());
    NewApproval newApproval = approvalCaptor.getValue();
    assertThat(newApproval.decision()).isEqualTo(ApprovalSnapshot.DECISION_REJECTED);
    assertThat(newApproval.rejectionTaxonomy()).isEqualTo("missing_scope");
    assertThat(newApproval.reason()).isEqualTo("the reason");
    assertThat(newApproval.reviewerRole()).isEqualTo("product_reviewer");

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort, times(1)).append(eventCaptor.capture());
    WorkflowEventRecord rejected = eventCaptor.getValue();
    assertThat(rejected.eventType()).isEqualTo(WorkflowEventType.APPROVAL_REJECTED);
    assertThat(rejected.details())
        .containsEntry("taggedFeedback", "missing_scope")
        .containsEntry("specRejectionLoopCount", 1)
        .containsEntry("reviewerRole", "product_reviewer")
        .containsEntry("idempotencyKey", "idem-reject-1234567890")
        .containsEntry("correlationId", "corr-r-1");

    // Counter incremented exactly once.
    verify(workflowRunRejectionLoopPort).incrementAndReadLoopCount(RUN_ID);
    // Threshold not crossed at loopCount=1 < 3, so isEscalationMarkerSet not queried and the
    // marker UPDATE is not invoked.
    verify(workflowRunRejectionLoopPort, never()).isEscalationMarkerSet(anyString());
    verify(workflowRunRejectionLoopPort, never()).markEscalationOnce(anyString());

    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.INVESTIGATING),
            any(TransitionActor.class),
            eq("reject specification"),
            eq("idem-reject-1234567890"),
            anyMap());

    assertInfoLogContains("rejectSpec success");
  }

  @Test
  void versionMismatchRejectsBeforeAnyWrite() {
    // AC9(c) — version-binding precedes any write; trap T3 ordering (mirror approveSpec).
    seedArtifact(4); // current artifact version differs from command's expected 3
    seedRunnerExecutionContextBundleVersion(2);

    DomainException error =
        catchDomainException(
            () ->
                approvalService.rejectSpec(
                    commandWithVersions(3, 2, RejectionTaxonomy.MISSING_SCOPE)));

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
  void thresholdNotExceededAppendsRejectionEventWithoutEscalationEvent() {
    // AC9(d) — counter 2->3 when threshold=4: no escalation event, marker stays false.
    SpecRejectionEscalationThresholdProvider provider =
        new SpecRejectionEscalationThresholdProvider(4);
    approvalService =
        new ApprovalService(
            artifactRecordPort,
            artifactService,
            approvalWritePort,
            workflowEventWritePort,
            workflowTransitionService,
            new ApprovalVersionBinder(artifactRecordPort, runnerExecutionRecordPort),
            workflowRunRejectionLoopPort,
            provider,
            org.mockito.Mockito.mock(
                org.dradgo.application.workflow.WorkflowOrchestrationService.class),
            FIXED_CLOCK);

    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
    when(workflowRunRejectionLoopPort.incrementAndReadLoopCount(RUN_ID)).thenReturn(3);

    approvalService.rejectSpec(commandWithVersions(3, 2, RejectionTaxonomy.UNCLEAR_SPECIFICATION));

    verify(workflowRunRejectionLoopPort, never()).isEscalationMarkerSet(anyString());
    verify(workflowRunRejectionLoopPort, never()).markEscalationOnce(anyString());
    verify(workflowEventWritePort, times(1)).append(any());
  }

  @Test
  void thresholdExceededFirstTimeAppendsEscalationEventAndFlipsMarker() {
    // AC9(e) — counter 2->3 when threshold=3: escalation.required appended once, marker
    // false->true.
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
    when(workflowRunRejectionLoopPort.incrementAndReadLoopCount(RUN_ID)).thenReturn(3);
    when(workflowRunRejectionLoopPort.isEscalationMarkerSet(RUN_ID)).thenReturn(false);
    when(workflowRunRejectionLoopPort.markEscalationOnce(RUN_ID)).thenReturn(1);

    approvalService.rejectSpec(
        commandWithVersions(3, 2, RejectionTaxonomy.MISUNDERSTOOD_IMPLEMENTATION));

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort, times(2)).append(eventCaptor.capture());
    List<WorkflowEventRecord> events = eventCaptor.getAllValues();
    assertThat(events.get(0).eventType()).isEqualTo(WorkflowEventType.APPROVAL_REJECTED);
    assertThat(events.get(1).eventType()).isEqualTo(WorkflowEventType.ESCALATION_REQUIRED);
    assertThat(events.get(1).details())
        .containsEntry("reason", "spec_rejection_loop_threshold_exceeded")
        .containsEntry("specRejectionLoopCount", 3)
        .containsEntry("threshold", 3);

    verify(workflowRunRejectionLoopPort).markEscalationOnce(RUN_ID);
    assertWarnLogContains("escalation marker raised");
  }

  @Test
  void thresholdAlreadyExceededDoesNotAppendDuplicateEscalationEvent() {
    // AC9(f) — counter 3->4 when threshold=3 and marker already true: no duplicate event.
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
    when(workflowRunRejectionLoopPort.incrementAndReadLoopCount(RUN_ID)).thenReturn(4);
    when(workflowRunRejectionLoopPort.isEscalationMarkerSet(RUN_ID)).thenReturn(true);

    approvalService.rejectSpec(commandWithVersions(3, 2, RejectionTaxonomy.MISSING_SCOPE));

    // Only the approval.rejected event is appended — no escalation.required.
    verify(workflowEventWritePort, times(1)).append(any());
    verify(workflowRunRejectionLoopPort, never()).markEscalationOnce(anyString());
  }

  @Test
  void illegalTransitionPropagatesAndRollsBackTransactionally() {
    // AC9(j) — surfaces ILLEGAL_TRANSITION; rollback shape verified by the Spring-slice contract
    // test. Here we pin the WARN log emission required by the cross-cutting logging task.
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);
    when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
    when(workflowRunRejectionLoopPort.incrementAndReadLoopCount(RUN_ID)).thenReturn(1);
    doThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "WaitingForSpecApproval cannot transition to Investigating",
                Map.of()))
        .when(workflowTransitionService)
        .transition(
            anyString(), any(WorkflowState.class), any(), anyString(), anyString(), anyMap());

    DomainException error =
        catchDomainException(
            () ->
                approvalService.rejectSpec(
                    commandWithVersions(3, 2, RejectionTaxonomy.MISSING_SCOPE)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);
    assertWarnLogContains("rejectSpec rejected ILLEGAL_TRANSITION");
  }

  @Test
  void taggedFeedbackPersistedAsRegistryWireValue() {
    // AC9(a) defense-in-depth: every PRODUCT taxonomy value persists through to the
    // rejectionTaxonomy column as the registry wire value (matches the CHECK constraint substring).
    // Role-scoping (D4): developer-subset values are rejected on the spec path — asserted by
    // developerTaxonomyRejectedOnSpecPath (code-review 2026-06-14).
    for (RejectionTaxonomy taxonomy : RejectionTaxonomy.values()) {
      if (!taxonomy.isProductValue()) {
        continue;
      }
      Mockito.reset(approvalWritePort, workflowEventWritePort, workflowTransitionService);
      Mockito.reset(workflowRunRejectionLoopPort);
      seedArtifact(3);
      seedRunnerExecutionContextBundleVersion(2);
      when(approvalWritePort.insert(any())).thenAnswer(this::persistedFromNew);
      when(workflowRunRejectionLoopPort.incrementAndReadLoopCount(RUN_ID)).thenReturn(1);

      approvalService.rejectSpec(commandWithVersions(3, 2, taxonomy));

      ArgumentCaptor<NewApproval> captor = ArgumentCaptor.forClass(NewApproval.class);
      verify(approvalWritePort).insert(captor.capture());
      assertThat(captor.getValue().rejectionTaxonomy()).isEqualTo(taxonomy.value());
    }
  }

  @Test
  void developerTaxonomyRejectedOnSpecPath() {
    // Role-scoping (D4): the V13 migration widened the shared rejection-taxonomy CHECK to admit the
    // developer values, so the DB no longer blocks a developer value on a spec rejection — the
    // service guard must, before any write (code-review finding 2026-06-14).
    seedArtifact(3);
    seedRunnerExecutionContextBundleVersion(2);

    DomainException error =
        catchDomainException(
            () ->
                approvalService.rejectSpec(
                    commandWithVersions(3, 2, RejectionTaxonomy.INCORRECT_APPROACH)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    assertThat(error.details())
        .containsEntry("reason", "spec_rejection_requires_product_taxonomy")
        .containsEntry("taggedFeedback", "incorrect_approach");

    verifyNoInteractions(approvalWritePort);
    verifyNoInteractions(workflowEventWritePort);
    verifyNoInteractions(workflowTransitionService);
    verifyNoInteractions(workflowRunRejectionLoopPort);

    assertWarnLogContains("spec_rejection_requires_product_taxonomy");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private RejectSpecCommand commandWithVersions(
      int artifactVersion, int contextVersion, RejectionTaxonomy taggedFeedback) {
    return new RejectSpecCommand(
        RUN_ID,
        ARTIFACT_ID,
        artifactVersion,
        contextVersion,
        "alex",
        ActorType.HUMAN,
        "idem-reject-1234567890",
        "corr-r-1",
        "product_reviewer",
        taggedFeedback,
        "the reason");
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

  private void assertInfoLogContains(String token) {
    boolean found =
        appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains(token));
    assertThat(found).as("expected an INFO log line containing '" + token + "'").isTrue();
  }

  private void assertWarnLogContains(String token) {
    boolean found =
        appender.list.stream()
            .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(token));
    assertThat(found).as("expected a WARN log line containing '" + token + "'").isTrue();
  }
}
