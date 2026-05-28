package org.dradgo.adapters.runner.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * docker-java implementation of {@link DockerEngineGateway}. The {@code com.github.dockerjava.*}
 * surface is confined to this class (story 3.1 trap T8) — the rest of the adapter slice only sees
 * {@link CreateContainerSpec} / {@link ContainerState} records.
 */
public class DefaultDockerEngineGateway implements DockerEngineGateway {

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

    return new ContainerState(status, exitCode, networkMode, binds, labels);
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
    client.removeContainerCmd(containerId).withForce(force).exec();
    log.info("docker rm containerId={} force={}", containerId, force);
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
