package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.ResumeRecoveryResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * Story 4.10 AC9 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/resume}. The recovery sibling of {@code
 * TakeoverEndpointContractTest}, wired to the RICH {@link RecoveryService#resume} (Reconciliation
 * 2) and mapping the {@link ResumeResponse} (Reconciliation 5 + 6). Covers: happy-path resume from
 * Paused (200 + {@code currentState=Executing} + {@code recoveryActionId ^rcv_} + present {@code
 * runnerExecutionId} + {@code replayed=false}, capturing the positional {@link ActorContext});
 * idempotent replay ({@code replayed=true}, {@code runnerExecutionId} null, {@code currentState}
 * null-tolerant); {@code RESUME_NOT_APPLICABLE} → 409 + {@code details.currentState};
 * IDEMPOTENCY_KEY_CONFLICT → 409; RUN_NOT_FOUND → 404; missing/blank {@code Idempotency-Key} → 400;
 * multi-valued header → 400 {@code INVALID_COMMAND_PAYLOAD}; {@code role != workflow_owner} → 400
 * {@code INVALID_REVIEWER_ROLE_FOR_ENDPOINT}; unknown body field → 400; omitted {@code
 * X-Actor-Identity} → captured {@code local-operator}.
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean RecoveryService} so the test runs without
 * Testcontainers. A {@link ListAppender} pins the INFO entry/success lines (free-form {@code
 * reasonText} never logged verbatim) and the WARN role-rejection line. The
 * {@code @BeforeAll}/{@code @AfterAll} identity-holder guard keeps the shared {@code
 * RedactionLayoutHolder} wired so a reused Surefire fork does not mask CapturedOutput-based sibling
 * tests (webmvctest-redaction-holder trap).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ResumeEndpointContractTest {

  private static final String RUN_ID = "run_resume_endpoint_a";
  private static final String RECOVERY_ID = "rcv_resume_endpoint_a";
  private static final String RESUMED_EVENT_ID = "evt_resume_endpoint_a";
  private static final String RUNNER_EXEC_ID = "rex_resume_endpoint_a";
  private static final String IDEMPOTENCY_KEY = "idem-resume-endpoint-aaaaaa";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private RecoveryService recoveryService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;
  @MockitoBean private org.dradgo.application.workflow.RunDependencyService runDependencyService;
  @MockitoBean private org.dradgo.application.workflow.SplitProposalService splitProposalService;
  @MockitoBean private org.dradgo.application.workflow.SplitCommitService splitCommitService;

  private static RedactionPolicyService priorRedactionService;

  private ListAppender<ILoggingEvent> appender;
  private Logger controllerLogger;

  @BeforeAll
  static void wireRedactionHolder() {
    priorRedactionService = RedactionLayoutHolder.currentForTesting();
    RedactionLayoutHolder.setRedactionService(
        new RedactionPolicyService(new DataClassificationService()));
  }

  @AfterAll
  static void unwireRedactionHolder() {
    if (priorRedactionService == null) {
      RedactionLayoutHolder.clearForTesting();
    } else {
      RedactionLayoutHolder.setRedactionService(priorRedactionService);
    }
  }

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
  void happyPathReturnsExecutingWithRunnerExecutionAndCapturesActorContext() throws Exception {
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                RECOVERY_ID,
                RESUMED_EVENT_ID,
                RUNNER_EXEC_ID,
                WorkflowState.EXECUTING,
                null,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "reasonText": "Broker recovered; resuming the parked run."
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Executing"))
        .andExpect(jsonPath("$.recoveryActionId").value(RECOVERY_ID))
        .andExpect(jsonPath("$.recoveryActionId").value(org.hamcrest.Matchers.startsWith("rcv_")))
        .andExpect(jsonPath("$.resumedEventId").value(RESUMED_EVENT_ID))
        .andExpect(jsonPath("$.runnerExecutionId").value(RUNNER_EXEC_ID))
        .andExpect(jsonPath("$.replayed").value(false));

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .resume(
            eq(RUN_ID),
            eq(IDEMPOTENCY_KEY),
            actorCaptor.capture(),
            eq("Broker recovered; resuming the parked run."));
    ActorContext actor = actorCaptor.getValue();
    assertThat(actor.actorIdentity()).isEqualTo("alex");
    assertThat(actor.actorType()).isEqualTo(ActorType.HUMAN);

    // Logging-instrumentation task: INFO received + success lines carry run/actor + result
    // telemetry; the free-form reasonText never appears verbatim (length only).
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST resume received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex"))
        .anyMatch(
            line ->
                line.contains("REST resume success")
                    && line.contains("currentState=Executing")
                    && line.contains("recoveryActionId=" + RECOVERY_ID)
                    && line.contains("runnerExecutionId=" + RUNNER_EXEC_ID));
    assertThat(infoLines()).noneMatch(line -> line.contains("resuming the parked run"));
  }

  @Test
  void idempotentReplayReturnsReplayedTrueWithNullRunnerAndNullTolerantState() throws Exception {
    // On replay the rich service returns the prior row without re-dispatch: runnerExecutionId null,
    // currentState may be null when the → Paused anchor can no longer be resolved (Reconciliation
    // 6).
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(RECOVERY_ID, RESUMED_EVENT_ID, null, null, null, true));

    String body =
        mockMvc
            .perform(
                post("/api/v1/workflows/{runId}/resume", RUN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .header("X-Actor-Identity", "alex")
                    .content(workflowOwnerBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true))
            .andExpect(jsonPath("$.runnerExecutionId").doesNotExist())
            .andExpect(jsonPath("$.currentState").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("\"replayed\":true");
  }

  @Test
  void resumeFromNonPausedStateReturns409ResumeNotApplicableWithCurrentState() throws Exception {
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RESUME_NOT_APPLICABLE,
                "Run is not Paused",
                Map.of("currentState", "WaitingForReview", "reason", "not_paused")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RESUME_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.retryable").value(false))
        .andExpect(jsonPath("$.details.currentState").value("WaitingForReview"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void blankIdempotencyKeyHeaderMapsToMissingIdempotencyKey() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "   ")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void multiValuedIdempotencyKeyHeaderRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("Idempotency-Key", "idem-resume-endpoint-bbbbbb")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("Idempotency-Key"));
  }

  @Test
  void blankRoleRejectedAsInvalidCommandPayloadByBeanValidation() throws Exception {
    // role carries @NotBlank (mirrors ApproveLintRequest), so a blank value is caught by bean
    // validation as INVALID_COMMAND_PAYLOAD BEFORE requireWorkflowOwnerRole runs — no WARN is
    // emitted. The non-blank-wrong-role path (below) is what reaches the boundary role gate.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "   ",
                      "reasonText": "valid reason"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details[*].field", org.hamcrest.Matchers.hasItem("role")));
  }

  @Test
  void nonWorkflowOwnerRoleRejectedAsInvalidReviewerRoleForEndpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "developer",
                      "reasonText": "valid reason"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"))
        .andExpect(jsonPath("$.details.field").value("role"))
        .andExpect(jsonPath("$.details.expected").value("workflow_owner"))
        .andExpect(jsonPath("$.details.actual").value("developer"));

    assertThat(warnLines()).anyMatch(line -> line.contains("role must be 'workflow_owner'"));
  }

  @Test
  void unknownBodyFieldRejectedAsInvalidCommandPayload() throws Exception {
    // @JsonIgnoreProperties(ignoreUnknown = false) — a mis-shaped body fails fast via the Jackson
    // deserialization advice as INVALID_COMMAND_PAYLOAD; the rich service is never reached.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "reasonText": "valid",
                      "bogus": "unexpected"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void omittedReasonTextIsAcceptedAndPassedThroughAsNull() throws Exception {
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                RECOVERY_ID,
                RESUMED_EVENT_ID,
                RUNNER_EXEC_ID,
                WorkflowState.EXECUTING,
                null,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner"
                    }
                    """))
        .andExpect(status().isOk());

    verify(recoveryService).resume(eq(RUN_ID), eq(IDEMPOTENCY_KEY), any(), eq(null));
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                RECOVERY_ID,
                RESUMED_EVENT_ID,
                RUNNER_EXEC_ID,
                WorkflowState.EXECUTING,
                null,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk());

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService).resume(eq(RUN_ID), eq(IDEMPOTENCY_KEY), actorCaptor.capture(), any());
    assertThat(actorCaptor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void requestCorrelationIdOnMdcPropagatesIntoCapturedActorAndResponseBody() throws Exception {
    String correlationId = "01900000-0000-7000-8000-aabbccddeeff";
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                RECOVERY_ID,
                RESUMED_EVENT_ID,
                RUNNER_EXEC_ID,
                WorkflowState.EXECUTING,
                correlationId,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value(correlationId));

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService).resume(eq(RUN_ID), eq(IDEMPOTENCY_KEY), actorCaptor.capture(), any());
    assertThat(actorCaptor.getValue().correlationId()).isEqualTo(correlationId);
  }

  @Test
  void replayThenReplayInvokesServiceEachTime() throws Exception {
    when(recoveryService.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                RECOVERY_ID, RESUMED_EVENT_ID, null, WorkflowState.EXECUTING, null, true));

    perform(workflowOwnerBody());
    perform(workflowOwnerBody());
    verify(recoveryService, times(2)).resume(any(), any(), any(), any());
  }

  private java.util.List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private java.util.List<String> warnLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private String workflowOwnerBody() {
    return """
        {
          "role": "workflow_owner",
          "reasonText": "Resuming the parked run."
        }
        """;
  }

  private org.springframework.test.web.servlet.MvcResult perform(String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/resume", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }
}
