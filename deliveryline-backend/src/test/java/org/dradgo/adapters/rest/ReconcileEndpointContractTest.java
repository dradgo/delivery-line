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
import org.dradgo.application.recovery.ReconcileRecoveryResult;
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
 * Story 4.11 AC9 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/reconcile}. The recovery sibling of {@code
 * ResumeEndpointContractTest}, wired to the RICH {@link RecoveryService#reconcile} (Reconciliation
 * 2) and mapping the {@link ReconcileResponse} (Reconciliation 5 + 7). Covers: happy-path reconcile
 * per {@code ReconciliationDecision} value (200 + {@code currentState=Reconciled} + {@code
 * recoveryActionId ^rcv_} + echoed {@code resolvedConflictId} + {@code replayed=false}, capturing
 * the six positional args incl. {@link ActorContext}); idempotent replay ({@code replayed=true},
 * non-null {@code currentState}); {@code MISSING_RECONCILIATION_DECISION} → 400; {@code
 * INVALID_RECONCILIATION_DECISION} → 400; blank {@code reasonText} → 400 {@code
 * INVALID_COMMAND_PAYLOAD}; {@code CONFLICT_NOT_FOUND} → 404; {@code CONFLICT_ALREADY_RESOLVED} →
 * 409; {@code RECONCILE_NOT_APPLICABLE} → 409; {@code RUN_NOT_FOUND} → 404; {@code
 * IDEMPOTENCY_KEY_CONFLICT} → 409; missing/blank {@code Idempotency-Key} → 400; multi-valued header
 * → 400; {@code role != workflow_owner} → 400; unknown body field → 400; omitted {@code
 * X-Actor-Identity} → captured {@code local-operator}.
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean RecoveryService} (present since 4.10) so the
 * test runs without Testcontainers. A {@link ListAppender} pins the INFO entry/success lines (the
 * free-form {@code reasonText} is never logged verbatim, but the governed {@code decision} value
 * IS) and the WARN role-rejection line. The {@code @BeforeAll}/{@code @AfterAll} identity-holder
 * guard keeps the shared {@code RedactionLayoutHolder} wired so a reused Surefire fork does not
 * mask CapturedOutput-based sibling tests (webmvctest-redaction-holder trap).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ReconcileEndpointContractTest {

  private static final String RUN_ID = "run_reconcile_endpoint_a";
  private static final String RECOVERY_ID = "rcv_reconcile_endpoint_a";
  private static final String RECONCILED_EVENT_ID = "evt_reconcile_endpoint_a";
  private static final String CONFLICT_ID = "icf_reconcile_endpoint_a";
  private static final String IDEMPOTENCY_KEY = "idem-reconcile-endpoint-aa";

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
  void happyPathReturnsReconciledWithResolvedConflictAndCapturesSixArgs() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ReconcileRecoveryResult(
                RECOVERY_ID,
                RECONCILED_EVENT_ID,
                CONFLICT_ID,
                WorkflowState.RECONCILED,
                null,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "conflictId": "icf_reconcile_endpoint_a",
                      "resolutionDecision": "accept_external_state",
                      "reasonText": "External PR merged; adopting the external state."
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Reconciled"))
        .andExpect(jsonPath("$.recoveryActionId").value(RECOVERY_ID))
        .andExpect(jsonPath("$.recoveryActionId").value(org.hamcrest.Matchers.startsWith("rcv_")))
        .andExpect(jsonPath("$.reconciledEventId").value(RECONCILED_EVENT_ID))
        .andExpect(jsonPath("$.resolvedConflictId").value(CONFLICT_ID))
        .andExpect(jsonPath("$.replayed").value(false));

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .reconcile(
            eq(RUN_ID),
            eq(CONFLICT_ID),
            eq("accept_external_state"),
            eq(IDEMPOTENCY_KEY),
            actorCaptor.capture(),
            eq("External PR merged; adopting the external state."));
    ActorContext actor = actorCaptor.getValue();
    assertThat(actor.actorIdentity()).isEqualTo("alex");
    assertThat(actor.actorType()).isEqualTo(ActorType.HUMAN);

    // Logging-instrumentation task: INFO received + success lines carry run/actor/conflict/decision
    // + result telemetry; the governed decision value appears verbatim, the free-form reasonText
    // never does (length only).
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST reconcile received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex")
                    && line.contains("conflictId=" + CONFLICT_ID)
                    && line.contains("decision=accept_external_state"))
        .anyMatch(
            line ->
                line.contains("REST reconcile success")
                    && line.contains("currentState=Reconciled")
                    && line.contains("recoveryActionId=" + RECOVERY_ID)
                    && line.contains("resolvedConflictId=" + CONFLICT_ID));
    assertThat(infoLines()).noneMatch(line -> line.contains("adopting the external state"));
  }

  @Test
  void eachReconciliationDecisionValueReachesTheServiceVerbatim() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ReconcileRecoveryResult(
                RECOVERY_ID,
                RECONCILED_EVENT_ID,
                CONFLICT_ID,
                WorkflowState.RECONCILED,
                null,
                false));

    for (String decision :
        new String[] {
          "accept_external_state",
          "accept_internal_state",
          "mark_completed_externally",
          "mark_failed_externally"
        }) {
      mockMvc
          .perform(
              post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaType.APPLICATION_JSON)
                  .header("Idempotency-Key", IDEMPOTENCY_KEY)
                  .content(bodyWithDecision(decision)))
          .andExpect(status().isOk());

      verify(recoveryService)
          .reconcile(eq(RUN_ID), eq(CONFLICT_ID), eq(decision), eq(IDEMPOTENCY_KEY), any(), any());
    }
  }

  @Test
  void idempotentReplayReturnsReplayedTrueWithNonNullState() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ReconcileRecoveryResult(
                RECOVERY_ID,
                RECONCILED_EVENT_ID,
                CONFLICT_ID,
                WorkflowState.RECONCILED,
                null,
                true));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true))
        .andExpect(jsonPath("$.currentState").value("Reconciled"))
        .andExpect(jsonPath("$.resolvedConflictId").value(CONFLICT_ID));
  }

  @Test
  void missingReconciliationDecisionReturns400() throws Exception {
    // resolutionDecision has NO @NotBlank (Reconciliation 6) — a blank/absent value reaches the
    // service, which surfaces the typed MISSING_RECONCILIATION_DECISION.
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MISSING_RECONCILIATION_DECISION,
                "Missing reconciliation decision",
                Map.of("field", "resolutionDecision")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "conflictId": "icf_reconcile_endpoint_a",
                      "reasonText": "valid reason"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_RECONCILIATION_DECISION"));
  }

  @Test
  void invalidReconciliationDecisionReturns400() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_RECONCILIATION_DECISION,
                "Invalid reconciliation decision",
                Map.of("field", "resolutionDecision", "value", "teleport")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(bodyWithDecision("teleport")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RECONCILIATION_DECISION"));
  }

  @Test
  void blankReasonTextRejectedAsInvalidCommandPayloadByService() throws Exception {
    // reasonText carries NO @NotBlank (code review 2026-07-13, same treatment as
    // resolutionDecision)
    // so the service's replay pre-check — which tolerates an omitted reason on an idempotent retry
    // —
    // stays reachable. On the fresh path a blank reason reaches resolveReconcileReasonText, which
    // surfaces the same typed INVALID_COMMAND_PAYLOAD with field=reasonText that bean validation
    // previously produced. The wire contract (code + field detail) is unchanged.
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_COMMAND_PAYLOAD,
                "Reconcile requires a non-blank reason",
                Map.of("field", "reasonText")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "conflictId": "icf_reconcile_endpoint_a",
                      "resolutionDecision": "accept_external_state",
                      "reasonText": "   "
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.field").value("reasonText"));
  }

  @Test
  void conflictNotFoundReturns404() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.CONFLICT_NOT_FOUND,
                "Integration conflict not found",
                Map.of("conflictId", CONFLICT_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CONFLICT_NOT_FOUND"));
  }

  @Test
  void conflictAlreadyResolvedReturns409() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.CONFLICT_ALREADY_RESOLVED,
                "Integration conflict already resolved",
                Map.of("conflictId", CONFLICT_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT_ALREADY_RESOLVED"));
  }

  @Test
  void reconcileFromTerminalStateReturns409ReconcileNotApplicable() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RECONCILE_NOT_APPLICABLE,
                "Reconcile is not applicable from terminal state Completed",
                Map.of("currentState", "Completed")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RECONCILE_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.details.currentState").value("Completed"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void blankIdempotencyKeyHeaderMapsToMissingIdempotencyKey() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
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
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("Idempotency-Key", "idem-reconcile-endpoint-bb")
                .content(workflowOwnerBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("Idempotency-Key"));
  }

  @Test
  void nonWorkflowOwnerRoleRejectedAsInvalidReviewerRoleForEndpoint() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "developer",
                      "conflictId": "icf_reconcile_endpoint_a",
                      "resolutionDecision": "accept_external_state",
                      "reasonText": "valid reason"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_REVIEWER_ROLE_FOR_ENDPOINT"))
        .andExpect(jsonPath("$.details.expected").value("workflow_owner"))
        .andExpect(jsonPath("$.details.actual").value("developer"));

    assertThat(warnLines()).anyMatch(line -> line.contains("role must be 'workflow_owner'"));
  }

  @Test
  void unknownBodyFieldRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "conflictId": "icf_reconcile_endpoint_a",
                      "resolutionDecision": "accept_external_state",
                      "reasonText": "valid",
                      "bogus": "unexpected"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ReconcileRecoveryResult(
                RECOVERY_ID,
                RECONCILED_EVENT_ID,
                CONFLICT_ID,
                WorkflowState.RECONCILED,
                null,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk());

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .reconcile(
            eq(RUN_ID), eq(CONFLICT_ID), any(), eq(IDEMPOTENCY_KEY), actorCaptor.capture(), any());
    assertThat(actorCaptor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void requestCorrelationIdOnMdcPropagatesIntoResponseBody() throws Exception {
    String correlationId = "01900000-0000-7000-8000-aabbccddeeff";
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ReconcileRecoveryResult(
                RECOVERY_ID,
                RECONCILED_EVENT_ID,
                CONFLICT_ID,
                WorkflowState.RECONCILED,
                correlationId,
                false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value(correlationId));

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .reconcile(
            eq(RUN_ID), eq(CONFLICT_ID), any(), eq(IDEMPOTENCY_KEY), actorCaptor.capture(), any());
    assertThat(actorCaptor.getValue().correlationId()).isEqualTo(correlationId);
  }

  @Test
  void replayThenReplayInvokesServiceEachTime() throws Exception {
    when(recoveryService.reconcile(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ReconcileRecoveryResult(
                RECOVERY_ID,
                RECONCILED_EVENT_ID,
                CONFLICT_ID,
                WorkflowState.RECONCILED,
                null,
                true));

    perform(workflowOwnerBody());
    perform(workflowOwnerBody());
    verify(recoveryService, times(2)).reconcile(any(), any(), any(), any(), any(), any());
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
    return bodyWithDecision("accept_external_state");
  }

  private String bodyWithDecision(String decision) {
    return """
        {
          "role": "workflow_owner",
          "conflictId": "icf_reconcile_endpoint_a",
          "resolutionDecision": "%s",
          "reasonText": "Reconciling the parked conflict."
        }
        """
        .formatted(decision);
  }

  private org.springframework.test.web.servlet.MvcResult perform(String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reconcile", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }
}
