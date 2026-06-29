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
import org.dradgo.application.recovery.TakeoverResult;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
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
 * Story 3.25 AC10 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/takeover}. The developer-takeover sibling of {@code
 * AcceptImplementationEndpointContractTest} / {@code RejectImplementationEndpointContractTest}, but
 * wired to the RICH {@link DeveloperTakeoverService} (R1) and mapping a richer {@link
 * TakeoverResponse} (R5 — cancelled-runner counts + preserved PR ref). Covers: happy-path takeover
 * from a non-terminal state (200 + {@code currentState=TakenOver} + counts + PR ref + {@code
 * recoveryActionId}); terminal-run → 409 ({@code ILLEGAL_TRANSITION} / {@code
 * WORKFLOW_RUN_TERMINAL}, no controller allowed-actions pre-check — R3); blank {@code reasonText} →
 * 400 {@code INVALID_COMMAND_PAYLOAD} (R3); non-developer/blank {@code reviewerRole} → 400 {@code
 * INVALID_REVIEWER_ROLE_FOR_ENDPOINT} (R8); missing/conflicting idempotency key; not-found; and
 * idempotent replay ({@code replayed=true}, counts {@code null}).
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean DeveloperTakeoverService} so the test runs
 * without Testcontainers (matches {@code AcceptImplementationEndpointContractTest}). A {@link
 * ListAppender} pins the INFO entry/success lines (free-form {@code reasonText} never logged
 * verbatim) and the WARN reviewer-role rejection line (logging-instrumentation task).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class TakeoverEndpointContractTest {

  private static final String RUN_ID = "run_takeover_endpoint_a";
  private static final String RECOVERY_ID = "rcv_takeover_endpoint_a";
  private static final String IDEMPOTENCY_KEY = "idem-takeover-endpoint-aaaaaa";
  private static final String PR_REF = "https://github.com/acme/repo/pull/42";

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

  // Story 3f-4 — WorkflowController gained the split-proposal service; the bean must exist for this
  // @WebMvcTest slice to construct the controller.
  @MockitoBean private org.dradgo.application.workflow.SplitProposalService splitProposalService;

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
  void happyPathReturnsTakenOverWithCountsPrRefAndCapturesDeveloperCommand() throws Exception {
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenReturn(
            new TakeoverResult(
                RUN_ID,
                RECOVERY_ID,
                WorkflowState.TAKEN_OVER,
                "evt_takeover_a",
                2,
                1,
                PR_REF,
                null,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "reasonText": "Agent stuck; taking over the PR manually.",
                      "reviewerRole": "developer"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("TakenOver"))
        .andExpect(jsonPath("$.recoveryActionId").value(RECOVERY_ID))
        .andExpect(jsonPath("$.cancelledInFlightCount").value(2))
        .andExpect(jsonPath("$.cancelledQueuedCount").value(1))
        .andExpect(jsonPath("$.preservedPrReference").value(PR_REF))
        .andExpect(jsonPath("$.replayed").value(false));

    ArgumentCaptor<TakeoverWorkflowCommand> captor =
        ArgumentCaptor.forClass(TakeoverWorkflowCommand.class);
    verify(developerTakeoverService).takeoverWorkflow(captor.capture());
    TakeoverWorkflowCommand captured = captor.getValue();
    assertThat(captured.workflowRunId()).isEqualTo(RUN_ID);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.actorType()).isEqualTo(ActorType.HUMAN);
    assertThat(captured.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(captured.reasonText()).isEqualTo("Agent stuck; taking over the PR manually.");

    // Logging-instrumentation task: INFO received + success lines carry run/actor + result
    // telemetry.
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST takeover received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex"))
        .anyMatch(
            line ->
                line.contains("REST takeover success")
                    && line.contains("currentState=TakenOver")
                    && line.contains("recoveryActionId=" + RECOVERY_ID));
    // The free-form reasonText must never appear verbatim in logs (length only).
    assertThat(infoLines()).noneMatch(line -> line.contains("taking over the PR manually"));
  }

  @Test
  void takeoverFromNonTerminalRunningStateSucceeds() throws Exception {
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenReturn(
            new TakeoverResult(
                RUN_ID, RECOVERY_ID, WorkflowState.TAKEN_OVER, "evt_b", 0, 0, null, null, false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentState").value("TakenOver"))
        .andExpect(jsonPath("$.cancelledInFlightCount").value(0))
        .andExpect(jsonPath("$.preservedPrReference").doesNotExist());
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(developerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void blankReasonTextRejectedAsInvalidCommandPayload() throws Exception {
    // R3: @NotBlank reasonText surfaces as INVALID_COMMAND_PAYLOAD via bean validation; the rich
    // service is never reached.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "reasonText": "   ",
                      "reviewerRole": "developer"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details[*].field", org.hamcrest.Matchers.hasItem("reasonText")));
  }

  @Test
  void blankReviewerRoleRejectedAsInvalidReviewerRoleForEndpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "reasonText": "valid reason",
                      "reviewerRole": "   "
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"))
        .andExpect(jsonPath("$.details.field").value("reviewerRole"))
        .andExpect(jsonPath("$.details.expected").value("developer"));

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
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "reasonText": "valid reason",
                      "reviewerRole": "product_reviewer"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"))
        .andExpect(jsonPath("$.details.actual").value("product_reviewer"));
  }

  @Test
  void takingOverTerminalRunReturns409IllegalTransition() throws Exception {
    // R3: terminal-state takeover fails inside the transition as ILLEGAL_TRANSITION; there is no
    // controller-side allowed-actions pre-check and no ACTION_NOT_ALLOWED code.
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "No legal transition from the current state",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
  }

  @Test
  void terminalRunReturns409WorkflowRunTerminal() throws Exception {
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.WORKFLOW_RUN_TERMINAL, "Run is in a terminal state", Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKFLOW_RUN_TERMINAL"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void idempotentReplayReturnsReplayedTrueWithNullCountsAndIdenticalBody() throws Exception {
    // On replay the rich service reconstructs the prior succeeded takeover: counts + PR ref null,
    // replayed=true (the cancels already happened and are not recomputed).
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenReturn(
            new TakeoverResult(
                RUN_ID,
                RECOVERY_ID,
                WorkflowState.TAKEN_OVER,
                "evt_r",
                null,
                null,
                null,
                null,
                true));

    String first = perform(developerBody()).getResponse().getContentAsString();
    String second = perform(developerBody()).getResponse().getContentAsString();
    assertThat(first).isEqualTo(second);
    assertThat(first).contains("\"replayed\":true");
    verify(developerTakeoverService, times(2)).takeoverWorkflow(any());
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenReturn(
            new TakeoverResult(
                RUN_ID, RECOVERY_ID, WorkflowState.TAKEN_OVER, "evt_c", 0, 0, null, null, false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(developerBody()))
        .andExpect(status().isOk());

    ArgumentCaptor<TakeoverWorkflowCommand> captor =
        ArgumentCaptor.forClass(TakeoverWorkflowCommand.class);
    verify(developerTakeoverService).takeoverWorkflow(captor.capture());
    assertThat(captor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void requestCorrelationIdOnMdcPropagatesIntoCapturedCommandAndResponseBody() throws Exception {
    String correlationId = "01900000-0000-7000-8000-aabbccddeeff";
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    when(developerTakeoverService.takeoverWorkflow(any()))
        .thenReturn(
            new TakeoverResult(
                RUN_ID,
                RECOVERY_ID,
                WorkflowState.TAKEN_OVER,
                "evt_d",
                1,
                0,
                null,
                correlationId,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(developerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value(correlationId));

    ArgumentCaptor<TakeoverWorkflowCommand> captor =
        ArgumentCaptor.forClass(TakeoverWorkflowCommand.class);
    verify(developerTakeoverService).takeoverWorkflow(captor.capture());
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
          "reasonText": "Taking over for manual continuation.",
          "reviewerRole": "developer"
        }
        """;
  }

  private org.springframework.test.web.servlet.MvcResult perform(String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/takeover", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }
}
