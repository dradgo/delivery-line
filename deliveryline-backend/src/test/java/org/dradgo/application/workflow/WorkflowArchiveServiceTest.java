package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.workflow.commands.ArchiveRunCommand;
import org.dradgo.application.workflow.commands.UnarchiveRunCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunArchivePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3d-8 (FR67, AC3/AC4/AC10) — unit coverage for {@link WorkflowArchiveService}: archive sets
 * the marker + appends a governed {@code workflow.archived} event (priorState == resultingState,
 * interventionMarker = true); double-archive / un-archive-not-archived raise {@code
 * ARCHIVE_NOT_APPLICABLE}; idempotent replay returns the prior result WITHOUT a second event;
 * RUN_NOT_FOUND on a miss; the archive write goes through the dedicated archive port (never the
 * state port).
 */
class WorkflowArchiveServiceTest {

  private static final String RUN_ID = "run_archive_unit_aaaaaaaa";
  private static final String KEY = "idem-archive-unit-aaaaaaaaaa";
  private static final Instant NOW = Instant.parse("2026-06-23T12:00:00Z");

  private WorkflowRunReadPort readPort;
  private WorkflowRunArchivePort archivePort;
  private WorkflowEventWritePort eventWritePort;
  private IdempotencyService idempotencyService;
  private WorkflowArchiveService service;

  @BeforeEach
  void setUp() {
    readPort = org.mockito.Mockito.mock(WorkflowRunReadPort.class);
    archivePort = org.mockito.Mockito.mock(WorkflowRunArchivePort.class);
    eventWritePort = org.mockito.Mockito.mock(WorkflowEventWritePort.class);
    idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    TransactionTemplate transactionTemplate = org.mockito.Mockito.mock(TransactionTemplate.class);
    // Run the callback inline (no real transaction in a unit test).
    when(transactionTemplate.execute(any()))
        .thenAnswer(
            invocation -> invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    service =
        new WorkflowArchiveService(
            readPort,
            archivePort,
            eventWritePort,
            idempotencyService,
            new IdempotencyKeyValidator(),
            transactionTemplate,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void reserve() {
    when(idempotencyService.checkAndReserve(any(), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
  }

  private WorkflowRunSnapshot run(OffsetDateTime archivedAt) {
    return new WorkflowRunSnapshot(RUN_ID, WorkflowState.FAILED, archivedAt, 3L, 0, false);
  }

  private ArchiveRunCommand archiveCommand() {
    return new ArchiveRunCommand(RUN_ID, "alex", ActorType.HUMAN, KEY, "corr-1", "ticket removed");
  }

  private UnarchiveRunCommand unarchiveCommand() {
    return new UnarchiveRunCommand(RUN_ID, "alex", ActorType.HUMAN, KEY, "corr-1", null);
  }

  @Test
  void archiveSetsMarkerAndAppendsGovernedEvent() {
    reserve();
    when(readPort.findByPublicId(RUN_ID)).thenReturn(Optional.of(run(null)));

    WorkflowArchiveResult result = service.archiveRun(archiveCommand());

    verify(archivePort).markArchived(RUN_ID, NOW);
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(event.capture());
    WorkflowEventRecord appended = event.getValue();
    assertThat(appended.eventType()).isEqualTo(WorkflowEventType.WORKFLOW_ARCHIVED);
    // Archiving is orthogonal to the lifecycle: prior == resulting == current state.
    assertThat(appended.priorState()).isEqualTo(WorkflowState.FAILED);
    assertThat(appended.resultingState()).isEqualTo(WorkflowState.FAILED);
    assertThat(appended.interventionMarker()).isTrue();
    assertThat(appended.details()).containsEntry(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, KEY);
    assertThat(appended.details()).containsEntry(WorkflowEventDetailKeys.REASON, "ticket removed");
    assertThat(result.archivedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    assertThat(result.currentState()).isEqualTo(WorkflowState.FAILED);
    assertThat(result.replay()).isFalse();
    verify(idempotencyService).complete(KEY, RUN_ID, IdempotencyRecordStatus.COMPLETED);
  }

  @Test
  void doubleArchiveRaisesArchiveNotApplicable() {
    reserve();
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(Optional.of(run(NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))));

    assertThatThrownBy(() -> service.archiveRun(archiveCommand()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARCHIVE_NOT_APPLICABLE);

    verify(archivePort, never()).markArchived(any(), any());
    verify(eventWritePort, never()).append(any());
    verify(idempotencyService).complete(eq(KEY), any(), eq(IdempotencyRecordStatus.FAILED));
  }

  @Test
  void unarchiveClearsMarkerAndAppendsEvent() {
    reserve();
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(Optional.of(run(NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))));

    WorkflowArchiveResult result = service.unarchiveRun(unarchiveCommand());

    verify(archivePort).clearArchived(RUN_ID);
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(event.capture());
    assertThat(event.getValue().eventType()).isEqualTo(WorkflowEventType.WORKFLOW_UNARCHIVED);
    assertThat(result.archivedAt()).isNull();
    assertThat(result.replay()).isFalse();
  }

  @Test
  void unarchiveNotArchivedRaisesArchiveNotApplicable() {
    reserve();
    when(readPort.findByPublicId(RUN_ID)).thenReturn(Optional.of(run(null)));

    assertThatThrownBy(() -> service.unarchiveRun(unarchiveCommand()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ARCHIVE_NOT_APPLICABLE);

    verify(archivePort, never()).clearArchived(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void idempotentReplayReturnsPriorResultWithoutSecondEvent() {
    when(idempotencyService.checkAndReserve(any(), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, RUN_ID));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(Optional.of(run(NOW.atOffset(ZoneOffset.UTC))));

    WorkflowArchiveResult result = service.archiveRun(archiveCommand());

    assertThat(result.replay()).isTrue();
    assertThat(result.archivedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    verify(archivePort, never()).markArchived(any(), any());
    verify(eventWritePort, never()).append(any());
    verify(idempotencyService, never()).complete(any(), any(), any());
  }

  @Test
  void archiveRunNotFoundRaisesRunNotFound() {
    reserve();
    when(readPort.findByPublicId(RUN_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.archiveRun(archiveCommand()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.RUN_NOT_FOUND);
  }
}
