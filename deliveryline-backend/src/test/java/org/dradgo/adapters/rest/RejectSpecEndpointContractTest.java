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
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RejectionTaxonomy;
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
 * Story 2.13 AC10 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/reject-spec}.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class RejectSpecEndpointContractTest {

  private static final String RUN_ID = "run_reject_endpoint_a";
  private static final String ARTIFACT_ID = "art_spec_reject_a";
  private static final String IDEMPOTENCY_KEY = "idem-reject-endpoint-aaaaaa";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;

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
        "expectedArtifactVersion": 2,
        "expectedContextBundleVersion": 1,
        "taggedFeedback": "MISSING_SCOPE",
        "reasonText": "Scope unclear."
      }
      """;

  @Test
  void happyPathReturnsInvestigatingStateAndPersistsTaggedFeedback() throws Exception {
    when(workflowCommandService.rejectSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.INVESTIGATING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 2,
                      "expectedContextBundleVersion": 1,
                      "taggedFeedback": "UNCLEAR_SPECIFICATION",
                      "reasonText": "Spec missing the negative-path criteria."
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Investigating"));

    ArgumentCaptor<RejectSpecCommand> captor = ArgumentCaptor.forClass(RejectSpecCommand.class);
    verify(workflowCommandService).rejectSpec(captor.capture());
    RejectSpecCommand captured = captor.getValue();
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.taggedFeedback()).isEqualTo(RejectionTaxonomy.UNCLEAR_SPECIFICATION);
    assertThat(captured.reasonText()).contains("negative-path");
  }

  @Test
  void missingIdempotencyKeyMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 2,
                      "expectedContextBundleVersion": 1,
                      "taggedFeedback": "MISSING_SCOPE",
                      "reasonText": "Scope unclear."
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void missingTaggedFeedbackBecomesInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 2,
                      "expectedContextBundleVersion": 1,
                      "reasonText": "no taxonomy"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details[0].field").exists());
  }

  @Test
  void workflowRunTerminalSurfacesAs409() throws Exception {
    when(workflowCommandService.rejectSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.WORKFLOW_RUN_TERMINAL,
                "Workflow run is terminal",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 2,
                      "expectedContextBundleVersion": 1,
                      "taggedFeedback": "MISSING_SCOPE",
                      "reasonText": "Scope unclear."
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKFLOW_RUN_TERMINAL"));
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(workflowCommandService.rejectSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.INVESTIGATING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 2,
                      "expectedContextBundleVersion": 1,
                      "taggedFeedback": "MISSING_SCOPE",
                      "reasonText": "Scope unclear."
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<RejectSpecCommand> captor = ArgumentCaptor.forClass(RejectSpecCommand.class);
    verify(workflowCommandService).rejectSpec(captor.capture());
    assertThat(captor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void invalidIdempotencyKeyFormatSurfacesTypedProblemDetails() throws Exception {
    when(workflowCommandService.rejectSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_IDEMPOTENCY_KEY,
                "Invalid idempotency key",
                Map.of("idempotencyKey", "short")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
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
    when(workflowCommandService.rejectSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX,
                "Malformed public id prefix",
                Map.of("field", "workflowRunId", "value", "garbage")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", "garbage")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(workflowCommandService.rejectSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void approvalVersionMismatchReturns409WithStaleVersionMetadata() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("expectedArtifactVersion", 2);
    details.put("currentArtifactVersion", 4);
    when(workflowCommandService.rejectSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.APPROVAL_VERSION_MISMATCH, "Artifact version is stale", details));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("APPROVAL_VERSION_MISMATCH"))
        .andExpect(jsonPath("$.details.currentArtifactVersion").value(4));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("existingFingerprint", "a".repeat(64));
    details.put("submittedFingerprint", "b".repeat(64));
    when(workflowCommandService.rejectSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                details));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void identicalReplayProducesIdenticalResponseBody() throws Exception {
    // P2 — same key + same payload calls the service twice (executeIdempotent handles dedup
    // server-side); response bodies must be byte-identical so callers can safely retry.
    when(workflowCommandService.rejectSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.INVESTIGATING, null));

    String body = DEFAULT_BODY.formatted(ARTIFACT_ID);

    String first =
        mockMvc
            .perform(
                post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .header("X-Actor-Identity", "alex")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mockMvc
            .perform(
                post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .header("X-Actor-Identity", "alex")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(first).isEqualTo(second);
    verify(workflowCommandService, times(2)).rejectSpec(any());
  }

  @Test
  void oversizeXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    String oversize = "a".repeat(LocalActorIdentityResolver.MAX_ACTOR_IDENTITY_LENGTH + 1);
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
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
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
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
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
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
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
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
    // the reject-spec arm so future controller refactors can't silently drop the guard for one
    // verb.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
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
    // RejectSpecRequest
    // means pre-2.13 callers sending the dropped `actorIdentity` body field receive a typed
    // INVALID_COMMAND_PAYLOAD rather than the field being silently ignored.
    String legacyBody =
        """
        {
          "artifactId": "%s",
          "expectedArtifactVersion": 2,
          "expectedContextBundleVersion": 1,
          "taggedFeedback": "MISSING_SCOPE",
          "reasonText": "Scope unclear.",
          "actorIdentity": "alex"
        }
        """
            .formatted(ARTIFACT_ID);

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
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
    String correlationId = "01900000-0000-7000-8000-1111aabbccdd";
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    when(workflowCommandService.rejectSpec(any()))
        .thenReturn(
            new WorkflowStateChangeResult(RUN_ID, WorkflowState.INVESTIGATING, correlationId));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(DEFAULT_BODY.formatted(ARTIFACT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value(correlationId));

    ArgumentCaptor<RejectSpecCommand> captor = ArgumentCaptor.forClass(RejectSpecCommand.class);
    verify(workflowCommandService).rejectSpec(captor.capture());
    assertThat(captor.getValue().correlationId()).isEqualTo(correlationId);
  }
}
