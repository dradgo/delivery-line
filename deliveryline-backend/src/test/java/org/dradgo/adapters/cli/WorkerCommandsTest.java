package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerQueueStatus;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkerStatus;
import org.junit.jupiter.api.Test;

/**
 * Story 3.19 (AC2/AC3) — {@code deliveryline workers status} CLI rendering. Mocks {@link
 * WorkflowInspectionService} (the single read seam — AC10) and uses the real {@link
 * WorkflowCommandOutputs} renderer, mirroring the {@code WorkflowBatchCommandsTest} pattern. Covers
 * text + JSON schema, stale color coding (TTY only), and the {@code --watch} refresh loop.
 */
class WorkerCommandsTest {

  private static final String ESC = Character.toString((char) 27);
  private static final OffsetDateTime DISPATCHED =
      OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);

  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());

  private RunnerQueueStatus status(long staleQueued, long staleDispatched) {
    return new RunnerQueueStatus(
        2,
        1L,
        1L,
        7L,
        DISPATCHED,
        42L,
        1L,
        3L,
        staleQueued,
        staleDispatched,
        List.of(
            new WorkerStatus(
                "worker-1", "busy", "rex_aaa", "run_bbb", DISPATCHED, "investigation")));
  }

  private WorkerCommands commands(CliInteractivityDetector detector) {
    return new WorkerCommands(inspection, outputs, detector);
  }

  private WorkerCommands commands(
      CliInteractivityDetector detector,
      BooleanSupplier watchGate,
      WorkerCommands.Sleeper sleeper) {
    return new WorkerCommands(inspection, outputs, detector, watchGate, sleeper);
  }

  private static CliInteractivityDetector detector(boolean interactive) {
    CliInteractivityDetector d = mock(CliInteractivityDetector.class);
    when(d.isInteractive()).thenReturn(interactive);
    return d;
  }

  @Test
  void textRendersPoolAndQueueAndWorkerRows() {
    when(inspection.getRunnerQueueStatus(null)).thenReturn(status(0L, 0L));

    String out = commands(detector(false)).status("text", false, 5000L, null);

    assertThat(out).contains("pool size: 2");
    assertThat(out).contains("queue depth: 7");
    assertThat(out).contains("idle workers: 1");
    assertThat(out).contains("worker-1 busy rex=rex_aaa run=run_bbb stage=investigation");
  }

  @Test
  void jsonEmitsStableSchemaWithoutAnsiEvenOnTty() {
    when(inspection.getRunnerQueueStatus(null)).thenReturn(status(3L, 2L));

    String out = commands(detector(true)).status("json", false, 5000L, null);

    assertThat(out).doesNotContain(ESC); // never color in JSON, even on a TTY
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("poolSize").asInt()).isEqualTo(2);
    assertThat(body.path("queueDepth").asLong()).isEqualTo(7L);
    assertThat(body.path("staleQueuedCount").asLong()).isEqualTo(3L);
    assertThat(body.path("staleDispatchedCount").asLong()).isEqualTo(2L);
    assertThat(body.path("workers").isArray()).isTrue();
    assertThat(body.path("workers").get(0).path("workerId").asText()).isEqualTo("worker-1");
    assertThat(body.path("workers").get(0).path("currentStage").asText())
        .isEqualTo("investigation");
  }

  @Test
  void staleCountsRenderYellowOnTtyAndPlainOffTty() {
    when(inspection.getRunnerQueueStatus(null)).thenReturn(status(4L, 1L));

    String tty = commands(detector(true)).status("text", false, 5000L, null);
    assertThat(tty).contains(ESC + "[33m4" + ESC + "[0m"); // staleQueued yellow

    when(inspection.getRunnerQueueStatus(null)).thenReturn(status(4L, 1L));
    String pipe = commands(detector(false)).status("text", false, 5000L, null);
    assertThat(pipe).doesNotContain(ESC);
    assertThat(pipe).contains("stale queued: 4");
  }

  @Test
  void batchIdScopesTheReadAndBlankIsTreatedAsNull() {
    when(inspection.getRunnerQueueStatus("bat_x")).thenReturn(status(0L, 0L));
    String out = commands(detector(false)).status("text", false, 5000L, "bat_x");
    assertThat(out).contains("pool size: 2");

    when(inspection.getRunnerQueueStatus(null)).thenReturn(status(0L, 0L));
    String blank = commands(detector(false)).status("text", false, 5000L, "   ");
    assertThat(blank).contains("pool size: 2");
  }

  @Test
  void watchRefreshesUntilGateStopsAndDoesNotSleepForever() throws Exception {
    when(inspection.getRunnerQueueStatus(null)).thenReturn(status(0L, 0L));
    // gate returns true once (→ a 2nd refresh) then false; sleeper counts calls (no real sleep).
    AtomicInteger gateCalls = new AtomicInteger();
    AtomicInteger sleeps = new AtomicInteger();
    BooleanSupplier gate = () -> gateCalls.getAndIncrement() < 1;
    WorkerCommands.Sleeper sleeper = ms -> sleeps.incrementAndGet();

    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream original = System.out;
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      String result = commands(detector(false), gate, sleeper).status("text", true, 1L, null);
      assertThat(result).isEmpty(); // watch returns empty; output went to stdout
    } finally {
      System.setOut(original);
    }

    String printed = captured.toString(StandardCharsets.UTF_8);
    // Two refreshes printed (gate allowed one extra loop), one sleep between them.
    assertThat(printed.split("pool size: 2", -1).length - 1).isEqualTo(2);
    assertThat(sleeps.get()).isEqualTo(1);
  }

  private static JsonNode readJson(String json) {
    try {
      return new ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
