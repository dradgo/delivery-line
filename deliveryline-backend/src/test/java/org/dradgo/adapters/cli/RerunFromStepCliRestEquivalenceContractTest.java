package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.dradgo.adapters.rest.WorkflowController;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RerunFromStepRecoveryResult;
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
 * Story 4.12 AC6 / Reconciliation 4 — CLI/REST equivalence for {@code rerun-from-step}. Drives the
 * same logical rerun through both surfaces — REST {@code POST /{runId}/rerun-from-step}
 * (header-derived actor + MDC correlation, omitted {@code X-Actor-Identity} → {@code
 * local-operator}) and the Spring Shell {@code deliveryline operator rerun-from-step} (positional
 * runId, omitted {@code --actor-identity} → {@code local-operator}, {@code --correlation-id}) —
 * then asserts the FIVE positional arguments captured by the mocked {@link
 * RecoveryService#rerunFromStep} are equal across the two surfaces, with {@link ActorContext}
 * compared by record equality.
 *
 * <p>{@link RecoveryService#rerunFromStep} takes five positional args — {@code (workflowRunId,
 * targetStep, idempotencyKey, actor, reasonText)} — with {@code targetStep} SECOND, BEFORE {@code
 * idempotencyKey} (Reconciliation 4). Lives in the {@code adapters.cli} package so it can use
 * {@link OperatorCommands}' package-private test constructor. The MDC trick + {@code
 * --correlation-id} keeps both captures stamped with the same correlation id.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class RerunFromStepCliRestEquivalenceContractTest {

  private static final String RUN_ID = "run_rerun_equiv_a";
  private static final String TARGET_STEP = "investigating";
  private static final String IDEMPOTENCY_KEY = "idem-rerun-equiv-aaaa";
  private static final String CORRELATION_ID = "corr-rerun-equiv-a";
  private static final String REASON = "Spec was wrong; re-specifying from scratch.";

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

  private static RerunFromStepRecoveryResult result() {
    return new RerunFromStepRecoveryResult(
        "rcv_rerun_equiv_a",
        "evt_rerun_equiv_a",
        List.of(),
        List.of(),
        "rex_rerun_equiv_a",
        WorkflowState.INVESTIGATING,
        CORRELATION_ID,
        false);
  }

  @Test
  void restAndCliProduceIdenticalRerunFromStepArguments() throws Exception {
    when(recoveryService.rerunFromStep(any(), any(), any(), any(), any())).thenReturn(result());

    // REST surface: omit X-Actor-Identity so it falls back to local-operator; correlation from MDC.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/rerun-from-step", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "targetStep": "investigating",
                      "reasonText": "Spec was wrong; re-specifying from scratch."
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<String> restRunId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> restTarget = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> restKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> restActor = ArgumentCaptor.forClass(ActorContext.class);
    ArgumentCaptor<String> restReason = ArgumentCaptor.forClass(String.class);
    verify(recoveryService)
        .rerunFromStep(
            restRunId.capture(),
            restTarget.capture(),
            restKey.capture(),
            restActor.capture(),
            restReason.capture());

    // CLI surface: a separately-mocked RecoveryService, driven with the same logical inputs;
    // --actor-identity omitted (→ local-operator), --correlation-id supplied to match REST's MDC.
    RecoveryService cliRecoveryService = mock(RecoveryService.class);
    when(cliRecoveryService.rerunFromStep(any(), any(), any(), any(), any())).thenReturn(result());
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
    operatorCommands.rerunFromStep(
        RUN_ID, TARGET_STEP, REASON, IDEMPOTENCY_KEY, null, CORRELATION_ID, "text", false);

    ArgumentCaptor<String> cliRunId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> cliTarget = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> cliKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> cliActor = ArgumentCaptor.forClass(ActorContext.class);
    ArgumentCaptor<String> cliReason = ArgumentCaptor.forClass(String.class);
    verify(cliRecoveryService)
        .rerunFromStep(
            cliRunId.capture(),
            cliTarget.capture(),
            cliKey.capture(),
            cliActor.capture(),
            cliReason.capture());

    // Five positional args equal across surfaces (ActorContext by record equality — Reconciliation
    // 4); targetStep is SECOND, before idempotencyKey.
    assertThat(cliRunId.getValue()).isEqualTo(restRunId.getValue()).isEqualTo(RUN_ID);
    assertThat(cliTarget.getValue()).isEqualTo(restTarget.getValue()).isEqualTo(TARGET_STEP);
    assertThat(cliKey.getValue()).isEqualTo(restKey.getValue()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(cliReason.getValue()).isEqualTo(restReason.getValue()).isEqualTo(REASON);
    assertThat(cliActor.getValue()).isEqualTo(restActor.getValue());
    // And both resolved the omitted actor identity to the shared local-operator fallback + HUMAN.
    assertThat(restActor.getValue())
        .isEqualTo(new ActorContext("local-operator", ActorType.HUMAN, CORRELATION_ID));
  }
}
