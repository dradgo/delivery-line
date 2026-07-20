package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dradgo.adapters.rest.WorkflowController;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.PauseRecoveryResult;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.registry.ActorType;
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
 * Story 4.13 AC6 / Reconciliation 4 — CLI/REST equivalence for {@code pause}. Drives the same
 * logical pause through both surfaces — REST {@code POST /{runId}/pause} (header-derived actor +
 * MDC correlation, omitted {@code X-Actor-Identity} → {@code local-operator}) and the Spring Shell
 * {@code deliveryline operator pause} (positional runId, omitted {@code --actor-identity} → {@code
 * local-operator}, {@code --correlation-id}) — then asserts the FOUR positional arguments captured
 * by the mocked {@link RecoveryService#pause} are equal across the two surfaces, with {@link
 * ActorContext} compared by record equality.
 *
 * <p>{@link RecoveryService#pause} takes four positional args — {@code (workflowRunId,
 * idempotencyKey, actor, reasonText)} — with NO domain field before {@code idempotencyKey} and
 * {@code reasonText} LAST, the SAME shape as {@code resume} (Reconciliation 4). Lives in the {@code
 * adapters.cli} package so it can use {@link OperatorCommands}' package-private test constructor.
 * The MDC trick + {@code --correlation-id} keeps both captures stamped with the same correlation
 * id.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class PauseCliRestEquivalenceContractTest {

  private static final String RUN_ID = "run_pause_equiv_a";
  private static final String IDEMPOTENCY_KEY = "idem-pause-equiv-aaaa";
  private static final String CORRELATION_ID = "corr-pause-equiv-a";
  private static final String REASON = "Uncertain about the runner output; pausing to inspect.";

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

  @BeforeEach
  void stubActorResolverAndSeedMdc() {
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
    MDC.put(MdcKeys.CORRELATION_ID, CORRELATION_ID);
  }

  @AfterEach
  void clearMdc() {
    MDC.remove(MdcKeys.CORRELATION_ID);
  }

  private static PauseRecoveryResult result() {
    return new PauseRecoveryResult(
        "rcv_pause_equiv_a",
        "evt_pause_equiv_a",
        WorkflowState.EXECUTING,
        1,
        2,
        WorkflowState.PAUSED,
        CORRELATION_ID,
        false);
  }

  @Test
  void restAndCliProduceIdenticalPauseArguments() throws Exception {
    when(recoveryService.pause(any(), any(), any(), any())).thenReturn(result());

    // REST surface: omit X-Actor-Identity so it falls back to local-operator; correlation from MDC.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/pause", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "reasonText": "Uncertain about the runner output; pausing to inspect."
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<String> restRunId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> restKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> restActor = ArgumentCaptor.forClass(ActorContext.class);
    ArgumentCaptor<String> restReason = ArgumentCaptor.forClass(String.class);
    verify(recoveryService)
        .pause(restRunId.capture(), restKey.capture(), restActor.capture(), restReason.capture());

    // CLI surface: a separately-mocked RecoveryService, driven with the same logical inputs;
    // --actor-identity omitted (→ local-operator), --correlation-id supplied to match REST's MDC.
    RecoveryService cliRecoveryService = mock(RecoveryService.class);
    when(cliRecoveryService.pause(any(), any(), any(), any())).thenReturn(result());
    OperatorCommands operatorCommands =
        new OperatorCommands(
            mock(WorkflowInspectionService.class),
            new WorkflowCommandOutputs(new ObjectMapper()),
            mock(CliInteractivityDetector.class),
            cliRecoveryService,
            new IdempotencyKeyValidator(),
            new LocalActorIdentityResolver("local-operator"),
            () -> CORRELATION_ID,
            () -> "idem-generated-should-not-be-used");
    operatorCommands.pause(RUN_ID, REASON, IDEMPOTENCY_KEY, null, CORRELATION_ID, "text", false);

    ArgumentCaptor<String> cliRunId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> cliKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> cliActor = ArgumentCaptor.forClass(ActorContext.class);
    ArgumentCaptor<String> cliReason = ArgumentCaptor.forClass(String.class);
    verify(cliRecoveryService)
        .pause(cliRunId.capture(), cliKey.capture(), cliActor.capture(), cliReason.capture());

    // Four positional args equal across surfaces (ActorContext by record equality — Reconciliation
    // 4); reasonText is LAST, no domain field before idempotencyKey.
    assertThat(cliRunId.getValue()).isEqualTo(restRunId.getValue()).isEqualTo(RUN_ID);
    assertThat(cliKey.getValue()).isEqualTo(restKey.getValue()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(cliReason.getValue()).isEqualTo(restReason.getValue()).isEqualTo(REASON);
    assertThat(cliActor.getValue()).isEqualTo(restActor.getValue());
    // And both resolved the omitted actor identity to the shared local-operator fallback + HUMAN.
    assertThat(restActor.getValue())
        .isEqualTo(new ActorContext("local-operator", ActorType.HUMAN, CORRELATION_ID));
  }
}
