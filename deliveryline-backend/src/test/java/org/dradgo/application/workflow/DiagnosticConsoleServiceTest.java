package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.runner.spi.RunnerConsoleStreamPort;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3d-6 (FR68, AC2/AC3/AC7/AC8) — plain unit coverage of {@link DiagnosticConsoleService}: the
 * LIVE-vs-not-live decision (DD-3, no finished fallback), BEST-EFFORT live redaction (a
 * deliberately-leaky chunk is redacted in the streamed output), the governed {@code
 * console.opened}/{@code console.closed} session events (AC3) carrying ONLY allow-listed detail
 * keys (DD-4), the once-guarded close (container-exit vs caller-close), and the no-secret-in-logs
 * sweep. Uses the real story-1.10 redaction stack; no Spring, no DB, no Docker.
 */
class DiagnosticConsoleServiceTest {

  private static final String RUN = "run_console0000001";
  private static final String REX = "rex_console0000001";
  // A deliberately-leaky chunk: the real classifier redacts the bearer token to a placeholder.
  private static final String LEAKY_CHUNK =
      "Authorization: Bearer ghp_1234567890abcdef1234567890abcdef1234";

  private final FakeConsoleStreamPort consolePort = new FakeConsoleStreamPort();
  private final RunnerExecutionRecordPort recordPort = mock(RunnerExecutionRecordPort.class);
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final SpyEventPort eventPort = new SpyEventPort();

  private final DiagnosticConsoleService service =
      new DiagnosticConsoleService(
          consolePort,
          recordPort,
          new RedactionPolicyService(new DataClassificationService()),
          eventPort,
          inspection);

  private final CapturingSink sink = new CapturingSink();

  @Test
  void liveAttachRedactsLeakyChunkBestEffortAndAppendsConsoleOpened() throws Exception {
    liveExecution();

    AutoCloseable handle = service.openConsole(RUN, "workflow_owner", sink);

    assertThat(sink.statusPhases).contains("live");
    // console.opened appended exactly once, carrying ONLY allow-listed detail keys (DD-4).
    assertThat(eventPort.events)
        .extracting(e -> e.type)
        .containsExactly(WorkflowEventType.CONSOLE_OPENED);
    assertThat(eventPort.events.get(0).details.keySet())
        .containsExactlyInAnyOrder(
            WorkflowEventDetailKeys.RUNNER_EXECUTION_ID, WorkflowEventDetailKeys.WORKFLOW_RUN_ID);

    // Drive a leaky chunk through the captured attach callback (simulating the container).
    consolePort.emit("stdout", LEAKY_CHUNK);
    assertThat(sink.chunks).hasSize(1);
    CapturingSink.Chunk chunk = sink.chunks.get(0);
    assertThat(chunk.stream()).isEqualTo("stdout");
    assertThat(chunk.text()).contains("[REDACTED_").doesNotContain("ghp_");
    assertThat(chunk.seq()).isZero();

    handle.close();
    assertThat(consolePort.closed).isTrue();
    // console.closed appended on the caller-close path.
    assertThat(eventPort.events).extracting(e -> e.type).contains(WorkflowEventType.CONSOLE_CLOSED);
  }

  @Test
  void terminalExecutionIsRejectedConsoleNotLiveWithNoAttachAndNoEvent() {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.of(REX));
    when(recordPort.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(RunnerExecutionStatus.COMPLETED)));

    service.openConsole(RUN, "workflow_owner", sink);

    assertThat(sink.errorReason).isEqualTo("console-not-live");
    assertThat(sink.endReason).isEqualTo("not-live");
    // DD-3 / Trap T4 — no attach engaged, no console.opened appended for a rejected attach.
    assertThat(consolePort.attachInvocations).isZero();
    assertThat(eventPort.events).isEmpty();
  }

  @Test
  void absentRunnerExecutionIsRejectedConsoleNotLive() {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.empty());

    service.openConsole(RUN, "workflow_owner", sink);

    assertThat(sink.errorReason).isEqualTo("console-not-live");
    assertThat(sink.endReason).isEqualTo("not-live");
    assertThat(consolePort.attachInvocations).isZero();
    assertThat(eventPort.events).isEmpty();
  }

  @Test
  void liveStatusButNoContainerAtAttachIsRejectedConsoleNotLive() {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.of(REX));
    when(recordPort.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(RunnerExecutionStatus.RUNNING)));
    consolePort.live = false; // status live, but the container is already gone

    service.openConsole(RUN, "workflow_owner", sink);

    assertThat(sink.errorReason).isEqualTo("console-not-live");
    assertThat(sink.endReason).isEqualTo("not-live");
    // The attach was engaged but reported not-live (no surviving container); NO console.opened is
    // appended for the rejected attach (the returned not-live subscription's close() is a no-op).
    assertThat(consolePort.attachInvocations).isEqualTo(1);
    assertThat(eventPort.events).isEmpty();
  }

  @Test
  void containerExitAppendsConsoleClosedExactlyOnceAcrossExitAndCallerClose() throws Exception {
    liveExecution();

    AutoCloseable handle = service.openConsole(RUN, "workflow_owner", sink);
    consolePort.completeStream(); // container exits → onEnd fires
    assertThat(sink.endReason).isEqualTo("container-exited");

    handle.close(); // caller close must NOT double-append console.closed (once-guard)

    long closedCount =
        eventPort.events.stream().filter(e -> e.type == WorkflowEventType.CONSOLE_CLOSED).count();
    assertThat(closedCount).isEqualTo(1);
  }

  @Test
  void openEventAppendFailureReleasesTheAttachAndSurfacesFailureWithoutLeaking() throws Exception {
    liveExecution();
    eventPort.failOnType = WorkflowEventType.CONSOLE_OPENED; // the console.opened append throws

    AutoCloseable handle = service.openConsole(RUN, "workflow_owner", sink);

    // Trap T3 — the live subscription is released here (the controller's catch unwinds before
    // gate.install, so an unreleased attach would leak its docker thread).
    assertThat(consolePort.closed).isTrue();
    // The failure surfaces via the sink — the service contract is no-throw, best-effort.
    assertThat(sink.errorReason).isEqualTo("console-failed");
    assertThat(sink.endReason).isEqualTo("console-failed");
    // No live phase announced, and NO orphan console.closed for a session that never opened
    // (nothing was persisted — the append threw before recording console.opened).
    assertThat(sink.statusPhases).doesNotContain("live");
    assertThat(eventPort.events).isEmpty();

    handle.close(); // returned no-op handle: idempotent, appends nothing further
    assertThat(eventPort.events).isEmpty();
  }

  @Test
  void containerExitDuringAttachSetupAppendsNoOrphanConsoleOpened() {
    liveExecution();
    consolePort.exitDuringAttach = true; // onEnd fires synchronously inside attachConsole

    service.openConsole(RUN, "workflow_owner", sink);

    // The container exited before the open could be recorded: only console.closed is appended,
    // never a console.opened for an already-ended session (AC3 open/close ordering preserved).
    assertThat(eventPort.events)
        .extracting(e -> e.type)
        .containsExactly(WorkflowEventType.CONSOLE_CLOSED);
    assertThat(sink.statusPhases).doesNotContain("live");
    assertThat(sink.endReason).isEqualTo("container-exited");
    assertThat(consolePort.closed).isTrue();
  }

  @Test
  void logsCarryRunIdentityButNeverStreamedContentOrSecrets() throws Exception {
    liveExecution();

    ch.qos.logback.classic.Logger serviceLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DiagnosticConsoleService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
    try {
      AutoCloseable handle = service.openConsole(RUN, "workflow_owner", sink);
      consolePort.emit("stdout", LEAKY_CHUNK);
      consolePort.completeStream();
      handle.close();
    } finally {
      serviceLogger.detachAppender(appender);
    }

    List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    // AC7 — the lifecycle logs carry the run identity for correlation...
    assertThat(messages).anyMatch(message -> message.contains(RUN));
    assertThat(messages).anyMatch(message -> message.contains(REX));
    // ...but NEVER the streamed content or any secret value (adversarial no-secret sweep).
    assertThat(messages).noneMatch(message -> message.contains("ghp_"));
    assertThat(messages).noneMatch(message -> message.contains("Authorization: Bearer"));
  }

  private void liveExecution() {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.of(REX));
    when(recordPort.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(RunnerExecutionStatus.RUNNING)));
  }

  private static RunnerExecutionSnapshot snapshot(RunnerExecutionStatus status) {
    return new RunnerExecutionSnapshot(
        REX,
        RUN,
        RunnerStage.EXECUTION,
        status,
        1,
        OffsetDateTime.parse("2026-06-22T00:00:00Z"),
        null,
        null,
        status.value().equals("running") ? null : OffsetDateTime.parse("2026-06-22T00:01:00Z"),
        OffsetDateTime.parse("2026-06-22T00:00:00Z"),
        null);
  }

  // ---- Fakes -------------------------------------------------------------------

  private static final class FakeConsoleStreamPort implements RunnerConsoleStreamPort {
    boolean live = true;
    boolean exitDuringAttach;
    int attachInvocations;
    boolean closed;
    private RawConsoleSink capturedSink;
    private Runnable capturedOnEnd;

    @Override
    public ConsoleSubscription attachConsole(String rex, RawConsoleSink onChunk, Runnable onEnd) {
      attachInvocations++;
      if (!live) {
        return ConsoleSubscription.notLive();
      }
      this.capturedSink = onChunk;
      this.capturedOnEnd = onEnd;
      if (exitDuringAttach) {
        // Simulate the container exiting in the window before attachConsole returns — the onEnd
        // callback fires on the docker thread and wins the close latch.
        onEnd.run();
      }
      return new ConsoleSubscription() {
        @Override
        public boolean isLive() {
          return true;
        }

        @Override
        public void close() {
          closed = true;
        }
      };
    }

    void emit(String stream, String rawChunk) {
      capturedSink.accept(stream, rawChunk);
    }

    void completeStream() {
      capturedOnEnd.run();
    }
  }

  private static final class SpyEventPort implements RunnerExecutionEventPort {
    record AppendedEvent(WorkflowEventType type, String reason, Map<String, Object> details) {}

    final List<AppendedEvent> events = new ArrayList<>();
    WorkflowEventType failOnType; // when set, append(...) of this type throws (event-store failure)

    @Override
    public String append(
        String workflowRunPublicId,
        WorkflowEventType eventType,
        ActorContext actor,
        String reason,
        FailureCategory failureCategory,
        OffsetDateTime createdAt,
        Map<String, Object> details) {
      if (eventType == failOnType) {
        throw new RuntimeException("simulated event-store failure");
      }
      events.add(new AppendedEvent(eventType, reason, Map.copyOf(details)));
      return "evt_console0000001";
    }
  }

  private static final class CapturingSink implements ConsoleStreamSink {
    record Chunk(String stream, String text, long seq) {}

    final List<Chunk> chunks = new ArrayList<>();
    final List<String> statusPhases = new ArrayList<>();
    String endReason;
    String errorReason;

    @Override
    public void onChunk(String stream, String redactedChunk, long seq) {
      chunks.add(new Chunk(stream, redactedChunk, seq));
    }

    @Override
    public void onStatus(String phase, String runnerExecutionId) {
      statusPhases.add(phase);
    }

    @Override
    public void onEnd(String reason) {
      endReason = reason;
    }

    @Override
    public void onError(String reason) {
      errorReason = reason;
    }
  }
}
