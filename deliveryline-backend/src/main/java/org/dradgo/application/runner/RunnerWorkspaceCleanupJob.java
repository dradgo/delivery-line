package org.dradgo.application.runner;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3.2 AC5 + AC6 — scheduled cleanup job that prunes runner workspaces past their retention
 * horizon, preserves orphan workspace directories that lack a corresponding {@code
 * runner_executions} row (Trap T7), and removes dangling containers whose row is missing or no
 * longer active (AC6).
 *
 * <p>Three sweeps run in order per {@link #runCleanup()}: workspaces, orphan dirs, dangling
 * containers (Trap T9). Each sweep is best-effort and per-row guarded so a single failure does not
 * stop the rest of the batch.
 *
 * <p>Lives in {@code application.runner} so the broker can call into it from tests without crossing
 * package boundaries. The Docker engine surface is reached via the {@link DockerHostPort}
 * application-side port (an empty {@link ObjectProvider} under the {@code runners.mock} profile
 * means the dangling-container sweep is a no-op).
 */
public class RunnerWorkspaceCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(RunnerWorkspaceCleanupJob.class);

  private static final String LABEL_KEY = "deliveryline.runnerExecutionId";
  private static final String LABEL_VALUE_PREFIX = "rex_";

  private final RunnerExecutionRecordPort recordPort;
  private final RunnerWorkspaceStore workspaceStore;
  private final ObjectProvider<DockerHostPort> dockerHostPortProvider;
  private final RunnerProperties runnerProperties;
  private final Clock clock;

  public RunnerWorkspaceCleanupJob(
      RunnerExecutionRecordPort recordPort,
      RunnerWorkspaceStore workspaceStore,
      ObjectProvider<DockerHostPort> dockerHostPortProvider,
      RunnerProperties runnerProperties,
      Clock clock) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.dockerHostPortProvider =
        Objects.requireNonNull(dockerHostPortProvider, "dockerHostPortProvider");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Scheduled entrypoint — runs workspaces → orphan-dirs → dangling-containers in order (Trap T9).
   * Returns total actions taken across all three sweeps for logging / metrics.
   */
  public int runCleanup() {
    log.info("workspace cleanup tick start");
    int workspacesDeleted = sweepWorkspaces();
    int orphanDirsPreserved = sweepWorkspaceOrphanDirs();
    int danglingContainersRemoved = sweepDanglingContainers();
    int total = workspacesDeleted + orphanDirsPreserved + danglingContainersRemoved;
    log.info(
        "workspace cleanup tick done workspacesDeleted={} orphanDirsPreserved={} danglingContainersRemoved={}",
        workspacesDeleted,
        orphanDirsPreserved,
        danglingContainersRemoved);
    return total;
  }

  /** AC5 sub-bullets (a)-(d): per-row workspace deletion + archive marker. */
  public int sweepWorkspaces() {
    OffsetDateTime cutoff =
        OffsetDateTime.now(clock)
            .withOffsetSameInstant(ZoneOffset.UTC)
            .minus(Duration.ofHours(runnerProperties.docker().workspaceRetentionHours()));
    List<RunnerExecutionSnapshot> candidates =
        recordPort.findCompletedBeforeAndNotArchived(
            cutoff, runnerProperties.timeoutScanBatchSize());
    log.info("sweepWorkspaces start cutoff={} candidates={}", cutoff, candidates.size());
    int deleted = 0;
    for (RunnerExecutionSnapshot snapshot : candidates) {
      try {
        workspaceStore.deleteWorkspace(snapshot.publicId());
        recordPort.markArchived(snapshot.publicId(), OffsetDateTime.now(clock));
        log.info(
            "workspace cleanup deleted runnerExecutionId={} completedAt={}",
            snapshot.publicId(),
            snapshot.completedAt());
        deleted++;
      } catch (RuntimeException error) {
        log.warn(
            "workspace cleanup best-effort failure runnerExecutionId={} cause={}",
            snapshot.publicId(),
            error.toString());
      }
    }
    log.info("sweepWorkspaces done deleted={}", deleted);
    return deleted;
  }

  /** AC5 sub-bullet (d) — WARN-preserve orphan {@code rex_*} directories that lack a row. */
  public int sweepWorkspaceOrphanDirs() {
    int preserved = 0;
    List<Path> directories = workspaceStore.listWorkspaceSubdirectories();
    for (Path directory : directories) {
      String rexId = directory.getFileName().toString();
      try {
        Optional<RunnerExecutionSnapshot> row = recordPort.findByPublicId(rexId);
        if (row.isEmpty()) {
          log.warn(
              "workspace orphan dir found runnerExecutionId={} workspaceRoot={} action=preserve",
              rexId,
              directory);
          preserved++;
        }
      } catch (DomainException invalidId) {
        // Trap T7: a `rex_`-prefixed dir whose name is not a valid runner-execution public id
        // (e.g. truncated/partial dir from a crashed create) cannot correspond to a DB row.
        // findByPublicId throws on the prefix check — treat it as an orphan dir to PRESERVE, not a
        // scan failure, so the operator-facing signal matches the T7 contract.
        log.warn(
            "workspace orphan dir found runnerExecutionId={} workspaceRoot={} action=preserve reason=invalid_id",
            rexId,
            directory);
        preserved++;
      } catch (RuntimeException error) {
        log.warn(
            "workspace orphan dir scan failure runnerExecutionId={} cause={}",
            rexId,
            error.toString());
      }
    }
    return preserved;
  }

  /** AC6 — remove dangling containers (broker thinks done, host still has the container). */
  public int sweepDanglingContainers() {
    DockerHostPort docker = dockerHostPortProvider.getIfAvailable();
    if (docker == null) {
      // runners.mock — no docker bean wired; cleanly skip.
      return 0;
    }
    List<DockerHostPort.DanglingContainerInfo> containers;
    try {
      containers = docker.listContainersByLabel(LABEL_KEY, LABEL_VALUE_PREFIX);
    } catch (RuntimeException error) {
      log.warn("sweepDanglingContainers list failure cause={}", error.toString());
      return 0;
    }
    int removed = 0;
    for (DockerHostPort.DanglingContainerInfo container : containers) {
      try {
        Optional<RunnerExecutionSnapshot> row =
            recordPort.findByPublicId(container.runnerExecutionId());
        if (row.isPresent() && isStillActive(row.get())) {
          continue;
        }
        String status = container.status();
        if (status != null
            && (status.startsWith("running")
                || "paused".equals(status)
                || "restarting".equals(status))) {
          docker.stopContainer(container.containerId(), Duration.ofSeconds(10L));
        }
        docker.removeContainer(container.containerId(), false);
        log.info(
            "dangling container removed runnerExecutionId={} containerId={} status={}",
            container.runnerExecutionId(),
            container.containerId(),
            container.status());
        removed++;
      } catch (RuntimeException error) {
        log.warn(
            "dangling container best-effort failure runnerExecutionId={} containerId={} cause={}",
            container.runnerExecutionId(),
            container.containerId(),
            error.toString());
      }
    }
    return removed;
  }

  private static boolean isStillActive(RunnerExecutionSnapshot row) {
    return row.status() == RunnerExecutionStatus.PENDING
        || row.status() == RunnerExecutionStatus.RUNNING;
  }
}
