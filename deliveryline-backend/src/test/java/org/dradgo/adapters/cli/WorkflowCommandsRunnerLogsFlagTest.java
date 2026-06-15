package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.RunnerLogReference;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerLogReferenceResult;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 3.6 AC7 / AC11(f) — CLI {@code workflow status --include-runner-logs} rendering. Asserts
 * the typed reference + metrics are surfaced (NEVER content) in both text and JSON modes, and the
 * unavailable path renders an honest sentinel.
 */
class WorkflowCommandsRunnerLogsFlagTest {

  private static final String RUN_ID = "run_status12345";
  private static final String REX_ID = "rex_runnerlogs0001";

  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final WorkflowCommands commands =
      new WorkflowCommands(
          mock(WorkflowCommandService.class),
          inspection,
          new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules()),
          () -> false,
          () -> "01964c38-1c45-7000-8000-000000000000",
          () -> "01964c38-1c45-7000-8000-000000000001",
          new IdempotencyKeyValidator(),
          mock(RecoveryService.class),
          null,
          new LocalActorIdentityResolver("local-operator"),
          null,
          null);

  private void stubStatus() {
    WorkflowStatusView view =
        new WorkflowStatusView(
            RUN_ID,
            WorkflowState.EXECUTING,
            "alex",
            "human",
            "workflow.stateChanged",
            java.time.OffsetDateTime.parse("2026-06-01T10:00:00Z"),
            List.of(new LatestArtifactView("spec", 1, "available")),
            null,
            null,
            null,
            null,
            null,
            null,
            "await_outcome",
            0,
            false);
    when(inspection.getStatus(RUN_ID)).thenReturn(view);
  }

  @Test
  void textModeRendersTypedReferenceMetricsNotContent() {
    stubStatus();
    when(inspection.findLatestRunnerExecutionId(RUN_ID)).thenReturn(Optional.of(REX_ID));
    when(inspection.getRunnerLogReference(REX_ID))
        .thenReturn(
            RunnerLogReferenceResult.available(
                REX_ID,
                new RunnerLogReference(
                    "/home/runner-logs/" + REX_ID, 1024L, DataClassification.LOCAL_ONLY, 3)));

    String rendered = commands.status(RUN_ID, "text", "corr-1", false, false, true);

    assertTrue(rendered.contains("# runner-logs (" + REX_ID + "):"));
    assertTrue(rendered.contains("reference: /home/runner-logs/" + REX_ID));
    assertTrue(rendered.contains("classification: local-only"));
    assertTrue(rendered.contains("byteSize: 1024"));
    assertTrue(rendered.contains("redactionCount: 3"));
  }

  @Test
  void jsonModeAttachesStructuredRunnerLogsObject() throws Exception {
    stubStatus();
    when(inspection.findLatestRunnerExecutionId(RUN_ID)).thenReturn(Optional.of(REX_ID));
    when(inspection.getRunnerLogReference(REX_ID))
        .thenReturn(
            RunnerLogReferenceResult.available(
                REX_ID,
                new RunnerLogReference(
                    "/home/runner-logs/" + REX_ID, 1024L, DataClassification.LOCAL_ONLY, 3)));

    String json = commands.status(RUN_ID, "json", "corr-1", false, false, true);
    JsonNode root = new ObjectMapper().readTree(json);

    assertEquals(2, root.get("schemaVersion").asInt());
    JsonNode runnerLogs = root.get("runnerLogs");
    assertEquals("available", runnerLogs.get("status").asText());
    assertEquals(REX_ID, runnerLogs.get("runnerExecutionId").asText());
    assertTrue(runnerLogs.get("reason").isNull());
    JsonNode logs = runnerLogs.get("logs");
    assertEquals("/home/runner-logs/" + REX_ID, logs.get("reference").asText());
    assertEquals(1024, logs.get("byteSize").asLong());
    assertEquals("local-only", logs.get("classification").asText());
    assertEquals(3, logs.get("redactionCount").asInt());
  }

  @Test
  void jsonModeUsesNullRunnerExecutionIdWhenNoRunnerExecutionExists() throws Exception {
    stubStatus();
    when(inspection.findLatestRunnerExecutionId(RUN_ID)).thenReturn(Optional.empty());

    String json = commands.status(RUN_ID, "json", "corr-1", false, false, true);
    JsonNode root = new ObjectMapper().readTree(json);

    JsonNode runnerLogs = root.get("runnerLogs");
    assertEquals("unavailable", runnerLogs.get("status").asText());
    assertTrue(runnerLogs.get("runnerExecutionId").isNull());
    assertEquals("noRunnerExecutionYet", runnerLogs.get("reason").asText());
  }

  @Test
  void textModeRendersUnavailableSentinelWhenNoRunnerExecution() {
    stubStatus();
    when(inspection.findLatestRunnerExecutionId(RUN_ID)).thenReturn(Optional.empty());

    String rendered = commands.status(RUN_ID, "text", "corr-1", false, false, true);

    assertTrue(rendered.contains("# runner-logs: none (noRunnerExecutionYet)"));
  }

  @Test
  void flagDisabledSkipsRunnerLogLookupEntirely() {
    stubStatus();

    String rendered = commands.status(RUN_ID, "text", "corr-1", false, false, false);

    assertFalse(rendered.contains("# runner-logs"));
  }
}
