package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RerunFromStepPreviewResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.22 (AC5, AC13) — per-endpoint contract test for the <strong>non-mutating</strong> {@code
 * GET /api/v1/workflows/&#123;workflowRunId&#125;/preview-rerun-from-step?targetStep=X}. Read-only
 * sibling of {@code FailureDiagnosticsEndpointContractTest} wired to the RICH {@link
 * RecoveryService#previewRerunFromStep}. Covers: happy-path preview (200 + echoed {@code
 * targetStep} + never-null {@code supersededArtifactIds}/{@code invalidatedApprovalIds});
 * empty-lists variant (arrays still present); {@code targetStep=bogus} → 400 {@code
 * INVALID_RERUN_TARGET_STEP} (proving NO {@code @NotBlank}/{@code @Pattern} masks the typed code);
 * omitted {@code targetStep} → 400 (the {@code required=false} String reaches the service as {@code
 * null}); wrong source state → 409 {@code ILLEGAL_TRANSITION} (OQ-1); unknown run → 404 {@code
 * RUN_NOT_FOUND}; malformed run id → 400 {@code INVALID_ID_PREFIX} (typed, not a masked 500). A
 * {@link ListAppender} pins the INFO received/success log lines; no idempotency-key/actor header on
 * a read endpoint.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class PreviewRerunFromStepEndpointContractTest {

  private static final String RUN_ID = "run_preview_endpoint_a";
  private static final String SUPERSEDED_ARTIFACT_ID = "art_preview_superseded_a";
  private static final String INVALIDATED_APPROVAL_ID = "apr_preview_invalidated_a";

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
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    controllerLogger = (Logger) LoggerFactory.getLogger(WorkflowController.class);
    controllerLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    controllerLogger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void happyPathReturns200WithEchoedTargetStepAndBothIdLists() throws Exception {
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenReturn(
            new RerunFromStepPreviewResult(
                WorkflowState.INVESTIGATING,
                List.of(SUPERSEDED_ARTIFACT_ID),
                List.of(INVALIDATED_APPROVAL_ID)));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", RUN_ID)
                .param("targetStep", "investigating")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.targetStep").value("investigating"))
        .andExpect(jsonPath("$.supersededArtifactIds[0]").value(SUPERSEDED_ARTIFACT_ID))
        .andExpect(jsonPath("$.invalidatedApprovalIds[0]").value(INVALIDATED_APPROVAL_ID));

    // Read endpoint: the service is called with the run id + normalized target step (no
    // idempotency-key / actor).
    verify(recoveryService).previewRerunFromStep(eq(RUN_ID), eq("investigating"));

    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST preview-rerun-from-step received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("targetStep=investigating"))
        .anyMatch(
            line ->
                line.contains("REST preview-rerun-from-step success")
                    && line.contains("supersededCount=1")
                    && line.contains("invalidatedApprovalCount=1"));
  }

  @Test
  void emptyPreviewStillReturnsBothArrays() throws Exception {
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenReturn(new RerunFromStepPreviewResult(WorkflowState.EXECUTING, List.of(), List.of()));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", RUN_ID)
                .param("targetStep", "executing")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetStep").value("executing"))
        .andExpect(jsonPath("$.supersededArtifactIds").isArray())
        .andExpect(jsonPath("$.supersededArtifactIds").isEmpty())
        .andExpect(jsonPath("$.invalidatedApprovalIds").isArray())
        .andExpect(jsonPath("$.invalidatedApprovalIds").isEmpty());
  }

  @Test
  void whitespacePaddedTargetStepIsNormalizedBeforeReachingService() throws Exception {
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenReturn(
            new RerunFromStepPreviewResult(WorkflowState.INVESTIGATING, List.of(), List.of()));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", RUN_ID)
                .param("targetStep", "  investigating  ")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetStep").value("investigating"));

    verify(recoveryService).previewRerunFromStep(eq(RUN_ID), eq("investigating"));
  }

  @Test
  void bogusTargetStepSurfacesTypedInvalidRerunTargetStepProvingNoConstraintMask()
      throws Exception {
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_RERUN_TARGET_STEP,
                "Invalid rerun target step: inbox",
                Map.of("provided", "inbox")));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", RUN_ID)
                .param("targetStep", "inbox")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_RERUN_TARGET_STEP"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void omittedTargetStepReachesServiceAsNullAndSurfacesInvalidRerunTargetStep() throws Exception {
    // required=false → a missing param is null (NOT MissingServletRequestParameterException); the
    // service's resolveTargetState raises the typed INVALID_RERUN_TARGET_STEP.
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_RERUN_TARGET_STEP,
                "Invalid rerun target step: null",
                Map.of()));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RERUN_TARGET_STEP"));

    verify(recoveryService).previewRerunFromStep(eq(RUN_ID), eq(null));
  }

  @Test
  void wrongSourceStateSurfacesIllegalTransition409() throws Exception {
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "Rerun-from-step is not applicable from state Completed",
                Map.of(
                    "runId", RUN_ID, "currentState", "Completed", "targetStep", "Investigating")));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", RUN_ID)
                .param("targetStep", "investigating")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"))
        .andExpect(jsonPath("$.details.currentState").value("Completed"));
  }

  @Test
  void unknownRunReturns404RunNotFound() throws Exception {
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found",
                Map.of("workflowRunId", RUN_ID)));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", RUN_ID)
                .param("targetStep", "investigating")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void malformedRunIdSurfacesTypedInvalidIdPrefix400() throws Exception {
    // The controller forwards workflowRunId unchanged; the service's PublicIdPrefixes.require
    // guards
    // a malformed id with the typed INVALID_ID_PREFIX (400) rather than letting it fall through to
    // a
    // masked 500 — the branch advertised in the endpoint's OpenAPI 400 description.
    String malformedRunId = "not_a_run_prefix_x";
    when(recoveryService.previewRerunFromStep(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX,
                "Invalid id prefix for run: " + malformedRunId,
                Map.of("provided", malformedRunId)));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/preview-rerun-from-step", malformedRunId)
                .param("targetStep", "investigating")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  private List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
