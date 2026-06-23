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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsVersionStamp;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsView;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.AllowedAction;
import org.dradgo.domain.registry.DomainErrorCode;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 2.14 AC6 / AC7 / AC10 — per-endpoint contract test for {@code GET
 * /api/v1/workflows/&#123;workflowRunId&#125;/allowed-actions}. Covers the happy path, the typed
 * 4xx surfaces (UNKNOWN_ACTOR_ROLE, INVALID_ID_PREFIX, RUN_NOT_FOUND), the actorRole query-param
 * default, the no-Idempotency-Key contract, and the TRAP 2 nullable-version-stamp pin.
 *
 * <p>Assertions on {@code code} / {@code status} / machine-readable {@code details} only — never on
 * human {@code title} / {@code detail} text (architecture line 712).
 */
// Review P8: ApprovalReviewerRoleResolver is @Import-ed only to satisfy WorkflowController's
// constructor — this endpoint does NOT use that resolver (the allowed-actions endpoint has its
// own RECOGNIZED_ACTOR_ROLES set with fail-closed validation per AC7). Without the @Import the
// @WebMvcTest slice would fail to construct the controller bean. Leaving it as @Import (cheap,
// real bean) rather than @MockitoBean (which would require unused stubs) is intentional.
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class AllowedActionsEndpointContractTest {

  private static final String RUN_ID = "run_allowed_actions_a";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;

  @Test
  void happyPathReturnsAllowedActionsAndVersionStamp() throws Exception {
    AllowedActionsView view =
        new AllowedActionsView(
            List.of(
                AllowedAction.APPROVE_SPEC,
                AllowedAction.REJECT_SPEC,
                AllowedAction.ANSWER_CLARIFICATION),
            new AllowedActionsVersionStamp("WaitingForSpecApproval", 2, 3, "evt_abc123"));
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), any())).thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.actions[0]").value("approve_spec"))
        .andExpect(jsonPath("$.actions[1]").value("reject_spec"))
        .andExpect(jsonPath("$.actions[2]").value("answer_clarification"))
        .andExpect(jsonPath("$.versionStamp.workflowState").value("WaitingForSpecApproval"))
        .andExpect(jsonPath("$.versionStamp.currentSpecArtifactVersion").value(2))
        .andExpect(jsonPath("$.versionStamp.currentContextBundleVersion").value(3))
        .andExpect(jsonPath("$.versionStamp.lastEventId").value("evt_abc123"));
  }

  @Test
  void actorRoleQueryParamDefaultsToProductReviewerWhenAbsent() throws Exception {
    AllowedActionsView view =
        new AllowedActionsView(
            List.of(AllowedAction.VIEW_ONLY),
            new AllowedActionsVersionStamp("Inbox", null, null, "evt_init"));
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), any())).thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    // Controller passes the raw (null) value through to the service; defaulting happens inside the
    // service. We capture the null so the contract is pinned at the boundary.
    ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
    verify(workflowInspectionService).getAllowedActions(eq(RUN_ID), roleCaptor.capture());
    assertThat(roleCaptor.getValue()).isNull();
  }

  @Test
  void actorRoleQueryParamHonoredWhenPresent() throws Exception {
    AllowedActionsView view =
        new AllowedActionsView(
            List.of(AllowedAction.RETRY, AllowedAction.VIEW_DIAGNOSTICS),
            new AllowedActionsVersionStamp("Failed", 1, 1, "evt_fail"));
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), eq("workflow_owner")))
        .thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .param("actorRole", "workflow_owner")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actions[0]").value("retry"))
        .andExpect(jsonPath("$.actions[1]").value("view_diagnostics"));

    verify(workflowInspectionService).getAllowedActions(eq(RUN_ID), eq("workflow_owner"));
  }

  @Test
  void getAllowedActionsAsDeveloperAtWaitingForReviewReturnsImplementationActions()
      throws Exception {
    // Story 3b-4: the `developer` role is now advertised on the actorRole enum; at
    // WaitingForReview the matrix returns the developer technical-review action set. The
    // service-unit matrix already pins the logic (WorkflowInspectionServiceAllowedActionsTest);
    // this pins the REST boundary + the now-advertised enum value.
    AllowedActionsView view =
        new AllowedActionsView(
            List.of(
                AllowedAction.ACCEPT_IMPLEMENTATION,
                AllowedAction.REJECT_IMPLEMENTATION,
                AllowedAction.TAKEOVER_WORKFLOW,
                AllowedAction.VIEW_ONLY),
            new AllowedActionsVersionStamp("WaitingForReview", 3, 1, "evt_review_100"));
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), eq("developer"))).thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .param("actorRole", "developer")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actions[0]").value("accept_implementation"))
        .andExpect(jsonPath("$.actions[1]").value("reject_implementation"))
        .andExpect(jsonPath("$.actions[2]").value("takeover_workflow"))
        .andExpect(jsonPath("$.actions[3]").value("view_only"))
        .andExpect(jsonPath("$.versionStamp.workflowState").value("WaitingForReview"));

    verify(workflowInspectionService).getAllowedActions(eq(RUN_ID), eq("developer"));
  }

  @Test
  void unknownActorRoleReturns400WithUnknownActorRoleProblemDetails() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("actorRole", "auditor");
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.UNKNOWN_ACTOR_ROLE, "Unknown actor role: auditor", details));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .param("actorRole", "auditor")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_ACTOR_ROLE"))
        .andExpect(jsonPath("$.retryable").value(false))
        .andExpect(jsonPath("$.details.actorRole").value("auditor"));
  }

  @Test
  void malformedRunIdReturns400WithInvalidIdPrefixProblemDetails() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("publicId", "not_a_run");
    when(workflowInspectionService.getAllowedActions(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_ID_PREFIX, "Invalid public ID prefix: not_a_run", details));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", "not_a_run")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_ID_PREFIX"));
  }

  @Test
  void nonExistentRunReturns404WithRunNotFoundProblemDetails() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", RUN_ID);
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + RUN_ID, details));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void noIdempotencyKeyRequired() throws Exception {
    AllowedActionsView view =
        new AllowedActionsView(
            List.of(AllowedAction.VIEW_ONLY),
            new AllowedActionsVersionStamp("Completed", 1, 1, "evt_done"));
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), any())).thenReturn(view);

    // No Idempotency-Key header — must still return 200, not the MISSING_IDEMPOTENCY_KEY surface
    // that AC6 explicitly excludes.
    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void caseVariantActorRoleReturns400WithUnknownActorRoleProblemDetails() throws Exception {
    // Review P10 / Auditor A2: pin AC7 case-sensitivity at the wire layer. `Product_Reviewer` is
    // not in the recognized set (lowercase-only) — must surface UNKNOWN_ACTOR_ROLE.
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("actorRole", "Product_Reviewer");
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.UNKNOWN_ACTOR_ROLE,
                "Unknown actor role: Product_Reviewer",
                details));

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .param("actorRole", "Product_Reviewer")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNKNOWN_ACTOR_ROLE"))
        .andExpect(jsonPath("$.details.actorRole").value("Product_Reviewer"));
  }

  @Test
  void nullableVersionStampFieldsSerializeAsJsonNull() throws Exception {
    // TRAP 2 pin: a fresh run with no spec drafted yet returns nulls for the version fields.
    AllowedActionsView view =
        new AllowedActionsView(
            List.of(AllowedAction.VIEW_ONLY),
            new AllowedActionsVersionStamp("Inbox", null, null, null));
    when(workflowInspectionService.getAllowedActions(eq(RUN_ID), any())).thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/allowed-actions", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionStamp.workflowState").value("Inbox"))
        .andExpect(
            jsonPath("$.versionStamp.currentSpecArtifactVersion").value(Matchers.nullValue()))
        .andExpect(
            jsonPath("$.versionStamp.currentContextBundleVersion").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.versionStamp.lastEventId").value(Matchers.nullValue()));
  }
}
