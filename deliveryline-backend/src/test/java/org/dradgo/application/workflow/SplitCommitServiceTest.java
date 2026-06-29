package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.ticketsource.TicketSourceSubticketOutcome;
import org.dradgo.application.integration.ticketsource.TicketSourceSubticketService;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.workflow.RunDependencyService.GatedDispatchOutcome;
import org.dradgo.application.workflow.SplitCommitResult.SubtaskCommitOutcome;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Story 3f-5 — unit coverage for {@link SplitCommitService}: best-effort two-pass fan-out, the R4
 * dependency-edge direction pin, the zero-child abort (R7), per-subtask failure isolation (R1), and
 * the CAS-guarded parent decomposition (R6).
 */
class SplitCommitServiceTest {

  private static final String RUN = "run_parent0001";
  private static final String PROPOSAL = "splprop_0001";
  private static final String PROJECT = "prj_default";
  private static final String KEY = "idem-split-approve-0001";
  private static final String PARENT_TICKET = "LIN-100";

  private final WorkflowRunReadPort workflowRunReadPort = mock(WorkflowRunReadPort.class);
  private final ProjectRuntimeConfigResolver projectResolver =
      mock(ProjectRuntimeConfigResolver.class);
  private final SplitProposalReadPort splitProposalReadPort = mock(SplitProposalReadPort.class);
  private final SplitProposalWritePort splitProposalWritePort = mock(SplitProposalWritePort.class);
  private final TicketSourceSubticketService ticketSourceSubticketService =
      mock(TicketSourceSubticketService.class);
  private final IntegrationLinkService integrationLinkService = mock(IntegrationLinkService.class);
  private final WorkflowRunCreatePort workflowRunCreatePort = mock(WorkflowRunCreatePort.class);
  private final RunDependencyService runDependencyService = mock(RunDependencyService.class);
  private final WorkflowTransitionService workflowTransitionService =
      mock(WorkflowTransitionService.class);
  private final WorkflowEventWritePort workflowEventWritePort = mock(WorkflowEventWritePort.class);
  private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
  private final IdempotencyKeyValidator idempotencyKeyValidator =
      mock(IdempotencyKeyValidator.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);

  private SplitCommitService service;
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @AfterEach
  void detachAppender() {
    if (logger != null && appender != null) {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @BeforeEach
  void setUp() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(SplitCommitService.class);
    logger.addAppender(appender);
    // Make the REQUIRES_NEW TransactionTemplate run its callbacks against a no-op tx status.
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
    lenient()
        .when(idempotencyKeyValidator.requireValid(anyString()))
        .thenAnswer(i -> i.getArgument(0));
    lenient()
        .when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    Project project = mock(Project.class);
    lenient().when(project.publicId()).thenReturn(PROJECT);
    lenient().when(projectResolver.resolveForRun(RUN)).thenReturn(project);
    IntegrationLink parentLink = mock(IntegrationLink.class);
    lenient().when(parentLink.externalRef()).thenReturn(PARENT_TICKET);
    lenient()
        .when(integrationLinkService.findActiveLinkByWorkflowRun(RUN))
        .thenReturn(Optional.of(parentLink));
    lenient()
        .when(workflowRunCreatePort.create(anyString(), any(), anyString(), anyString()))
        .thenReturn(null);
    lenient()
        .when(runDependencyService.dispatchWhenUnblocked(anyString(), any()))
        .thenReturn(GatedDispatchOutcome.DISPATCHED);
    lenient().when(splitProposalWritePort.approveOpenForRun(RUN)).thenReturn(1);
    // The per-proposal dedup key (P7) is derived from the latest proposal id; default to an open
    // proposal so first-attempt paths proceed (per-test findOpenForRun supplies the real subtasks).
    lenient()
        .when(splitProposalReadPort.findLatestForRun(RUN))
        .thenReturn(Optional.of(proposal(List.of(), List.of())));

    service =
        new SplitCommitService(
            workflowRunReadPort,
            projectResolver,
            splitProposalReadPort,
            splitProposalWritePort,
            ticketSourceSubticketService,
            integrationLinkService,
            workflowRunCreatePort,
            runDependencyService,
            workflowTransitionService,
            workflowEventWritePort,
            idempotencyService,
            idempotencyKeyValidator,
            transactionManager);
  }

  private void stubGateRun(WorkflowState state) {
    when(workflowRunReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, state, null, 1L, 0, false, PROJECT, null)));
  }

  private SplitProposalView proposal(
      List<SplitSubtaskView> subtasks, List<SplitDependencyView> deps) {
    return new SplitProposalView(
        PROPOSAL,
        RUN,
        SplitProposalView.STATUS_OPEN,
        0,
        "art_1",
        1,
        "claude",
        "claude",
        true,
        subtasks,
        deps,
        null);
  }

  private ApproveSplitCommand command() {
    return new ApproveSplitCommand(RUN, "alice", ActorType.HUMAN, KEY, "corr-1");
  }

  private TicketSourceSubticketOutcome createdOutcome(String childRef) {
    return TicketSourceSubticketOutcome.created(
        new CreateSubticketResult(TicketRef.of(childRef), "k", "fp", false, Map.of()));
  }

  @Test
  void mixedOutcomesCreatesChildrenLinksAndDecomposesParent() {
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(
            Optional.of(
                proposal(
                    List.of(
                        new SplitSubtaskView(1, "A", "scope A"),
                        new SplitSubtaskView(2, "B", "scope B")),
                    List.of())));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenReturn(createdOutcome("LIN-100-1"))
        .thenReturn(TicketSourceSubticketOutcome.internalOnlySkipped());

    SplitCommitResult result = service.commit(command());

    assertThat(result.parentDecomposed()).isTrue();
    assertThat(result.outcome()).isEqualTo(SplitCommitResult.OUTCOME_DECOMPOSED);
    assertThat(result.childRunIds()).hasSize(2);
    assertThat(result.subtasks())
        .extracting(SubtaskCommitOutcome::status)
        .containsExactly(
            SubtaskCommitOutcome.STATUS_CREATED, SubtaskCommitOutcome.STATUS_INTERNAL_ONLY);

    // Both children minted with parentRunId + parent project; each gated-dispatched.
    verify(workflowRunCreatePort, times(2))
        .create(anyString(), eq(WorkflowState.INBOX), eq(PROJECT), eq(RUN));
    verify(runDependencyService, times(2)).dispatchWhenUnblocked(anyString(), any());
    // No dependency edges in this proposal.
    verify(runDependencyService, never()).declareDependencies(any());

    // CAS open->approved, transition to Split, and a separate workflow.split event.
    verify(splitProposalWritePort).approveOpenForRun(RUN);
    verify(workflowTransitionService)
        .transition(eq(RUN), eq(WorkflowState.SPLIT), any(), eq("approve_split"), anyString());
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(event.capture());
    assertThat(event.getValue().eventType()).isEqualTo(WorkflowEventType.SPLIT);
    assertThat(event.getValue().details()).containsKey("childRunIds");
    assertThat(logged("split parent decomposed")).isTrue();
  }

  private boolean logged(String fragment) {
    return appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
  }

  private boolean loggedAtLeast(Level level, String fragment) {
    return appender.list.stream()
        .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment));
  }

  @Test
  void dependencyEdgeDirectionIsPinned() {
    // R4 — proposal edge {from:3,to:1} must map to declareDependencies(child3, [child1]): child3 is
    // the dependent, child1 the prerequisite. A backwards mapping inverts execution order silently.
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(
            Optional.of(
                proposal(
                    List.of(
                        new SplitSubtaskView(1, "A", "a"),
                        new SplitSubtaskView(2, "B", "b"),
                        new SplitSubtaskView(3, "C", "c")),
                    List.of(new SplitDependencyView(3, 1)))));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenReturn(TicketSourceSubticketOutcome.internalOnlySkipped());

    // Capture the minted child ids in ordinal order (Pass A iterates sorted ordinals 1,2,3).
    ArgumentCaptor<String> childIds = ArgumentCaptor.forClass(String.class);

    service.commit(command());

    verify(workflowRunCreatePort, times(3))
        .create(childIds.capture(), eq(WorkflowState.INBOX), eq(PROJECT), eq(RUN));
    List<String> minted = childIds.getAllValues();
    String child1 = minted.get(0);
    String child3 = minted.get(2);

    ArgumentCaptor<DeclareRunDependenciesCommand> decl =
        ArgumentCaptor.forClass(DeclareRunDependenciesCommand.class);
    verify(runDependencyService).declareDependencies(decl.capture());
    assertThat(decl.getValue().runId()).isEqualTo(child3);
    assertThat(decl.getValue().dependsOnRunIds()).containsExactly(child1);
  }

  @Test
  void zeroChildAbortLeavesParentAndProposalUntouched() {
    // R7 — every subtask fails ⇒ aborted result, NO CAS, NO transition, NO workflow.split event.
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(proposal(List.of(new SplitSubtaskView(1, "A", "a")), List.of())));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenThrow(new RuntimeException("connector boom"));

    SplitCommitResult result = service.commit(command());

    assertThat(result.parentDecomposed()).isFalse();
    assertThat(result.outcome()).isEqualTo(SplitCommitResult.OUTCOME_ABORTED_NO_CHILDREN);
    assertThat(result.childRunIds()).isEmpty();
    assertThat(result.subtasks())
        .singleElement()
        .extracting(SubtaskCommitOutcome::status)
        .isEqualTo(SubtaskCommitOutcome.STATUS_FAILED);
    verify(splitProposalWritePort, never()).approveOpenForRun(anyString());
    verify(workflowTransitionService, never()).transition(any(), any(), any(), any(), anyString());
    verify(workflowEventWritePort, never()).append(any());
    assertThat(loggedAtLeast(Level.WARN, "split aborted no children")).isTrue();
  }

  @Test
  void perSubtaskFailureIsolationStillCommitsTheRest() {
    // R1 — one subtask throws; the other still mints a child and the parent decomposes.
    stubGateRun(WorkflowState.WAITING_FOR_REVIEW);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(
            Optional.of(
                proposal(
                    List.of(new SplitSubtaskView(1, "A", "a"), new SplitSubtaskView(2, "B", "b")),
                    List.of())));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenThrow(new RuntimeException("boom on 1"))
        .thenReturn(TicketSourceSubticketOutcome.internalOnlySkipped());

    SplitCommitResult result = service.commit(command());

    assertThat(result.parentDecomposed()).isTrue();
    assertThat(result.childRunIds()).hasSize(1);
    assertThat(result.subtasks())
        .extracting(SubtaskCommitOutcome::status)
        .containsExactly(
            SubtaskCommitOutcome.STATUS_FAILED, SubtaskCommitOutcome.STATUS_INTERNAL_ONLY);
    verify(workflowRunCreatePort, times(1)).create(anyString(), any(), anyString(), eq(RUN));
    assertThat(loggedAtLeast(Level.WARN, "split subtask failed")).isTrue();
  }

  @Test
  void casAlreadyApprovedReplaySkipsTransitionAndEvent() {
    // R6 — a defense-in-depth replay where the proposal was already approved: CAS returns 0, so the
    // decomposition reports decomposed=true but emits NO second transition / workflow.split.
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(proposal(List.of(new SplitSubtaskView(1, "A", "a")), List.of())));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenReturn(TicketSourceSubticketOutcome.internalOnlySkipped());
    when(splitProposalWritePort.approveOpenForRun(RUN)).thenReturn(0);

    SplitCommitResult result = service.commit(command());

    assertThat(result.parentDecomposed()).isTrue();
    verify(workflowTransitionService, never()).transition(any(), any(), any(), any(), anyString());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void topLevelReplayReturnsPriorResultWithoutReExecuting() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, RUN));
    when(splitProposalReadPort.findLatestForRun(RUN))
        .thenReturn(
            Optional.of(
                new SplitProposalView(
                    PROPOSAL,
                    RUN,
                    SplitProposalView.STATUS_APPROVED,
                    0,
                    "art_1",
                    1,
                    "c",
                    "c",
                    true,
                    List.of(),
                    List.of(),
                    null)));
    when(workflowRunReadPort.findByParentRunId(RUN))
        .thenReturn(
            List.of(
                new WorkflowRunSnapshot(
                    "run_child0001", WorkflowState.INBOX, null, 1L, 0, false, PROJECT, RUN)));

    SplitCommitResult result = service.commit(command());

    assertThat(result.parentDecomposed()).isTrue();
    assertThat(result.childRunIds()).containsExactly("run_child0001");
    verify(workflowRunCreatePort, never()).create(anyString(), any(), anyString(), anyString());
    verify(splitProposalWritePort, never()).approveOpenForRun(anyString());
    verify(workflowTransitionService, never()).transition(any(), any(), any(), any(), anyString());
    assertThat(loggedAtLeast(Level.WARN, "split commit idempotent replay")).isTrue();
  }

  @Test
  void rejectsWhenNotAtSplitGate() {
    stubGateRun(WorkflowState.EXECUTING);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.commit(command()))
        .isInstanceOf(org.dradgo.domain.DomainException.class);
    verify(workflowRunCreatePort, never()).create(anyString(), any(), anyString(), anyString());
  }

  @Test
  void cyclicDependencyGraphRejectedBeforeAnyChildMinted() {
    // P0 — a transitive cycle ({1->2},{2->1}) is rejected fail-fast BEFORE Pass A, so no child runs
    // are minted and the parent + proposal are untouched (the harvester does not filter cycles).
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(
            Optional.of(
                proposal(
                    List.of(new SplitSubtaskView(1, "A", "a"), new SplitSubtaskView(2, "B", "b")),
                    List.of(new SplitDependencyView(1, 2), new SplitDependencyView(2, 1)))));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.commit(command()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.RUN_DEPENDENCY_CYCLE);
    verify(workflowRunCreatePort, never()).create(anyString(), any(), anyString(), anyString());
    verify(splitProposalWritePort, never()).approveOpenForRun(anyString());
    assertThat(loggedAtLeast(Level.WARN, "split rejected dependency cycle")).isTrue();
  }

  @Test
  void existingChildrenReconcileWithoutRecreating() {
    // P5 / Edge-F2 — a retry (e.g. under a different client key) where the parent already has
    // children must reconcile from durable state, not double-create.
    when(workflowRunReadPort.findByParentRunId(RUN))
        .thenReturn(
            List.of(
                new WorkflowRunSnapshot(
                    "run_child0001", WorkflowState.INBOX, null, 1L, 0, false, PROJECT, RUN)));

    SplitCommitResult result = service.commit(command());

    assertThat(result.childRunIds()).containsExactly("run_child0001");
    verify(workflowRunCreatePort, never()).create(anyString(), any(), anyString(), anyString());
    verify(splitProposalWritePort, never()).approveOpenForRun(anyString());
    assertThat(loggedAtLeast(Level.WARN, "split commit idempotent replay")).isTrue();
  }

  @Test
  void reservationUsesDeterministicPerProposalKey() {
    // P7 — the reservation keys on split-commit:<run>:<proposal> (NOT the client Idempotency-Key)
    // so
    // concurrent approves under different client keys serialize on the same proposal.
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(proposal(List.of(new SplitSubtaskView(1, "A", "a")), List.of())));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenReturn(TicketSourceSubticketOutcome.internalOnlySkipped());
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

    service.commit(command());

    verify(idempotencyService).checkAndReserve(key.capture(), anyString(), any(), anyString());
    assertThat(key.getValue()).isEqualTo("split-commit:" + RUN + ":" + PROPOSAL);
  }

  @Test
  void passBEdgeFailureLeavesDependentUndispatchedButCommitsTheRest() {
    // P3 + P4 — declareDependencies for the dependent throws; the fan-out is NOT aborted: the
    // independent child still dispatches, the dependent is left un-dispatched (cannot run ahead of
    // its un-wired prerequisite), and the parent still decomposes.
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(
            Optional.of(
                proposal(
                    List.of(new SplitSubtaskView(1, "A", "a"), new SplitSubtaskView(2, "B", "b")),
                    List.of(new SplitDependencyView(2, 1)))));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenReturn(TicketSourceSubticketOutcome.internalOnlySkipped());
    when(runDependencyService.declareDependencies(any()))
        .thenThrow(new RuntimeException("declare boom"));

    SplitCommitResult result = service.commit(command());

    assertThat(result.parentDecomposed()).isTrue();
    assertThat(result.childRunIds()).hasSize(2);
    // Only the independent child (ordinal 1) is dispatched; the dependent (ordinal 2) is held back.
    verify(runDependencyService, times(1)).dispatchWhenUnblocked(anyString(), any());
    assertThat(loggedAtLeast(Level.WARN, "split dependency declaration failed")).isTrue();
    assertThat(loggedAtLeast(Level.WARN, "split child left undispatched")).isTrue();
  }

  @Test
  void decomposeRethrowsWhenParentLeftItsGateDuringFanOut() {
    // P6 — the SPLIT transition is rejected AND the parent is not actually in Split (it left its
    // gate
    // mid-fan-out): the commit must NOT report a false success; it rethrows.
    stubGateRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(splitProposalReadPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(proposal(List.of(new SplitSubtaskView(1, "A", "a")), List.of())));
    when(ticketSourceSubticketService.createSubticketIfSupported(any(), any(), any()))
        .thenReturn(TicketSourceSubticketOutcome.internalOnlySkipped());
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "rejected", Map.of()))
        .when(workflowTransitionService)
        .transition(eq(RUN), eq(WorkflowState.SPLIT), any(), eq("approve_split"), anyString());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.commit(command()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);
    assertThat(loggedAtLeast(Level.ERROR, "parent left its gate during fan-out")).isTrue();
  }
}
