package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerLogStore;
import org.dradgo.application.runner.spi.RunnerLogStreamPort;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3d-5 (FR65, AC1/AC2/AC8) — plain unit coverage of {@link StepLogStreamService}: the
 * live-vs-finished decision, BEST-EFFORT live redaction (a deliberately-leaky line is redacted in
 * the streamed output), finished-mode replay of the already-redacted persisted log, and the
 * no-persisted-write guarantee (ADR 0025 D4 / Trap T6). Uses the real story-1.10 redaction stack;
 * no Spring, no DB, no Docker.
 */
class StepLogStreamServiceTest {

  private static final String RUN = "run_capture0000001";
  private static final String REX = "rex_capture0000001";
  // A deliberately-leaky line: the real classifier redacts the bearer token to a placeholder.
  private static final String LEAKY_LINE =
      "Authorization: Bearer ghp_1234567890abcdef1234567890abcdef1234";

  private final FakeLogStreamPort streamPort = new FakeLogStreamPort();
  private final FakeLogStore logStore = new FakeLogStore();
  private final RunnerExecutionRecordPort recordPort = mock(RunnerExecutionRecordPort.class);
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);

  private final StepLogStreamService service =
      new StepLogStreamService(
          streamPort,
          logStore,
          recordPort,
          new RedactionPolicyService(new DataClassificationService()),
          inspection);

  private final CapturingSink sink = new CapturingSink();

  @Test
  void liveStreamRedactsLeakyLineBestEffortAndForwardsToSink() throws Exception {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.of(REX));
    when(recordPort.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(RunnerExecutionStatus.RUNNING)));

    AutoCloseable handle = service.streamRunnerLogs(RUN, sink);

    assertThat(sink.statusPhases).contains("live");
    // Drive a leaky line through the captured follow callback (simulating the container).
    streamPort.emit("stdout", LEAKY_LINE);
    assertThat(sink.lines).hasSize(1);
    CapturingSink.Line line = sink.lines.get(0);
    assertThat(line.stream()).isEqualTo("stdout");
    assertThat(line.text()).contains("[REDACTED_").doesNotContain("ghp_");
    assertThat(line.seq()).isZero();

    streamPort.completeStream();
    assertThat(sink.endReason).isEqualTo("container-exited");

    handle.close();
    assertThat(streamPort.closed).isTrue();
    // ADR 0025 D4 / Trap T6 — the viewer path NEVER writes to the persisted store.
    assertThat(logStore.writeCount.get()).isZero();
  }

  @Test
  void finishedExecutionReplaysPersistedRedactedLogWithoutReredactionOrWrite() {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.of(REX));
    when(recordPort.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(RunnerExecutionStatus.COMPLETED)));
    // Already-redacted content (story 3.6); MUST be replayed verbatim (Trap T4).
    logStore.redacted =
        Optional.of(
            new RedactedRunnerLog(
                "step started\n[REDACTED_AUTHORIZATION_HEADER]\n", "warning: low disk\n", false));

    service.streamRunnerLogs(RUN, sink);

    assertThat(sink.statusPhases).contains("finished");
    assertThat(sink.lines)
        .extracting(CapturingSink.Line::text)
        .containsExactly("step started", "[REDACTED_AUTHORIZATION_HEADER]", "warning: low disk");
    assertThat(sink.endReason).isEqualTo("finished-replay-complete");
    // The live follow port is never engaged for a terminal execution.
    assertThat(streamPort.followInvocations).isZero();
    assertThat(logStore.writeCount.get()).isZero();
  }

  @Test
  void noRunnerExecutionEndsGracefully() {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.empty());

    service.streamRunnerLogs(RUN, sink);

    assertThat(sink.endReason).isEqualTo("no-runner-execution");
    assertThat(sink.lines).isEmpty();
    assertThat(streamPort.followInvocations).isZero();
  }

  @Test
  void liveStatusButNoContainerFallsBackToFinishedReplay() {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.of(REX));
    when(recordPort.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(RunnerExecutionStatus.RUNNING)));
    streamPort.live = false; // status live, but the container is already gone
    logStore.redacted = Optional.of(new RedactedRunnerLog("recovered line\n", "", false));

    service.streamRunnerLogs(RUN, sink);

    assertThat(sink.statusPhases).contains("finished");
    assertThat(sink.lines).extracting(CapturingSink.Line::text).containsExactly("recovered line");
    assertThat(sink.endReason).isEqualTo("finished-replay-complete");
  }

  @Test
  void logsCarryRunIdentityButNeverStreamedContentOrSecrets() throws Exception {
    when(inspection.findLatestRunnerExecutionId(RUN)).thenReturn(Optional.of(REX));
    when(recordPort.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(RunnerExecutionStatus.RUNNING)));

    ch.qos.logback.classic.Logger serviceLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StepLogStreamService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
    try {
      AutoCloseable handle = service.streamRunnerLogs(RUN, sink);
      streamPort.emit("stdout", LEAKY_LINE);
      streamPort.completeStream();
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

  private static RunnerExecutionSnapshot snapshot(RunnerExecutionStatus status) {
    return new RunnerExecutionSnapshot(
        REX,
        RUN,
        RunnerStage.EXECUTION,
        status,
        1,
        OffsetDateTime.parse("2026-06-21T00:00:00Z"),
        null,
        null,
        status.value().equals("running") ? null : OffsetDateTime.parse("2026-06-21T00:01:00Z"),
        OffsetDateTime.parse("2026-06-21T00:00:00Z"),
        null);
  }

  // ---- Fakes -------------------------------------------------------------------

  private static final class FakeLogStreamPort implements RunnerLogStreamPort {
    boolean live = true;
    int followInvocations;
    boolean closed;
    private RawLogLineSink capturedSink;
    private Runnable capturedOnEnd;

    @Override
    public LiveLogSubscription followLiveLogs(String rex, RawLogLineSink onLine, Runnable onEnd) {
      followInvocations++;
      if (!live) {
        return LiveLogSubscription.notLive();
      }
      this.capturedSink = onLine;
      this.capturedOnEnd = onEnd;
      return new LiveLogSubscription() {
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

    void emit(String stream, String rawLine) {
      capturedSink.accept(stream, rawLine);
    }

    void completeStream() {
      capturedOnEnd.run();
    }
  }

  private static final class FakeLogStore implements RunnerLogStore {
    final AtomicInteger writeCount = new AtomicInteger();
    Optional<RedactedRunnerLog> redacted = Optional.empty();

    @Override
    public RunnerLogReference write(String rex, byte[] stdout, byte[] stderr) {
      writeCount.incrementAndGet();
      throw new AssertionError("the log viewer path must never write to the persisted store");
    }

    @Override
    public Optional<RunnerLogReference> find(String rex) {
      return Optional.empty();
    }

    @Override
    public Optional<RedactedRunnerLog> readRedacted(String rex) {
      return redacted;
    }
  }

  private static final class CapturingSink implements LogStreamSink {
    record Line(String stream, String text, long seq) {}

    final List<Line> lines = new ArrayList<>();
    final List<String> statusPhases = new ArrayList<>();
    String endReason;
    String errorReason;

    @Override
    public void onLine(String stream, String redactedLine, long seq) {
      lines.add(new Line(stream, redactedLine, seq));
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
