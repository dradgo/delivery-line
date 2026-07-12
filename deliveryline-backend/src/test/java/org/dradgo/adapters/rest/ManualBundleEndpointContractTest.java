package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ManualBundleLookupResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3d-4 (review follow-up 2026-06-23) — per-endpoint contract test for the read-only {@code
 * GET /api/v1/workflows/&#123;workflowRunId&#125;/manual-bundle}. Pins the available (200 + base64
 * bundle), the typed {@code bundleNotPersisted} unavailable (200, NOT a 500), the wrong-state
 * {@code MANUAL_EXECUTION_NOT_APPLICABLE} (409), and the {@code RUN_NOT_FOUND} (404) mappings —
 * asserting {@code code}+{@code status} and the response shape only. The endpoint takes no {@code
 * Idempotency-Key} (idempotent read).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ManualBundleEndpointContractTest {

  private static final String RUN_ID = "run_manual_bundle_a";
  private static final String REX_ID = "rex_manual_bundle_a";

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

  private ListAppender<ILoggingEvent> appender;
  private Logger controllerLogger;

  @BeforeEach
  void setUp() {
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
  void availableBundleReturns200WithBase64Bytes() throws Exception {
    byte[] redacted = "redacted-bundle-bytes".getBytes(StandardCharsets.UTF_8);
    ContextBundle bundle =
        new ContextBundle(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            2,
            DataClassification.SHAREABLE_REDACTED,
            redacted);
    when(workflowInspectionService.getManualBundle(RUN_ID))
        .thenReturn(ManualBundleLookupResult.available(RUN_ID, bundle));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/manual-bundle", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.runnerExecutionId").value(REX_ID))
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.contextBundleVersion").value(2))
        .andExpect(jsonPath("$.bundleBase64").value(Base64.getEncoder().encodeToString(redacted)));

    // Controller log-branch pin: success INFO carries run/runnerExecution telemetry + availability.
    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST get manual-bundle received")
                    && line.contains("workflowRunId=" + RUN_ID))
        .anyMatch(
            line ->
                line.contains("REST get manual-bundle success") && line.contains("available=true"));
  }

  @Test
  void bundleNotPersistedReturns200WithTypedUnavailableReasonNotA500() throws Exception {
    when(workflowInspectionService.getManualBundle(RUN_ID))
        .thenReturn(ManualBundleLookupResult.unavailable(RUN_ID, REX_ID, "bundleNotPersisted"));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/manual-bundle", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.unavailableReason").value("bundleNotPersisted"))
        .andExpect(jsonPath("$.bundleBase64").doesNotExist());
  }

  @Test
  void notParkedRunReturns409ManualExecutionNotApplicable() throws Exception {
    when(workflowInspectionService.getManualBundle(RUN_ID))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE,
                "Manual execution is not applicable",
                Map.of("runId", RUN_ID)));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/manual-bundle", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MANUAL_EXECUTION_NOT_APPLICABLE"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void unknownRunReturns404RunNotFound() throws Exception {
    when(workflowInspectionService.getManualBundle(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found", Map.of("runId", RUN_ID)));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/manual-bundle", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  private java.util.List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
