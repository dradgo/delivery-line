package org.dradgo.adapters.runner.docker;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Project-owned, Docker-free snapshot of a container's runtime state, returned by {@link
 * DockerEngineGateway#inspectContainer(String)}.
 *
 * <p>Story 3.1 trap T8 + T9: the gateway materializes only the fields the adapter needs to classify
 * a {@code poll(...)} outcome. The {@code com.github.dockerjava.api.model.*} types stay behind the
 * gateway.
 *
 * <p>{@link #status()} mirrors Docker's container status string ({@code created}, {@code running},
 * {@code paused}, {@code restarting}, {@code exited}, {@code dead}, {@code removing}). The adapter
 * switches on this string verbatim — no enum translation here so adding new statuses (e.g.,
 * experimental engine releases) does not break the boundary record.
 */
public record ContainerState(
    String status,
    Integer exitCode,
    String networkMode,
    List<CreateContainerSpec.BindMount> binds,
    Map<String, String> labels,
    OffsetDateTime startedAt,
    String healthStatus) {

  public ContainerState {
    Objects.requireNonNull(status, "status");
    binds = binds == null ? List.of() : List.copyOf(binds);
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }

  /** Story 3.2 compatibility constructor: omits the {@code healthStatus} field added later. */
  public ContainerState(
      String status,
      Integer exitCode,
      String networkMode,
      List<CreateContainerSpec.BindMount> binds,
      Map<String, String> labels,
      OffsetDateTime startedAt) {
    this(status, exitCode, networkMode, binds, labels, startedAt, null);
  }

  /** Story 3.1 compatibility constructor: omits the {@code startedAt} field added by story 3.2. */
  public ContainerState(
      String status,
      Integer exitCode,
      String networkMode,
      List<CreateContainerSpec.BindMount> binds,
      Map<String, String> labels) {
    this(status, exitCode, networkMode, binds, labels, null, null);
  }

  public boolean isExited() {
    return "exited".equals(status) || "dead".equals(status);
  }
}
