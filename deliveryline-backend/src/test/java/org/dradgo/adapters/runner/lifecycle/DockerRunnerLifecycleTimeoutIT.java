package org.dradgo.adapters.runner.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Story 3.2a AC6 / story-3.2 AC10 (a) — <b>broker-driven</b> timeout enforcement of a real,
 * SIGTERM-ignoring container. Closes the {@code [Review][Patch]} finding that the original scaffold
 * called {@code adapter.terminate} directly and never exercised {@link
 * org.dradgo.application.runner.RunnerBroker#scanForTimeouts()}.
 *
 * <p>The test seeds a {@code running} {@code runner_executions} row whose {@code timeout_at} is
 * already in the past, primes the adapter's container handle (exactly as the broker's startup
 * recovery would), then drives {@code broker.scanForTimeouts()} and asserts the full broker
 * outcome: the row flips to {@code timed_out}, a dedicated {@code runner.timeout} event is appended
 * (NOT the generic {@code runner.failed}), and the SIGTERM-ignoring container is force-exited by the
 * {@code stop → kill-after-grace} path.
 *
 * <p><b>AC nuance to confirm on WSL2 (Trap T15):</b> {@code docker stop -t N} sends SIGTERM then
 * SIGKILLs after the grace, so the broker usually observes the container already exited →
 * termination outcome {@code STOPPED_GRACEFULLY} (the {@code terminationOutcome} written into the
 * {@code runner.timeout} event details); {@code KILLED_AFTER_GRACE} only arises when {@code docker
 * stop} itself fails. Either way the row is {@code timed_out} and the container has exited — this IT
 * asserts that broker-level invariant.
 */
class DockerRunnerLifecycleTimeoutIT extends BrokerDrivenDockerLifecycleITSupport {

  @Test
  void brokerScanFlipsPastDeadlineRowToTimedOutAndForcesContainerExit() {
    // Run in Executing so the broker's driveWorkflowFailed (Executing → Failed) is a legal
    // transition; runner row deadline is already elapsed.
    String runId = seedWorkflowRun("Executing");
    String rex = seedRunningRunner(runId, "execution", /* lastActivitySecondsAgo= */ 30, /* timeoutSecondsFromNow= */ -5);
    String containerId =
        launchLabeledContainer(rex, runId, "execution", "sh", "-c", "trap '' TERM; sleep 3600");

    // Prime the broker's in-process handle for this rex via the production recovery probe (the
    // broker does this on startup before scans). recoverHandle only caches the container id; it does
    // not mutate the DB row, so the seeded past-deadline timeout_at is preserved for the scan.
    assertThat(adapter.recoverHandle(rex)).isPresent();
    assertThat(adapter.findContainerIdFor(rex)).contains(containerId);

    int flipped = broker.scanForTimeouts();

    assertThat(flipped).as("the single past-deadline row must be flipped").isEqualTo(1);
    assertThat(runnerStatus(rex)).isEqualTo("timed_out");
    assertThat(eventCount(runId, "runner.timeout"))
        .as("exactly one dedicated runner.timeout event must be appended")
        .isEqualTo(1);
    assertThat(eventCount(runId, "runner.failed"))
        .as("the timeout path must NOT emit the generic runner.failed event (Trap T10)")
        .isZero();

    awaitNotRunning(containerId, Duration.ofSeconds(15));
    assertThat(isRunning(containerId))
        .as("the SIGTERM-ignoring container must be force-exited by stop→kill-after-grace")
        .isFalse();
  }
}
