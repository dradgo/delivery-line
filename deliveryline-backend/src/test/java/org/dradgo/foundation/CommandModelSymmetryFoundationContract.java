package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.dradgo.adapters.cli.WorkflowCommands;
import org.dradgo.adapters.rest.WorkflowController;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.AcceptClarificationCommand;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RegenerateSpecCommand;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.commands.WorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Foundation contract #6 for the shared workflow command model.
 *
 * <p>Epic 1 exposes REST entry points for every permitted {@link WorkflowCommand} type and one CLI
 * path that traverses the same shared command model: submit. This contract locks both invariants:
 *
 * <ol>
 *   <li>the sealed permit set stays explicit and every permit still maps to the canonical REST
 *       command record, and
 *   <li>submit remains equivalent across REST and CLI for the same payload and mocked {@code
 *       DomainResult}.
 * </ol>
 */
@Tag("foundation-gate")
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class CommandModelSymmetryFoundationContract {

  // Story 3.20 added AcceptImplementationCommand and story 3.21 added RejectImplementationCommand
  // to the sealed permit set (service-only surface via WorkflowCommandService.acceptImplementation
  // /
  // rejectImplementation). Story 3.23 wired the accept-implementation REST endpoint + its
  // round-trip
  // capture below (everyWorkflowCommandPermitRoundTripsThroughRestAsTheCanonicalRecord). The
  // RejectImplementationCommand REST round-trip stays deferred to story 3.24 (reject-implementation
  // endpoint + OpenAPI), so it remains recorded as a known permit without a manual REST capture
  // until that story lands.
  //
  // Story 3e-2 (FR10 incorporation loop) added AcceptClarificationCommand (accept-clarification
  // REST) and RegenerateSpecCommand (regenerate-spec REST) to the sealed permit set. They are
  // recorded here as known permits to keep the set explicit; their REST round-trip captures follow
  // the RejectImplementationCommand precedent (permit recorded without a mandatory capture block).
  private static final Set<Class<?>> EXPECTED_PERMITS =
      Set.of(
          SubmitWorkflowCommand.class,
          ApproveSpecCommand.class,
          RejectSpecCommand.class,
          AcceptImplementationCommand.class,
          RejectImplementationCommand.class,
          SubmitClarificationCommand.class,
          RetryWorkflowCommand.class,
          TakeoverWorkflowCommand.class,
          AcceptClarificationCommand.class,
          RegenerateSpecCommand.class);

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;

  // Story 6.9 — WorkflowController now also depends on WorkflowInspectionService (GET reads); the
  // bean must exist for this command-symmetry @WebMvcTest slice to construct the controller.
  @MockitoBean
  private org.dradgo.application.workflow.WorkflowInspectionService workflowInspectionService;

  // Story 2.13 — WorkflowController depends on LocalActorIdentityResolver; mocked here to
  // deterministically return "alex" so the captured commands match the expected actor identity.
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

  // Story 3.25 — WorkflowController wires the rich DeveloperTakeoverService for POST /takeover. The
  // bean must exist for this slice to construct the controller; the existing captureTakeover
  // round-trip still exercises the transition-only /takeover-workflow path (R9 — unchanged).
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;

  @MockitoBean private WorkflowArchiveService workflowArchiveService;

  @org.junit.jupiter.api.BeforeEach
  void stubActorResolver() {
    when(localActorIdentityResolver.resolve(any())).thenReturn("alex");
  }

  @Test
  void everyWorkflowCommandPermitRoundTripsThroughRestAsTheCanonicalRecord() throws Exception {
    Class<?>[] permits = WorkflowCommand.class.getPermittedSubclasses();
    if (permits == null || permits.length == 0) {
      fail(
          FoundationGateAssertions.tagged(
              "1.7",
              "WorkflowCommand reports no sealed permits - sealed-interface contract is broken"));
      return;
    }
    if (!Set.of(permits).equals(EXPECTED_PERMITS)) {
      fail(
          FoundationGateAssertions.tagged(
              "1.7",
              "WorkflowCommand sealed permits drifted. Expected "
                  + EXPECTED_PERMITS
                  + " but found "
                  + Set.of(permits)));
    }

    List<String> violations = new ArrayList<>();

    SubmitWorkflowCommand expectedSubmit =
        new SubmitWorkflowCommand(
            "alex",
            ActorType.HUMAN,
            "idem-submit-aaaaaaaaaaaaaaaa",
            "corr-submit-1",
            "LIN-FOUND-001");
    when(workflowCommandService.submit(any()))
        .thenReturn(
            new SubmitWorkflowResult("run_found_submit01", WorkflowState.INBOX, "corr-submit-1"));
    SubmitWorkflowCommand capturedSubmit =
        captureSubmit(
            "/api/v1/workflows/submit-workflow",
            "idem-submit-aaaaaaaaaaaaaaaa",
            """
            {
              "linearTicketReference": "LIN-FOUND-001",
              "actorIdentity": "alex",
              "actorType": "HUMAN",
              "correlationId": "corr-submit-1"
            }
            """);
    if (!expectedSubmit.equals(capturedSubmit)) {
      violations.add(
          "SubmitWorkflowCommand REST-to-command mismatch: expected="
              + expectedSubmit
              + " captured="
              + capturedSubmit);
    }

    // Story 2.13: actorIdentity comes from X-Actor-Identity header (mocked resolver returns
    // "alex"); correlationId from MDC is null in this @WebMvcTest slice (no CorrelationIdFilter
    // registered). Wire DTO renames artifactVersion → expectedArtifactVersion and contextVersion
    // → expectedContextBundleVersion; the command record keeps the short names for fingerprint
    // symmetry with SubmitClarificationCommand.
    ApproveSpecCommand expectedApprove =
        new ApproveSpecCommand(
            "run_found_submit01",
            "art_spec_v1_xyz",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-approve-bbbbbbbbbbbbbbbb",
            null,
            // The REST body intentionally omits reviewerRole below so this assertion also pins
            // the ApprovalReviewerRoleResolver MVP-fallback default (product_reviewer) wired by
            // story 2.9.
            "product_reviewer",
            null);
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                "run_found_submit01", WorkflowState.EXECUTING, "corr-approve-1"));
    ApproveSpecCommand capturedApprove =
        captureApprove(
            "/api/v1/workflows/run_found_submit01/approve-spec",
            "idem-approve-bbbbbbbbbbbbbbbb",
            """
            {
              "artifactId": "art_spec_v1_xyz",
              "expectedArtifactVersion": 1,
              "expectedContextBundleVersion": 1
            }
            """);
    if (!expectedApprove.equals(capturedApprove)) {
      violations.add(
          "ApproveSpecCommand REST-to-command mismatch: expected="
              + expectedApprove
              + " captured="
              + capturedApprove);
    }

    // Story 2.13: header-derived actor, MDC-null correlation, renamed wire versions (see approve).
    RejectSpecCommand expectedReject =
        new RejectSpecCommand(
            "run_found_submit01",
            "art_spec_v1_xyz",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-reject-cccccccccccccccc",
            null,
            "product_reviewer",
            org.dradgo.domain.registry.RejectionTaxonomy.UNCLEAR_SPECIFICATION,
            "Spec missing acceptance criteria for the negative path");
    when(workflowCommandService.rejectSpec(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                "run_found_submit01", WorkflowState.WAITING_FOR_SPEC_APPROVAL, "corr-reject-1"));
    RejectSpecCommand capturedReject =
        captureReject(
            "/api/v1/workflows/run_found_submit01/reject-spec",
            "idem-reject-cccccccccccccccc",
            """
            {
              "artifactId": "art_spec_v1_xyz",
              "expectedArtifactVersion": 1,
              "expectedContextBundleVersion": 1,
              "taggedFeedback": "UNCLEAR_SPECIFICATION",
              "reasonText": "Spec missing acceptance criteria for the negative path"
            }
            """);
    if (!expectedReject.equals(capturedReject)) {
      violations.add(
          "RejectSpecCommand REST-to-command mismatch: expected="
              + expectedReject
              + " captured="
              + capturedReject);
    }

    // Story 3.23: the accept-implementation endpoint requires reviewerRole=developer (unlike
    // approve-spec, whose body omits the role to pin the resolver default). The verbose wire
    // versions map to the short command fields; header-derived actor, MDC-null correlation here.
    AcceptImplementationCommand expectedAccept =
        new AcceptImplementationCommand(
            "run_found_submit01",
            "art_impl_v1_xyz",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-accept-ffffffffffffffff",
            null,
            "developer",
            null);
    when(workflowCommandService.acceptImplementation(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                "run_found_submit01", WorkflowState.EXECUTING, "corr-accept-1"));
    AcceptImplementationCommand capturedAccept =
        captureAcceptImplementation(
            "/api/v1/workflows/run_found_submit01/accept-implementation",
            "idem-accept-ffffffffffffffff",
            """
            {
              "artifactId": "art_impl_v1_xyz",
              "expectedArtifactVersion": 1,
              "expectedContextBundleVersion": 1,
              "reviewerRole": "developer"
            }
            """);
    if (!expectedAccept.equals(capturedAccept)) {
      violations.add(
          "AcceptImplementationCommand REST-to-command mismatch: expected="
              + expectedAccept
              + " captured="
              + capturedAccept);
    }

    RetryWorkflowCommand expectedRetry =
        new RetryWorkflowCommand(
            "run_found_submit01",
            "alex",
            ActorType.HUMAN,
            "idem-retry-dddddddddddddddd",
            "corr-retry-1",
            "Transient runner crash, manual retry requested");
    when(workflowCommandService.retryWorkflow(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                "run_found_submit01", WorkflowState.EXECUTING, "corr-retry-1"));
    RetryWorkflowCommand capturedRetry =
        captureRetry(
            "/api/v1/workflows/run_found_submit01/retry-workflow",
            "idem-retry-dddddddddddddddd",
            """
            {
              "actorIdentity": "alex",
              "actorType": "HUMAN",
              "correlationId": "corr-retry-1",
              "reasonText": "Transient runner crash, manual retry requested"
            }
            """);
    if (!expectedRetry.equals(capturedRetry)) {
      violations.add(
          "RetryWorkflowCommand REST-to-command mismatch: expected="
              + expectedRetry
              + " captured="
              + capturedRetry);
    }

    TakeoverWorkflowCommand expectedTakeover =
        new TakeoverWorkflowCommand(
            "run_found_submit01",
            "alex",
            ActorType.HUMAN,
            "idem-takeover-eeeeeeeeeeeeee",
            "corr-takeover-1",
            "Operator takeover for manual intervention");
    when(workflowCommandService.takeoverWorkflow(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                "run_found_submit01", WorkflowState.EXECUTING, "corr-takeover-1"));
    TakeoverWorkflowCommand capturedTakeover =
        captureTakeover(
            "/api/v1/workflows/run_found_submit01/takeover-workflow",
            "idem-takeover-eeeeeeeeeeeeee",
            """
            {
              "actorIdentity": "alex",
              "actorType": "HUMAN",
              "correlationId": "corr-takeover-1",
              "reasonText": "Operator takeover for manual intervention"
            }
            """);
    if (!expectedTakeover.equals(capturedTakeover)) {
      violations.add(
          "TakeoverWorkflowCommand REST-to-command mismatch: expected="
              + expectedTakeover
              + " captured="
              + capturedTakeover);
    }

    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.7",
              "shared command-model REST-to-command runtime equality broken ("
                  + violations.size()
                  + " violation"
                  + (violations.size() == 1 ? "" : "s")
                  + "): "
                  + String.join("; ", violations)));
    }
  }

  @Test
  void submitSharedCommandModelProducesEquivalentCommandAndResultAcrossRestAndCli()
      throws Exception {
    SubmitWorkflowResult canned =
        new SubmitWorkflowResult("run_found_submit01", WorkflowState.INBOX, "corr-submit-1");
    when(workflowCommandService.submit(any())).thenReturn(canned);

    SubmitWorkflowCommand restCommand =
        captureSubmit(
            "/api/v1/workflows/submit-workflow",
            "idem-submit-aaaaaaaaaaaaaaaa",
            """
            {
              "linearTicketReference": "LIN-FOUND-001",
              "actorIdentity": "alex",
              "actorType": "HUMAN",
              "correlationId": "corr-submit-1"
            }
            """);

    clearInvocations(workflowCommandService);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService, () -> false, () -> "generated-idempotency-key-unused");
    String cliOutput =
        cli.submit(
            "LIN-FOUND-001",
            "alex",
            ActorType.HUMAN,
            "idem-submit-aaaaaaaaaaaaaaaa",
            "corr-submit-1",
            null,
            false);

    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(workflowCommandService).submit(captor.capture());
    SubmitWorkflowCommand cliCommand = captor.getValue();

    assertEquals(
        restCommand,
        cliCommand,
        FoundationGateAssertions.tagged(
            "1.7",
            "CLI and REST submit surfaces built different SubmitWorkflowCommand records for the"
                + " same payload"));
    assertEquals(
        "run_found_submit01 submitted (state: Inbox)",
        cliOutput,
        FoundationGateAssertions.tagged(
            "1.7",
            "CLI submit surface rendered a different result payload from the REST DomainResult"));
  }

  private SubmitWorkflowCommand captureSubmit(String path, String idempotencyKey, String body)
      throws Exception {
    mockMvc
        .perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(body))
        .andExpect(status().isOk());
    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(workflowCommandService).submit(captor.capture());
    SubmitWorkflowCommand captured = captor.getValue();
    assertNotNull(captured, "captured SubmitWorkflowCommand was null");
    return captured;
  }

  private ApproveSpecCommand captureApprove(String path, String idempotencyKey, String body)
      throws Exception {
    mockMvc
        .perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(body))
        .andExpect(status().isOk());
    ArgumentCaptor<ApproveSpecCommand> captor = ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(captor.capture());
    ApproveSpecCommand captured = captor.getValue();
    assertNotNull(captured, "captured ApproveSpecCommand was null");
    return captured;
  }

  private RejectSpecCommand captureReject(String path, String idempotencyKey, String body)
      throws Exception {
    mockMvc
        .perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(body))
        .andExpect(status().isOk());
    ArgumentCaptor<RejectSpecCommand> captor = ArgumentCaptor.forClass(RejectSpecCommand.class);
    verify(workflowCommandService).rejectSpec(captor.capture());
    RejectSpecCommand captured = captor.getValue();
    assertNotNull(captured, "captured RejectSpecCommand was null");
    return captured;
  }

  private AcceptImplementationCommand captureAcceptImplementation(
      String path, String idempotencyKey, String body) throws Exception {
    mockMvc
        .perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(body))
        .andExpect(status().isOk());
    ArgumentCaptor<AcceptImplementationCommand> captor =
        ArgumentCaptor.forClass(AcceptImplementationCommand.class);
    verify(workflowCommandService).acceptImplementation(captor.capture());
    AcceptImplementationCommand captured = captor.getValue();
    assertNotNull(captured, "captured AcceptImplementationCommand was null");
    return captured;
  }

  private RetryWorkflowCommand captureRetry(String path, String idempotencyKey, String body)
      throws Exception {
    mockMvc
        .perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(body))
        .andExpect(status().isOk());
    ArgumentCaptor<RetryWorkflowCommand> captor =
        ArgumentCaptor.forClass(RetryWorkflowCommand.class);
    verify(workflowCommandService).retryWorkflow(captor.capture());
    RetryWorkflowCommand captured = captor.getValue();
    assertNotNull(captured, "captured RetryWorkflowCommand was null");
    return captured;
  }

  private TakeoverWorkflowCommand captureTakeover(String path, String idempotencyKey, String body)
      throws Exception {
    mockMvc
        .perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(body))
        .andExpect(status().isOk());
    ArgumentCaptor<TakeoverWorkflowCommand> captor =
        ArgumentCaptor.forClass(TakeoverWorkflowCommand.class);
    verify(workflowCommandService).takeoverWorkflow(captor.capture());
    TakeoverWorkflowCommand captured = captor.getValue();
    assertNotNull(captured, "captured TakeoverWorkflowCommand was null");
    return captured;
  }

  @Test
  void domainResultStructurallyEqualOnSubmitAcrossInvocationsOfTheSameRestPayload()
      throws Exception {
    SubmitWorkflowResult canned =
        new SubmitWorkflowResult("run_found_submit01", WorkflowState.INBOX, "corr-submit-1");
    when(workflowCommandService.submit(any())).thenReturn(canned);

    String body =
        """
        {
          "linearTicketReference": "LIN-FOUND-001",
          "actorIdentity": "alex",
          "actorType": "HUMAN",
          "correlationId": "corr-submit-1"
        }
        """;

    String first =
        mockMvc
            .perform(
                post("/api/v1/workflows/submit-workflow")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "idem-submit-aaaaaaaaaaaaaaaa")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workflowRunId").value(canned.workflowRunId()))
            .andExpect(jsonPath("$.currentState").value(canned.currentState().value()))
            .andExpect(jsonPath("$.correlationId").value(canned.correlationId()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mockMvc
            .perform(
                post("/api/v1/workflows/submit-workflow")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "idem-submit-aaaaaaaaaaaaaaaa")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertEquals(
        first,
        second,
        FoundationGateAssertions.tagged(
            "1.7",
            "REST surface produced different wire responses for the same DomainResult across two"
                + " identical invocations - response serializer is non-deterministic"));

    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(workflowCommandService, org.mockito.Mockito.times(2)).submit(captor.capture());
    SubmitWorkflowCommand a = captor.getAllValues().get(0);
    SubmitWorkflowCommand b = captor.getAllValues().get(1);
    assertEquals(
        a,
        b,
        FoundationGateAssertions.tagged(
            "1.7",
            "REST surface produced different SubmitWorkflowCommand records for the same payload"
                + " across two identical invocations - DTO-to-command mapping is"
                + " non-deterministic"));
  }
}
