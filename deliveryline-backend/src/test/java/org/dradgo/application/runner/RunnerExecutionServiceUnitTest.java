package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunnerExecutionServiceUnitTest {

  private static final String REX = "rex_unit12345678";
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-11T08:00:00Z"), ZoneOffset.UTC);

  @Test
  void markRunningDelegatesToPortWithUtcNow() {
    RunnerExecutionRecordPort port = mock(RunnerExecutionRecordPort.class);
    RunnerExecutionSnapshot updated = snapshot(RunnerExecutionStatus.RUNNING);
    when(port.transitionToRunning(eq(REX), any(OffsetDateTime.class))).thenReturn(updated);

    RunnerExecutionService service = new RunnerExecutionService(port, FIXED_CLOCK);
    RunnerExecutionSnapshot result = service.markRunning(REX);

    ArgumentCaptor<OffsetDateTime> activity = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(port).transitionToRunning(eq(REX), activity.capture());
    assertEquals(OffsetDateTime.parse("2026-05-11T08:00:00Z"), activity.getValue());
    assertEquals(RunnerExecutionStatus.RUNNING, result.status());
  }

  @Test
  void recordCompletedDelegatesToPort() {
    RunnerExecutionRecordPort port = mock(RunnerExecutionRecordPort.class);
    RunnerExecutionSnapshot terminal = snapshot(RunnerExecutionStatus.COMPLETED);
    when(port.markCompleted(eq(REX), any(OffsetDateTime.class))).thenReturn(terminal);

    RunnerExecutionService service = new RunnerExecutionService(port, FIXED_CLOCK);
    RunnerExecutionSnapshot result = service.recordCompleted(REX);

    assertEquals(RunnerExecutionStatus.COMPLETED, result.status());
    verify(port).markCompleted(eq(REX), any(OffsetDateTime.class));
  }

  @Test
  void recordFailedPassesFailureCategoryThroughAndRejectsNull() {
    RunnerExecutionRecordPort port = mock(RunnerExecutionRecordPort.class);
    when(port.markFailed(eq(REX), eq(FailureCategory.RUNNER_CRASH), any(OffsetDateTime.class)))
        .thenReturn(snapshot(RunnerExecutionStatus.FAILED));

    RunnerExecutionService service = new RunnerExecutionService(port, FIXED_CLOCK);
    assertNotNull(service.recordFailed(REX, FailureCategory.RUNNER_CRASH));
    verify(port).markFailed(eq(REX), eq(FailureCategory.RUNNER_CRASH), any(OffsetDateTime.class));

    assertThrows(NullPointerException.class, () -> service.recordFailed(REX, null));
  }

  @Test
  void recordTimedOutAndOrphanedDelegateToTheirPortMethods() {
    RunnerExecutionRecordPort port = mock(RunnerExecutionRecordPort.class);
    when(port.markTimedOut(eq(REX), any(OffsetDateTime.class)))
        .thenReturn(snapshot(RunnerExecutionStatus.TIMED_OUT));
    when(port.markOrphaned(eq(REX), any(OffsetDateTime.class)))
        .thenReturn(snapshot(RunnerExecutionStatus.ORPHANED));

    RunnerExecutionService service = new RunnerExecutionService(port, FIXED_CLOCK);
    assertEquals(RunnerExecutionStatus.TIMED_OUT, service.recordTimedOut(REX).status());
    assertEquals(RunnerExecutionStatus.ORPHANED, service.recordOrphaned(REX).status());
  }

  @Test
  void touchActivityForwardsCallToPort() {
    RunnerExecutionRecordPort port = mock(RunnerExecutionRecordPort.class);
    RunnerExecutionSnapshot pending = snapshot(RunnerExecutionStatus.RUNNING);
    when(port.touchActivity(eq(REX), any(OffsetDateTime.class), eq(Duration.ofSeconds(1200))))
        .thenReturn(pending);

    RunnerExecutionService service = new RunnerExecutionService(port, FIXED_CLOCK);
    RunnerExecutionSnapshot result = service.touchActivity(REX, Duration.ofSeconds(1200));
    assertTrue(result == pending);
  }

  private static RunnerExecutionSnapshot snapshot(RunnerExecutionStatus status) {
    OffsetDateTime now = OffsetDateTime.parse("2026-05-11T08:00:00Z");
    return new RunnerExecutionSnapshot(
        REX,
        "run_test12345678",
        RunnerStage.INVESTIGATION,
        status,
        1,
        now,
        now.plusSeconds(600),
        null,
        RunnerExecutionStateMachine.isTerminal(status) ? now : null,
        now,
        null);
  }
}
