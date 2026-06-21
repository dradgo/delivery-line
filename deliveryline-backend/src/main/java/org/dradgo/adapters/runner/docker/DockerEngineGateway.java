package org.dradgo.adapters.runner.docker;

import java.time.Duration;
import java.util.Optional;
import org.dradgo.application.runner.spi.RunnerLogStreamPort;

/**
 * Adapter-internal wrapper around the Docker client surface. The {@code DockerRunnerAdapter} never
 * touches {@code com.github.dockerjava.api.*} types directly; every Docker action is mediated by
 * this gateway, returning project-owned records.
 *
 * <p>Story 3.1:
 *
 * <ul>
 *   <li>Trap T7: {@code --network=none} is fixed at create time via {@link CreateContainerSpec#
 *       networkMode()} — implementations MUST NOT patch network after start.
 *   <li>Trap T8: implementations MUST keep Docker-java types behind this interface.
 *   <li>Trap T11: {@link #stopContainer(String, Duration)} is the only operation the adapter's
 *       {@code cancel(...)} path invokes; {@link #removeContainer(String, boolean)} is reserved for
 *       story 3.2's cleanup job.
 * </ul>
 */
public interface DockerEngineGateway {

  /** Creates a container according to {@code spec} and returns the resulting container id. */
  String createContainer(CreateContainerSpec spec);

  /** Starts a previously-created container; non-blocking. */
  void startContainer(String containerId);

  /** Inspects the container and returns a typed snapshot suitable for adapter classification. */
  ContainerState inspectContainer(String containerId);

  /** Issues {@code docker stop} with the given graceful shutdown window. */
  void stopContainer(String containerId, Duration graceful);

  /** Reserved for story 3.2's cleanup job. The 3.1 adapter MUST NOT call this from cancel. */
  void removeContainer(String containerId, boolean force);

  /**
   * Story 3.2 AC1: issues {@code docker kill} (immediate {@code SIGKILL}). Idempotent — already
   * exited / removed containers are treated as no-ops.
   */
  void killContainer(String containerId);

  /**
   * Story 3.2 AC4: probes the Docker engine for a container labeled with the given runner-execution
   * id. Uses {@code docker ps -a --filter label=deliveryline.runnerExecutionId={rex}}. Returns
   * {@link Optional#empty()} when no matching container survives on the host.
   */
  Optional<String> findContainerIdByRunnerExecutionId(String runnerExecutionId);

  /**
   * Story 3d-5 (FR65) — follow a container's stdout/stderr live ({@code docker logs --follow}). Each
   * decoded line is delivered to {@code onLine} ({@code stream} = {@code "stdout"}/{@code
   * "stderr"}); {@code onEnd} fires once when the stream completes (container exit). Returns an
   * {@link AutoCloseable} whose {@code close()} stops the follow + releases the docker callback (no
   * leaked follow threads — Trap T3). Docker-java types stay confined to the implementing gateway
   * (Trap T8); callers only see the project-owned {@link RunnerLogStreamPort.RawLogLineSink}.
   */
  AutoCloseable followContainerLogs(
      String containerId, RunnerLogStreamPort.RawLogLineSink onLine, Runnable onEnd);
}
