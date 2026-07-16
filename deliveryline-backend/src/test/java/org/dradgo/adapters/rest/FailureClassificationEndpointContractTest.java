package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureClassificationView;
import org.dradgo.application.workflow.WorkflowInspectionService.PriorClassification;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
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
 * Story 4.24 (AC2/AC5/AC9, Task 3) — per-endpoint contract test for {@code GET
 * /api/v1/workflows/&#123;workflowRunId&#125;/failure-classification}. The read surface that
 * projects the {@code done} story 4.9 {@link FailureClassificationView} onto the wire so the
 * classification dialog + Run Context Strip can render the current + prior classification. Covers:
 * a classified run (200 with current triple + provenance + ordered priors); a KNOWN
 * never-classified run (200 with {@code currentTaxonomyValue} omitted + empty {@code
 * priorClassifications}, NOT 404); an unknown run (404 {@code RUN_NOT_FOUND}); a malformed id (400
 * {@code INVALID_ID_PREFIX}). Read-only — no Idempotency-Key/actor/role.
 *
 * <p>{@code @WebMvcTest} + {@code @MockitoBean WorkflowInspectionService} runs without
 * Testcontainers. A {@link ListAppender} pins the INFO entry/success lines (sanitized {@code
 * workflowRunId} + {@code classified} boolean — never {@code reasonText}). The
 * {@code @BeforeAll}/{@code @AfterAll} identity-holder guard keeps the shared {@code
 * RedactionLayoutHolder} wired so a reused Surefire fork does not mask CapturedOutput-based sibling
 * tests (webmvctest-redaction-holder trap).
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class FailureClassificationEndpointContractTest {

  private static final String RUN_ID = "run_classification_read_a";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-16T10:00:00Z");

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
  void detachAppender() {
    controllerLogger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void classifiedRunReturnsCurrentTripleProvenanceAndOrderedPriors() throws Exception {
    FailureClassificationView view =
        new FailureClassificationView(
            "agent_execution_failure",
            "agent_execution_failure",
            false,
            null,
            NOW,
            "alex",
            List.of(
                new PriorClassification("context_gap", "context_gap", NOW.minusHours(1), "alex"),
                new PriorClassification(
                    "specification_gap", "specification_gap", NOW.minusHours(2), "amelia")));
    when(workflowInspectionService.getFailureClassification(eq(RUN_ID))).thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/failure-classification", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentTaxonomyValue").value("agent_execution_failure"))
        .andExpect(jsonPath("$.currentDisplayLabel").value("agent_execution_failure"))
        .andExpect(jsonPath("$.deprecated").value(false))
        .andExpect(jsonPath("$.classifiedBy").value("alex"))
        .andExpect(jsonPath("$.priorClassifications", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$.priorClassifications[0].taxonomyValue").value("context_gap"))
        .andExpect(jsonPath("$.priorClassifications[0].classifiedBy").value("alex"))
        .andExpect(jsonPath("$.priorClassifications[1].taxonomyValue").value("specification_gap"));

    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST get failure-classification success")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("classified=true"));
  }

  @Test
  void neverClassifiedRunReturns200WithNullCurrentAndEmptyPriors() throws Exception {
    FailureClassificationView view =
        new FailureClassificationView(null, null, false, null, null, null, List.of());
    when(workflowInspectionService.getFailureClassification(eq(RUN_ID))).thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/failure-classification", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        // nullable current fields are omitted from the wire (NOT :null), never-classified.
        .andExpect(jsonPath("$.currentTaxonomyValue").doesNotExist())
        .andExpect(jsonPath("$.currentDisplayLabel").doesNotExist())
        .andExpect(jsonPath("$.classifiedAt").doesNotExist())
        .andExpect(jsonPath("$.classifiedBy").doesNotExist())
        .andExpect(jsonPath("$.deprecated").value(false))
        .andExpect(jsonPath("$.priorClassifications", org.hamcrest.Matchers.hasSize(0)));

    assertThat(infoLines())
        .anyMatch(
            line ->
                line.contains("REST get failure-classification success")
                    && line.contains("classified=false"));
  }

  @Test
  void unknownRunReturns404RunNotFound() throws Exception {
    when(workflowInspectionService.getFailureClassification(eq(RUN_ID)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "Workflow run not found: " + RUN_ID,
                Map.of("runId", RUN_ID)));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/failure-classification", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void malformedRunIdReturns400InvalidIdPrefix() throws Exception {
    when(workflowInspectionService.getFailureClassification(eq("not-a-run")))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX,
                "Malformed run id",
                Map.of("value", "not-a-run")));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/failure-classification", "not-a-run")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"));
  }

  private List<String> infoLines() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
