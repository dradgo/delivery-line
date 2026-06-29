package org.dradgo.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dradgo.adapters.cli.WorkflowCommands;
import org.dradgo.adapters.rest.WorkflowController;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
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

@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class WorkflowAdapterEquivalenceTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;

  // Story 3.25 — WorkflowController now also wires the rich DeveloperTakeoverService for POST
  // /takeover. Unused by this slice, but the bean must exist for the controller to construct.
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;

  @MockitoBean private WorkflowArchiveService workflowArchiveService;

  // Story 6.9 — WorkflowController now also delegates GET reads to WorkflowInspectionService.
  // This @WebMvcTest slice exercises only the POST command equivalence, but the bean must exist
  // for the controller to construct.
  @MockitoBean private WorkflowInspectionService workflowInspectionService;

  // Story 2.13 — WorkflowController depends on LocalActorIdentityResolver. The submit endpoint
  // doesn't use it, but the controller bean still requires injection at construction.
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

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
    // Story 2.13 review P13: stub resolve(...) so any future approve/reject coverage added here
    // doesn't silently inject a null actorIdentity into captured commands. Mirrors the resolver's
    // production behavior: trim non-blank header → identity; blank/null → "local-operator".
    when(localActorIdentityResolver.resolve(any()))
        .thenAnswer(
            invocation -> {
              String supplied = invocation.getArgument(0);
              if (supplied == null || supplied.isBlank()) {
                return "local-operator";
              }
              return supplied.trim();
            });
  }

  @Test
  void cliAndRestSubmitTranslateTheSameLogicalPayloadIntoTheSameCommand() throws Exception {
    when(workflowCommandService.submit(any()))
        .thenReturn(
            new SubmitWorkflowResult("run_submit1234", WorkflowState.INBOX, "corr-submit-1"));

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService, () -> false, () -> "01964c38-1c45-7000-8000-000000000000");

    String cliOutput =
        cli.submit(
            "LIN-123",
            "alex",
            ActorType.HUMAN,
            "idem-submit-1234567890",
            "corr-submit-1",
            null,
            false);

    mockMvc
        .perform(
            post("/api/v1/workflows/submit-workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-submit-1234567890")
                .content(
                    """
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.workflowRunId").value("run_submit1234"))
        .andExpect(jsonPath("$.currentState").value("Inbox"))
        .andExpect(jsonPath("$.correlationId").value("corr-submit-1"));

    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(workflowCommandService, times(2)).submit(captor.capture());
    assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
    assertEquals("run_submit1234 submitted (state: Inbox)", cliOutput);
  }

  @Test
  void cliAndRestSurfaceTheSameStableErrorCodeWhenSubmitFailsValidation() throws Exception {
    DomainException failure =
        new DomainException(DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Invalid command payload");
    when(workflowCommandService.submit(any())).thenThrow(failure);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService, () -> false, () -> "01964c38-1c45-7000-8000-000000000000");

    DomainException cliError =
        assertThrows(
            DomainException.class,
            () ->
                cli.submit(
                    "LIN-123",
                    "alex",
                    ActorType.HUMAN,
                    "idem-submit-1234567890",
                    "corr-submit-1",
                    null,
                    false));

    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, cliError.errorCode());
    mockMvc
        .perform(
            post("/api/v1/workflows/submit-workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-submit-1234567890")
                .content(
                    """
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(cliError.errorCode().value()));
  }

  @Test
  void missingIdempotencyHeaderFlowsThroughTheActualWebLayerAsSpecificIdempotencyFailure()
      throws Exception {
    when(workflowCommandService.submit(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MISSING_IDEMPOTENCY_KEY,
                "Missing idempotency key",
                new java.util.LinkedHashMap<>(java.util.Map.of())));

    mockMvc
        .perform(
            post("/api/v1/workflows/submit-workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(
                    """
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(DomainErrorCode.MISSING_IDEMPOTENCY_KEY.value()));
  }

  @Test
  void cliAndRestSurfaceTheSameStableErrorCodeWhenIdempotencyKeyIsMalformed() throws Exception {
    java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
    details.put("idempotencyKey", "bad key");
    details.put("rule", "[A-Za-z0-9-]{16,128} or UUIDv4/UUIDv7");
    DomainException failure =
        new DomainException(
            DomainErrorCode.INVALID_IDEMPOTENCY_KEY, "Invalid idempotency key", details);
    when(workflowCommandService.submit(any())).thenThrow(failure);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService, () -> false, () -> "01964c38-1c45-7000-8000-000000000000");

    DomainException cliError =
        assertThrows(
            DomainException.class,
            () ->
                cli.submit(
                    "LIN-123",
                    "alex",
                    ActorType.HUMAN,
                    "idem-submit-1234567890",
                    "corr-submit-1",
                    null,
                    false));

    assertEquals(DomainErrorCode.INVALID_IDEMPOTENCY_KEY, cliError.errorCode());
    mockMvc
        .perform(
            post("/api/v1/workflows/submit-workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-submit-1234567890")
                .content(
                    """
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(DomainErrorCode.INVALID_IDEMPOTENCY_KEY.value()));
  }

  @Test
  void cliAndRestSurfaceTheSameStableErrorCodeOnIdempotencyConflict() throws Exception {
    java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
    details.put("idempotencyKey", "idem-submit-1234567890");
    details.put("existingFingerprint", "a".repeat(64));
    details.put("submittedFingerprint", "b".repeat(64));
    DomainException failure =
        new DomainException(
            DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, "Idempotency key conflict", details);
    when(workflowCommandService.submit(any())).thenThrow(failure);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService, () -> false, () -> "01964c38-1c45-7000-8000-000000000000");

    DomainException cliError =
        assertThrows(
            DomainException.class,
            () ->
                cli.submit(
                    "LIN-123",
                    "alex",
                    ActorType.HUMAN,
                    "idem-submit-1234567890",
                    "corr-submit-1",
                    null,
                    false));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, cliError.errorCode());
    mockMvc
        .perform(
            post("/api/v1/workflows/submit-workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-submit-1234567890")
                .content(
                    """
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT.value()))
        .andExpect(jsonPath("$.details.existingFingerprint").value("a".repeat(64)))
        .andExpect(jsonPath("$.details.submittedFingerprint").value("b".repeat(64)));
  }

  @Test
  void cliAndRestProduceEquivalentResponsesOnIdempotencyReplay() throws Exception {
    // Service mock returns the same SubmitWorkflowResult twice, simulating the
    // replay path where checkAndReserve sees a prior COMPLETED record. The
    // adapter contract must produce equivalent shapes on both calls — a
    // genuine retry should not be observable to the caller as a different
    // response than the original.
    when(workflowCommandService.submit(any()))
        .thenReturn(
            new SubmitWorkflowResult("run_submit1234", WorkflowState.INBOX, "corr-submit-1"));

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService, () -> false, () -> "01964c38-1c45-7000-8000-000000000000");

    String firstCli =
        cli.submit(
            "LIN-123",
            "alex",
            ActorType.HUMAN,
            "idem-replay-1234567890",
            "corr-submit-1",
            null,
            false);
    String secondCli =
        cli.submit(
            "LIN-123",
            "alex",
            ActorType.HUMAN,
            "idem-replay-1234567890",
            "corr-submit-1",
            null,
            false);
    assertEquals(firstCli, secondCli);

    String body =
        """
			{
			  "linearTicketReference": "LIN-123",
			  "actorIdentity": "alex",
			  "actorType": "HUMAN",
			  "correlationId": "corr-submit-1"
			}
			""";
    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              post("/api/v1/workflows/submit-workflow")
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaType.APPLICATION_JSON)
                  .header("Idempotency-Key", "idem-replay-1234567890")
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.workflowRunId").value("run_submit1234"))
          .andExpect(jsonPath("$.currentState").value("Inbox"))
          .andExpect(jsonPath("$.correlationId").value("corr-submit-1"));
    }
  }

  @Test
  void actualWebLayerParsesJsonEnumsAndHeadersIntoTheSharedSubmitCommand() throws Exception {
    when(workflowCommandService.submit(any()))
        .thenReturn(new SubmitWorkflowResult("run_submit5678", WorkflowState.INBOX, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/submit-workflow")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-submit-abcdef123456")
                .content(
                    """
					{
					  "linearTicketReference": "LIN-456",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-2"
					}
					"""))
        .andExpect(status().isOk());

    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(workflowCommandService).submit(captor.capture());
    assertEquals(
        new SubmitWorkflowCommand(
            "alex", ActorType.HUMAN, "idem-submit-abcdef123456", "corr-submit-2", "LIN-456"),
        captor.getValue());
  }
}
