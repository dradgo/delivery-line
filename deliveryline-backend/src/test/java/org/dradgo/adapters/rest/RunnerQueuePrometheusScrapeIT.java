package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3.19 (AC11b/AC12) — the headline metric {@code deliveryline_runner_queue_depth} is
 * scrapeable from Actuator's Prometheus endpoint AND its value matches the DB queued count. Boots
 * the full app (@SpringBootTest + Testcontainers Postgres) so the {@code management} exposure +
 * {@code micrometer-registry-prometheus} are exercised end-to-end. Named {@code *IT} so Surefire's
 * no-Docker tier excludes it (memory: springboot-testcontainers-test-must-be-IT).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
// Spring Boot's test slice installs a DisableMetricsExportContextCustomizer that turns OFF every
// metrics exporter (so unrelated @SpringBootTests don't spin up a PrometheusMeterRegistry). This is
// the one test that MUST scrape /actuator/prometheus, so re-enable the Prometheus registry here —
// the production app has it on by default (AC5 / Reconciliation 4).
@TestPropertySource(properties = "management.prometheus.metrics.export.enabled=true")
@Tag("integration")
class RunnerQueuePrometheusScrapeIT {

  private static final Pattern QUEUE_DEPTH =
      Pattern.compile("(?m)^deliveryline_runner_queue_depth(?:\\{[^}]*})?\\s+([0-9.E+-]+)\\s*$");

  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void queueDepthMetricIsScrapeableAndMatchesDbCount() throws Exception {
    // Start clean, seed a known number of queued rows.
    jdbcTemplate.update("delete from runner_executions");
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    int seeded = 3;
    for (int i = 0; i < seeded; i++) {
      jdbcTemplate.update(
          """
          insert into runner_executions
            (public_id, workflow_run_id, stage, status, context_bundle_version,
             last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
          values (?, (select id from workflow_runs where public_id = ?),
                  'investigation', 'queued', 1, now(), now() + interval '10 minutes', 100, 0, now())
          """,
          "rex_scrape" + i + UUID.randomUUID().toString().replace("-", "").substring(0, 4),
          runId);
    }
    long dbCount =
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where status = 'queued'", Long.class);
    assertThat(dbCount).isEqualTo(seeded);

    // The gauge caches for ~1s; poll across that window until the scrape reflects the seeded rows.
    double scraped = pollQueueDepthUntil(seeded, 10);

    assertThat(scraped).isEqualTo((double) seeded);
    assertThat(scraped).isEqualTo((double) dbCount);
  }

  private double pollQueueDepthUntil(int expected, int attempts)
      throws IOException, InterruptedException {
    double last = Double.NaN;
    for (int i = 0; i < attempts; i++) {
      HttpResponse<String> response = get("/actuator/prometheus");
      assertThat(response.statusCode())
          .as("/actuator/prometheus must be exposed (management exposure + prometheus registry)")
          .isEqualTo(200);
      Matcher m = QUEUE_DEPTH.matcher(response.body());
      assertThat(m.find())
          .as("deliveryline_runner_queue_depth must be present in the scrape")
          .isTrue();
      last = Double.parseDouble(m.group(1));
      if (last == expected) {
        return last;
      }
      Thread.sleep(1100L); // > the 1s gauge cache window so the next scrape re-queries
    }
    return last;
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
  }
}
