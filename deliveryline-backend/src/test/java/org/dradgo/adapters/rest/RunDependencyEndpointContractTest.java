package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.BlockedDependencyView;
import org.dradgo.application.workflow.DeclareRunDependenciesCommand;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.RunDependencyGraphView;
import org.dradgo.application.workflow.RunDependencyService;
import org.dradgo.application.workflow.SplitProposalService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 3f-3 (AC9/AC10) — REST contract for the run-dependency declare/show endpoints. Pins the
 * happy-path declare (200 + graph), the inspect read (200), the cycle → 409 mapping, and the
 * missing-Idempotency-Key → 400 gate, plus the REST-to-command mapping.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class RunDependencyEndpointContractTest {

  private static final String RUN_ID = "run_dep_endpoint_aaa";
  private static final String PREREQ = "run_dep_prereq_bbb";
  private static final String IDEMPOTENCY_KEY = "idem-dep-endpoint-aaaaaa";

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
  @MockitoBean private RunDependencyService runDependencyService;
  @MockitoBean private SplitProposalService splitProposalService;
  @MockitoBean private org.dradgo.application.workflow.SplitCommitService splitCommitService;

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
  }

  private static RunDependencyGraphView blockedGraph() {
    return new RunDependencyGraphView(
        List.of(new BlockedDependencyView(PREREQ, WorkflowState.INVESTIGATING)),
        List.of(),
        List.of(new BlockedDependencyView(PREREQ, WorkflowState.INVESTIGATING)),
        true);
  }

  @Test
  void declareHappyPathReturnsGraphAndCapturesCommand() throws Exception {
    when(runDependencyService.declareDependencies(any())).thenReturn(blockedGraph());

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/dependencies", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content("{\"dependsOnRunIds\": [\"" + PREREQ + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blockedByDependencies").value(true))
        .andExpect(jsonPath("$.prerequisites[0].runId").value(PREREQ))
        .andExpect(jsonPath("$.prerequisites[0].state").value("Investigating"))
        .andExpect(jsonPath("$.blockedOn[0].runId").value(PREREQ));

    ArgumentCaptor<DeclareRunDependenciesCommand> captor =
        ArgumentCaptor.forClass(DeclareRunDependenciesCommand.class);
    verify(runDependencyService).declareDependencies(captor.capture());
    DeclareRunDependenciesCommand captured = captor.getValue();
    assertThat(captured.runId()).isEqualTo(RUN_ID);
    assertThat(captured.dependsOnRunIds()).containsExactly(PREREQ);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.actorType()).isEqualTo(ActorType.HUMAN);
    assertThat(captured.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
  }

  @Test
  void showReturnsGraph() throws Exception {
    when(runDependencyService.graphView(RUN_ID)).thenReturn(blockedGraph());

    mockMvc
        .perform(
            get("/api/v1/workflows/{runId}/dependencies", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blockedByDependencies").value(true))
        .andExpect(jsonPath("$.prerequisites[0].runId").value(PREREQ));
  }

  @Test
  void cycleDeclarationMapsToConflict() throws Exception {
    when(runDependencyService.declareDependencies(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_DEPENDENCY_CYCLE,
                "cycle",
                Map.of("runId", RUN_ID, "dependsOnRunId", PREREQ, "reason", "cycle_detected")));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/dependencies", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content("{\"dependsOnRunIds\": [\"" + PREREQ + "\"]}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RUN_DEPENDENCY_CYCLE"));
  }

  @Test
  void declareWithBlankIdempotencyKeyIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/dependencies", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", " ")
                .content("{\"dependsOnRunIds\": [\"" + PREREQ + "\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }
}
