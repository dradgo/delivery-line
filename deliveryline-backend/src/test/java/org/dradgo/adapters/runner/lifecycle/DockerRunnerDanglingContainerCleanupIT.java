package org.dradgo.adapters.runner.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.exception.NotFoundException;
import java.time.Clock;
import java.util.Optional;
import org.dradgo.adapters.runner.EnabledIfDockerAvailable;
import org.dradgo.application.runner.RunnerWorkspaceCleanupJob;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3.2a AC10 (l) — the dangling-container sweep stops + removes a labelled container that has
 * no {@code runner_executions} row, and leaves unrelated (unlabelled) containers untouched. Uses
 * the real {@link DockerHostPort} (the engine gateway) with the record port stubbed to "no row" and
 * the min-age guard disabled (0) so a freshly-launched container is immediately eligible.
 * WSL2-gated (Trap T15).
 */
@Tag("docker-runner-it")
@EnabledIfDockerAvailable
class DockerRunnerDanglingContainerCleanupIT extends DockerLifecycleITSupport {

  @Test
  @SuppressWarnings("unchecked")
  void sweepRemovesLabeledRowlessContainerAndLeavesUnrelatedContainerUntouched() {
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    String dangling = launchLabeledContainer(rex, "sleep", "3600");
    String unrelated = launchUnlabeledContainer("sleep", "3600");

    RunnerExecutionRecordPort recordPort = mock(RunnerExecutionRecordPort.class);
    when(recordPort.findByPublicId(any())).thenReturn(Optional.empty()); // no DB row → dangling
    ObjectProvider<DockerHostPort> provider = mock(ObjectProvider.class);
    // DefaultDockerEngineGateway implements DockerHostPort (see
    // DockerConfiguration#dockerHostPort).
    when(provider.getIfAvailable()).thenReturn((DockerHostPort) gateway);

    // `properties` carries danglingContainerMinAgeSeconds=0 (set in the support base), so the
    // just-launched container is eligible immediately.
    RunnerWorkspaceCleanupJob job =
        new RunnerWorkspaceCleanupJob(
            recordPort, workspaceStore, provider, properties, Clock.systemUTC());

    int removed = job.sweepDanglingContainers();

    assertThat(removed).isGreaterThanOrEqualTo(1);
    assertThatThrownBy(() -> dockerClient.inspectContainerCmd(dangling).exec())
        .as("the labelled, rowless container must be stopped + removed")
        .isInstanceOf(NotFoundException.class);
    assertThat(isRunning(unrelated))
        .as("an unrelated (unlabelled) container must NOT be touched by the sweep")
        .isTrue();
  }
}
