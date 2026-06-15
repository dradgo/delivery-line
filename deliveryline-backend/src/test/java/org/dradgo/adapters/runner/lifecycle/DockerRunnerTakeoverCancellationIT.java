package org.dradgo.adapters.runner.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.TakeoverResult;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.22 (AC5 / AC12, docker tier) — developer takeover gracefully stops the LIVE container of
 * a {@code running} runner row. Seeds a {@code running} runner_executions row, launches a real
 * labeled container, primes the adapter's container handle (exactly as the broker's startup
 * recovery would), then drives {@link DeveloperTakeoverService#takeoverWorkflow}. Asserts the DB
 * row flips to {@code cancelled_for_takeover} AND the container is force-exited by the post-commit
 * best-effort {@code docker stop} (mirrors {@link DockerRunnerLifecycleTimeoutIT}'s container-exit
 * assertion).
 *
 * <p>WSL2 gate (Trap T15) + {@code alpine:3.20} pre-pull required — see {@link
 * BrokerDrivenDockerLifecycleITSupport}.
 */
class DockerRunnerTakeoverCancellationIT extends BrokerDrivenDockerLifecycleITSupport {

  @Autowired private DeveloperTakeoverService takeoverService;

  @Test
  void takeoverFlipsRunningRowToCancelledForTakeoverAndStopsTheContainer() {
    String runId = seedWorkflowRun("WaitingForReview");
    String rex =
        seedRunningRunner(
            runId, "execution", /* lastActivitySecondsAgo= */ 5, /* timeoutSecondsFromNow= */ 3600);
    String containerId = launchLabeledContainer(rex, runId, "execution", "sleep", "3600");

    // Prime the adapter's in-process handle for this rex via the production recovery probe (the
    // broker does this on startup) so the post-commit cancel can resolve the container id.
    assertThat(adapter.recoverHandle(rex)).isPresent();
    assertThat(adapter.findContainerIdFor(rex)).contains(containerId);

    TakeoverResult result =
        takeoverService.takeoverWorkflow(
            new TakeoverWorkflowCommand(
                runId,
                "alex",
                ActorType.HUMAN,
                "idem-takeover-docker-0001",
                "corr-takeover-docker",
                "developer continuing in IDE"));

    assertThat(result.resultingState().value()).isEqualTo("TakenOver");
    assertThat(result.cancelledInFlightCount()).isEqualTo(1);
    assertThat(runnerStatus(rex)).isEqualTo("cancelled_for_takeover");

    awaitNotRunning(containerId, Duration.ofSeconds(15));
    assertThat(isRunning(containerId))
        .as("the live container must be stopped by the post-commit best-effort docker stop")
        .isFalse();
  }
}
