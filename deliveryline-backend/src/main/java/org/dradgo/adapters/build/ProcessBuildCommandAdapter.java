package org.dradgo.adapters.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.dradgo.application.runner.workspace.spi.BuildCommandPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 3h-1 (AC1/AC3/AC7, FR75) — {@link BuildCommandPort} adapter running the governed project's
 * build command backend-side via {@code ProcessBuilder} in the materialized host workspace. Mirrors
 * the {@code adapters.git.CliGitAdapter} process-running idiom: run the command through {@code sh
 * -c} (so the operator's build command may use shell features), drain stdout + stderr concurrently
 * on separate threads (no pipe-buffer deadlock), {@code waitFor(timeout)} + {@code destroyForcibly}
 * on overrun, and force-destroy in {@code finally}.
 *
 * <p>The adapter NEVER logs the captured bytes — only the exit code + stream lengths. Redaction of
 * the captured output is the caller's responsibility ({@code BuildStageService} routes it through
 * {@code RunnerLogCaptureService}). Its own slice package {@code adapters.build} is registered in
 * the ArchUnit adapter-layout allow-list (mirrors how {@code adapters.git} was added for story
 * 3.9).
 */
@Component
public class ProcessBuildCommandAdapter implements BuildCommandPort {

  private static final Logger log = LoggerFactory.getLogger(ProcessBuildCommandAdapter.class);

  /**
   * Upper bound on how long {@link #awaitOutput} waits for a drain future to reach EOF AFTER the
   * process has exited or been force-killed — a backstop against a detached grandchild holding the
   * pipe open, so {@code run()} always returns within {@code timeout + this grace}.
   */
  private static final long DRAIN_GRACE_SECONDS = 15;

  /**
   * Per-stream cap on captured build output. Bounds heap so a runaway/verbose build (e.g. a fast
   * print loop or dependency-download spam) that emits hundreds of MB WITHIN the timeout cannot OOM
   * the JVM before the downstream redaction/log-capture cap applies — the drain keeps reading to
   * EOF (so the child never blocks on a full pipe) but buffers at most this many bytes and marks
   * the remainder truncated.
   */
  private static final int MAX_CAPTURED_BYTES = 2 * 1024 * 1024;

  private final String shellExecutable;

  public ProcessBuildCommandAdapter() {
    this("sh");
  }

  ProcessBuildCommandAdapter(String shellExecutable) {
    this.shellExecutable =
        shellExecutable == null || shellExecutable.isBlank() ? "sh" : shellExecutable.trim();
  }

  @Override
  public BuildResult run(Path repoDir, String command, Duration timeout) {
    if (repoDir == null) {
      throw new IllegalArgumentException("repoDir must not be null");
    }
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("build command must be non-blank");
    }
    long timeoutMillis =
        (timeout == null || timeout.isZero() || timeout.isNegative())
            ? Duration.ofMinutes(10).toMillis()
            : timeout.toMillis();

    List<String> argv = List.of(shellExecutable, "-c", command);
    ProcessBuilder builder = new ProcessBuilder(argv).directory(repoDir.toFile());

    log.info(
        "build command start repoDir={} shell={} timeoutMillis={}",
        repoDir,
        shellExecutable,
        timeoutMillis);

    Process process = null;
    // Dedicated 2-thread pool so BOTH pipe drains run concurrently regardless of host core count —
    // ForkJoinPool.commonPool parallelism is max(1, cores-1) = 1 on a ≤2-core box, where two
    // supplyAsync drains would serialize and a >64 KB-on-one-stream build deadlocks (child blocks
    // on a full pipe, the un-started drain never runs) → the process would be force-killed and a
    // PASSING build misreported as timed out. Daemon threads so a stuck native readAllBytes never
    // blocks JVM shutdown; shutdownNow in finally so the pool is not leaked per build.
    ExecutorService drainPool =
        Executors.newFixedThreadPool(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "build-drain");
              thread.setDaemon(true);
              return thread;
            });
    try {
      process = builder.start();
      CompletableFuture<String> stdoutFuture = drainAsync(process.getInputStream(), drainPool);
      CompletableFuture<String> stderrFuture = drainAsync(process.getErrorStream(), drainPool);
      boolean exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        String stdout = awaitOutput(stdoutFuture);
        String stderr = awaitOutput(stderrFuture);
        log.warn(
            "build command timed out repoDir={} timeoutMillis={} stdoutLen={} stderrLen={}",
            repoDir,
            timeoutMillis,
            stdout.length(),
            stderr.length());
        return BuildResult.timedOut(stdout, stderr);
      }
      int exit = process.exitValue();
      String stdout = awaitOutput(stdoutFuture);
      String stderr = awaitOutput(stderrFuture);
      if (exit == 0) {
        log.info(
            "build command exit repoDir={} exitCode=0 stdoutLen={} stderrLen={}",
            repoDir,
            stdout.length(),
            stderr.length());
      } else {
        log.warn(
            "build command exit repoDir={} exitCode={} stdoutLen={} stderrLen={}",
            repoDir,
            exit,
            stdout.length(),
            stderr.length());
      }
      return BuildResult.of(exit, stdout, stderr);
    } catch (IOException io) {
      // Executor failure: the shell/command could not be started. Report as a non-zero exit so the
      // caller's bounded loop still applies rather than crashing the delivery tail (ERROR — this is
      // an unexpected infrastructure failure, not a normal build failure).
      log.error("build command executor failure repoDir={} cause={}", repoDir, io.getMessage());
      return BuildResult.executorFailure("build executor unavailable: " + io.getMessage());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.warn("build command interrupted repoDir={}", repoDir);
      return BuildResult.executorFailure("build interrupted");
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      drainPool.shutdownNow();
    }
  }

  private static CompletableFuture<String> drainAsync(
      InputStream stream, ExecutorService executor) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return drainBounded(stream);
          } catch (IOException error) {
            throw new CompletionException(error);
          }
        },
        executor);
  }

  /**
   * Read {@code stream} to EOF (so the child never blocks on a full pipe) while buffering at most
   * {@link #MAX_CAPTURED_BYTES} — overflow is drained-and-discarded and the returned text is
   * annotated as truncated. This bounds heap independently of build output size (the OOM guard),
   * separate from the timeout (the wall-clock guard).
   */
  private static String drainBounded(InputStream stream) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    boolean truncated = false;
    int read;
    while ((read = stream.read(chunk)) != -1) {
      int room = MAX_CAPTURED_BYTES - buffer.size();
      if (room > 0) {
        buffer.write(chunk, 0, Math.min(read, room));
      }
      if (read > room) {
        truncated = true;
      }
    }
    String captured = buffer.toString(StandardCharsets.UTF_8);
    return truncated
        ? captured + "\n[output truncated at " + MAX_CAPTURED_BYTES + " bytes]"
        : captured;
  }

  /**
   * Bound the wait on a drain future. {@code waitFor(timeout)}/{@code destroyForcibly()} only bound
   * the PROCESS; the drain threads block in {@code readAllBytes()} until EOF, which never arrives
   * if a detached grandchild inherited and still holds the stdout/stderr pipe. An unbounded {@code
   * join()} there would defeat the whole {@code build-stage.timeout} guarantee and hang the caller.
   * Wait at most {@link #DRAIN_GRACE_SECONDS} for EOF, then give up with whatever was buffered (the
   * orphaned drain thread is left to finish/leak — bounded and rare — rather than blocking {@code
   * run()}).
   */
  private static String awaitOutput(CompletableFuture<String> future) {
    try {
      return future.get(DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException drainStuck) {
      future.cancel(true);
      log.warn(
          "build command output drain exceeded {}s grace — returning partial", DRAIN_GRACE_SECONDS);
      return "";
    } catch (ExecutionException | CompletionException error) {
      return "";
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return "";
    }
  }
}
