package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.RunnerLogReference;
import org.dradgo.application.runner.spi.RunnerLogStore;
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

/**
 * Story 4.4 (AC5/AC10) — real-Postgres end-to-end coverage for {@code GET
 * /api/v1/runner-executions/&#123;rexId&#125;/logs/download}. The {@code @WebMvcTest} slice ({@code
 * RunnerExecutionControllerTest}) pins the controller wiring with mocks; this {@code *IT} boots the
 * full app (@SpringBootTest + Testcontainers Postgres, memory:
 * springboot-testcontainers-test-must-be-IT) so the parts the slice cannot exercise are proven
 * against the real substrate:
 *
 * <ul>
 *   <li>the already-redacted bytes are read back from the on-disk {@link RunnerLogStore} and served
 *       verbatim as a {@code text/plain} attachment;
 *   <li>the {@code view_runner_logs} state gate (a {@code Failed} run grants it to every role);
 *   <li>a successful download actually PERSISTS an {@code audit.logDownloaded} row in {@code
 *       workflow_events} (the append the mock-based slice can only verify was invoked); and
 *   <li>an unknown runner execution returns 404 {@code RUNNER_EXECUTION_NOT_FOUND} with NO audit
 *       row.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RunnerExecutionControllerIT {

  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RunnerLogStore runnerLogStore;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void servesRedactedLogAndPersistsAuditEvent() throws Exception {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    String rexId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    // A Failed run grants view_runner_logs to every role (WorkflowInspectionService FAILED branch).
    seedFailedRunWithRedactedLog(runId, rexId, "redacted stdout line", "redacted stderr line");

    HttpResponse<String> response =
        get("/api/v1/runner-executions/" + rexId + "/logs/download?actorRole=workflow_owner");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
    assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
        .contains("attachment")
        .contains("runner-" + rexId + ".log");
    assertThat(response.body()).contains("redacted stdout line").contains("redacted stderr line");

    // AC5 — the download is recorded as a persisted audit.logDownloaded event carrying the rex id.
    Long auditRows =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events "
                + "where event_type = 'audit.logDownloaded' "
                + "  and details->>'runnerExecutionId' = ?",
            Long.class,
            rexId);
    assertThat(auditRows).isEqualTo(1L);
  }

  @Test
  void returns404AndNoAuditWhenRunnerExecutionUnknown() throws Exception {
    String unknownRex = PublicIdPrefixes.RUNNER_EXECUTION.next();

    HttpResponse<String> response =
        get("/api/v1/runner-executions/" + unknownRex + "/logs/download?actorRole=workflow_owner");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("RUNNER_EXECUTION_NOT_FOUND");

    Long auditRows =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'audit.logDownloaded'",
            Long.class);
    assertThat(auditRows).isEqualTo(0L);
  }

  private void seedFailedRunWithRedactedLog(
      String runId, String rexId, String stdout, String stderr) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Failed')", runId);
    // Write the already-redacted bytes to the durable log store the controller reads back.
    RunnerLogReference reference =
        runnerLogStore.write(
            rexId,
            stdout.getBytes(StandardCharsets.UTF_8),
            stderr.getBytes(StandardCharsets.UTF_8));
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           raw_output_reference, raw_output_classification, raw_output_byte_size, redaction_count)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', 'failed', 1, now(), now() + interval '10 minutes', 100, 0, now(),
                ?, 'shareable-redacted', ?, 0)
        """,
        rexId,
        runId,
        reference.referencePath(),
        reference.byteSize());
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
  }
}
