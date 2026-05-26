package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Story 2.13 review P14: pins that the real {@code CorrelationIdFilter} populates {@code
 * MdcKeys.CORRELATION_ID} on the per-request MDC, and that the controller reads from MDC (Trap T5)
 * — not from a {@code @RequestHeader} — to stamp the application command. Failing this contract
 * would mean the filter and the controller disagree about the correlation id used downstream.
 *
 * <p>{@code @SpringBootTest} loads the production filter chain; {@code @MockitoBean
 * WorkflowCommandService} replaces the persistence-bound service so the test can capture the
 * commands the controller constructs without any DB interaction. The
 * {@code @Import(TestcontainersConfiguration.class)} mirrors the sibling {@code
 * WorkflowMutationEndpointsContractTest} so the Spring context boots even though no real DB queries
 * run.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
class WorkflowControllerMdcCorrelationPropagationTest {

  @Autowired private Environment environment;
  @MockitoBean private WorkflowCommandService workflowCommandService;

  @Test
  void requestCorrelationIdHeaderReachesApproveSpecCommandViaMdc() throws Exception {
    String runId = "run_corr_appr_" + System.nanoTime();
    String correlationId = UUID.randomUUID().toString();
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(runId, WorkflowState.EXECUTING, correlationId));

    HttpResponse<String> response =
        post(
            "/api/v1/workflows/" + runId + "/approve-spec",
            """
            {
              "artifactId": "art_corr_appr",
              "expectedArtifactVersion": 1,
              "expectedContextBundleVersion": 1
            }
            """,
            "idem-corr-appr-" + System.nanoTime(),
            correlationId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);

    ArgumentCaptor<ApproveSpecCommand> captor = ArgumentCaptor.forClass(ApproveSpecCommand.class);
    org.mockito.Mockito.verify(workflowCommandService).approveSpec(captor.capture());
    assertThat(captor.getValue().correlationId())
        .as("controller must read correlationId from MDC populated by CorrelationIdFilter")
        .isEqualTo(correlationId);
  }

  @Test
  void missingRequestCorrelationIdAutoGeneratesUuidV7AndReachesCommand() throws Exception {
    String runId = "run_corr_clar_" + System.nanoTime();
    String clarId = "clr_corr_" + System.nanoTime();
    when(workflowCommandService.answerClarification(any()))
        .thenAnswer(
            invocation -> {
              SubmitClarificationCommand cmd = invocation.getArgument(0);
              return new WorkflowStateChangeResult(
                  runId, WorkflowState.WAITING_FOR_SPEC_APPROVAL, cmd.correlationId(), "answered");
            });

    HttpResponse<String> response =
        post(
            "/api/v1/workflows/" + runId + "/clarifications/" + clarId + "/answer",
            """
            {
              "artifactId": "art_corr_clar",
              "expectedArtifactVersion": 1,
              "answerText": "answer"
            }
            """,
            "idem-corr-clar-" + System.nanoTime(),
            null);

    assertThat(response.statusCode()).isEqualTo(200);
    String filterGenerated = response.headers().firstValue("X-Correlation-Id").orElseThrow();
    assertThat(UUID.fromString(filterGenerated).version())
        .as("CorrelationIdFilter generates a UUIDv7 when X-Correlation-Id is absent")
        .isEqualTo(7);

    ArgumentCaptor<SubmitClarificationCommand> captor =
        ArgumentCaptor.forClass(SubmitClarificationCommand.class);
    org.mockito.Mockito.verify(workflowCommandService).answerClarification(captor.capture());
    assertThat(captor.getValue().correlationId())
        .as("captured command must echo the filter-generated correlationId, not a fresh UUID")
        .isEqualTo(filterGenerated);
  }

  private HttpResponse<String> post(
      String path, String body, String idempotencyKey, String correlationId)
      throws IOException, InterruptedException {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (correlationId != null) {
      builder.header("X-Correlation-Id", correlationId);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
