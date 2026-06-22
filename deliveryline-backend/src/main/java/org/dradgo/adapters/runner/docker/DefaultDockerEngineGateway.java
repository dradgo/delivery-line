package org.dradgo.adapters.runner.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.AttachContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.RunnerConsoleStreamPort;
import org.dradgo.application.runner.spi.RunnerLogStreamPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * docker-java implementation of {@link DockerEngineGateway}. The {@code com.github.dockerjava.*}
 * surface is confined to this class (story 3.1 trap T8) — the rest of the adapter slice only sees
 * {@link CreateContainerSpec} / {@link ContainerState} records.
 */
public class DefaultDockerEngineGateway implements DockerEngineGateway, DockerHostPort {

  private static final Logger log = LoggerFactory.getLogger(DefaultDockerEngineGateway.class);
  private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^([A-Za-z]):[/\\\\](.*)$");
  // Story 3d-5 OQ-3 — seed the live follow with a small recent backlog so the viewer shows context
  // on open rather than only lines emitted after subscribe. Small bound for a single local
  // operator.
  private static final String LIVE_FOLLOW_TAIL_LINES = "200";

  private final DockerClient client;

  public DefaultDockerEngineGateway(DockerClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public String createContainer(CreateContainerSpec spec) {
    Objects.requireNonNull(spec, "spec");
    List<Bind> binds = new ArrayList<>(spec.binds().size());
    for (CreateContainerSpec.BindMount mount : spec.binds()) {
      Bind bind =
          new Bind(
              formatHostPathForDocker(mount.hostPath()),
              new Volume(mount.containerPath()),
              mount.readOnly()
                  ? com.github.dockerjava.api.model.AccessMode.ro
                  : com.github.dockerjava.api.model.AccessMode.rw);
      binds.add(bind);
    }
    HostConfig hostConfig =
        HostConfig.newHostConfig().withBinds(binds).withNetworkMode(spec.networkMode());
    // Apply configured Docker security options (e.g. "seccomp=unconfined") so a real Codex
    // read-only stage's bubblewrap sandbox can create a user namespace (Docker's default seccomp
    // profile blocks it). Empty list → omit the call, preserving the locked-down default for
    // mock/contract runs.
    if (!spec.securityOpts().isEmpty()) {
      hostConfig.withSecurityOpts(List.copyOf(spec.securityOpts()));
    }
    try (CreateContainerCmd cmd = client.createContainerCmd(spec.image())) {
      cmd.withHostConfig(hostConfig).withLabels(spec.labels());
      // Story 3.5 AC3 + Trap T8: apply env via docker-java withEnv (NOT the docker CLI), so secret
      // values land in the container's Config.Env and never appear in any process argv / docker ps
      // command column. Empty map → omit the call entirely.
      Map<String, String> environment = spec.environment();
      if (!environment.isEmpty()) {
        List<String> envPairs = new ArrayList<>(environment.size());
        for (Map.Entry<String, String> entry : environment.entrySet()) {
          envPairs.add(entry.getKey() + "=" + entry.getValue());
        }
        cmd.withEnv(envPairs);
      }
      CreateContainerResponse response = cmd.exec();
      // Trap T5: log the env-var COUNT only — never env names, never values.
      log.info(
          "docker create image={} containerId={} networkMode={} bindCount={} envVarCount={}",
          DockerLogSanitizer.redactImageTag(spec.image()),
          response.getId(),
          spec.networkMode(),
          spec.binds().size(),
          environment.size());
      return response.getId();
    }
  }

  @Override
  public void startContainer(String containerId) {
    Objects.requireNonNull(containerId, "containerId");
    client.startContainerCmd(containerId).exec();
    log.info("docker start containerId={}", containerId);
  }

  @Override
  public ContainerState inspectContainer(String containerId) {
    Objects.requireNonNull(containerId, "containerId");
    InspectContainerResponse response = client.inspectContainerCmd(containerId).exec();
    InspectContainerResponse.ContainerState state = response.getState();
    String status = state != null && state.getStatus() != null ? state.getStatus() : "unknown";
    Integer exitCode =
        state != null
            ? state.getExitCodeLong() == null ? null : state.getExitCodeLong().intValue()
            : null;
    HostConfig hostConfig = response.getHostConfig();
    String networkMode =
        hostConfig != null && hostConfig.getNetworkMode() != null
            ? hostConfig.getNetworkMode()
            : "default";

    List<CreateContainerSpec.BindMount> binds = new ArrayList<>();
    if (hostConfig != null && hostConfig.getBinds() != null) {
      for (Bind bind : hostConfig.getBinds()) {
        boolean readOnly = bind.getAccessMode() == com.github.dockerjava.api.model.AccessMode.ro;
        binds.add(
            new CreateContainerSpec.BindMount(
                java.nio.file.Path.of(bind.getPath()), bind.getVolume().getPath(), readOnly));
      }
    }

    Map<String, String> labels = new LinkedHashMap<>();
    if (response.getConfig() != null && response.getConfig().getLabels() != null) {
      labels.putAll(response.getConfig().getLabels());
    }

    OffsetDateTime startedAt = state != null ? parseIso8601(state.getStartedAt()) : null;

    return new ContainerState(status, exitCode, networkMode, binds, labels, startedAt);
  }

  @Override
  public void stopContainer(String containerId, Duration graceful) {
    Objects.requireNonNull(containerId, "containerId");
    Objects.requireNonNull(graceful, "graceful");
    try {
      client.stopContainerCmd(containerId).withTimeout((int) graceful.toSeconds()).exec();
      log.info("docker stop containerId={} graceful={}", containerId, graceful);
    } catch (com.github.dockerjava.api.exception.NotModifiedException alreadyStopped) {
      log.info("docker stop containerId={} reason=already_stopped (treated as no-op)", containerId);
    }
  }

  @Override
  public void removeContainer(String containerId, boolean force) {
    Objects.requireNonNull(containerId, "containerId");
    try {
      client.removeContainerCmd(containerId).withForce(force).exec();
      log.info("docker rm containerId={} force={}", containerId, force);
    } catch (NotFoundException missing) {
      log.info("docker rm containerId={} reason=not_found (treated as no-op)", containerId);
    }
  }

  @Override
  public void killContainer(String containerId) {
    Objects.requireNonNull(containerId, "containerId");
    try {
      client.killContainerCmd(containerId).exec();
      log.info("docker kill containerId={}", containerId);
    } catch (com.github.dockerjava.api.exception.NotModifiedException alreadyStopped) {
      log.info("docker kill containerId={} reason=already_stopped (treated as no-op)", containerId);
    } catch (NotFoundException missing) {
      log.info("docker kill containerId={} reason=not_found (treated as no-op)", containerId);
    }
  }

  @Override
  public Optional<String> findContainerIdByRunnerExecutionId(String runnerExecutionId) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    Map<String, String> labelFilter = Map.of("deliveryline.runnerExecutionId", runnerExecutionId);
    List<Container> matches =
        client.listContainersCmd().withShowAll(true).withLabelFilter(labelFilter).exec();
    if (matches == null || matches.isEmpty()) {
      log.info("docker ps -a --filter runnerExecutionId={} matches=0", runnerExecutionId);
      return Optional.empty();
    }
    // Order: a still-running container wins first, then newest-created. With multiple matches for
    // one rex (recovery-after-retry race + leaked containers from prior crashes), preferring the
    // running container avoids letting a newer-but-exited retry container shadow the live one (a
    // pure newest-created sort would mis-rank that case). null getState()/getCreated() sort last.
    matches.sort(
        Comparator.comparing((Container c) -> "running".equalsIgnoreCase(c.getState()))
            .thenComparing(DefaultDockerEngineGateway::safeCreated)
            .reversed());
    log.info(
        "docker ps -a --filter runnerExecutionId={} matches={} chosenContainerId={}",
        runnerExecutionId,
        matches.size(),
        matches.get(0).getId());
    return Optional.of(matches.get(0).getId());
  }

  @Override
  public AutoCloseable followContainerLogs(
      String containerId, RunnerLogStreamPort.RawLogLineSink onLine, Runnable onEnd) {
    Objects.requireNonNull(containerId, "containerId");
    Objects.requireNonNull(onLine, "onLine");
    Objects.requireNonNull(onEnd, "onEnd");
    LineBuffer stdoutBuffer = new LineBuffer("stdout", onLine);
    LineBuffer stderrBuffer = new LineBuffer("stderr", onLine);
    LogContainerCmd cmd =
        client
            .logContainerCmd(containerId)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .withTimestamps(false)
            // OQ-3 — seed with a small recent backlog so the viewer shows context on open.
            .withTail(Integer.parseInt(LIVE_FOLLOW_TAIL_LINES));
    ResultCallback.Adapter<Frame> callback =
        new ResultCallback.Adapter<>() {
          @Override
          public void onNext(Frame frame) {
            if (frame == null || frame.getPayload() == null) {
              return;
            }
            byte[] payload = frame.getPayload();
            if (frame.getStreamType() == StreamType.STDERR) {
              stderrBuffer.append(payload);
            } else {
              stdoutBuffer.append(payload);
            }
          }

          @Override
          public void onComplete() {
            stdoutBuffer.flush();
            stderrBuffer.flush();
            onEnd.run();
          }

          @Override
          public void onError(Throwable throwable) {
            // Best-effort (Trap T3): a follow error degrades to "stream ended" — NEVER propagate
            // into the SSE caller thread.
            log.warn(
                "docker logs --follow containerId={} error cause={}",
                containerId,
                throwable.toString());
            stdoutBuffer.flush();
            stderrBuffer.flush();
            onEnd.run();
          }
        };
    cmd.exec(callback);
    log.info("docker logs --follow start containerId={}", containerId);
    return () -> {
      try {
        callback.close();
      } catch (IOException | RuntimeException closeFailure) {
        log.warn(
            "docker logs --follow close failed containerId={} cause={}",
            containerId,
            closeFailure.toString());
      } finally {
        cmd.close();
        log.info("docker logs --follow stop containerId={}", containerId);
      }
    };
  }

  @Override
  public AutoCloseable attachContainerConsole(
      String containerId, RunnerConsoleStreamPort.RawConsoleSink onChunk, Runnable onEnd) {
    Objects.requireNonNull(containerId, "containerId");
    Objects.requireNonNull(onChunk, "onChunk");
    Objects.requireNonNull(onEnd, "onEnd");
    // Reuse the byte-buffered UTF-8 line splitter so a multi-byte codepoint split across two
    // attach frames is decoded once at the boundary (no mojibake). The sink shape is identical to
    // the log path's RawLogLineSink, so adapt by stream name.
    LineBuffer stdoutBuffer = new LineBuffer("stdout", onChunk::accept);
    LineBuffer stderrBuffer = new LineBuffer("stderr", onChunk::accept);
    // Trap T6 / DD-1: open the attach for OUTPUT ONLY — withStdOut/withStdErr but NEVER withStdIn.
    // No input channel is wired into the container; this is the provable read-only guarantee at the
    // docker layer. withFollowStream(true) keeps the stream live until the container exits / close.
    AttachContainerCmd cmd =
        client
            .attachContainerCmd(containerId)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true);
    ResultCallback.Adapter<Frame> callback =
        new ResultCallback.Adapter<>() {
          @Override
          public void onNext(Frame frame) {
            if (frame == null || frame.getPayload() == null) {
              return;
            }
            byte[] payload = frame.getPayload();
            if (frame.getStreamType() == StreamType.STDERR) {
              stderrBuffer.append(payload);
            } else {
              stdoutBuffer.append(payload);
            }
          }

          @Override
          public void onComplete() {
            stdoutBuffer.flush();
            stderrBuffer.flush();
            onEnd.run();
          }

          @Override
          public void onError(Throwable throwable) {
            // Best-effort (Trap T3): an attach error degrades to "stream ended" — NEVER propagate
            // into the SSE caller thread.
            log.warn(
                "docker attach (read-only) containerId={} error cause={}",
                containerId,
                throwable.toString());
            stdoutBuffer.flush();
            stderrBuffer.flush();
            onEnd.run();
          }
        };
    cmd.exec(callback);
    log.info("docker attach (read-only, no-stdin) start containerId={}", containerId);
    return () -> {
      try {
        callback.close();
      } catch (IOException | RuntimeException closeFailure) {
        log.warn(
            "docker attach (read-only) close failed containerId={} cause={}",
            containerId,
            closeFailure.toString());
      } finally {
        cmd.close();
        log.info("docker attach (read-only) stop containerId={}", containerId);
      }
    };
  }

  /**
   * Splits a frame byte-stream into newline-terminated lines, buffering trailing partial BYTES
   * across frames so (a) a line straddling two frames is emitted once, intact, and (b) a multi-byte
   * UTF-8 codepoint split across two frames is decoded once at the line boundary rather than
   * per-frame (decoding each frame independently would emit U+FFFD for the straddling char). {@link
   * #flush()} emits any final unterminated line on stream completion.
   */
  private static final class LineBuffer {

    private static final byte LF = (byte) '\n';
    private static final byte CR = (byte) '\r';

    private final String stream;
    private final RunnerLogStreamPort.RawLogLineSink sink;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    LineBuffer(String stream, RunnerLogStreamPort.RawLogLineSink sink) {
      this.stream = stream;
      this.sink = sink;
    }

    void append(byte[] payload) {
      pending.write(payload, 0, payload.length);
      byte[] buffered = pending.toByteArray();
      int lineStart = 0;
      int consumed = 0;
      for (int i = 0; i < buffered.length; i++) {
        if (buffered[i] == LF) {
          int end = i;
          if (end > lineStart && buffered[end - 1] == CR) {
            end--;
          }
          sink.accept(
              stream, new String(buffered, lineStart, end - lineStart, StandardCharsets.UTF_8));
          lineStart = i + 1;
          consumed = lineStart;
        }
      }
      pending.reset();
      if (consumed < buffered.length) {
        pending.write(buffered, consumed, buffered.length - consumed);
      }
    }

    void flush() {
      byte[] buffered = pending.toByteArray();
      pending.reset();
      if (buffered.length == 0) {
        return;
      }
      int end = buffered.length;
      if (end > 0 && buffered[end - 1] == CR) {
        end--;
      }
      sink.accept(stream, new String(buffered, 0, end, StandardCharsets.UTF_8));
    }
  }

  @Override
  public List<DockerHostPort.DanglingContainerInfo> listContainersByLabel(
      String labelKey, String labelValuePrefix) {
    Objects.requireNonNull(labelKey, "labelKey");
    // Docker engine supports `--filter label=<key>` for presence-only matches. Value-prefix
    // filtering is applied client-side after the engine returns the candidate set, since the
    // engine does not natively support prefix filtering on label values.
    List<Container> containers =
        client.listContainersCmd().withShowAll(true).withLabelFilter(List.of(labelKey)).exec();
    if (containers == null || containers.isEmpty()) {
      log.info("docker ps -a --filter label={} matches=0", labelKey);
      return List.of();
    }
    List<DockerHostPort.DanglingContainerInfo> out = new ArrayList<>(containers.size());
    for (Container container : containers) {
      Map<String, String> labels = container.getLabels();
      String rex = labels == null ? null : labels.get(labelKey);
      if (rex == null) {
        continue;
      }
      if (labelValuePrefix != null
          && !labelValuePrefix.isEmpty()
          && !rex.startsWith(labelValuePrefix)) {
        continue;
      }
      // Story 3.2a AC4 (b): normalize to the engine STATE (running|exited|created|paused|dead|
      // restarting), not the human getStatus() ("Up 3 minutes"). The cleanup sweep matches on this
      // normalized state to decide stop-before-rm, so a human status string would break the match.
      String rawState = container.getState();
      String status =
          (rawState == null || rawState.isBlank())
              ? "unknown"
              : rawState.toLowerCase(java.util.Locale.ROOT);
      OffsetDateTime createdAt = null;
      if (container.getCreated() != null) {
        createdAt =
            OffsetDateTime.ofInstant(Instant.ofEpochSecond(container.getCreated()), ZoneOffset.UTC);
      }
      out.add(new DockerHostPort.DanglingContainerInfo(container.getId(), rex, status, createdAt));
    }
    log.info(
        "docker ps -a --filter label={} prefix={} matches={}",
        labelKey,
        labelValuePrefix,
        out.size());
    return out;
  }

  private static long safeCreated(Container container) {
    Long created = container.getCreated();
    return created == null ? 0L : created;
  }

  private static OffsetDateTime parseIso8601(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    // Docker engine returns the StartedAt sentinel "0001-01-01T00:00:00Z" before the container has
    // started. Treat that as null so callers do not see an artificially-early activity timestamp.
    if (raw.startsWith("0001-01-01")) {
      return null;
    }
    try {
      return OffsetDateTime.parse(raw).withOffsetSameInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  static String formatHostPathForDocker(java.nio.file.Path hostPath) {
    String normalized = hostPath.toAbsolutePath().normalize().toString();
    Matcher matcher = WINDOWS_DRIVE_PATH.matcher(normalized);
    if (!matcher.matches()) {
      return normalized;
    }
    String drive = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
    String rest = matcher.group(2).replace('\\', '/');
    return "/" + drive + "/" + rest;
  }
}
