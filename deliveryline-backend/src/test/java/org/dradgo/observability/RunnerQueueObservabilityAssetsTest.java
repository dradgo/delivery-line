package org.dradgo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Story 3.19 (AC12) — validity of the committed observability assets:
 *
 * <ul>
 *   <li>the Grafana "Runner Queue" dashboard JSON parses and carries the headline panel
 *       (unconditional);
 *   <li>{@code promtool check rules alerts.yml} passes — but GATED on {@code promtool}
 *       availability: SKIPs (not fails) where the binary is absent (e.g. the Windows dev box / CI
 *       without Prometheus), logging the skip per the no-silent-caps discipline.
 * </ul>
 *
 * <p>Paths resolve from the module working directory ({@code deliveryline-backend}) up to the repo
 * root {@code infra/observability/} tree.
 */
class RunnerQueueObservabilityAssetsTest {

  private static final Path GRAFANA_DASHBOARD =
      Path.of("..", "infra", "observability", "grafana", "dashboards", "runner-queue.json");
  private static final Path ALERTS =
      Path.of("..", "infra", "observability", "prometheus", "alerts.yml");

  @Test
  void grafanaRunnerQueueDashboardJsonIsValidAndHasHeadlinePanel() throws Exception {
    assertThat(Files.exists(GRAFANA_DASHBOARD))
        .as("dashboard JSON must be committed at %s", GRAFANA_DASHBOARD)
        .isTrue();
    String json = Files.readString(GRAFANA_DASHBOARD);
    JsonNode root = new ObjectMapper().readTree(json); // throws on invalid JSON

    assertThat(root.path("title").asText()).isEqualTo("Runner Queue");
    assertThat(root.path("uid").asText()).isNotBlank();
    JsonNode panels = root.path("panels");
    assertThat(panels.isArray()).isTrue();
    assertThat(panels).isNotEmpty();
    // The headline queue-depth panel must be present.
    assertThat(json).contains("deliveryline_runner_queue_depth");
  }

  @Test
  void promtoolValidatesTheAlertRulesWhenAvailable() throws Exception {
    assertThat(Files.exists(ALERTS)).as("alerts.yml must be committed at %s", ALERTS).isTrue();

    boolean promtoolAvailable = canRun("promtool", "--version");
    // SKIP (not fail) where promtool is absent; the rule file itself is still asserted present
    // above.
    assumeTrue(
        promtoolAvailable,
        "promtool not on PATH — skipping `promtool check rules` (rule file presence still asserted)");

    Process process =
        new ProcessBuilder("promtool", "check", "rules", ALERTS.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    assertThat(finished).as("promtool check rules must complete").isTrue();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.exitValue())
        .as("promtool check rules must pass; output:%n%s", output)
        .isZero();
  }

  private static boolean canRun(String... command) {
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      return p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception probeFailure) {
      return false;
    }
  }
}
