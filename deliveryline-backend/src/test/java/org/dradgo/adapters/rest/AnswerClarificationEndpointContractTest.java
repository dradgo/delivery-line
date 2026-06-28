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
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 2.13 AC10 — per-endpoint contract test for the NEW {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/clarifications/&#123;clarificationId&#125;/answer}
 * endpoint. Trap T6 pin: answering does NOT mutate the workflow state.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class AnswerClarificationEndpointContractTest {

  private static final String RUN_ID = "run_answer_clar_a";
  private static final String CLARIFICATION_ID = "clr_answer_a";
  private static final String ARTIFACT_ID = "art_spec_clar_a";
  private static final String IDEMPOTENCY_KEY = "idem-answer-clar-aaaaaa";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

  // Story 3f-3 — WorkflowController gained the run-dependency declaration/inspection service; the
  // bean must exist for this @WebMvcTest slice to construct the controller.
  @MockitoBean private org.dradgo.application.workflow.RunDependencyService runDependencyService;

  @BeforeEach
  void stubActorResolver() {
    // Story 2.13 round-4 D-R4-1 + P-R4-12: delegate to a real LocalActorIdentityResolver so the
    // mock honours the production length/charset gates instead of only the trim-and-fallback path.
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
  }

  @AfterEach
  void clearCorrelationMdc() {
    MDC.remove(MdcKeys.CORRELATION_ID);
  }

  private static final String DEFAULT_BODY =
      """
      {
        "artifactId": "%s",
        "expectedArtifactVersion": 1,
        "answerText": "any"
      }
      """;

  @Test
  void happyPathReturnsAnsweredClarificationStatusWithUnchangedWorkflowState() throws Exception {
    // Trap T6: workflow state must not advance — render WaitingForSpecApproval after the answer.
    when(workflowCommandService.answerClarification(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                RUN_ID, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, "answered"));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": "Confirmed: the rate limit applies per-user, not per-org."
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.clarificationId").value(CLARIFICATION_ID))
        .andExpect(jsonPath("$.clarificationStatus").value("answered"))
        .andExpect(jsonPath("$.currentState").value("WaitingForSpecApproval"));

    ArgumentCaptor<SubmitClarificationCommand> captor =
        ArgumentCaptor.forClass(SubmitClarificationCommand.class);
    verify(workflowCommandService).answerClarification(captor.capture());
    SubmitClarificationCommand captured = captor.getValue();
    assertThat(captured.workflowRunId()).isEqualTo(RUN_ID);
    assertThat(captured.clarificationId()).isEqualTo(CLARIFICATION_ID);
    assertThat(captured.artifactId()).isEqualTo(ARTIFACT_ID);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.answerText()).contains("rate limit");
  }

  @Test
  void missingIdempotencyKeyMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": "anything"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void blankAnswerTextBecomesInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": " "
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details[0].field").exists());
  }

  @Test
  void clarificationNotFoundReturns404() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.CLARIFICATION_NOT_FOUND,
                "Clarification not found",
                Map.of("clarificationId", CLARIFICATION_ID)));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": "any"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CLARIFICATION_NOT_FOUND"));
  }

  @Test
  void staleExpectedArtifactVersionReturnsClarificationArtifactVersionMismatch() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.CLARIFICATION_ARTIFACT_VERSION_MISMATCH,
                "Clarification artifact version is stale",
                Map.of("expectedArtifactVersion", 1, "currentArtifactVersion", 2)));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": "any"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLARIFICATION_ARTIFACT_VERSION_MISMATCH"))
        .andExpect(jsonPath("$.details.currentArtifactVersion").value(2));
  }

  @Test
  void clarificationTerminalStateReturns409() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.CLARIFICATION_TERMINAL_STATE,
                "Clarification is in a terminal state",
                Map.of("clarificationId", CLARIFICATION_ID)));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": "any"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLARIFICATION_TERMINAL_STATE"));
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                RUN_ID, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, "answered"));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": "any"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<SubmitClarificationCommand> captor =
        ArgumentCaptor.forClass(SubmitClarificationCommand.class);
    verify(workflowCommandService).answerClarification(captor.capture());
    assertThat(captor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void invalidIdempotencyKeyFormatSurfacesTypedProblemDetails() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_IDEMPOTENCY_KEY,
                "Invalid idempotency key",
                Map.of("idempotencyKey", "short")));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "short")
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
  }

  @Test
  void invalidIdPrefixOnRunIdSurfacesTypedProblemDetails() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX,
                "Malformed public id prefix",
                Map.of("field", "workflowRunId", "value", "garbage")));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    "garbage",
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("existingFingerprint", "a".repeat(64));
    details.put("submittedFingerprint", "b".repeat(64));
    when(workflowCommandService.answerClarification(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                details));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void idempotentReplayAfterRunReachedTerminalStillRendersOriginalAnswerTimeState()
      throws Exception {
    // Story 2.13 round-4 P-R4-15: when the workflow run has advanced to a terminal state (Done /
    // Failed) between the original answer commit and a subsequent idempotent replay, the replay
    // body must still carry the answer-time clarificationStatus and the answer-time workflow
    // state captured in the idempotency record's resultRef — NOT the live current state of the
    // run. This pins the controller's faithful rendering of the service's replay shape so a
    // future refactor that bypasses replayStateChange (e.g., reads the live state instead) is
    // caught here.
    when(workflowCommandService.answerClarification(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                RUN_ID, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, "answered"));

    String first =
        mockMvc
            .perform(
                post(
                        "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                        RUN_ID,
                        CLARIFICATION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .header("X-Actor-Identity", "alex")
                    .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Second call simulates the post-terminal replay — service still surfaces answer-time state
    // (replayStateChange returns the captured original state from the result-ref, not live).
    String second =
        mockMvc
            .perform(
                post(
                        "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                        RUN_ID,
                        CLARIFICATION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .header("X-Actor-Identity", "alex")
                    .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentState").value("WaitingForSpecApproval"))
            .andExpect(jsonPath("$.clarificationStatus").value("answered"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(first).isEqualTo(second);
    verify(workflowCommandService, times(2)).answerClarification(any());
  }

  @Test
  void oversizeXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    String oversize = "a".repeat(LocalActorIdentityResolver.MAX_ACTOR_IDENTITY_LENGTH + 1);
    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", oversize)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.reason").value("oversize"));
  }

  @Test
  void controlCharXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    String poisoned = "alex" + Character.toString(0x000A) + "imposter";
    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", poisoned)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.reason").value("control_char"));
  }

  @Test
  void formatCategoryXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    String spoofed = "alex" + Character.toString(0x202E) + "imposter";
    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", spoofed)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.reason").value("format_category"));
  }

  @Test
  void commaFoldedXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex, imposter")
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.reason").value("comma_folded_multi_value"));
  }

  @Test
  void multipleXActorIdentityHeadersRejectedAsInvalidCommandPayload() throws Exception {
    // Round-3 review P9: production guard already covers all three mutation endpoints; this pins
    // the answer-clarification arm so future controller refactors can't silently drop the guard
    // for one verb.
    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .header("X-Actor-Identity", "imposter")
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.valueCount").value(2));
  }

  @Test
  void legacyBodyFieldRejectedAsInvalidCommandPayload() throws Exception {
    // Round-3 review D3 follow-up: @JsonIgnoreProperties(ignoreUnknown = false) on
    // AnswerClarificationRequest means pre-2.13 callers sending body fields the rebuild dropped
    // (e.g. `actorIdentity`) receive a typed INVALID_COMMAND_PAYLOAD rather than silent ignore.
    String legacyBody =
        """
        {
          "artifactId": "%s",
          "expectedArtifactVersion": 1,
          "answerText": "anything",
          "actorIdentity": "alex"
        }
        """
            .formatted(ARTIFACT_ID);

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(legacyBody))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void requestCorrelationIdOnMdcPropagatesIntoCapturedCommandAndResponseBody() throws Exception {
    String correlationId = "01900000-0000-7000-8000-2222ccddeeff";
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    when(workflowCommandService.answerClarification(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                RUN_ID, WorkflowState.WAITING_FOR_SPEC_APPROVAL, correlationId, "answered"));

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    CLARIFICATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value(correlationId));

    ArgumentCaptor<SubmitClarificationCommand> captor =
        ArgumentCaptor.forClass(SubmitClarificationCommand.class);
    verify(workflowCommandService).answerClarification(captor.capture());
    assertThat(captor.getValue().correlationId()).isEqualTo(correlationId);
  }
}
