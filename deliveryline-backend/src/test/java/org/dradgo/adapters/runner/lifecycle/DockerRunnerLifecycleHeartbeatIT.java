package org.dradgo.adapters.runner.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Story 3.2a AC7 / story-3.2 AC10 (b) + (c) — <b>broker-driven</b> heartbeat activity extension
 * against a real running container. Closes the {@code [Review][Patch]} finding that the original
 * scaffold asserted {@code adapter.poll(...)} returned {@code HeartbeatTouched} but never proved
 * the broker translated that into a {@code last_activity_at} / {@code timeout_at} advance via
 * {@link org.dradgo.application.runner.RunnerBroker#pollActiveExecutions()}.
 *
 * <p>The container is a plain {@code sleep} so {@code poll} classifies it running and reaches the
 * heartbeat-source check; the heartbeat sources (log-file growth, then the {@code heartbeat.touch}
 * marker) are written to the host workspace tree the production beans read. Each {@code
 * pollActiveExecutions()} tick must drive the seeded row's {@code last_activity_at} strictly
 * forward, extend its {@code timeout_at}, and keep the row {@code running}.
 */
class DockerRunnerLifecycleHeartbeatIT extends BrokerDrivenDockerLifecycleITSupport {

  @Test
  void logFileGrowthDrivesBrokerActivityExtensionAcrossPolls() throws Exception {
    String runId = seedWorkflowRun("Executing");
    // Far-future deadline: this IT proves activity advance, not the timeout path.
    String rex =
        seedRunningRunner(
            runId, "execution", /* lastActivitySecondsAgo= */ 30, /* timeoutSecondsFromNow= */ 600);
    prepareWorkspace(rex);
    launchLabeledContainer(rex, runId, "execution", "sleep", "3600");

    // recoverHandle caches the container id + re-seeds the log-observation floor at ~now, so only
    // genuinely-new bytes written AFTER recovery count as activity (Trap T against stale re-emit).
    assertThat(adapter.recoverHandle(rex)).isPresent();
    OffsetDateTime timeoutBefore = timeoutAt(rex);

    // First real bytes after the re-seed floor → first broker activity advance.
    sleepQuietly(Duration.ofMillis(1100));
    writeFile(logsDir(rex).resolve("runner.stdout"), "tick-1\n");
    assertThat(broker.pollActiveExecutions())
        .as("the broker must process the heartbeat tick")
        .isGreaterThanOrEqualTo(1);
    OffsetDateTime activityAfterFirst = lastActivityAt(rex);

    // Further growth → a second, strictly-later activity advance.
    sleepQuietly(Duration.ofMillis(1100));
    writeFile(logsDir(rex).resolve("runner.stdout"), "tick-1\ntick-2\n");
    assertThat(broker.pollActiveExecutions()).isGreaterThanOrEqualTo(1);
    OffsetDateTime activityAfterSecond = lastActivityAt(rex);

    assertThat(activityAfterSecond)
        .as("last_activity_at must advance on each heartbeat-bearing poll")
        .isAfter(activityAfterFirst);
    assertThat(timeoutAt(rex))
        .as("timeout_at must be extended past its seeded value as the lease is refreshed")
        .isAfter(timeoutBefore);
    assertThat(runnerStatus(rex))
        .as("a heartbeating row must never leave running")
        .isEqualTo("running");
  }

  @Test
  void heartbeatTouchMarkerDrivesBrokerActivityExtension() throws Exception {
    String runId = seedWorkflowRun("Executing");
    String rex =
        seedRunningRunner(
            runId, "execution", /* lastActivitySecondsAgo= */ 30, /* timeoutSecondsFromNow= */ 600);
    prepareWorkspace(rex);
    launchLabeledContainer(rex, runId, "execution", "sleep", "3600");

    assertThat(adapter.recoverHandle(rex)).isPresent();
    OffsetDateTime activityBefore = lastActivityAt(rex);

    sleepQuietly(Duration.ofMillis(1100));
    writeFile(outputDir(rex).resolve("heartbeat.touch"), "");
    assertThat(broker.pollActiveExecutions()).isGreaterThanOrEqualTo(1);

    assertThat(lastActivityAt(rex))
        .as("a fresh heartbeat.touch marker must drive the broker activity advance")
        .isAfter(activityBefore);
    assertThat(runnerStatus(rex)).isEqualTo("running");
  }
}
