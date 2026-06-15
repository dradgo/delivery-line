package org.dradgo.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerQueueCounts;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerQueueStatus;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkerStatus;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;

/**
 * Story 3.19 (AC11a) — foundation contract: {@link
 * WorkflowInspectionService#getRunnerQueueStatus(String)} populates EVERY {@link RunnerQueueStatus}
 * and {@link WorkerStatus} field from the read-port aggregate + leased rows. Named {@code
 * *FoundationContract} so Maven discovery skips it; reached only via {@code
 * FoundationGateVerificationTest} (Contract #13). The scrapeable-metric half of AC11 is {@code
 * RunnerQueuePrometheusScrapeIT}.
 */
class RunnerQueueInspectionFoundationContract {

  private static final OffsetDateTime TS =
      OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void everyRunnerQueueStatusAndWorkerStatusFieldIsPopulated() {
    RunnerExecutionRecordPort recordPort = mock(RunnerExecutionRecordPort.class);
    when(recordPort.loadQueueCounts(any(Duration.class), any(Duration.class), isNull()))
        .thenReturn(new RunnerQueueCounts(5L, 1L, 2L, 1L, 3L, TS, 99L));
    when(recordPort.findLeasedRunning(isNull(), anyInt()))
        .thenReturn(
            List.of(
                new RunnerExecutionSnapshot(
                    "rex_fc000001",
                    "run_fc000001",
                    RunnerStage.INVESTIGATION,
                    RunnerExecutionStatus.RUNNING,
                    1,
                    TS,
                    TS.plusMinutes(10),
                    null,
                    null,
                    TS.minusMinutes(1),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    TS,
                    "worker-fc",
                    100,
                    1,
                    "corr-fc",
                    null,
                    null,
                    null)));

    WorkflowInspectionService service =
        new WorkflowInspectionService(
            mock(WorkflowRunReadPort.class),
            mock(WorkflowEventReadPort.class),
            mock(ArtifactRecordPort.class),
            mock(ArtifactPayloadStore.class),
            mock(ApprovalReadPort.class),
            mock(IntegrationLinkService.class),
            mock(RedactionPolicyService.class),
            mock(RecoveryService.class),
            recordPort,
            mock(RunnerScratchStore.class),
            mock(ClarificationReadPort.class),
            mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
            RunnerProperties.defaults(),
            RunnerWorkerPoolProperties.defaults());

    RunnerQueueStatus view = service.getRunnerQueueStatus(null);

    // Every scalar field carries a real value.
    assertThat(view.poolSize()).as("poolSize").isGreaterThan(0);
    assertThat(view.activeWorkers()).as("activeWorkers").isEqualTo(1L);
    assertThat(view.idleWorkers()).as("idleWorkers").isEqualTo(1L);
    assertThat(view.queueDepth()).as("queueDepth").isEqualTo(5L);
    assertThat(view.oldestQueuedAt()).as("oldestQueuedAt").isEqualTo(TS);
    assertThat(view.oldestQueuedAgeSeconds()).as("oldestQueuedAgeSeconds").isEqualTo(99L);
    assertThat(view.inFlightExecutions()).as("inFlightExecutions").isEqualTo(1L);
    assertThat(view.recentThroughputPerMinute()).as("recentThroughputPerMinute").isEqualTo(3L);
    assertThat(view.staleQueuedCount()).as("staleQueuedCount").isEqualTo(2L);
    assertThat(view.staleDispatchedCount()).as("staleDispatchedCount").isEqualTo(1L);

    // The per-worker view is fully populated.
    assertThat(view.workers()).hasSize(1);
    WorkerStatus worker = view.workers().get(0);
    assertThat(worker.workerId()).as("workerId").isEqualTo("worker-fc");
    assertThat(worker.state()).as("state").isEqualTo("busy");
    assertThat(worker.currentRunnerExecutionId())
        .as("currentRunnerExecutionId")
        .isEqualTo("rex_fc000001");
    assertThat(worker.currentWorkflowRunId()).as("currentWorkflowRunId").isEqualTo("run_fc000001");
    assertThat(worker.dispatchedAt()).as("dispatchedAt").isEqualTo(TS);
    assertThat(worker.currentStage()).as("currentStage").isEqualTo("investigation");
  }
}
