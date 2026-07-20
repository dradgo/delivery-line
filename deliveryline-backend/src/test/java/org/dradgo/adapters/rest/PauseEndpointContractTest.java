package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.List;
import java.util.Map;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.PauseRecoveryResult;
import org.dradgo.application.recovery.RecoveryService;
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
 * Story 4.13 AC9 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/pause}. The recovery sibling of {@code
 * ResumeEndpointContractTest} / {@code RerunFromStepEndpointContractTest}, wired to the RICH {@link
 * RecoveryService#pause} (Reconciliation 2) and mapping the {@link PauseResponse} (Reconciliation
 * 6). Covers: happy-path pause-from-Executing + pause-from-WaitingForReview (200 + {@code
 * currentState=Paused} + echoed {@code priorState} + {@code recoveryActionId ^rcv_} + the two
 * {@code int} cancellation counts + {@code replayed=false}, capturing the FOUR positional args — no
 * domain field before {@code idempotencyKey}, {@code reasonText} LAST); idempotent replay ({@code
 * replayed=true}, both counts 0, {@code priorState} non-null, {@code currentState=Paused});
 * degenerate replay ({@code priorState=null} → response omits it, proving the null-guard); {@code
 * MISSING_REASON_TEXT} → 400 (blank reason — proving {@code reasonText} is NOT {@code @NotBlank},
 * the defining trap); {@code PAUSE_NOT_APPLICABLE} → 409 ({@code details.currentState} present);
 * {@code RUN_NOT_FOUND} → 404; {@code IDEMPOTENCY_KEY_CONFLICT} → 409; missing/blank {@code
 * Idempotency-Key} → 400; multi-valued header → 400; {@code role != workflow_owner} → 400; blank
 * {@code role} → 400 {@code INVALID_COMMAND_PAYLOAD} (bean-validation fires first); unknown body
 * field → 400; omitted {@code X-Actor-Identity} → captured {@code local-operator}.
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean RecoveryService} so the test runs without
 * Testcontainers. A {@link ListAppender} pins the INFO entry/success lines (free-form {@code
 * reasonText} never logged verbatim; {@code priorState}/{@code currentState} values ARE logged —
 * bounded governed enums) and the WARN role-rejection line. The
 * {@code @BeforeAll}/{@code @AfterAll} identity-holder guard keeps the shared {@code
 * RedactionLayoutHolder} wired so a reused Surefire fork does not mask CapturedOutput-based sibling
 * tests (webmvctest-redaction-holder trap).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class PauseEndpointContractTest {

  private static final String RUN_ID = "run_pause_endpoint_a";
  private static final String RECOVERY_ID = "rcv_pause_endpoint_a";
  private static final String PAUSED_EVENT_ID = "evt_pause_endpoint_a";
  private static final String IDEMPOTENCY_KEY = "idem-pause-endpoint-aaaaaa";

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

  private static PauseRecoveryResult freshResult(
      WorkflowState priorState, int inFlight, int queued) {
    return new PauseRecoveryResult(
        RECOVERY_ID,
        PAUSED_EVENT_ID,
        priorState,
        inFlight,
        queued,
        WorkflowState.PAUSED,
        null,
        false);
  }

  @Test
  void happyPathPauseFromExecutingReturns200WithCountsAndCapturesFourPositionalArgs()
      throws Exception {
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenReturn(freshResult(WorkflowState.EXECUTING, 2, 3));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "reasonText": "Uncertain about the runner output; pausing to inspect."
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Paused"))
        .andExpect(jsonPath("$.priorState").value("Executing"))
        .andExpect(jsonPath("$.recoveryActionId").value(RECOVERY_ID))
        .andExpect(jsonPath("$.recoveryActionId").value(org.hamcrest.Matchers.startsWith("rcv_")))
        .andExpect(jsonPath("$.pausedEventId").value(PAUSED_EVENT_ID))
        .andExpect(jsonPath("$.cancelledInFlightCount").value(2))
        .andExpect(jsonPath("$.cancelledQueuedCount").value(3))
        .andExpect(jsonPath("$.replayed").value(false));

    // Reconciliation 4 — the four positional args, reasonText LAST, no domain field before the key.
    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .pause(
            eq(RUN_ID),
            eq(IDEMPOTENCY_KEY),
            actorCaptor.capture(),
            eq("Uncertain about the runner output; pausing to inspect."));
    ActorContext actor = actorCaptor.getValue();
    assertThat(actor.actorIdentity()).isEqualTo("alex");
    assertThat(actor.actorType()).isEqualTo(ActorType.HUMAN);

    // Logging-instrumentation task: INFO received + success lines carry run/actor + state
    // telemetry;
    // the free-form reasonText never appears verbatim (length only).
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST pause received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex"))
        .anyMatch(
            line ->
                line.contains("REST pause success")
                    && line.contains("currentState=Paused")
                    && line.contains("priorState=Executing")
                    && line.contains("recoveryActionId=" + RECOVERY_ID)
                    && line.contains("cancelledInFlightCount=2")
                    && line.contains("cancelledQueuedCount=3"));
    assertThat(infoLines()).noneMatch(line -> line.contains("pausing to inspect"));
  }

  @Test
  void happyPathPauseFromWaitingForReviewReturns200WithZeroCounts() throws Exception {
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenReturn(freshResult(WorkflowState.WAITING_FOR_REVIEW, 0, 0));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentState").value("Paused"))
        .andExpect(jsonPath("$.priorState").value("WaitingForReview"))
        .andExpect(jsonPath("$.cancelledInFlightCount").value(0))
        .andExpect(jsonPath("$.cancelledQueuedCount").value(0));

    verify(recoveryService).pause(eq(RUN_ID), eq(IDEMPOTENCY_KEY), any(), any());
  }

  @Test
  void idempotentReplayReturnsReplayedTrueWithZeroCountsAndNonNullState() throws Exception {
    // On replay the rich service returns the prior row without re-cancellation: both counts 0,
    // priorState re-derived (non-null), currentState still Paused (Reconciliation 6).
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenReturn(
            new PauseRecoveryResult(
                RECOVERY_ID,
                PAUSED_EVENT_ID,
                WorkflowState.EXECUTING,
                0,
                0,
                WorkflowState.PAUSED,
                null,
                true));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true))
        .andExpect(jsonPath("$.currentState").value("Paused"))
        .andExpect(jsonPath("$.priorState").value("Executing"))
        .andExpect(jsonPath("$.cancelledInFlightCount").value(0))
        .andExpect(jsonPath("$.cancelledQueuedCount").value(0));
  }

  @Test
  void degenerateReplayWithNullPriorStateOmitsPriorStateProvingNullGuard() throws Exception {
    // ⚠️ Reconciliation 6 — priorState MAY BE NULL on a degenerate replay (the persisted
    // recovery.paused event lost its typed priorState AND no → Paused transition event survives).
    // PauseResponse.from null-guards it, so the wire field is omitted; currentState (always Paused)
    // is dereferenced directly and stays present.
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenReturn(
            new PauseRecoveryResult(
                RECOVERY_ID, PAUSED_EVENT_ID, null, 0, 0, WorkflowState.PAUSED, null, true));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentState").value("Paused"))
        .andExpect(jsonPath("$.priorState").doesNotExist())
        .andExpect(jsonPath("$.replayed").value(true));
  }

  @Test
  void blankReasonTextSurfacesTypedMissingReasonTextProvingNoNotBlank() throws Exception {
    // ⚠️ The DEFINING trap (shared with rerun 4.12, opposite of reconcile 4.11 and resume 4.10):
    // reasonText carries NO @NotBlank (Reconciliation 5) — a blank value passes bean-validation and
    // reaches the service, which surfaces the DISTINCT typed MISSING_REASON_TEXT (400). An
    // @NotBlank
    // here would preempt it as INVALID_COMMAND_PAYLOAD, masking the typed code.
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MISSING_REASON_TEXT, "Missing reason text", Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "reasonText": "   "
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_REASON_TEXT"))
        .andExpect(jsonPath("$.retryable").value(false));

    verify(recoveryService).pause(eq(RUN_ID), eq(IDEMPOTENCY_KEY), any(), eq("   "));
  }

  @Test
  void wrongSourceStateSurfacesPauseNotApplicable409WithCurrentStateDetail() throws Exception {
    // Reconciliation 7 — the PAUSABLE_SOURCE_STATES gate throws the dedicated PAUSE_NOT_APPLICABLE
    // (409) for any source outside the 8 pausable states (incl. terminal, already Paused,
    // TakenOver).
    // ACTION_NOT_ALLOWED does NOT exist. details.currentState is present.
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.PAUSE_NOT_APPLICABLE,
                "Pause is not applicable from state Completed",
                Map.of("runId", RUN_ID, "currentState", "Completed")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAUSE_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.details.currentState").value("Completed"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
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
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
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
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
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
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("Idempotency-Key", "idem-pause-endpoint-bbbbbb")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("Idempotency-Key"));
  }

  @Test
  void blankRoleRejectedAsInvalidCommandPayloadByBeanValidation() throws Exception {
    // role carries @NotBlank (mirrors resume/reconcile/rerun), so a blank value is caught by bean
    // validation as INVALID_COMMAND_PAYLOAD BEFORE requireWorkflowOwnerRole runs — no WARN emitted.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
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
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
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
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
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
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(recoveryService.pause(any(), any(), any(), any()))
        .thenReturn(freshResult(WorkflowState.EXECUTING, 1, 0));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk());

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService).pause(eq(RUN_ID), eq(IDEMPOTENCY_KEY), actorCaptor.capture(), any());
    assertThat(actorCaptor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void multiValuedActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    // The pause endpoint invokes rejectMultiValuedActorIdentityHeader before resolving the actor —
    // two X-Actor-Identity headers coalesce into a duplicate the guard rejects as a typed
    // INVALID_COMMAND_PAYLOAD (details.header = X-Actor-Identity); the rich service is never
    // reached.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .header("X-Actor-Identity", "mallory")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"));

    org.mockito.Mockito.verifyNoInteractions(recoveryService);
  }

  @Test
  void unsafeActorIdentityHeaderValueRejectedAsInvalidCommandPayload() throws Exception {
    // requireSafe rejects an unsafe actor identity (here an oversize value >
    // MAX_ACTOR_IDENTITY_LENGTH=128; the same guard also rejects control-char/CRLF-injection
    // values)
    // as a typed INVALID_COMMAND_PAYLOAD (details.header = X-Actor-Identity) before the service
    // call
    // — the @BeforeEach stub calls through to the real resolver so the guard actually fires.
    String oversizeActor = "a".repeat(129);
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", oversizeActor)
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"));

    org.mockito.Mockito.verifyNoInteractions(recoveryService);
  }

  private List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private List<String> warnLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private String workflowOwnerBody() {
    return """
        {
          "role": "workflow_owner",
          "reasonText": "Pausing to inspect before continuing."
        }
        """;
  }
}
