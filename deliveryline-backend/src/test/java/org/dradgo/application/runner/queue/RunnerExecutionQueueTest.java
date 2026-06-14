package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.queue.spi.RunnerQueueNotificationPort;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Story 3.17a (AC2/AC4/AC5) + 3.17b — unit coverage of {@link RunnerExecutionQueue} with mocked SPI
 * ports: enqueue happy path (mint + persist with the V14 dispatch carriage + event + best-effort
 * NOTIFY), public-id mint, backpressure rejection (no row, no event, no notify), dequeue
 * delegation, and input validation. Priority ordering + the real SKIP-LOCKED lease are SQL concerns
 * proven in {@code RunnerExecutionQueueIT}. With no ambient transaction the post-commit NOTIFY
 * degrades to an immediate best-effort call (verified here).
 */
class RunnerExecutionQueueTest {

  private static final String RUN_ID = "run_queue12345678";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-06-14T10:00:00Z"), ZoneOffset.UTC);

  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private RunnerQueueNotificationPort notificationPort;
  private RunnerExecutionQueue queue;

  @BeforeEach
  void setUp() {
    recordPort = org.mockito.Mockito.mock(RunnerExecutionRecordPort.class);
    eventPort = org.mockito.Mockito.mock(RunnerExecutionEventPort.class);
    notificationPort = org.mockito.Mockito.mock(RunnerQueueNotificationPort.class);
    queue =
        new RunnerExecutionQueue(
            recordPort, eventPort, RunnerProperties.defaults(), notificationPort, CLOCK);
  }

  private static ActorContext actor(String correlationId) {
    return new ActorContext("system", ActorType.SYSTEM, correlationId);
  }

  @Test
  void enqueueMintsRowPersistsCarriageAppendsEventAndNotifies() {
    when(recordPort.countQueued()).thenReturn(5L);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(3);
    when(recordPort.insertQueued(
            any(),
            eq(RUN_ID),
            eq(RunnerStage.INVESTIGATION),
            eq(3),
            any(),
            eq(70),
            eq("corr-1"),
            eq("idem-1"),
            eq("system"),
            eq("system")))
        .thenAnswer(invocation -> snapshotFor(invocation.getArgument(0)));
    when(eventPort.append(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn("evt_queued01");

    QueuedRunnerExecution result =
        queue.enqueue(RUN_ID, RunnerStage.INVESTIGATION, "idem-1", actor("corr-1"), 70);

    // Backpressure cap was checked under defaults (100); resulting depth = prior + 1.
    assertEquals(6L, result.currentDepth());
    assertEquals(70, result.queuePriority());
    assertEquals("corr-1", result.correlationId());
    assertEquals("evt_queued01", result.queuedEventPublicId());
    assertEquals(RUN_ID, result.workflowRunPublicId());

    // A fresh rex_ id was minted and threaded into the row insert (with the V14 carriage) + event.
    ArgumentCaptor<String> rexId = ArgumentCaptor.forClass(String.class);
    verify(recordPort)
        .insertQueued(
            rexId.capture(),
            eq(RUN_ID),
            eq(RunnerStage.INVESTIGATION),
            eq(3),
            eq(Duration.ofSeconds(600)),
            eq(70),
            eq("corr-1"),
            eq("idem-1"),
            eq("system"),
            eq("system"));
    assertEquals(
        PublicIdPrefixes.RUNNER_EXECUTION, PublicIdPrefixes.fromPublicId(rexId.getValue()));
    assertEquals(result.runnerExecutionPublicId(), rexId.getValue());

    ArgumentCaptor<WorkflowEventType> eventType = ArgumentCaptor.forClass(WorkflowEventType.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<ActorContext> actor = ArgumentCaptor.forClass(ActorContext.class);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eventType.capture(),
            actor.capture(),
            eq("runner_queued"),
            eq(null),
            any(OffsetDateTime.class),
            details.capture());
    assertEquals(WorkflowEventType.RUNNER_QUEUED, eventType.getValue());
    assertEquals(rexId.getValue(), details.getValue().get("runnerExecutionId"));
    assertEquals(70, details.getValue().get("queuePriority"));
    assertEquals("investigation", details.getValue().get("stage"));
    assertEquals("corr-1", actor.getValue().correlationId());

    // No ambient tx synchronization in a unit test → the post-commit NOTIFY degrades to an
    // immediate
    // best-effort call (Decision D8).
    verify(notificationPort).notifyQueued();
  }

  @Test
  void enqueueAtCapacityRaisesRunnerQueueFullAndWritesNothing() {
    // defaults() queue-max-depth = 100; at-or-above the cap rejects before any insert/event/notify.
    when(recordPort.countQueued()).thenReturn(100L);

    DomainException ex =
        assertThrows(
            DomainException.class,
            () -> queue.enqueue(RUN_ID, RunnerStage.EXECUTION, "idem-1", actor("corr-1"), 100));

    assertEquals(DomainErrorCode.RUNNER_QUEUE_FULL, ex.errorCode());
    assertEquals(100L, ex.details().get("currentDepth"));
    assertEquals(100, ex.details().get("maxDepth"));
    verify(recordPort, never())
        .insertQueued(any(), any(), any(), anyInt(), any(), anyInt(), any(), any(), any(), any());
    verifyNoInteractions(eventPort);
    verifyNoInteractions(notificationPort);
  }

  @Test
  void dequeueDelegatesToRecordPortAndReturnsLeasedRow() {
    RunnerExecutionSnapshot leased = snapshotFor("rex_leased00001a");
    when(recordPort.dequeueNext("worker-1")).thenReturn(Optional.of(leased));

    Optional<RunnerExecutionSnapshot> result = queue.dequeue("worker-1");

    assertTrue(result.isPresent());
    assertSame(leased, result.get());
    verify(recordPort).dequeueNext("worker-1");
  }

  @Test
  void dequeueReturnsEmptyWhenQueueDrained() {
    when(recordPort.dequeueNext("worker-1")).thenReturn(Optional.empty());
    assertTrue(queue.dequeue("worker-1").isEmpty());
  }

  @Test
  void enqueueRejectsNegativeQueuePriorityBeforeAnyWrite() {
    // queuePriority is the live dequeue ORDER BY key — a negative value would front-run every
    // default-100 row, so it is rejected before any port interaction.
    assertThrows(
        IllegalArgumentException.class,
        () -> queue.enqueue(RUN_ID, RunnerStage.INVESTIGATION, "idem-1", actor("corr-1"), -1));
    verifyNoInteractions(recordPort);
    verifyNoInteractions(eventPort);
    verifyNoInteractions(notificationPort);
  }

  @Test
  void enqueueRejectsBlankIdempotencyKeyBeforeAnyWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> queue.enqueue(RUN_ID, RunnerStage.INVESTIGATION, "  ", actor("corr-1"), 0));
    verifyNoInteractions(recordPort);
    verifyNoInteractions(eventPort);
    verifyNoInteractions(notificationPort);
  }

  @Test
  void enqueueAcceptsNullCorrelationIdOnActor() {
    // The actor's correlationId may be null (story 1.19 best-effort); it is persisted as a null
    // carriage value, never rejected.
    when(recordPort.countQueued()).thenReturn(0L);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(1);
    when(recordPort.insertQueued(
            any(),
            eq(RUN_ID),
            any(),
            anyInt(),
            any(),
            eq(0),
            eq(null),
            eq("idem-1"),
            eq("system"),
            eq("system")))
        .thenAnswer(invocation -> snapshotFor(invocation.getArgument(0)));
    when(eventPort.append(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn("evt_queued01");

    QueuedRunnerExecution result =
        queue.enqueue(RUN_ID, RunnerStage.INVESTIGATION, "idem-1", actor(null), 0);

    assertEquals(0, result.queuePriority());
    verify(recordPort)
        .insertQueued(
            any(),
            eq(RUN_ID),
            any(),
            anyInt(),
            any(),
            eq(0),
            eq(null),
            eq("idem-1"),
            eq("system"),
            eq("system"));
  }

  @Test
  void dequeueRejectsBlankWorkerId() {
    assertThrows(IllegalArgumentException.class, () -> queue.dequeue("  "));
  }

  private static RunnerExecutionSnapshot snapshotFor(String publicId) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        publicId,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.QUEUED,
        1,
        now,
        now.plusMinutes(10),
        null,
        null,
        now,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        70,
        0,
        "corr-1",
        "idem-1",
        "system",
        "system");
  }
}
