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
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RerunFromStepRecoveryResult;
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
 * Story 4.12 AC9 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/rerun-from-step}. The recovery sibling of {@code
 * ResumeEndpointContractTest} / {@code ReconcileEndpointContractTest}, wired to the RICH {@link
 * RecoveryService#rerunFromStep} (Reconciliation 2) and mapping the {@link RerunFromStepResponse}
 * (Reconciliation 7 + 8). Covers: happy-path rerun-to-investigating + rerun-to-executing (200 +
 * {@code currentState} + {@code recoveryActionId ^rcv_} + echoed superseded/invalidated lists +
 * present {@code runnerExecutionId} + {@code replayed=false}, capturing the FIVE positional args
 * with {@code targetStep} SECOND); idempotent replay ({@code replayed=true}, {@code
 * runnerExecutionId} null, {@code currentState} still non-null); {@code INVALID_RERUN_TARGET_STEP}
 * → 400 (blank/unknown target — proving {@code targetStep} is NOT {@code @NotBlank}); {@code
 * MISSING_REASON_TEXT} → 400 (blank reason — proving {@code reasonText} is NOT {@code @NotBlank},
 * the defining divergence from 4.11); {@code ILLEGAL_TRANSITION} → 409 (wrong source state); {@code
 * RUN_NOT_FOUND} → 404; {@code IDEMPOTENCY_KEY_CONFLICT} → 409; missing/blank {@code
 * Idempotency-Key} → 400; multi-valued header → 400; {@code role != workflow_owner} → 400; unknown
 * body field → 400; omitted {@code X-Actor-Identity} → captured {@code local-operator}.
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean RecoveryService} so the test runs without
 * Testcontainers. A {@link ListAppender} pins the INFO entry/success lines (free-form {@code
 * reasonText} never logged verbatim; {@code targetStep} value IS logged — a bounded governed enum)
 * and the WARN role-rejection line. The {@code @BeforeAll}/{@code @AfterAll} identity-holder guard
 * keeps the shared {@code RedactionLayoutHolder} wired so a reused Surefire fork does not mask
 * CapturedOutput-based sibling tests (webmvctest-redaction-holder trap).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class RerunFromStepEndpointContractTest {

  private static final String RUN_ID = "run_rerun_endpoint_a";
  private static final String RECOVERY_ID = "rcv_rerun_endpoint_a";
  private static final String RERUN_EVENT_ID = "evt_rerun_endpoint_a";
  private static final String RUNNER_EXEC_ID = "rex_rerun_endpoint_a";
  private static final String SUPERSEDED_ARTIFACT_ID = "art_rerun_endpoint_superseded_a";
  private static final String INVALIDATED_APPROVAL_ID = "apr_rerun_endpoint_invalidated_a";
  private static final String IDEMPOTENCY_KEY = "idem-rerun-endpoint-aaaaaa";

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

  private static RerunFromStepRecoveryResult freshResult(WorkflowState state) {
    return new RerunFromStepRecoveryResult(
        RECOVERY_ID,
        RERUN_EVENT_ID,
        List.of(SUPERSEDED_ARTIFACT_ID),
        List.of(INVALIDATED_APPROVAL_ID),
        RUNNER_EXEC_ID,
        state,
        null,
        false);
  }

  @Test
  void happyPathRerunToInvestigatingReturns200WithListsAndCapturesFivePositionalArgs()
      throws Exception {
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(freshResult(WorkflowState.INVESTIGATING));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "targetStep": "investigating",
                      "reasonText": "Spec was wrong; re-specifying from scratch."
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Investigating"))
        .andExpect(jsonPath("$.recoveryActionId").value(RECOVERY_ID))
        .andExpect(jsonPath("$.recoveryActionId").value(org.hamcrest.Matchers.startsWith("rcv_")))
        .andExpect(jsonPath("$.rerunEventId").value(RERUN_EVENT_ID))
        .andExpect(jsonPath("$.supersededArtifactIds[0]").value(SUPERSEDED_ARTIFACT_ID))
        .andExpect(jsonPath("$.invalidatedApprovalIds[0]").value(INVALIDATED_APPROVAL_ID))
        .andExpect(jsonPath("$.runnerExecutionId").value(RUNNER_EXEC_ID))
        .andExpect(jsonPath("$.replayed").value(false));

    // Reconciliation 4 — the five positional args, targetStep SECOND (before idempotencyKey).
    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .rerunFromStep(
            eq(RUN_ID),
            eq("investigating"),
            eq(IDEMPOTENCY_KEY),
            actorCaptor.capture(),
            eq("Spec was wrong; re-specifying from scratch."));
    ActorContext actor = actorCaptor.getValue();
    assertThat(actor.actorIdentity()).isEqualTo("alex");
    assertThat(actor.actorType()).isEqualTo(ActorType.HUMAN);

    // Logging-instrumentation task: INFO received + success lines carry run/actor + targetStep +
    // result telemetry; the free-form reasonText never appears verbatim (length only).
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST rerun-from-step received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex")
                    && line.contains("targetStep=investigating"))
        .anyMatch(
            line ->
                line.contains("REST rerun-from-step success")
                    && line.contains("currentState=Investigating")
                    && line.contains("recoveryActionId=" + RECOVERY_ID)
                    && line.contains("runnerExecutionId=" + RUNNER_EXEC_ID)
                    && line.contains("supersededCount=1")
                    && line.contains("invalidatedApprovalCount=1"));
    assertThat(infoLines()).noneMatch(line -> line.contains("re-specifying from scratch"));
  }

  @Test
  void happyPathRerunToExecutingReturns200AtExecuting() throws Exception {
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(freshResult(WorkflowState.EXECUTING));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody("executing")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentState").value("Executing"));

    verify(recoveryService)
        .rerunFromStep(eq(RUN_ID), eq("executing"), eq(IDEMPOTENCY_KEY), any(), any());
  }

  @Test
  void idempotentReplayReturnsReplayedTrueWithNullRunnerAndNonNullState() throws Exception {
    // On replay the rich service returns the prior row without re-dispatch: runnerExecutionId null,
    // currentState still non-null (the stored recovery.rerunFromStep event's targetState —
    // Reconciliation 8).
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(
            new RerunFromStepRecoveryResult(
                RECOVERY_ID,
                RERUN_EVENT_ID,
                List.of(),
                List.of(),
                null,
                WorkflowState.INVESTIGATING,
                null,
                true));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody("investigating")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true))
        .andExpect(jsonPath("$.runnerExecutionId").doesNotExist())
        .andExpect(jsonPath("$.currentState").value("Investigating"))
        .andExpect(jsonPath("$.supersededArtifactIds").isArray())
        .andExpect(jsonPath("$.invalidatedApprovalIds").isArray());
  }

  @Test
  void blankTargetStepSurfacesTypedInvalidRerunTargetStepProvingNoNotBlank() throws Exception {
    // targetStep carries NO @NotBlank (Reconciliation 5) — a blank value passes bean-validation and
    // reaches the service, which surfaces the TYPED INVALID_RERUN_TARGET_STEP (400). An @NotBlank
    // here would preempt it as INVALID_COMMAND_PAYLOAD, failing this assertion.
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_RERUN_TARGET_STEP,
                "Invalid rerun target step",
                Map.of("provided", "")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "targetStep": "   ",
                      "reasonText": "valid reason"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_RERUN_TARGET_STEP"))
        .andExpect(jsonPath("$.retryable").value(false));

    verify(recoveryService).rerunFromStep(eq(RUN_ID), eq("   "), eq(IDEMPOTENCY_KEY), any(), any());
  }

  @Test
  void unknownTargetStepSurfacesTypedInvalidRerunTargetStep() throws Exception {
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_RERUN_TARGET_STEP,
                "Invalid rerun target step",
                Map.of("provided", "inbox")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody("inbox")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RERUN_TARGET_STEP"));
  }

  @Test
  void blankReasonTextSurfacesTypedMissingReasonTextProvingNoNotBlank() throws Exception {
    // ⚠️ The DEFINING divergence from reconcile (4.11): reasonText carries NO @NotBlank
    // (Reconciliation 6) — a blank value passes bean-validation and reaches the service, which
    // surfaces the DISTINCT typed MISSING_REASON_TEXT (400). An @NotBlank here would preempt it as
    // INVALID_COMMAND_PAYLOAD, breaking epic AC4 and failing this assertion.
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MISSING_REASON_TEXT, "Missing reason text", Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "targetStep": "investigating",
                      "reasonText": "   "
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_REASON_TEXT"))
        .andExpect(jsonPath("$.retryable").value(false));

    verify(recoveryService)
        .rerunFromStep(eq(RUN_ID), eq("investigating"), eq(IDEMPOTENCY_KEY), any(), eq("   "));
  }

  @Test
  void wrongSourceStateSurfacesIllegalTransition409() throws Exception {
    // Reconciliation 9 — there is NO *_NOT_APPLICABLE code; the RERUN_SOURCE_STATES gate throws
    // ILLEGAL_TRANSITION (409) for any source outside {Failed, WaitingForReview}, incl. terminal.
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "Rerun-from-step is not applicable from state Completed",
                Map.of("runId", RUN_ID, "currentState", "Completed", "targetStep", "Executing")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody("executing")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"))
        .andExpect(jsonPath("$.details.currentState").value("Completed"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                Map.of()));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody("investigating")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody("investigating")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(workflowOwnerBody("investigating")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void blankIdempotencyKeyHeaderMapsToMissingIdempotencyKey() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "   ")
                .content(workflowOwnerBody("investigating")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void multiValuedIdempotencyKeyHeaderRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("Idempotency-Key", "idem-rerun-endpoint-bbbbbb")
                .content(workflowOwnerBody("investigating")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("Idempotency-Key"));
  }

  @Test
  void blankRoleRejectedAsInvalidCommandPayloadByBeanValidation() throws Exception {
    // role carries @NotBlank (mirrors resume/reconcile), so a blank value is caught by bean
    // validation as INVALID_COMMAND_PAYLOAD BEFORE requireWorkflowOwnerRole runs — no WARN emitted.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "   ",
                      "targetStep": "investigating",
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
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "developer",
                      "targetStep": "investigating",
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
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "targetStep": "investigating",
                      "reasonText": "valid",
                      "bogus": "unexpected"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(freshResult(WorkflowState.INVESTIGATING));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(workflowOwnerBody("investigating")))
        .andExpect(status().isOk());

    ArgumentCaptor<ActorContext> actorCaptor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .rerunFromStep(
            eq(RUN_ID), eq("investigating"), eq(IDEMPOTENCY_KEY), actorCaptor.capture(), any());
    assertThat(actorCaptor.getValue().actorIdentity()).isEqualTo("local-operator");
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

  private String workflowOwnerBody(String targetStep) {
    return """
        {
          "role": "workflow_owner",
          "targetStep": "%s",
          "reasonText": "Rerunning from the safe boundary."
        }
        """
        .formatted(targetStep);
  }
}
