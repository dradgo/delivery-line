package org.dradgo.application.runner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.dradgo.application.runner.spi.DockerHostPort.NetworkInfo;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * DinD Testcontainers Task 7 — fast-tier (Mockito, no engine) coverage of the dangling DinD sweep
 * that reaps orphan per-run dockerd sidecars ({@code deliveryline.dind=rex_*}) and their per-run
 * bridge networks ({@code deliveryline-net-rex_*}) whose run row is terminal or gone — sidecar
 * before network (a network cannot be removed while a container is attached), preserving resources
 * of a still-active (PENDING/RUNNING) run.
 */
class RunnerWorkspaceCleanupJobDindUnitTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC);

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
    job =
        new RunnerWorkspaceCleanupJob(
            recordPort, workspaceStore, provider, RunnerProperties.defaults(), CLOCK);
  }

  @Test
  void orphanSidecarAndNetworkWhoseRowIsGoneAreReapedContainerBeforeNetwork() {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    DanglingContainerInfo sidecar =
        new DanglingContainerInfo("dind_gone", "rex_gone00000001", "running", now.minusMinutes(30));
    NetworkInfo network =
        new NetworkInfo("net_gone", "deliveryline-net-rex_gone00000001", "rex_gone00000001");
    when(docker.listContainersByLabel("deliveryline.dind", "rex_")).thenReturn(List.of(sidecar));
    when(docker.listNetworksByLabel("deliveryline.dind")).thenReturn(List.of(network));
    when(recordPort.findByPublicId("rex_gone00000001")).thenReturn(Optional.empty()); // row gone

    int reaped = job.sweepDanglingDind();

    // running sidecar → two-pass stop-then-force-remove; network removed AFTER the sidecar.
    InOrder inOrder = inOrder(docker);
    inOrder.verify(docker).stopContainer(eq("dind_gone"), any());
    inOrder.verify(docker).removeContainer("dind_gone", true);
    inOrder.verify(docker).removeNetwork("deliveryline-net-rex_gone00000001");
    org.junit.jupiter.api.Assertions.assertEquals(2, reaped);
  }

  @Test
  void exitedOrphanSidecarUsesSinglePassRemove() {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    DanglingContainerInfo sidecar =
        new DanglingContainerInfo("dind_dead", "rex_dead00000001", "exited", now.minusMinutes(30));
    when(docker.listContainersByLabel("deliveryline.dind", "rex_")).thenReturn(List.of(sidecar));
    when(docker.listNetworksByLabel("deliveryline.dind")).thenReturn(List.of());
    when(recordPort.findByPublicId("rex_dead00000001")).thenReturn(Optional.empty());

    int reaped = job.sweepDanglingDind();

    verify(docker, never()).stopContainer(any(), any());
    verify(docker).removeContainer("dind_dead", false);
    org.junit.jupiter.api.Assertions.assertEquals(1, reaped);
  }

  @Test
  void sidecarAndNetworkOfStillActiveRunArePreserved() {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    DanglingContainerInfo sidecar =
        new DanglingContainerInfo("dind_live", "rex_live00000001", "running", now.minusMinutes(30));
    NetworkInfo network =
        new NetworkInfo("net_live", "deliveryline-net-rex_live00000001", "rex_live00000001");
    when(docker.listContainersByLabel("deliveryline.dind", "rex_")).thenReturn(List.of(sidecar));
    when(docker.listNetworksByLabel("deliveryline.dind")).thenReturn(List.of(network));
    when(recordPort.findByPublicId("rex_live00000001"))
        .thenReturn(Optional.of(activeSnapshot("rex_live00000001")));

    int reaped = job.sweepDanglingDind();

    verify(docker, never()).stopContainer(any(), any());
    verify(docker, never()).removeContainer(eq("dind_live"), anyBoolean());
    verify(docker, never()).removeNetwork(any());
    org.junit.jupiter.api.Assertions.assertEquals(0, reaped);
  }

  @Test
  void invalidLabelValueIsPreservedNotDestroyed() {
    // A sidecar/network whose deliveryline.dind label value is not a valid runner-execution public
    // id (truncated/garbage) cannot be correlated to a row — PRESERVE it (mirror the container
    // sweep's Trap-T7 posture) rather than destroy an uncorrelatable resource.
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    DanglingContainerInfo sidecar =
        new DanglingContainerInfo("dind_bad", "rex_bad", "running", now.minusMinutes(30));
    NetworkInfo network = new NetworkInfo("net_bad", "deliveryline-net-rex_bad", "rex_bad");
    when(docker.listContainersByLabel("deliveryline.dind", "rex_")).thenReturn(List.of(sidecar));
    when(docker.listNetworksByLabel("deliveryline.dind")).thenReturn(List.of(network));
    when(recordPort.findByPublicId("rex_bad"))
        .thenThrow(new DomainException(DomainErrorCode.INVALID_COMMAND_PAYLOAD, "invalid id"));

    int reaped = job.sweepDanglingDind();

    verify(docker, never()).removeContainer(eq("dind_bad"), anyBoolean());
    verify(docker, never()).removeNetwork(any());
    org.junit.jupiter.api.Assertions.assertEquals(0, reaped);
  }

  @Test
  void noDockerBeanMakesSweepANoOp() {
    @SuppressWarnings("unchecked")
    ObjectProvider<DockerHostPort> empty = mock(ObjectProvider.class);
    when(empty.getIfAvailable()).thenReturn(null);
    RunnerWorkspaceCleanupJob mockProfileJob =
        new RunnerWorkspaceCleanupJob(
            recordPort, workspaceStore, empty, RunnerProperties.defaults(), CLOCK);

    org.junit.jupiter.api.Assertions.assertEquals(0, mockProfileJob.sweepDanglingDind());
  }

  private static RunnerExecutionSnapshot activeSnapshot(String rex) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        rex,
        "run_live00000001",
        RunnerStage.EXECUTION,
        RunnerExecutionStatus.RUNNING,
        1,
        now,
        now,
        null,
        null,
        now,
        null);
  }
}
