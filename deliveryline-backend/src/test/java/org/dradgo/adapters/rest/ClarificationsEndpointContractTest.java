package org.dradgo.adapters.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ClarificationView;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-endpoint contract test for {@code GET /api/v1/workflows/{workflowRunId}/clarifications} — the
 * clarification-read surface that activates the (previously dormant) Clarification Region. Covers
 * the happy path (open + answered rows in the frontend-pinned wire shape), the empty-run calm path,
 * the nullable answer fields, and the typed 4xx surfaces (INVALID_ID_PREFIX, RUN_NOT_FOUND), plus
 * the no-Idempotency-Key read contract.
 *
 * <p>Assertions on machine-readable {@code code} only — never on human {@code title}/{@code
 * detail}.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ClarificationsEndpointContractTest {

  private static final String RUN_ID = "run_clarifications_a";

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

  @Test
  void happyPathReturnsClarificationsInFrontendWireShape() throws Exception {
    ClarificationView open =
        new ClarificationView(
            "clr_open1",
            RUN_ID,
            "art_spec1",
            1,
            "Q-001",
            "Should this only add REST endpoints?",
            "open",
            null,
            null,
            null,
            null,
            OffsetDateTime.of(2026, 6, 25, 20, 9, 0, 0, ZoneOffset.UTC));
    ClarificationView answered =
        new ClarificationView(
            "clr_ans2",
            RUN_ID,
            "art_spec1",
            1,
            "Q-002",
            "Should the report action change?",
            "answered",
            "Yes, keep CLI parity.",
            "reviewer-a",
            "human",
            OffsetDateTime.of(2026, 6, 25, 20, 30, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 6, 25, 20, 10, 0, 0, ZoneOffset.UTC));
    when(workflowInspectionService.getClarifications(eq(RUN_ID)))
        .thenReturn(List.of(open, answered));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/clarifications", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.clarifications[0].clarificationId").value("clr_open1"))
        .andExpect(jsonPath("$.clarifications[0].workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.clarifications[0].artifactId").value("art_spec1"))
        .andExpect(jsonPath("$.clarifications[0].artifactVersion").value(1))
        .andExpect(jsonPath("$.clarifications[0].questionId").value("Q-001"))
        .andExpect(jsonPath("$.clarifications[0].status").value("open"))
        .andExpect(jsonPath("$.clarifications[0].answerText").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.clarifications[1].clarificationId").value("clr_ans2"))
        .andExpect(jsonPath("$.clarifications[1].status").value("answered"))
        .andExpect(jsonPath("$.clarifications[1].answerText").value("Yes, keep CLI parity."))
        .andExpect(jsonPath("$.clarifications[1].answeredByActor").value("reviewer-a"))
        .andExpect(jsonPath("$.clarifications[1].answeredByActorType").value("human"));

    verify(workflowInspectionService).getClarifications(eq(RUN_ID));
  }

  @Test
  void emptyRunReturnsEmptyListNot404() throws Exception {
    when(workflowInspectionService.getClarifications(eq(RUN_ID))).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/clarifications", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clarifications").isArray())
        .andExpect(jsonPath("$.clarifications").isEmpty());
  }

  @Test
  void noIdempotencyKeyRequired() throws Exception {
    when(workflowInspectionService.getClarifications(eq(RUN_ID))).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/clarifications", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void malformedRunIdReturns400WithInvalidIdPrefixProblemDetails() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("publicId", "not_a_run");
    when(workflowInspectionService.getClarifications(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX, "Invalid public ID prefix: not_a_run", details));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/clarifications", "not_a_run")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"));
  }

  @Test
  void nonExistentRunReturns404WhenServiceRaisesRunNotFound() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", RUN_ID);
    when(workflowInspectionService.getClarifications(eq(RUN_ID)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + RUN_ID, details));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/clarifications", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }
}
