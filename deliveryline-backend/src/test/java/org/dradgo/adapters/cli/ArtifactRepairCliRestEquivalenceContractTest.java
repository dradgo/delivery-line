package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dradgo.adapters.rest.ArtifactDriftController;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.ArtifactRepairResult;
import org.dradgo.application.artifact.RepairArtifactDriftCommand;
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
 * Story 4.16 (AC8) — CLI/REST equivalence for artifact-repair. Both surfaces call {@code
 * ArtifactReconciliationService.repairArtifactDrift(command)} directly; this captures the command
 * from each and asserts identical arguments (ActorContext by record equality, the omitted actor
 * identity resolving to the shared local-operator fallback on both sides).
 */
@WebMvcTest(controllers = ArtifactDriftController.class)
class ArtifactRepairCliRestEquivalenceContractTest {

  private static final String DRIFT_ID = "adr_repair0001";
  private static final String ACTION = "mark_corrupted";
  private static final String IDEMPOTENCY_KEY = "idem-repair-equiv-aaaa";
  private static final String CORRELATION_ID = "corr-repair-equiv-a";
  private static final String REASON = "Confirmed corrupt payload on disk.";

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

  private static ArtifactRepairResult result() {
    return new ArtifactRepairResult(
        DRIFT_ID, ACTION, "rcv_repair0001", "evt_repair0001", true, CORRELATION_ID, false);
  }

  @Test
  void restAndCliProduceIdenticalRepairCommands() throws Exception {
    when(artifactReconciliationService.repairArtifactDrift(any())).thenReturn(result());

    // REST surface: omit X-Actor-Identity so it falls back to local-operator; correlation from MDC.
    mockMvc
        .perform(
            post("/api/v1/artifact-drift/{driftId}/repair", DRIFT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .content(
                    """
                    {
                      "role": "workflow_owner",
                      "repairAction": "mark_corrupted",
                      "reasonText": "Confirmed corrupt payload on disk."
                    }
                    """))
        .andExpect(status().isOk());

    ArgumentCaptor<RepairArtifactDriftCommand> restCommand =
        ArgumentCaptor.forClass(RepairArtifactDriftCommand.class);
    verify(artifactReconciliationService).repairArtifactDrift(restCommand.capture());

    // CLI surface: a separately-mocked service, same logical inputs; --actor-identity omitted
    // (-> local-operator), --correlation-id supplied to match REST's MDC.
    ArtifactReconciliationService cliService = mock(ArtifactReconciliationService.class);
    when(cliService.repairArtifactDrift(any())).thenReturn(result());
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
    operatorCommands.artifactRepair(
        DRIFT_ID, ACTION, REASON, null, null, IDEMPOTENCY_KEY, null, CORRELATION_ID, "text", false);

    ArgumentCaptor<RepairArtifactDriftCommand> cliCommand =
        ArgumentCaptor.forClass(RepairArtifactDriftCommand.class);
    verify(cliService).repairArtifactDrift(cliCommand.capture());

    RepairArtifactDriftCommand rest = restCommand.getValue();
    RepairArtifactDriftCommand cli = cliCommand.getValue();
    assertThat(cli.driftId()).isEqualTo(rest.driftId()).isEqualTo(DRIFT_ID);
    assertThat(cli.repairAction()).isEqualTo(rest.repairAction()).isEqualTo(ACTION);
    assertThat(cli.reasonText()).isEqualTo(rest.reasonText()).isEqualTo(REASON);
    assertThat(cli.completionEvidence()).isEqualTo(rest.completionEvidence()).isNull();
    assertThat(cli.backupSource()).isEqualTo(rest.backupSource()).isNull();
    assertThat(cli.idempotencyKey()).isEqualTo(rest.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    assertThat(cli.actor()).isEqualTo(rest.actor());
    assertThat(rest.actor())
        .isEqualTo(new ActorContext("local-operator", ActorType.HUMAN, CORRELATION_ID));
  }
}
