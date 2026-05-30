package org.dradgo.application.runner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.DockerHostPort.DanglingContainerInfo;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3.2a AC4 (a) — fast-tier (Mockito, no engine) coverage of the dangling-container sweep's
 * min-age guard: a labelled-but-rowless container younger than {@code
 * danglingContainerMinAgeSeconds} (it may be mid dispatch→row-insert) is preserved, while an older
 * rowless container is removed.
 */
class RunnerWorkspaceCleanupJobDanglingUnitTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC);

  private RunnerExecutionRecordPort recordPort;
  private RunnerWorkspaceStore workspaceStore;
  private DockerHostPort docker;
  private RunnerWorkspaceCleanupJob job;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    recordPort = mock(RunnerExecutionRecordPort.class);
    workspaceStore = mock(RunnerWorkspaceStore.class);
    docker = mock(DockerHostPort.class);
    ObjectProvider<DockerHostPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(docker);
    // No workspaces / orphan dirs in scope for this focused sweep test.
    when(recordPort.findCompletedBeforeAndNotArchived(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of());
    when(workspaceStore.listWorkspaceSubdirectories()).thenReturn(List.of());
    job =
        new RunnerWorkspaceCleanupJob(
            recordPort, workspaceStore, provider, RunnerProperties.defaults(), CLOCK);
  }

  @Test
  void rowlessContainerWithinMinAgeIsPreservedWhileOlderRowlessContainerIsRemoved() {
    // defaults(): danglingContainerMinAgeSeconds = 120.
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    DanglingContainerInfo young =
        new DanglingContainerInfo(
            "containeryoung", "rex_young0000001", "exited", now.minusSeconds(30));
    DanglingContainerInfo old =
        new DanglingContainerInfo(
            "containerold", "rex_old000000001", "exited", now.minusMinutes(10));
    when(docker.listContainersByLabel(any(), any())).thenReturn(List.of(young, old));
    when(recordPort.findByPublicId(any())).thenReturn(Optional.empty()); // rowless

    int removed = job.sweepDanglingContainers();

    // Young rowless container preserved (within the dispatch→row-insert grace window).
    verify(docker, never()).removeContainer(eq("containeryoung"), anyBoolean());
    // Old rowless container removed (not running → force=false, single-pass rm).
    verify(docker).removeContainer("containerold", false);
    org.junit.jupiter.api.Assertions.assertEquals(1, removed);
  }

  @Test
  void rowlessContainerWithUnknownCreatedAtAgesOutAndIsRemoved() {
    // Story 3.2a code-review (2026-05-29): a null createdAt now ages OUT (is removed) rather than
    // being preserved on every tick. An unknown-age container that can never satisfy the grace
    // comparison would otherwise leak forever; the min-age guard only protects containers with a
    // known, recent createdAt.
    DanglingContainerInfo unknownCreated =
        new DanglingContainerInfo("containerunknown", "rex_unknown00001", "exited", null);
    when(docker.listContainersByLabel(any(), any())).thenReturn(List.of(unknownCreated));
    when(recordPort.findByPublicId(any())).thenReturn(Optional.empty());

    int removed = job.sweepDanglingContainers();

    // exited → single-pass force=false removal.
    verify(docker).removeContainer("containerunknown", false);
    org.junit.jupiter.api.Assertions.assertEquals(1, removed);
  }

  @Test
  void runningRowlessContainerPastMinAgeIsStoppedThenForceRemoved() {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    DanglingContainerInfo running =
        new DanglingContainerInfo(
            "containerrun", "rex_run000000001", "running", now.minusMinutes(10));
    when(docker.listContainersByLabel(any(), any())).thenReturn(List.of(running));
    when(recordPort.findByPublicId(any())).thenReturn(Optional.empty());

    job.sweepDanglingContainers();

    // Two-pass: stop (graceful) then force-remove to avoid the stop→rm 409 race (AC4 b/c).
    verify(docker).stopContainer(eq("containerrun"), any());
    verify(docker).removeContainer("containerrun", true);
  }

  @Test
  void unknownStatusRowlessContainerPastMinAgeUsesStopThenForceRemove() {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    DanglingContainerInfo unknown =
        new DanglingContainerInfo(
            "containerunknown", "rex_unknown00001", "unknown", now.minusMinutes(10));
    when(docker.listContainersByLabel(any(), any())).thenReturn(List.of(unknown));
    when(recordPort.findByPublicId(any())).thenReturn(Optional.empty());

    job.sweepDanglingContainers();

    verify(docker).stopContainer(eq("containerunknown"), any());
    verify(docker).removeContainer("containerunknown", true);
  }

  @Test
  void quarantinedWorkspaceIsPreservedNotDeletedButMarkedArchived() {
    // Story 3.5 Trap T10: a completed workspace carrying a .quarantine marker (secret leak) is
    // preserved for diagnostics — deleteWorkspace is NEVER called — but it is marked archived so it
    // drops out of future sweeps (WARN once).
    org.dradgo.application.runner.spi.RunnerExecutionSnapshot leaked =
        new org.dradgo.application.runner.spi.RunnerExecutionSnapshot(
            "rex_leaked000001",
            "run_leaked000001",
            org.dradgo.domain.registry.RunnerStage.EXECUTION,
            org.dradgo.domain.registry.RunnerExecutionStatus.FAILED,
            1,
            OffsetDateTime.now(CLOCK).minusHours(48),
            OffsetDateTime.now(CLOCK).minusHours(47),
            org.dradgo.domain.registry.FailureCategory.RUNNER_SECRET_LEAK,
            OffsetDateTime.now(CLOCK).minusHours(47),
            OffsetDateTime.now(CLOCK).minusHours(48),
            null);
    when(recordPort.findCompletedBeforeAndNotArchived(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(leaked));
    when(workspaceStore.isQuarantined("rex_leaked000001")).thenReturn(true);

    job.sweepWorkspaces();

    verify(workspaceStore, never()).deleteWorkspace(any());
    verify(recordPort).markArchived(eq("rex_leaked000001"), any());
  }
}
