package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
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
 * Story 3.23 AC10 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/accept-implementation}. The technical-approval twin
 * of {@code ApproveSpecEndpointContractTest}; covers the artifact-type-dependent happy paths
 * (implementationPlan → 200 + {@code Executing}; prOutput → 200 + {@code Completed}), the new
 * developer-only {@code reviewerRole} boundary validation (R4 — blank/non-developer → {@code
 * INVALID_REVIEWER_ROLE_FOR_ENDPOINT}), the spec-artifact guard surfaced by the service as {@code
 * INVALID_COMMAND_PAYLOAD} (R7), the payload-eligibility 503 (R8 — {@code
 * ARTIFACT_PAYLOAD_UNAVAILABLE}) and prOutput PR-link 409 ({@code ARTIFACT_PR_LINK_MISMATCH}), plus
 * the inherited idempotency / version-mismatch / not-found / illegal-transition codes and
 * idempotent replay.
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean WorkflowCommandService} so the test runs
 * without Testcontainers (matches {@code ApproveSpecEndpointContractTest}). A {@link ListAppender}
 * pins the INFO entry/success lines and the WARN reviewer-role rejection line
 * (logging-instrumentation task).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class AcceptImplementationEndpointContractTest {

  private static final String RUN_ID = "run_accept_endpoint_a";
  private static final String ARTIFACT_ID = "art_impl_endpoint_a";
  private static final String IDEMPOTENCY_KEY = "idem-accept-endpoint-aaaaaa";

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

  private ListAppender<ILoggingEvent> appender;
  private Logger controllerLogger;

  @BeforeEach
  void stubActorResolver() {
    // Mirror ApproveSpecEndpointContractTest: delegate to a real resolver so the production
    // length/charset gates run instead of only the trim-and-fallback path.
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
  void happyPathPlanReturnsExecutingAndCapturesDeveloperCommand() throws Exception {
    when(workflowCommandService.acceptImplementation(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 2,
                      "expectedContextBundleVersion": 3,
                      "reviewerRole": "developer",
                      "reason": "LGTM"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Executing"));

    ArgumentCaptor<AcceptImplementationCommand> captor =
        ArgumentCaptor.forClass(AcceptImplementationCommand.class);
    verify(workflowCommandService).acceptImplementation(captor.capture());
    AcceptImplementationCommand captured = captor.getValue();
    assertThat(captured.workflowRunId()).isEqualTo(RUN_ID);
    assertThat(captured.artifactId()).isEqualTo(ARTIFACT_ID);
    // Trap T1: verbose wire versions map to the short command fields.
    assertThat(captured.artifactVersion()).isEqualTo(2);
    assertThat(captured.contextVersion()).isEqualTo(3);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.actorType()).isEqualTo(ActorType.HUMAN);
    assertThat(captured.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(captured.reviewerRole()).isEqualTo("developer");
    assertThat(captured.reason()).isEqualTo("LGTM");

    // Logging-instrumentation task: INFO received + success lines carry run/actor telemetry.
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST accept-implementation received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex"))
        .anyMatch(
            line ->
                line.contains("REST accept-implementation success")
                    && line.contains("currentState=Executing"));
    // The free-form reason must never appear verbatim in logs.
    assertThat(infoLines()).noneMatch(line -> line.contains("LGTM"));
  }

  @Test
  void happyPathPrOutputReturnsCompleted() throws Exception {
    when(workflowCommandService.acceptImplementation(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.COMPLETED, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(developerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentState").value("Completed"));
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(developerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void blankReviewerRoleRejectedAsInvalidReviewerRoleForEndpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1,
                      "reviewerRole": "   "
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"))
        .andExpect(jsonPath("$.retryable").value(false))
        .andExpect(jsonPath("$.details.field").value("reviewerRole"))
        .andExpect(jsonPath("$.details.expected").value("developer"));

    // WARN audit line precedes the typed rejection (logging-instrumentation task).
    assertThat(
            appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList())
        .anyMatch(line -> line.contains("reviewerRole must be 'developer'"));
  }

  @Test
  void nonDeveloperReviewerRoleRejectedAsInvalidReviewerRoleForEndpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1,
                      "reviewerRole": "product_reviewer"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"))
        .andExpect(jsonPath("$.details.actual").value("product_reviewer"));
  }

  @Test
  void specArtifactSurfacesInvalidCommandPayloadFromService() throws Exception {
    // R7: the service guards artifact type and raises INVALID_COMMAND_PAYLOAD for a spec artifact;
    // there is no controller-side type check and no ARTIFACT_TYPE_MISMATCH code.
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("reason", "technical_approval_requires_implementation_artifact");
    when(workflowCommandService.acceptImplementation(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_COMMAND_PAYLOAD,
                "Technical approval requires an implementation artifact",
                details));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(
            jsonPath("$.details.reason")
                .value("technical_approval_requires_implementation_artifact"));
  }

  @Test
  void requestSchemaValidationFailureSurfacesInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": " ",
                      "expectedArtifactVersion": -1,
                      "expectedContextBundleVersion": 1,
                      "reviewerRole": "developer"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        // Pin BOTH intended violations rather than the non-deterministically-ordered $.details[0]:
        // a valid expectedContextBundleVersion removes the third (silent) violation, and matching
        // the
        // field set proves @NotBlank(artifactId) + @Positive(expectedArtifactVersion) both fired.
        .andExpect(
            jsonPath("$.details[*].field", hasItems("artifactId", "expectedArtifactVersion")))
        .andExpect(jsonPath("$.details[0].constraint").exists());
  }

  @Test
  void approvalVersionMismatchReturns409() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("expectedArtifactVersion", 1);
    details.put("currentArtifactVersion", 4);
    when(workflowCommandService.acceptImplementation(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.APPROVAL_VERSION_MISMATCH, "Artifact version is stale", details));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("APPROVAL_VERSION_MISMATCH"))
        .andExpect(jsonPath("$.details.currentArtifactVersion").value(4));
  }

  @Test
  void artifactPayloadUnavailableReturns503() throws Exception {
    // R8: accept runs the eligibility gate; the live catalog maps this to 503 retryable.
    when(workflowCommandService.acceptImplementation(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
                "Artifact payload is not yet available",
                Map.of("artifactId", ARTIFACT_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ARTIFACT_PAYLOAD_UNAVAILABLE"))
        .andExpect(jsonPath("$.retryable").value(true));
  }

  @Test
  void artifactPrLinkMismatchReturns409() throws Exception {
    // R8: prOutput acceptance runs the PR-link gate.
    when(workflowCommandService.acceptImplementation(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH,
                "Artifact PR reference no longer matches the durable link",
                Map.of("artifactId", ARTIFACT_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ARTIFACT_PR_LINK_MISMATCH"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(workflowCommandService.acceptImplementation(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void illegalTransitionWhenStateForbidsActionReturns409() throws Exception {
    // R3: "state forbids the action" surfaces as ILLEGAL_TRANSITION; no ACTION_NOT_ALLOWED code.
    when(workflowCommandService.acceptImplementation(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "No legal transition from the current state",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(workflowCommandService.acceptImplementation(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void identicalReplayProducesIdenticalResponseBody() throws Exception {
    when(workflowCommandService.acceptImplementation(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null));

    String body = developerBody();
    String first = perform(body).getResponse().getContentAsString();
    String second = perform(body).getResponse().getContentAsString();
    assertThat(first).isEqualTo(second);
    verify(workflowCommandService, times(2)).acceptImplementation(any());
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(workflowCommandService.acceptImplementation(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isOk());

    ArgumentCaptor<AcceptImplementationCommand> captor =
        ArgumentCaptor.forClass(AcceptImplementationCommand.class);
    verify(workflowCommandService).acceptImplementation(captor.capture());
    assertThat(captor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void requestCorrelationIdOnMdcPropagatesIntoCapturedCommandAndResponseBody() throws Exception {
    String correlationId = "01900000-0000-7000-8000-aabbccddeeff";
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    when(workflowCommandService.acceptImplementation(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, correlationId));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(developerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value(correlationId));

    ArgumentCaptor<AcceptImplementationCommand> captor =
        ArgumentCaptor.forClass(AcceptImplementationCommand.class);
    verify(workflowCommandService).acceptImplementation(captor.capture());
    assertThat(captor.getValue().correlationId()).isEqualTo(correlationId);
  }

  private java.util.List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private String developerBody() {
    return """
        {
          "artifactId": "%s",
          "expectedArtifactVersion": 1,
          "expectedContextBundleVersion": 1,
          "reviewerRole": "developer"
        }
        """
        .formatted(ARTIFACT_ID);
  }

  private org.springframework.test.web.servlet.MvcResult perform(String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }
}
