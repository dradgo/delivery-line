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
  // DinD Testcontainers Task 7 — the per-run dockerd sidecar carries deliveryline.dind=<rex>, and
  // its bridge network deliveryline-net-<rex> carries the same label; the dind sweep reaps both
  // when their run row is terminal/gone.
  private static final String DIND_LABEL_KEY = "deliveryline.dind";

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
    // DinD Testcontainers Task 7 — normal-completion backstop for the per-run dockerd sidecars +
    // networks (the DockerRunnerAdapter only tears down on cancel / dispatch-failure; a run that
    // completes normally leaves its sidecar for this sweep to reap). Runs AFTER the
    // runner-container
    // sweep — independent label space, order not load-bearing.
    int danglingDindReaped = sweepDanglingDind();
    int total =
        workspacesDeleted + orphanDirsPreserved + danglingContainersRemoved + danglingDindReaped;
    log.info(
        "workspace cleanup tick done workspacesDeleted={} orphanDirsPreserved={} danglingContainersRemoved={} danglingDindReaped={}",
        workspacesDeleted,
        orphanDirsPreserved,
        danglingContainersRemoved,
        danglingDindReaped);
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
        // Story 3.5 Trap T10: a workspace flagged with the .quarantine marker (post-execution
        // secret leak) is preserved for diagnostics rather than deleted. Mark it archived so it
        // drops out of future candidate queries (WARN once, not every tick), but keep the files.
        if (workspaceStore.isQuarantined(snapshot.publicId())) {
          log.warn(
              "workspace cleanup skip quarantined runnerExecutionId={} action=preserve reason=secret_leak",
              snapshot.publicId());
          recordPort.markArchived(snapshot.publicId(), OffsetDateTime.now(clock));
          continue;
        }
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
    long minAgeSeconds = runnerProperties.docker().danglingContainerMinAgeSeconds();
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    int removed = 0;
    for (DockerHostPort.DanglingContainerInfo container : containers) {
      try {
        Optional<RunnerExecutionSnapshot> row;
        try {
          row = recordPort.findByPublicId(container.runnerExecutionId());
        } catch (DomainException invalidId) {
          // The container's runnerExecutionId label is `rex_`-prefixed but not a valid public id
          // (truncated/garbage). We cannot correlate it to a row, so we PRESERVE rather than
          // destroy an uncorrelatable container (mirrors the orphan-dir Trap-T7 posture).
          log.warn(
              "dangling container skip containerId={} reason=invalid_id labelValue={}",
              container.containerId(),
              container.runnerExecutionId());
          continue;
        }
        if (row.isPresent() && isStillActive(row.get())) {
          continue;
        }
        // Story 3.2a AC4 (a): min-age guard. A labelled-but-rowless container may be in the
        // dispatch→row-insert window — preserve it until it ages past the grace window so a
        // freshly-launching runner is never destroyed mid-dispatch. The guard applies ONLY to
        // rowless containers; a container whose row exists and is terminal is genuinely dangling.
        // Story 3.2a code-review (2026-05-29): a null createdAt now ages OUT (is removed) rather
        // than being preserved on every tick — an unknown-age container that can never satisfy the
        // grace comparison would otherwise leak forever.
        if (row.isEmpty()
            && minAgeSeconds > 0L
            && container.createdAt() != null
            && container.createdAt().isAfter(now.minusSeconds(minAgeSeconds))) {
          log.info(
              "dangling container skip containerId={} reason=within_min_age createdAt={} minAgeSeconds={}",
              container.containerId(),
              container.createdAt(),
              minAgeSeconds);
          continue;
        }
        // Story 3.2a AC4 (b)+(c): match on the normalized engine state and use a two-pass
        // stop-then-force-rm for a still-running container so the stop→rm 409 race cannot leak it.
        String status = container.status();
        boolean running =
            status != null
                && ("running".equals(status)
                    || "paused".equals(status)
                    || "restarting".equals(status)
                    || "unknown".equals(status));
        if (running) {
          docker.stopContainer(container.containerId(), Duration.ofSeconds(10L));
          // force=true: the container was running; removing with force avoids the brief window
          // between `docker stop` returning and the engine marking the container exited (409 race).
          docker.removeContainer(container.containerId(), true);
        } else {
          docker.removeContainer(container.containerId(), false);
        }
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

  /**
   * DinD Testcontainers Task 7 — reap orphan per-run dockerd sidecars ({@code
   * deliveryline.dind=rex_*}) and their per-run bridge networks ({@code deliveryline-net-rex_*})
   * whose run row is terminal or gone. Sidecars are stopped+removed BEFORE their networks (a
   * network cannot be removed while a container is still attached). Best-effort + per-resource
   * guarded like the sibling sweeps; a still-active (PENDING/RUNNING) run's resources are
   * preserved, and an uncorrelatable (invalid-id label) resource is preserved rather than destroyed
   * (Trap-T7 posture, mirroring {@link #sweepDanglingContainers}). Returns the count of resources
   * reaped (sidecars + networks). No-op under {@code runners.mock} (no {@link DockerHostPort}
   * bean).
   *
   * <p>Unlike {@link #sweepDanglingContainers}, this sweep needs NO min-age grace guard: the
   * sidecar is provisioned inside {@code DockerRunnerAdapter.dispatch} only on the queue-worker
   * path, which runs off a row the queue already leased with a committed {@code RUNNING} status —
   * so a just-provisioned sidecar always has a present+active row ({@code isStillActive} ⇒
   * preserved) and can never be seen as row-gone mid-provision (the dispatch→row-insert race the
   * container guard defends against does not exist here because the row precedes provision rather
   * than following it).
   */
  public int sweepDanglingDind() {
    DockerHostPort docker = dockerHostPortProvider.getIfAvailable();
    if (docker == null) {
      return 0;
    }
    int reaped = 0;
    // Sidecars first: a network cannot be removed while a container is attached to it.
    List<DockerHostPort.DanglingContainerInfo> sidecars;
    try {
      sidecars = docker.listContainersByLabel(DIND_LABEL_KEY, LABEL_VALUE_PREFIX);
    } catch (RuntimeException error) {
      log.warn("sweepDanglingDind sidecar list failure cause={}", error.toString());
      sidecars = List.of();
    }
    for (DockerHostPort.DanglingContainerInfo sidecar : sidecars) {
      try {
        RowLookup lookup = safeFindRow(sidecar.runnerExecutionId());
        if (!lookup.correlatable()) {
          // Uncorrelatable label value (invalid public id) — preserve, do not destroy.
          log.warn(
              "dind sidecar sweep skip containerId={} reason=invalid_id labelValue={}",
              sidecar.containerId(),
              sidecar.runnerExecutionId());
          continue;
        }
        if (lookup.row().isPresent() && isStillActive(lookup.row().get())) {
          continue;
        }
        String status = sidecar.status();
        boolean running =
            status != null
                && ("running".equals(status)
                    || "paused".equals(status)
                    || "restarting".equals(status)
                    || "unknown".equals(status));
        if (running) {
          docker.stopContainer(sidecar.containerId(), Duration.ofSeconds(10L));
          // force=true: avoid the brief stop→rm 409 race for a container that was still running.
          docker.removeContainer(sidecar.containerId(), true);
        } else {
          docker.removeContainer(sidecar.containerId(), false);
        }
        log.info(
            "dind sidecar reaped runnerExecutionId={} containerId={} status={}",
            sidecar.runnerExecutionId(),
            sidecar.containerId(),
            sidecar.status());
        reaped++;
      } catch (RuntimeException error) {
        log.warn(
            "dind sidecar sweep best-effort failure runnerExecutionId={} containerId={} cause={}",
            sidecar.runnerExecutionId(),
            sidecar.containerId(),
            error.toString());
      }
    }
    // Then orphan networks whose run row is gone/terminal.
    List<DockerHostPort.NetworkInfo> networks;
    try {
      networks = docker.listNetworksByLabel(DIND_LABEL_KEY);
    } catch (RuntimeException error) {
      log.warn("sweepDanglingDind network list failure cause={}", error.toString());
      networks = List.of();
    }
    for (DockerHostPort.NetworkInfo network : networks) {
      try {
        RowLookup lookup = safeFindRow(network.labelValue());
        if (!lookup.correlatable()) {
          log.warn(
              "dind network sweep skip network={} reason=invalid_id labelValue={}",
              network.name(),
              network.labelValue());
          continue;
        }
        if (lookup.row().isPresent() && isStillActive(lookup.row().get())) {
          continue;
        }
        docker.removeNetwork(network.name());
        log.info(
            "dind network reaped runnerExecutionId={} network={}",
            network.labelValue(),
            network.name());
        reaped++;
      } catch (RuntimeException error) {
        log.warn(
            "dind network sweep best-effort failure network={} cause={}",
            network.name(),
            error.toString());
      }
    }
    return reaped;
  }

  /**
   * Look up the run row for a {@code deliveryline.dind} label value. The three-valued result is
   * deliberate: {@code correlatable=false} ⇒ an UNCORRELATABLE label value (an invalid public id
   * that {@link RunnerExecutionRecordPort#findByPublicId} rejects) which the sweep treats as
   * preserve-not-destroy; {@code correlatable=true} with an empty {@code row} ⇒ the row is
   * genuinely gone (reapable); a present {@code row} ⇒ inspect its status. Modeled as an explicit
   * result rather than a nullable {@link Optional} so the uncorrelatable signal cannot be confused
   * with a genuinely-empty lookup.
   */
  private RowLookup safeFindRow(String rexId) {
    try {
      return RowLookup.correlated(recordPort.findByPublicId(rexId));
    } catch (DomainException invalidId) {
      return RowLookup.uncorrelatable();
    }
  }

  private record RowLookup(boolean correlatable, Optional<RunnerExecutionSnapshot> row) {
    static RowLookup uncorrelatable() {
      return new RowLookup(false, Optional.empty());
    }

    static RowLookup correlated(Optional<RunnerExecutionSnapshot> row) {
      return new RowLookup(true, row);
    }
  }

  private static boolean isStillActive(RunnerExecutionSnapshot row) {
    return row.status() == RunnerExecutionStatus.PENDING
        || row.status() == RunnerExecutionStatus.RUNNING;
  }
}
