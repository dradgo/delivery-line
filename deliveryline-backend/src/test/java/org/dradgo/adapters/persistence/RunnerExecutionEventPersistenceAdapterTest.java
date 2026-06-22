package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/**
 * Story 3d-6 — pins the {@link RunnerExecutionEventPersistenceAdapter} type whitelist: the
 * diagnostic-console session events ({@code console.opened}/{@code console.closed}) are accepted
 * and forwarded to the canonical {@link WorkflowEventWritePort} with NO prior/resulting state (a
 * console session is not a workflow-state change), while a non-whitelisted state-change type is
 * still rejected. Without the whitelist addition a real append of a console event would throw
 * {@link IllegalArgumentException} (the gap 3d-3 hit for {@code manual.executionRequested}).
 */
class RunnerExecutionEventPersistenceAdapterTest {

  private static final String RUN = "run_console0000001";
  private final WorkflowEventWritePort writePort = mock(WorkflowEventWritePort.class);
  private final RunnerExecutionEventPersistenceAdapter adapter =
      new RunnerExecutionEventPersistenceAdapter(writePort);

  @ParameterizedTest
  @EnumSource(
      value = WorkflowEventType.class,
      names = {"CONSOLE_OPENED", "CONSOLE_CLOSED"})
  void acceptsConsoleSessionEventsAndForwardsThemWithNoStateChange(WorkflowEventType type) {
    String eventId =
        adapter.append(
            RUN,
            type,
            new ActorContext("workflow_owner", ActorType.HUMAN, null),
            "diagnostic_console",
            null,
            OffsetDateTime.parse("2026-06-22T00:00:00Z"),
            Map.of("runnerExecutionId", "rex_console0000001", "workflowRunId", RUN));

    assertThat(eventId).startsWith("evt_");
    ArgumentCaptor<WorkflowEventRecord> captor = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(writePort).append(captor.capture());
    WorkflowEventRecord record = captor.getValue();
    assertThat(record.eventType()).isEqualTo(type);
    // A console session is not a workflow-state change — both state fields are null (DD-4).
    assertThat(record.priorState()).isNull();
    assertThat(record.resultingState()).isNull();
    assertThat(record.details()).containsOnlyKeys("runnerExecutionId", "workflowRunId");
  }

  @Test
  void stillRejectsAStateChangeEventTypeThatMustGoThroughTheTransitionService() {
    assertThatThrownBy(
            () ->
                adapter.append(
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    new ActorContext("system", ActorType.SYSTEM, null),
                    "nope",
                    null,
                    OffsetDateTime.parse("2026-06-22T00:00:00Z"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(writePort, org.mockito.Mockito.never()).append(any());
  }
}
