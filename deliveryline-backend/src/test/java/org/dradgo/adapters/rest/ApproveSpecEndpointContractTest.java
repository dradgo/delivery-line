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
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
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
 * Story 2.13 AC10 — per-endpoint contract test for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/approve-spec}. Covers happy path, the typed error
 * codes reachable through the controller path (MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY via
 * service-thrown DomainException, INVALID_COMMAND_PAYLOAD, APPROVAL_VERSION_MISMATCH,
 * IDEMPOTENCY_KEY_CONFLICT), idempotent replay, and the {@code X-Actor-Identity} header fallback.
 *
 * <p>Uses {@code @WebMvcTest} + {@code @MockitoBean WorkflowCommandService} so the test runs
 * without Testcontainers (matches the pattern in {@code WorkflowAdapterEquivalenceTest} and {@code
 * ProblemDetailsContractTest}). Full {@code @SpringBootTest + Testcontainers} persistence-row /
 * event-log validation is deferred to the code-review batch per the project's established split.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ApproveSpecEndpointContractTest {

  private static final String RUN_ID = "run_approve_endpoint_a";
  private static final String ARTIFACT_ID = "art_spec_endpoint_a";
  private static final String IDEMPOTENCY_KEY = "idem-approve-endpoint-aaaaaa";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  // Story 4.10 — WorkflowController gained the recovery service; the bean must exist for this
  // @WebMvcTest slice to construct the controller.
  @MockitoBean private RecoveryService recoveryService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;

  // Story 3f-3 — WorkflowController gained the run-dependency declaration/inspection service; the
  // bean must exist for this @WebMvcTest slice to construct the controller.
  @MockitoBean private org.dradgo.application.workflow.RunDependencyService runDependencyService;

  // Story 3f-4 — WorkflowController gained the split-proposal service; the bean must exist for
  // this
  // @WebMvcTest slice to construct the controller.
  @MockitoBean private org.dradgo.application.workflow.SplitProposalService splitProposalService;
  @MockitoBean private org.dradgo.application.workflow.SplitCommitService splitCommitService;

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

  @Test
  void happyPathReturnsStateChangeBodyWithMachineReadableFields() throws Exception {
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
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
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Executing"));

    ArgumentCaptor<ApproveSpecCommand> captor = ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(captor.capture());
    ApproveSpecCommand captured = captor.getValue();
    assertThat(captured.workflowRunId()).isEqualTo(RUN_ID);
    assertThat(captured.artifactId()).isEqualTo(ARTIFACT_ID);
    assertThat(captured.artifactVersion()).isEqualTo(1);
    assertThat(captured.contextVersion()).isEqualTo(1);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(captured.reviewerRole()).isEqualTo("product_reviewer");
  }

  @Test
  void missingIdempotencyKeyHeaderMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void invalidCommandPayloadSurfacesFieldLevelDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": " ",
                      "expectedArtifactVersion": -1
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details[0].field").exists())
        .andExpect(jsonPath("$.details[0].constraint").exists());
  }

  @Test
  void approvalVersionMismatchReturns409WithStaleVersionMetadata() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("expectedArtifactVersion", 1);
    details.put("currentArtifactVersion", 3);
    when(workflowCommandService.approveSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.APPROVAL_VERSION_MISMATCH, "Artifact version is stale", details));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("APPROVAL_VERSION_MISMATCH"))
        .andExpect(jsonPath("$.details.currentArtifactVersion").value(3));
  }

  @Test
  void idempotencyKeyConflictReturns409() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("existingFingerprint", "a".repeat(64));
    details.put("submittedFingerprint", "b".repeat(64));
    when(workflowCommandService.approveSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key reused with a different fingerprint",
                details));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
  }

  @Test
  void identicalReplayProducesIdenticalResponseBody() throws Exception {
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null));

    String body =
        """
        {
          "artifactId": "%s",
          "expectedArtifactVersion": 1,
          "expectedContextBundleVersion": 1
        }
        """
            .formatted(ARTIFACT_ID);

    String first = perform(body).getResponse().getContentAsString();
    String second = perform(body).getResponse().getContentAsString();
    assertThat(first).isEqualTo(second);
    verify(workflowCommandService, times(2)).approveSpec(any());
  }

  @Test
  void missingActorIdentityHeaderFallsBackToConfiguredLocalOperator() throws Exception {
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<ApproveSpecCommand> captor = ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(captor.capture());
    assertThat(captor.getValue().actorIdentity()).isEqualTo("local-operator");
  }

  @Test
  void invalidIdempotencyKeyFormatSurfacesTypedProblemDetails() throws Exception {
    // Service-thrown DomainException(INVALID_IDEMPOTENCY_KEY) — validator runs inside the service
    // (WorkflowCommandService.executeIdempotent uses IdempotencyKeyValidator.requireValid). The
    // controller forwards the supplied key verbatim, so the typed error reaches the mapper.
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", "short");
    details.put("rule", "[A-Za-z0-9-]{16,128} or UUIDv4/UUIDv7");
    when(workflowCommandService.approveSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_IDEMPOTENCY_KEY, "Invalid idempotency key", details));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "short")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void invalidIdPrefixOnRunIdSurfacesTypedProblemDetails() throws Exception {
    when(workflowCommandService.approveSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX,
                "Malformed public id prefix",
                Map.of("field", "workflowRunId", "value", "garbage")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", "garbage")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"));
  }

  @Test
  void unknownWorkflowRunReturns404RunNotFound() throws Exception {
    when(workflowCommandService.approveSpec(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void requestCorrelationIdOnMdcPropagatesIntoCapturedCommandAndResponseBody() throws Exception {
    // The CorrelationIdFilter is not loaded by @WebMvcTest. Simulate the filter by stamping
    // the canonical MDC key directly; the controller reads MDC.get(MdcKeys.CORRELATION_ID) when
    // building the command (Trap T5), so the captured command's correlationId field should match
    // and the response body's correlationId (from WorkflowStateChangeResult) should echo it back.
    String correlationId = "01900000-0000-7000-8000-aabbccddeeff";
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, correlationId));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value(correlationId));

    ArgumentCaptor<ApproveSpecCommand> captor = ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(captor.capture());
    assertThat(captor.getValue().correlationId()).isEqualTo(correlationId);
  }

  @Test
  void legacyBodyFieldRejectedAsInvalidCommandPayload() throws Exception {
    // Round-3 review D3 follow-up: @JsonIgnoreProperties(ignoreUnknown = false) on
    // ApproveSpecRequest means pre-2.13 callers sending the dropped `actorIdentity` body field
    // receive a typed INVALID_COMMAND_PAYLOAD rather than the field being silently ignored.
    String legacyBody =
        """
        {
          "artifactId": "%s",
          "expectedArtifactVersion": 1,
          "expectedContextBundleVersion": 1,
          "actorIdentity": "alex"
        }
        """
            .formatted(ARTIFACT_ID);

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(legacyBody))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void oversizeXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    String oversize = "a".repeat(LocalActorIdentityResolver.MAX_ACTOR_IDENTITY_LENGTH + 1);
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", oversize)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
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
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", poisoned)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.reason").value("control_char"));
  }

  @Test
  void formatCategoryXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    // U+202E RIGHT-TO-LEFT OVERRIDE — bidi spoofing vector. Use Character.toString(int) so the
    // source contains no literal bidi-format character (keeps the file readable on copy/paste).
    String spoofed = "alex" + Character.toString(0x202E) + "imposter";
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", spoofed)
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.reason").value("format_category"));
  }

  @Test
  void commaFoldedXActorIdentityHeaderRejectedAsInvalidCommandPayload() throws Exception {
    // Story 2.13 round-4 P-R4-2: RFC 7230 §3.2.2 — proxies may fold same-name headers into a
    // single comma-separated field-line. servletRequest.getHeaders(...) returns one element; the
    // count guard misses the duplication; the comma guard catches it.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex, imposter")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.reason").value("comma_folded_multi_value"));
  }

  @Test
  void multipleXActorIdentityHeadersRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .header("X-Actor-Identity", "imposter")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
        .andExpect(jsonPath("$.details.header").value("X-Actor-Identity"))
        .andExpect(jsonPath("$.details.valueCount").value(2));
  }

  private org.springframework.test.web.servlet.MvcResult perform(String body) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }
}
