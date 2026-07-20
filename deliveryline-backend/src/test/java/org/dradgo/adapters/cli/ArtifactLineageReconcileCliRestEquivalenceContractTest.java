package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dradgo.adapters.rest.ArtifactLineageController;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.LineageReconciliationResult;
import org.dradgo.application.artifact.ReconcileLineageCommand;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.16a (AC9) — CLI/REST equivalence for reconcile-lineage. Both surfaces call {@code
 * ArtifactReconciliationService.reconcileLineage(command)} directly; this captures the command from
 * each and asserts identical arguments (ActorContext by record equality, the omitted actor identity
 * resolving to the shared local-operator fallback on both sides).
 */
@WebMvcTest(controllers = ArtifactLineageController.class)
class ArtifactLineageReconcileCliRestEquivalenceContractTest {

  private static final String ARTIFACT_ID = "art_lineage0001";
  private static final String ACTION = "reattach_to_existing_lineage";
  private static final String PARENT = "art_lineage0002";
  private static final String IDEMPOTENCY_KEY = "idem-lineage-equiv-aaaa";
  private static final String CORRELATION_ID = "corr-lineage-equiv-a";
  private static final String REASON = "Re-parent onto the surviving leaf.";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ArtifactReconciliationService artifactReconciliationService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

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

  private static LineageReconciliationResult result() {
    return new LineageReconciliationResult(
        ARTIFACT_ID, ACTION, "rcv_lineage0001", "evt_lineage0001", PARENT, CORRELATION_ID, false);
  }

  @Test
  void restAndCliProduceIdenticalReconcileLineageCommands() throws Exception {
    when(artifactReconciliationService.reconcileLineage(any())).thenReturn(result());

    // REST surface: omit X-Actor-Identity so it falls back to local-operator; correlation from MDC.
    mockMvc
        .perform(
            post("/api/v1/artifacts/{artifactId}/reconcile-lineage", ARTIFACT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "lineageAction": "reattach_to_existing_lineage",
                      "chosenParentArtifactId": "art_lineage0002",
                      "reasonText": "Re-parent onto the surviving leaf."
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<ReconcileLineageCommand> restCommand =
        ArgumentCaptor.forClass(ReconcileLineageCommand.class);
    verify(artifactReconciliationService).reconcileLineage(restCommand.capture());

    // CLI surface: a separately-mocked service, same logical inputs; --actor-identity omitted
    // (-> local-operator), --correlation-id supplied to match REST's MDC.
    ArtifactReconciliationService cliService = mock(ArtifactReconciliationService.class);
    when(cliService.reconcileLineage(any())).thenReturn(result());
    OperatorCommands operatorCommands =
        new OperatorCommands(
            mock(WorkflowInspectionService.class),
            new WorkflowCommandOutputs(new ObjectMapper()),
            mock(CliInteractivityDetector.class),
            mock(RecoveryService.class),
            new IdempotencyKeyValidator(),
            new LocalActorIdentityResolver("local-operator"),
            () -> CORRELATION_ID,
            () -> "idem-generated-should-not-be-used",
            cliService);
    operatorCommands.reconcileLineage(
        ARTIFACT_ID, ACTION, PARENT, REASON, IDEMPOTENCY_KEY, null, CORRELATION_ID, "text", false);

    ArgumentCaptor<ReconcileLineageCommand> cliCommand =
        ArgumentCaptor.forClass(ReconcileLineageCommand.class);
    verify(cliService).reconcileLineage(cliCommand.capture());

    ReconcileLineageCommand rest = restCommand.getValue();
    ReconcileLineageCommand cli = cliCommand.getValue();
    assertThat(cli.targetArtifactId()).isEqualTo(rest.targetArtifactId()).isEqualTo(ARTIFACT_ID);
    assertThat(cli.lineageAction()).isEqualTo(rest.lineageAction()).isEqualTo(ACTION);
    assertThat(cli.chosenParentArtifactId())
        .isEqualTo(rest.chosenParentArtifactId())
        .isEqualTo(PARENT);
    assertThat(cli.reasonText()).isEqualTo(rest.reasonText()).isEqualTo(REASON);
    assertThat(cli.idempotencyKey()).isEqualTo(rest.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(cli.actor()).isEqualTo(rest.actor());
    assertThat(rest.actor())
        .isEqualTo(new ActorContext("local-operator", ActorType.HUMAN, CORRELATION_ID));
  }
}
