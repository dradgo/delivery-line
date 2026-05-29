package org.dradgo.adapters.runner.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Story 3.2a AC5 / story-3.2 AC10 (f) + (g) — <b>broker-driven</b> restart recovery via {@link
 * org.dradgo.application.runner.RunnerBroker#recoverOnStartup()}. Closes the {@code
 * [Review][Patch]} finding that the original scaffold only asserted {@code
 * adapter.recoverHandle(...)}'s in-process classification and never proved the broker's restart
 * path against a real {@code runner_executions} row (no DB row, no lease re-arm, no completion
 * ingestion).
 *
 * <p>(f) A {@code running} row whose lease has already elapsed (simulating a broker outage) is
 * recovered from a still-running container via the {@code deliveryline.runnerExecutionId} label
 * filter: the row stays {@code running}, its lease is re-armed ({@code last_activity_at} advanced —
 * review decision D2), and the broker now holds the container handle.
 *
 * <p>(g) A {@code running} row whose container has already exited after writing a valid {@code
 * output/runner-result.v1.json} is recovered by harvesting that result through {@code onResult}:
 * the row transitions to {@code completed} and a dedicated {@code runner.completed} event is
 * appended.
 */
class DockerRunnerLifecycleRecoveryIT extends BrokerDrivenDockerLifecycleITSupport {

  @Test
  void recoverOnStartupRearmsStillRunningContainerLeaseAndKeepsRowRunning() {
    String runId = seedWorkflowRun("Executing");
    // Lease already elapsed (1h idle) — a naive next scanForTimeouts would kill a genuinely-alive
    // container, which is exactly why recovery re-arms the lease (D2).
    String rex =
        seedRunningRunner(
            runId,
            "execution",
            /* lastActivitySecondsAgo= */ 3600,
            /* timeoutSecondsFromNow= */ -60);
    String containerId = launchLabeledContainer(rex, runId, "execution", "sleep", "3600");
    OffsetDateTime leaseBefore = lastActivityAt(rex);

    // No handle priming: a fresh post-restart broker has an empty cache for this rex.
    broker.recoverOnStartup();

    assertThat(runnerStatus(rex))
        .as("a recovered, still-running container must stay running")
        .isEqualTo("running");
    assertThat(lastActivityAt(rex))
        .as("recovery must re-arm the lease so the recovered container gets a fresh window (D2)")
        .isAfter(leaseBefore);
    assertThat(adapter.findContainerIdFor(rex))
        .as("recovery must cache the container id discovered via the label filter")
        .contains(containerId);
  }

  @Test
  void recoverOnStartupIngestsExitedContainerResultAndCompletesRow() throws Exception {
    // Investigation stage producing a spec; run must be non-terminal for artifact ingestion.
    String runId = seedWorkflowRun("Investigating");
    String rex =
        seedRunningRunner(
            runId,
            "investigation",
            /* lastActivitySecondsAgo= */ 30,
            /* timeoutSecondsFromNow= */ 600);
    prepareWorkspace(rex);

    // The runner wrote a schema-valid result + its referenced artifact content before exiting.
    writeFile(outputDir(rex).resolve("runner-result.v1.json"), happySpecResult(runId, rex));
    scratchStore.writeArtifactContent(
        rex, "spec/v1.json", "# Generated spec\n".getBytes(StandardCharsets.UTF_8));

    String containerId = launchLabeledContainer(rex, runId, "investigation", "true");
    awaitNotRunning(containerId, Duration.ofSeconds(10));

    broker.recoverOnStartup();

    assertThat(runnerStatus(rex))
        .as("recovery must harvest the exited container's result and complete the row")
        .isEqualTo("completed");
    assertThat(eventCount(runId, "runner.completed"))
        .as("a dedicated runner.completed event must be appended exactly once")
        .isEqualTo(1);
  }

  /** A schema-valid {@code runner-result.v1} payload referencing a single spec artifact. */
  private static String happySpecResult(String runId, String rex) {
    return """
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {
              "artifactId": "art_mockspec00000001",
              "artifactType": "spec",
              "contentReference": "spec/v1.json"
            }
          ],
          "normalizedOutput": {
            "summary": "Recovered investigation completed; spec drafted.",
            "outcome": "success"
          },
          "checksum": {
            "algorithm": "SHA-256",
            "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"
          },
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """
        .formatted(runId, rex);
  }
}
