package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.BlockedDependencyView;
import org.dradgo.application.workflow.DeclareRunDependenciesCommand;
import org.dradgo.application.workflow.RunDependencyGraphView;
import org.dradgo.application.workflow.RunDependencyService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Story 3f-3 (AC9/AC10) — CLI parity for the run-dependency declare/show subcommands. */
class WorkflowDependencyCommandsTest {

  private static final String DEPENDENT = "run_cli_dependent1";
  private static final String PREREQ_A = "run_cli_prereq_aa";
  private static final String PREREQ_B = "run_cli_prereq_bb";

  private final RunDependencyService runDependencyService = mock(RunDependencyService.class);
  private final WorkflowCommands commands = newCommands();

  private WorkflowCommands newCommands() {
    WorkflowCommands c =
        new WorkflowCommands(
            mock(WorkflowCommandService.class),
            mock(WorkflowInspectionService.class),
            new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules()),
            () -> false,
            () -> "01964c38-1c45-7000-8000-000000000000",
            () -> "01964c38-1c45-7000-8000-000000000001",
            new IdempotencyKeyValidator(),
            mock(RecoveryService.class),
            null,
            new LocalActorIdentityResolver("local-operator"),
            null,
            null,
            null);
    c.setRunDependencyService(runDependencyService);
    return c;
  }

  private static RunDependencyGraphView blockedGraph() {
    return new RunDependencyGraphView(
        List.of(new BlockedDependencyView(PREREQ_A, WorkflowState.INVESTIGATING)),
        List.of(),
        List.of(new BlockedDependencyView(PREREQ_A, WorkflowState.INVESTIGATING)),
        true);
  }

  @Test
  void dependenciesShowRendersGraphAsText() {
    when(runDependencyService.graphView(DEPENDENT)).thenReturn(blockedGraph());

    String out = commands.dependenciesShow(DEPENDENT, "text", "corr-show");

    assertThat(out).contains("blocked=true");
    assertThat(out).contains(PREREQ_A + "[Investigating]");
  }

  @Test
  void dependenciesShowRendersGraphAsJson() {
    when(runDependencyService.graphView(DEPENDENT)).thenReturn(blockedGraph());

    String out = commands.dependenciesShow(DEPENDENT, "json", "corr-show-json");

    assertThat(out).contains("\"blockedByDependencies\":true");
    assertThat(out).contains("\"runId\":\"" + PREREQ_A + "\"");
  }

  @Test
  void dependenciesAddParsesCommaListAndDeclares() {
    when(runDependencyService.declareDependencies(any())).thenReturn(blockedGraph());

    String out =
        commands.dependenciesAdd(
            DEPENDENT, PREREQ_A + "," + PREREQ_B, "text", "alex", "idem-cli-key-aaaa", "corr-add");

    assertThat(out).contains("blocked=true");
    ArgumentCaptor<DeclareRunDependenciesCommand> captor =
        ArgumentCaptor.forClass(DeclareRunDependenciesCommand.class);
    verify(runDependencyService).declareDependencies(captor.capture());
    DeclareRunDependenciesCommand captured = captor.getValue();
    assertThat(captured.runId()).isEqualTo(DEPENDENT);
    assertThat(captured.dependsOnRunIds()).containsExactly(PREREQ_A, PREREQ_B);
    assertThat(captured.actorIdentity()).isEqualTo("alex");
  }
}
