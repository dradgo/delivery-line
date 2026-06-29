package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.ManualArtifactSubmissionService.ManualArtifactSubmissionCommand;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3d-4 (review follow-up 2026-06-23) — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/manual-artifact}. Mirrors {@code
 * AcceptImplementationEndpointContractTest}: a {@code @WebMvcTest} slice with a mocked {@link
 * ManualArtifactSubmissionService} pins the error-code→status mappings the catalog declares —
 * happy-path 200, {@code MISSING_IDEMPOTENCY_KEY} 400, {@code MANUAL_EXECUTION_NOT_APPLICABLE} +
 * {@code IDEMPOTENCY_KEY_CONFLICT} 409, the merged runner-output codes ({@code
 * RUNNER_OUTPUT_VALIDATION_FAILED} / {@code RUNNER_ARTIFACT_TYPE_MISMATCH}) 502, {@code
 * RUN_NOT_FOUND} 404, and the boundary {@code @NotNull result} → {@code INVALID_COMMAND_PAYLOAD}
 * 400 — asserting {@code code}+{@code status} only, never human text. Also pins the header preamble
 * threading (idempotency key + actor) and idempotent-replay byte-identity of the response body.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ManualArtifactEndpointContractTest {

  private static final String RUN_ID = "run_manual_artifact_a";
  private static final String IDEMPOTENCY_KEY = "idem-manual-artifact-aaaaaa";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;

  // Story 3f-3 — WorkflowController gained the run-dependency declaration/inspection service; the
  // bean must exist for this @WebMvcTest slice to construct the controller.
  @MockitoBean private org.dradgo.application.workflow.RunDependencyService runDependencyService;

  // Story 3f-4 — WorkflowController gained the split-proposal service; the bean must exist for
  // this
  // @WebMvcTest slice to construct the controller.
  @MockitoBean private org.dradgo.application.workflow.SplitProposalService splitProposalService;
  @MockitoBean private org.dradgo.application.workflow.SplitCommitService splitCommitService;

  private ListAppender<ILoggingEvent> appender;
  private Logger controllerLogger;

  @BeforeEach
  void stubActorResolver() {
    LocalActorIdentityResolver real = new LocalActorIdentityResolver("local-operator");
    when(localActorIdentityResolver.resolve(any()))
        .thenAnswer(invocation -> real.resolve(invocation.getArgument(0)));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              real.requireSafe(invocation.getArgument(0));
              return null;
            })
        .when(localActorIdentityResolver)
        .requireSafe(any());

    appender = new ListAppender<>();
    appender.start();
    controllerLogger = (Logger) LoggerFactory.getLogger(WorkflowController.class);
    controllerLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(appender);
    appender.stop();
    MDC.remove(MdcKeys.CORRELATION_ID);
  }

  @Test
  void happyPathReturnsWaitingForReviewAndThreadsHeaderPreamble() throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.WAITING_FOR_REVIEW, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(submissionBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("WaitingForReview"));

    ArgumentCaptor<ManualArtifactSubmissionCommand> captor =
        ArgumentCaptor.forClass(ManualArtifactSubmissionCommand.class);
    verify(manualArtifactSubmissionService).submit(captor.capture());
    ManualArtifactSubmissionCommand captured = captor.getValue();
    assertThat(captured.workflowRunId()).isEqualTo(RUN_ID);
    assertThat(captured.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.actorType()).isEqualTo(ActorType.HUMAN);

    // Controller log-branch pin: entry + success INFO carry run/actor telemetry; never the payload.
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST manual-artifact received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex"))
        .anyMatch(
            line ->
                line.contains("REST manual-artifact success")
                    && line.contains("currentState=WaitingForReview"));
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(submissionBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void manualExecutionNotApplicableReturns409() throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE,
                "Manual execution is not applicable",
                Map.of("runId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(submissionBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MANUAL_EXECUTION_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(submissionBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void outputValidationFailureReturns502() throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED,
                "Manual artifact failed runner-output validation",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(submissionBody()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("RUNNER_OUTPUT_VALIDATION_FAILED"));
  }

  @Test
  void artifactTypeMismatchReturns502() throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH,
                "Manual artifact type is not allowed for the parked stage",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(submissionBody()))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("RUNNER_ARTIFACT_TYPE_MISMATCH"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found", Map.of("runId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(submissionBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void missingResultBodyFieldSurfacesInvalidCommandPayload() throws Exception {
    // Boundary @NotNull on `result`; the authoritative runner-contract validation lives in the
    // service, so the controller-side check is intentionally minimal (result present).
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void identicalReplayProducesIdenticalResponseBody() throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.WAITING_FOR_REVIEW, null));

    String first = perform().getResponse().getContentAsString();
    String second = perform().getResponse().getContentAsString();
    assertThat(first).isEqualTo(second);
    verify(manualArtifactSubmissionService, times(2)).submit(any());
  }

  private java.util.List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private String submissionBody() {
    // A runner-result-shaped `result` node; the service is mocked, so only well-formed JSON matters
    // here (the authoritative schema validation is exercised in
    // ManualArtifactSubmissionServiceTest).
    return """
        {
          "result": {
            "schemaVersion": 1,
            "workflowRunId": "run_manual_artifact_a",
            "runnerExecutionId": "rex_manual_a",
            "artifactReferences": []
          }
        }
        """;
  }

  private org.springframework.test.web.servlet.MvcResult perform() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(submissionBody()))
        .andExpect(status().isOk())
        .andReturn();
  }
}
