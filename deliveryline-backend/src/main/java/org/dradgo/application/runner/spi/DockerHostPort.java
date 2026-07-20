package org.dradgo.application.runner.spi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Application-facing port for the host-level Docker operations the {@link
 * org.dradgo.application.runner.RunnerWorkspaceCleanupJob} needs without crossing the
 * application→adapters boundary. The docker-java adapter implementation is registered only under
 * the {@code runners.docker} Spring profile; consumers receive an {@link java.util.Optional}-shaped
 * injection (via {@code ObjectProvider}) so the application layer compiles without docker-java on
 * the classpath.
 *
 * <p>The {@link DanglingContainerInfo} record is project-owned (no docker-java leakage). Trap T8
 * lives at the adapter boundary: the implementation extracts {@code runnerExecutionId} from
 * container labels, never from the container name.
 */
public interface DockerHostPort {

  /** Best-effort {@code docker stop} with the supplied graceful window. Idempotent. */
  void stopContainer(String containerId, Duration graceful);

  /** Best-effort {@code docker kill}. Idempotent. */
  void killContainer(String containerId);

  /** Best-effort {@code docker rm}. Idempotent — already-removed containers are no-ops. */
  void removeContainer(String containerId, boolean force);

  /**
   * Story 3.2 AC6: enumerate all containers carrying {@code labelKey} whose value begins with
   * {@code labelValuePrefix}. The cleanup job uses {@code
   * labelKey="deliveryline.runnerExecutionId"} and {@code labelValuePrefix="rex_"} to identify
   * deliveryline-owned containers.
   */
  List<DanglingContainerInfo> listContainersByLabel(String labelKey, String labelValuePrefix);

  /**
   * DinD Testcontainers Task 7 — enumerate all networks carrying {@code labelKey} (presence match).
   * The dind sweep uses {@code labelKey="deliveryline.dind"} to find orphan per-run bridge networks
   * ({@code deliveryline-net-rex_*}); the label value is the owning {@code runnerExecutionId}, used
   * to correlate the network to its run row.
   */
  List<NetworkInfo> listNetworksByLabel(String labelKey);

  /** Remove a network by name/id. Idempotent — a missing network is a no-op. */
  void removeNetwork(String name);

  /** Project-owned snapshot of a Docker container surfaced by {@link #listContainersByLabel}. */
  record DanglingContainerInfo(
      String containerId, String runnerExecutionId, String status, OffsetDateTime createdAt) {

    public DanglingContainerInfo {
      Objects.requireNonNull(containerId, "containerId");
      Objects.requireNonNull(status, "status");
    }
  }

  /**
   * Project-owned snapshot of a Docker network surfaced by {@link #listNetworksByLabel} (no
   * docker-java leakage). {@code labelValue} is the {@code deliveryline.dind} label value (the
   * owning {@code runnerExecutionId}).
   */
  record NetworkInfo(String id, String name, String labelValue) {

    public NetworkInfo {
      Objects.requireNonNull(name, "name");
    }
  }
}
