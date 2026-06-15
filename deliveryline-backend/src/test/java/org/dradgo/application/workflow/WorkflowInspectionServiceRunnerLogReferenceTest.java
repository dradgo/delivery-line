package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerLogReferenceResult;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;

/** Story 3.6 AC7 / AC11(d) — {@link WorkflowInspectionService#getRunnerLogReference}. */
class WorkflowInspectionServiceRunnerLogReferenceTest {

  private static final String REX = "rex_inspectlog0001";

  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          mock(WorkflowRunReadPort.class),
          mock(WorkflowEventReadPort.class),
          mock(ArtifactRecordPort.class),
          mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class),
          mock(ApprovalReadPort.class),
          mock(IntegrationLinkService.class),
          mock(RedactionPolicyService.class),
          mock(RecoveryService.class),
          runnerExecutions,
          mock(RunnerScratchStore.class),
          mock(ClarificationReadPort.class),
          mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults());

  private static RunnerExecutionSnapshot snapshot(
      String reference, DataClassification classification, Long byteSize, Integer redactionCount) {
    return new RunnerExecutionSnapshot(
        REX,
        "run_inspectlog0001",
        RunnerStage.EXECUTION,
        RunnerExecutionStatus.COMPLETED,
        1,
        OffsetDateTime.parse("2026-06-01T10:00:00Z"),
        OffsetDateTime.parse("2026-06-01T10:10:00Z"),
        null,
        OffsetDateTime.parse("2026-06-01T10:05:00Z"),
        OffsetDateTime.parse("2026-06-01T09:55:00Z"),
        null,
        null,
        reference,
        classification,
        byteSize,
        redactionCount);
  }

  @Test
  void returnsTypedAvailableReferenceWhenColumnsArePopulated() {
    when(runnerExecutions.findByPublicId(REX))
        .thenReturn(
            Optional.of(snapshot("/runner-logs/" + REX, DataClassification.LOCAL_ONLY, 256L, 4)));

    RunnerLogReferenceResult result = service.getRunnerLogReference(REX);

    assertThat(result.available()).isTrue();
    assertThat(result.reference().referencePath()).isEqualTo("/runner-logs/" + REX);
    assertThat(result.reference().byteSize()).isEqualTo(256L);
    assertThat(result.reference().classification()).isEqualTo(DataClassification.LOCAL_ONLY);
    assertThat(result.reference().redactionCount()).isEqualTo(4);
  }

  @Test
  void returnsUnavailableLogsNotCapturedWhenColumnsAreNull() {
    when(runnerExecutions.findByPublicId(REX))
        .thenReturn(Optional.of(snapshot(null, null, null, null)));

    RunnerLogReferenceResult result = service.getRunnerLogReference(REX);

    assertThat(result.available()).isFalse();
    assertThat(result.reason()).isEqualTo("logsNotCaptured");
  }

  @Test
  void returnsUnavailableIncompleteMetadataWhenOnlySomeColumnsArePopulated() {
    when(runnerExecutions.findByPublicId(REX))
        .thenReturn(
            Optional.of(snapshot("/runner-logs/" + REX, DataClassification.LOCAL_ONLY, null, 2)));

    RunnerLogReferenceResult result = service.getRunnerLogReference(REX);

    assertThat(result.available()).isFalse();
    assertThat(result.reason()).isEqualTo("incompleteLogMetadata");
  }

  @Test
  void returnsUnavailableRunnerExecutionNotFoundWhenRowMissing() {
    when(runnerExecutions.findByPublicId(REX)).thenReturn(Optional.empty());

    RunnerLogReferenceResult result = service.getRunnerLogReference(REX);

    assertThat(result.available()).isFalse();
    assertThat(result.reason()).isEqualTo("runnerExecutionNotFound");
  }
}
