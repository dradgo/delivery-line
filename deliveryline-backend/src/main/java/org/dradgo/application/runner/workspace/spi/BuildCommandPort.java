package org.dradgo.application.runner.workspace.spi;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Story 3h-1 (AC1/AC3/AC7, FR75) — application-owned SPI for the host-side build command that backs
 * the pre-review build-validation stage. Implemented by {@code
 * adapters.build.ProcessBuildCommandAdapter}, which runs the governed project's configured build
 * command via {@code ProcessBuilder} in the already-materialized host workspace directory (the same
 * directory {@code RepositoryWorkspaceService.captureAndPush} operates on).
 *
 * <p>Mirrors the {@link GitCommandPort} shape (Trap T1): the port speaks only a repo {@link Path} +
 * the command string + a {@link Duration} bound and returns a domain-shaped {@link BuildResult} —
 * it NEVER exposes {@code Process}, streams, or any process-library type to the application layer.
 *
 * <p><b>Failure posture.</b> Unlike git operations, a non-zero build exit is the EXPECTED signal
 * that drives the bounded auto-fix loop — so this port does NOT throw on a failed build; it returns
 * a {@link BuildResult} carrying the non-zero {@code exitCode}. A timeout kills the process and is
 * reported as a non-zero exit ({@link #TIMEOUT_EXIT_CODE}); an executor failure (the process cannot
 * even start) is likewise reported as a non-zero exit ({@link #EXECUTOR_FAILURE_EXIT_CODE}) so the
 * caller's bounded loop still applies rather than crashing the tail.
 *
 * <p><b>Redaction.</b> The returned {@code stdout}/{@code stderr} are RAW — the caller ({@code
 * BuildStageService}) MUST route them through {@code RunnerLogCaptureService} (the story-3.6
 * redaction / secret-scan / store path) before anything is persisted or referenced, and MUST NEVER
 * log the bytes (ids + lengths only). The adapter itself never logs the captured bytes.
 */
public interface BuildCommandPort {

  /** Reported {@code exitCode} when the build process is killed for exceeding its timeout. */
  int TIMEOUT_EXIT_CODE = -1;

  /** Reported {@code exitCode} when the build process could not be started (executor failure). */
  int EXECUTOR_FAILURE_EXIT_CODE = -2;

  /**
   * Run {@code command} in {@code repoDir}, bounded by {@code timeout}. Never throws for a failed
   * or timed-out build — the non-zero {@code exitCode} is the failure signal.
   *
   * @param repoDir the materialized host workspace directory to run in (must exist)
   * @param command the governed project's build command (non-blank)
   * @param timeout the wall-clock bound; on overrun the process is force-killed and reported as
   *     {@link #TIMEOUT_EXIT_CODE}
   * @return the exit code + captured (raw) stdout/stderr
   */
  BuildResult run(Path repoDir, String command, Duration timeout);

  /**
   * Result of a build command run. {@code exitCode == 0} ⇒ success; any other value ⇒ failure (the
   * loop-driving signal). {@code stdout}/{@code stderr} are RAW captured output (redaction is the
   * caller's responsibility via {@code RunnerLogCaptureService}).
   */
  record BuildResult(int exitCode, String stdout, String stderr) {

    public BuildResult {
      stdout = stdout == null ? "" : stdout;
      stderr = stderr == null ? "" : stderr;
    }

    public boolean succeeded() {
      return exitCode == 0;
    }

    public static BuildResult of(int exitCode, String stdout, String stderr) {
      return new BuildResult(exitCode, stdout, stderr);
    }

    public static BuildResult timedOut(String stdout, String stderr) {
      return new BuildResult(TIMEOUT_EXIT_CODE, stdout, stderr);
    }

    public static BuildResult executorFailure(String message) {
      return new BuildResult(
          EXECUTOR_FAILURE_EXIT_CODE, "", Objects.requireNonNullElse(message, ""));
    }
  }
}
