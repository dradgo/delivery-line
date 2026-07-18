package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.integration.conflict.IntegrationConflictTerminalRunReconciliationSweepService.SweepResult;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.application.integration.conflict.spi.TerminalRunConflict;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Story 4.30 (AC2/AC3/AC5) — unit coverage of {@link
 * IntegrationConflictTerminalRunReconciliationSweepService} with all ports mocked. Real-PG
 * behaviour (the terminal-run read query, FK-satisfied recovery_actions insert, markResolved) is
 * covered by {@code IntegrationConflictTerminalRunSweepIT}.
 */
class IntegrationConflictTerminalRunReconciliationSweepServiceTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC);

  private final IntegrationConflictReadPort readPort = mock(IntegrationConflictReadPort.class);
  private final IntegrationConflictService conflictService = mock(IntegrationConflictService.class);
  private final RecoveryActionRecordPort recoveryActionRecordPort =
      mock(RecoveryActionRecordPort.class);
  private final WorkflowEventWritePort workflowEventWritePort = mock(WorkflowEventWritePort.class);
  private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    // insert echoes back a rcv_ snapshot so resolveConflict receives the FK id.
    when(recoveryActionRecordPort.insert(any()))
        .thenAnswer(
            invocation -> {
              RecoveryActionWriteCommand cmd = invocation.getArgument(0);
              return new RecoveryActionSnapshot(
                  "rcv_" + cmd.idempotencyKey().hashCode(),
                  1L,
                  cmd.workflowRunPublicId(),
                  cmd.actionType(),
                  cmd.triggeringEventPublicId(),
                  cmd.resultingEventPublicId(),
                  cmd.actorIdentity(),
                  cmd.actorType(),
                  cmd.idempotencyKey(),
                  cmd.resultStatus(),
                  java.time.OffsetDateTime.now(FIXED),
                  cmd.reviewerRole());
            });

    appender = new ListAppender<>();
    appender.start();
    logger =
        (Logger)
            org.slf4j.LoggerFactory.getLogger(
                IntegrationConflictTerminalRunReconciliationSweepService.class);
    logger.setLevel(Level.DEBUG);
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    appender.stop();
  }

  private IntegrationConflictTerminalRunReconciliationSweepService newSweep(
      IntegrationConflictTerminalSweepProperties props) {
    return new IntegrationConflictTerminalRunReconciliationSweepService(
        readPort,
        conflictService,
        recoveryActionRecordPort,
        workflowEventWritePort,
        props,
        FIXED,
        txManager);
  }

  @Test
  void systemResolvesEachStrandedConflictAndRecordsAuditTrail() {
    IntegrationConflictTerminalSweepProperties props =
        new IntegrationConflictTerminalSweepProperties(true, 60_000L, 100);
    when(readPort.findUnresolvedConflictsOnTerminalRuns(100))
        .thenReturn(
            List.of(
                new TerminalRunConflict("icf_a", "run_a", "Reconciled"),
                new TerminalRunConflict("icf_b", "run_b", "Completed")));

    SweepResult result = newSweep(props).sweep();

    assertThat(result.found()).isEqualTo(2);
    assertThat(result.cleared()).isEqualTo(2);
    assertThat(result.batchLimitHit()).isFalse();

    // AC3 — each clear takes the P3a per-run reconcile lock FIRST, then routes markResolved through
    // the in-package service (never a raw write port call).
    verify(conflictService).lockRunForReconcile("run_a");
    verify(conflictService).lockRunForReconcile("run_b");
    verify(conflictService).resolveConflict(eq("icf_a"), eq("run_a"), any(), eq(FIXED.instant()));
    verify(conflictService).resolveConflict(eq("icf_b"), eq("run_b"), any(), eq(FIXED.instant()));

    // AC3 — a SYSTEM-actor recovery_actions row (reconcile / system / succeeded / reviewer=system).
    ArgumentCaptor<RecoveryActionWriteCommand> actionCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryActionRecordPort, org.mockito.Mockito.times(2)).insert(actionCaptor.capture());
    assertThat(actionCaptor.getAllValues())
        .allSatisfy(
            cmd -> {
              assertThat(cmd.actionType()).isEqualTo("reconcile");
              assertThat(cmd.actorType()).isEqualTo(ActorType.SYSTEM);
              assertThat(cmd.actorIdentity()).isEqualTo("system");
              assertThat(cmd.reviewerRole()).isEqualTo("system");
              assertThat(cmd.resultStatus()).isEqualTo("succeeded");
              assertThat(cmd.idempotencyKey()).startsWith("terminal-run-sweep:");
            });

    // AC3 — a RECOVERY_RECONCILED audit event; the run does NOT change state (prior == resulting ==
    // terminal state), SYSTEM actor.
    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort, org.mockito.Mockito.times(2)).append(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .allSatisfy(
            e -> {
              assertThat(e.eventType()).isEqualTo(WorkflowEventType.RECOVERY_RECONCILED);
              assertThat(e.actorType()).isEqualTo(ActorType.SYSTEM);
              assertThat(e.priorState()).isEqualTo(e.resultingState());
            });
    assertThat(eventCaptor.getAllValues())
        .anyMatch(e -> e.priorState() == WorkflowState.RECONCILED)
        .anyMatch(e -> e.priorState() == WorkflowState.COMPLETED);
    // Review P1 — every auto-clear event carries the AUTO_CLEARED=true discriminator so a timeline
    // consumer can tell a SYSTEM terminal-run strand-clear from an operator reconcile (which would
    // instead transition the run to Reconciled and carry a reconciliationDecision).
    assertThat(eventCaptor.getAllValues())
        .allSatisfy(
            e -> assertThat(e.details()).containsEntry(WorkflowEventDetailKeys.AUTO_CLEARED, true));

    // AC2 — per-item WARN + INFO tick summary.
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("auto-cleared stranded conflict")
                    && e.getFormattedMessage().contains("icf_a"));
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.INFO
                    && e.getFormattedMessage()
                        .contains("TERMINAL-RUN SWEEP tick complete found=2 cleared=2"));
  }

  @Test
  void noOpWhenNoTerminalRunConflicts() {
    IntegrationConflictTerminalSweepProperties props =
        IntegrationConflictTerminalSweepProperties.defaults();
    when(readPort.findUnresolvedConflictsOnTerminalRuns(props.batchLimit())).thenReturn(List.of());

    SweepResult result = newSweep(props).sweep();

    assertThat(result.found()).isZero();
    assertThat(result.cleared()).isZero();
    assertThat(result.batchLimitHit()).isFalse();
    verify(conflictService, never()).lockRunForReconcile(any());
    verify(conflictService, never()).resolveConflict(any(), any(), any(), any());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void warnsWhenBatchLimitHit() {
    IntegrationConflictTerminalSweepProperties props =
        new IntegrationConflictTerminalSweepProperties(true, 60_000L, 2);
    when(readPort.findUnresolvedConflictsOnTerminalRuns(2))
        .thenReturn(
            List.of(
                new TerminalRunConflict("icf_c", "run_c", "Reconciled"),
                new TerminalRunConflict("icf_d", "run_d", "TakenOver")));

    SweepResult result = newSweep(props).sweep();

    assertThat(result.found()).isEqualTo(2);
    assertThat(result.cleared()).isEqualTo(2);
    assertThat(result.batchLimitHit()).isTrue();
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("TERMINAL-RUN SWEEP hit batch limit"));
  }

  @Test
  void alreadyResolvedConflictIsSkippedNotCounted() {
    // A concurrent live reconcile (or an overlapping tick) cleared the row first: resolveConflict's
    // idempotent WHERE resolved_at IS NULL throws CONFLICT_ALREADY_RESOLVED — a benign skip, NOT a
    // failure, and NOT counted as cleared.
    IntegrationConflictTerminalSweepProperties props =
        new IntegrationConflictTerminalSweepProperties(true, 60_000L, 100);
    when(readPort.findUnresolvedConflictsOnTerminalRuns(100))
        .thenReturn(List.of(new TerminalRunConflict("icf_race", "run_race", "Reconciled")));
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.CONFLICT_ALREADY_RESOLVED, "already resolved"))
        .when(conflictService)
        .resolveConflict(eq("icf_race"), eq("run_race"), any(), any());

    SweepResult result = newSweep(props).sweep();

    assertThat(result.found()).isEqualTo(1);
    assertThat(result.cleared()).isZero();
    // No false "auto-cleared" WARN for a row we did not actually clear.
    assertThat(appender.list)
        .noneMatch(e -> e.getFormattedMessage().contains("auto-cleared stranded conflict"));
    assertThat(appender.list)
        .anyMatch(e -> e.getFormattedMessage().contains("already resolved concurrently"));
  }

  @Test
  @SuppressWarnings("unused")
  void batchLimitIsPassedFromProperties() {
    IntegrationConflictTerminalSweepProperties props =
        new IntegrationConflictTerminalSweepProperties(true, 60_000L, 25);
    when(readPort.findUnresolvedConflictsOnTerminalRuns(anyInt())).thenReturn(List.of());

    newSweep(props).sweep();

    verify(readPort).findUnresolvedConflictsOnTerminalRuns(25);
  }
}
