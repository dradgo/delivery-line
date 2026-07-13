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
import org.dradgo.application.recovery.ClassifyFailureResult;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.registry.ActorType;
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
 * Story 4.14 AC6 / Reconciliation 7 — CLI/REST equivalence for {@code classify-failure}. Drives the
 * same logical classification through both surfaces — REST {@code POST /{runId}/classify-failure}
 * (header-derived actor + MDC correlation, omitted {@code X-Actor-Identity} → {@code
 * local-operator}) and the Spring Shell {@code deliveryline operator classify-failure} (positional
 * runId, omitted {@code --actor-identity} → {@code local-operator}, {@code --correlation-id}) —
 * then asserts the FIVE positional arguments captured by the mocked {@link
 * RecoveryService#classifyFailure} are equal across the two surfaces, with {@link ActorContext}
 * compared by record equality.
 *
 * <p>{@link RecoveryService#classifyFailure} takes five positional args — {@code (workflowRunId,
 * taxonomyValue, idempotencyKey, actor, reasonText)} — with {@code taxonomyValue} the single domain
 * field BEFORE {@code idempotencyKey} (like reconcile, unlike resume/pause) and {@code reasonText}
 * LAST (Reconciliation 7). Lives in the {@code adapters.cli} package so it can use {@link
 * OperatorCommands}' package-private test constructor. The MDC trick + {@code --correlation-id}
 * keeps both captures stamped with the same correlation id.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class ClassifyFailureCliRestEquivalenceContractTest {

  private static final String RUN_ID = "run_classify_equiv_a";
  private static final String TAXONOMY = "agent_execution_failure";
  private static final String IDEMPOTENCY_KEY = "idem-classify-equiv-aaaa";
  private static final String CORRELATION_ID = "corr-classify-equiv-a";
  private static final String REASON = "Classifying the failure for cross-run analysis.";

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

  private static ClassifyFailureResult result() {
    return new ClassifyFailureResult(
        "rcv_classify_equiv_a", "evt_classify_equiv_a", TAXONOMY, null, CORRELATION_ID, false);
  }

  @Test
  void restAndCliProduceIdenticalClassifyFailureArguments() throws Exception {
    when(recoveryService.classifyFailure(any(), any(), any(), any(), any())).thenReturn(result());

    // REST surface: omit X-Actor-Identity so it falls back to local-operator; correlation from MDC.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/classify-failure", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "taxonomyValue": "agent_execution_failure",
                      "reasonText": "Classifying the failure for cross-run analysis."
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<String> restRunId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> restTaxonomy = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> restKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> restActor = ArgumentCaptor.forClass(ActorContext.class);
    ArgumentCaptor<String> restReason = ArgumentCaptor.forClass(String.class);
    verify(recoveryService)
        .classifyFailure(
            restRunId.capture(),
            restTaxonomy.capture(),
            restKey.capture(),
            restActor.capture(),
            restReason.capture());

    // CLI surface: a separately-mocked RecoveryService, driven with the same logical inputs;
    // --actor-identity omitted (→ local-operator), --correlation-id supplied to match REST's MDC.
    RecoveryService cliRecoveryService = mock(RecoveryService.class);
    when(cliRecoveryService.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(result());
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
    operatorCommands.classifyFailure(
        RUN_ID, TAXONOMY, REASON, IDEMPOTENCY_KEY, null, CORRELATION_ID, "text", false);

    ArgumentCaptor<String> cliRunId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> cliTaxonomy = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> cliKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> cliActor = ArgumentCaptor.forClass(ActorContext.class);
    ArgumentCaptor<String> cliReason = ArgumentCaptor.forClass(String.class);
    verify(cliRecoveryService)
        .classifyFailure(
            cliRunId.capture(),
            cliTaxonomy.capture(),
            cliKey.capture(),
            cliActor.capture(),
            cliReason.capture());

    // Five positional args equal across surfaces (ActorContext by record equality — Reconciliation
    // 7); taxonomyValue is the single domain field BEFORE idempotencyKey, reasonText LAST.
    assertThat(cliRunId.getValue()).isEqualTo(restRunId.getValue()).isEqualTo(RUN_ID);
    assertThat(cliTaxonomy.getValue()).isEqualTo(restTaxonomy.getValue()).isEqualTo(TAXONOMY);
    assertThat(cliKey.getValue()).isEqualTo(restKey.getValue()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(cliReason.getValue()).isEqualTo(restReason.getValue()).isEqualTo(REASON);
    assertThat(cliActor.getValue()).isEqualTo(restActor.getValue());
    // And both resolved the omitted actor identity to the shared local-operator fallback + HUMAN.
    assertThat(restActor.getValue())
        .isEqualTo(new ActorContext("local-operator", ActorType.HUMAN, CORRELATION_ID));
  }
}
