package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Story 4.4 (AC5) — the {@code audit.logDownloaded} append is a non-transition governed event
 * (prior == resulting == currentState, interventionMarker = true, {@code runnerExecutionId}
 * detail).
 */
class RunnerLogDownloadAuditServiceTest {

  private static final String RUN = "run_audit12345678";
  private static final String REX = "rex_audit12345678";

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventWritePort events = mock(WorkflowEventWritePort.class);
  private final RunnerLogDownloadAuditService service =
      new RunnerLogDownloadAuditService(
          runs, events, Clock.fixed(Instant.parse("2026-07-07T10:00:00Z"), ZoneOffset.UTC));

  @Test
  void appendsAuditLogDownloadedAsNonTransitionGovernedEvent() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN, WorkflowState.FAILED, null, 3L, 0, false, "prj_default", null)));

    service.recordLogDownloaded(RUN, REX, "operator-jane", ActorType.HUMAN);

    ArgumentCaptor<WorkflowEventRecord> captor = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(events).append(captor.capture());
    WorkflowEventRecord record = captor.getValue();
    assertThat(record.eventType()).isEqualTo(WorkflowEventType.AUDIT_LOG_DOWNLOADED);
    assertThat(record.priorState()).isEqualTo(WorkflowState.FAILED);
    assertThat(record.resultingState()).isEqualTo(WorkflowState.FAILED);
    assertThat(record.interventionMarker()).isTrue();
    assertThat(record.failureCategory()).isNull();
    assertThat(record.actorIdentity()).isEqualTo("operator-jane");
    assertThat(record.actorType()).isEqualTo(ActorType.HUMAN);
    assertThat(record.details()).containsEntry(WorkflowEventDetailKeys.RUNNER_EXECUTION_ID, REX);
  }

  @Test
  void skipsAppendWhenRunNotFound() {
    when(runs.findByPublicId(RUN)).thenReturn(Optional.empty());

    service.recordLogDownloaded(RUN, REX, "operator-jane", ActorType.HUMAN);

    verify(events, never()).append(org.mockito.ArgumentMatchers.any());
  }
}
