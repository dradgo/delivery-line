package org.dradgo.adapters.runner.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
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
    try (CreateContainerCmd cmd = client.createContainerCmd(spec.image())) {
      cmd.withHostConfig(hostConfig).withLabels(spec.labels());
      CreateContainerResponse response = cmd.exec();
      log.info(
          "docker create image={} containerId={} networkMode={} bindCount={}",
          DockerLogSanitizer.redactImageTag(spec.image()),
          response.getId(),
          spec.networkMode(),
          spec.binds().size());
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
