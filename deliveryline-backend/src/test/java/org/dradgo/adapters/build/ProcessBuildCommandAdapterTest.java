package org.dradgo.adapters.build;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.dradgo.application.runner.workspace.spi.BuildCommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Story 3h-1 (Task 9, AC1/AC7) — {@link ProcessBuildCommandAdapter} boundary behavior + logging
 * pins. The executor-failure path (a bogus shell that cannot start) is deterministic on every OS
 * and proves the non-throwing failure contract + the ERROR boundary log; the real-command cases run
 * only where a POSIX {@code sh} is reliably present (Linux CI).
 */
class ProcessBuildCommandAdapterTest {

  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void attachAppender() {
    logAppender = new ListAppender<>();
    logAppender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(ProcessBuildCommandAdapter.class);
    logger.setLevel(Level.DEBUG);
    logger.addAppender(logAppender);
  }

  private List<String> logMessages() {
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
  }

  @Test
  void executorFailureIsReportedAsNonZeroExitAndLoggedAtError(@TempDir Path repoDir) {
    // A shell that cannot be started must NOT throw — it returns the executor-failure exit code so
    // the caller's bounded loop still applies, and it logs the boundary at ERROR.
    ProcessBuildCommandAdapter adapter =
        new ProcessBuildCommandAdapter("definitely-not-a-real-shell-xyz");

    BuildCommandPort.BuildResult result = adapter.run(repoDir, "echo hello", Duration.ofSeconds(5));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.exitCode()).isEqualTo(BuildCommandPort.EXECUTOR_FAILURE_EXIT_CODE);
    assertThat(logMessages()).anyMatch(m -> m.contains("build command executor failure"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void successfulCommandReturnsZeroAndLogsStartAndExit(@TempDir Path repoDir) {
    ProcessBuildCommandAdapter adapter = new ProcessBuildCommandAdapter();

    BuildCommandPort.BuildResult result = adapter.run(repoDir, "printf ok", Duration.ofSeconds(30));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("ok");
    assertThat(logMessages()).anyMatch(m -> m.contains("build command start"));
    assertThat(logMessages())
        .anyMatch(m -> m.contains("build command exit") && m.contains("exitCode=0"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void nonZeroCommandReturnsNonZeroAndLogsExit(@TempDir Path repoDir) {
    ProcessBuildCommandAdapter adapter = new ProcessBuildCommandAdapter();

    BuildCommandPort.BuildResult result = adapter.run(repoDir, "exit 7", Duration.ofSeconds(30));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.exitCode()).isEqualTo(7);
    assertThat(logMessages())
        .anyMatch(m -> m.contains("build command exit") && m.contains("exitCode=7"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void subSecondTimeoutIsNotTruncatedToZeroSeconds(@TempDir Path repoDir) {
    // Regression: a fractional timeout used to pass through Duration.toSeconds() → 0 → waitFor(0)
    // returned immediately and force-killed the process, reporting EVERY build as timed out. With
    // millisecond resolution a 300ms budget comfortably completes `printf ok` → exit 0.
    ProcessBuildCommandAdapter adapter = new ProcessBuildCommandAdapter();

    BuildCommandPort.BuildResult result = adapter.run(repoDir, "printf ok", Duration.ofMillis(300));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("ok");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void oversizedOutputIsBoundedAndAnnotatedTruncated(@TempDir Path repoDir) {
    // Regression: an unbounded readAllBytes() would materialize the whole stream and could OOM. The
    // drain now buffers at most MAX_CAPTURED_BYTES (2 MiB) while still reading to EOF, so a ~3 MiB
    // emission is captured bounded and flagged truncated rather than held whole in heap.
    ProcessBuildCommandAdapter adapter = new ProcessBuildCommandAdapter();

    BuildCommandPort.BuildResult result =
        adapter.run(repoDir, "head -c 3145728 /dev/zero | tr '\\0' a", Duration.ofSeconds(30));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.stdout()).contains("[output truncated at");
    // 2 MiB cap + the short truncation annotation — well under the 3 MiB the command emitted.
    assertThat(result.stdout().length()).isLessThan((2 * 1024 * 1024) + 100);
  }
}
