package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveResult;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.commands.ArchiveRunCommand;
import org.dradgo.application.workflow.commands.UnarchiveRunCommand;
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
 * Story 3d-8 (FR67, AC3/AC4/AC5/AC9) — per-endpoint contract test for {@code POST /archive}, {@code
 * POST /unarchive}, and the {@code listWorkflows} {@code includeArchived} query param. Mirrors
 * {@code TakeoverEndpointContractTest}: {@code @WebMvcTest} + {@code @MockitoBean} (no
 * Testcontainers). Covers the happy paths (200 + archivedAt marker; un-archive clears it), the
 * typed error mappings (404 {@code RUN_NOT_FOUND}, 409 {@code ARCHIVE_NOT_APPLICABLE}, 400 missing
 * idempotency key / blank reason), and that {@code includeArchived} is threaded to the service.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ArchiveRunEndpointContractTest {

  private static final String RUN_ID = "run_archive_endpoint_aaa";
  private static final String IDEMPOTENCY_KEY = "idem-archive-endpoint-aaaaaa";
  private static final OffsetDateTime ARCHIVED_AT =
      OffsetDateTime.of(2026, 6, 23, 12, 0, 0, 0, ZoneOffset.UTC);

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;

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

  @Test
  void archiveHappyPathSetsMarkerAndCapturesHumanCommand() throws Exception {
    when(workflowArchiveService.archiveRun(any()))
        .thenReturn(
            new WorkflowArchiveResult(RUN_ID, WorkflowState.FAILED, ARCHIVED_AT, "corr", false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/archive", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-Actor-Identity", "alex")
                .content("{\"reason\": \"ticket removed from source\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workflowRunId").value(RUN_ID))
        .andExpect(jsonPath("$.currentState").value("Failed"))
        .andExpect(jsonPath("$.archivedAt").exists());

    ArgumentCaptor<ArchiveRunCommand> captor = ArgumentCaptor.forClass(ArchiveRunCommand.class);
    verify(workflowArchiveService).archiveRun(captor.capture());
    ArchiveRunCommand captured = captor.getValue();
    assertThat(captured.workflowRunPublicId()).isEqualTo(RUN_ID);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
    assertThat(captured.actorType()).isEqualTo(ActorType.HUMAN);
    assertThat(captured.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(captured.reason()).isEqualTo("ticket removed from source");
  }

  @Test
  void unarchiveHappyPathClearsMarker() throws Exception {
    when(workflowArchiveService.unarchiveRun(any()))
        .thenReturn(new WorkflowArchiveResult(RUN_ID, WorkflowState.FAILED, null, "corr", false));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/unarchive", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content("{\"reason\": \"back in scope\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentState").value("Failed"))
        .andExpect(jsonPath("$.archivedAt").doesNotExist());

    ArgumentCaptor<UnarchiveRunCommand> captor = ArgumentCaptor.forClass(UnarchiveRunCommand.class);
    verify(workflowArchiveService).unarchiveRun(captor.capture());
    assertThat(captor.getValue().reason()).isEqualTo("back in scope");
  }

  @Test
  void archiveAlreadyArchivedMapsToConflict() throws Exception {
    when(workflowArchiveService.archiveRun(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ARCHIVE_NOT_APPLICABLE,
                "already archived",
                Map.of("runId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/archive", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content("{\"reason\": \"obsolete\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("ARCHIVE_NOT_APPLICABLE"));
  }

  @Test
  void archiveRunNotFoundMapsToNotFound() throws Exception {
    when(workflowArchiveService.archiveRun(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND, "not found", Map.of("runId", RUN_ID)));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/archive", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content("{\"reason\": \"obsolete\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void archiveMissingIdempotencyKeyMapsToTypedProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/archive", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"obsolete\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
  }

  @Test
  void archiveBlankReasonRejectedAsInvalidCommandPayload() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/archive", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content("{\"reason\": \"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void listWorkflowsThreadsIncludeArchivedToService() throws Exception {
    when(workflowInspectionService.listRuns(
            any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/workflows")
                .param("includeArchived", "true")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
    verify(workflowInspectionService).listRuns(eq(null), eq(true), eq(50));

    mockMvc
        .perform(get("/api/v1/workflows").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
    verify(workflowInspectionService).listRuns(eq(null), eq(false), eq(50));
  }
}
