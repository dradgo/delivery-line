package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerDispatchAck;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3c-7 (AC7) — end-to-end proof, over real-Postgres wiring, that a run scoped to a
 * <strong>non-{@code default}</strong> project dispatches with <em>that</em> project's repository
 * ref, OpenSpec mode, and connector selection, while a {@code default}-project run stays
 * byte-identical to pre-3c (parity).
 *
 * <p>Each test drives the <em>real</em> {@link RunnerBroker#dispatch} over the real persisted run +
 * the real {@link ProjectRuntimeConfigResolver} (reading {@code workflow_runs.project_id} from real
 * Postgres) and captures the composed {@link RunnerDispatchRequest}, asserting its {@code
 * repositoryRef()} and {@code openspecEnabled()} — the exact inputs {@code DockerRunnerAdapter}
 * emits the clone mount and {@code DELIVERYLINE_RUNNER_OPENSPEC} env from (the adapter-side env
 * emission is pinned separately in {@code DockerRunnerAdapterUnitTest}). Only the two leaf adapters
 * that cannot run in-process are mocked: the {@link RunnerAdapter} (to capture the request, no
 * container) and {@link RepositoryWorkspaceService} (to stub the git clone); everything between —
 * broker, resolver, context-bundle composition, idempotency, persistence — is real. Named {@code
 * *IT} so Failsafe runs it ([[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class RunProjectAssociationIT {

  private static final ActorContext ACTOR = new ActorContext("alex", ActorType.HUMAN, "corr-3c7");

  @Autowired private WorkflowCommandService commandService;
  @Autowired private ProjectStore projectStore;
  @Autowired private ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  @Autowired private ProjectConnectorResolver projectConnectorResolver;
  @Autowired private WorkflowProperties workflowProperties;
  @Autowired private RunnerProperties runnerProperties;
  @Autowired private RunnerBroker runnerBroker;

  // The two leaf adapters that cannot execute in-process: the runner container and the git clone.
  // Mocking ONLY these keeps the broker -> resolver -> bundle-compose -> request chain entirely
  // real.
  @MockitoBean private RunnerAdapter runnerAdapter;
  @MockitoBean private RepositoryWorkspaceService repositoryWorkspaceService;

  @Test
  void nonDefaultProjectRunDispatchesWithThatProjectsRepoOpenSpecAndConnectorSelection() {
    // Insert a non-default project directly (no CRUD/UI yet — 3c-8/3c-9) with a repo binding,
    // connector kinds, and an OpenSpec flag all DIFFERING from the seeded default.
    String publicId = PublicIdPrefixes.PROJECT.next();
    Project acme =
        projectStore.insert(
            new Project(
                publicId,
                "Acme",
                "acme-3c7",
                ProjectStatus.ACTIVE,
                "acme/widgets",
                ConnectorKind.LINEAR,
                ConnectorKind.GITLAB,
                true,
                null,
                false,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                null));

    // Submit a run scoped to it via an explicit project reference (slug).
    SubmitWorkflowResult submit =
        commandService.submit(
            new SubmitWorkflowCommand(
                "alex",
                ActorType.HUMAN,
                "idem-3c7-nondefault-001",
                "corr-3c7-nondefault",
                "LIN-102",
                "acme-3c7"));
    String runId = submit.workflowRunId();

    // AC1/AC6 — the run is durably bound to the non-default project at create.
    assertThat(projectStore.findProjectIdForRun(runId)).contains(acme.publicId());

    // AC2 — the resolver seams the broker consumes derive from THIS project.
    assertThat(projectRuntimeConfigResolver.resolveRepositoryRef(runId)).contains("acme/widgets");
    assertThat(projectRuntimeConfigResolver.resolveOpenSpecEnabled(runId)).isTrue();

    // AC3/AC4 — connector selection follows the project's kinds.
    Project resolved = projectRuntimeConfigResolver.resolveForRun(runId);
    assertThat(projectConnectorResolver.resolveRepositoryHost(resolved).connectorKind())
        .isEqualTo(ConnectorKind.GITLAB);
    assertThat(projectConnectorResolver.findTicketSource(resolved)).isPresent();

    // AC7 — DRIVE A REAL DISPATCH and assert the composed RunnerDispatchRequest carries the
    // non-default project's repo ref + OpenSpec flag (resolved by the real resolver over real
    // Postgres, not a mocked resolver as in RunnerBrokerUnitTest).
    RunnerDispatchRequest request = driveDispatchAndCapture(runId, "idem-3c7-nondefault-dispatch");
    assertThat(request.repositoryRef()).isEqualTo("acme/widgets");
    assertThat(request.openspecEnabled())
        .as("non-default project openspecEnabled=true => DockerRunnerAdapter emits the env")
        .isTrue();
  }

  @Test
  void defaultProjectRunStaysByteIdenticalToPre3c() {
    // Parity (R7) — no explicit reference => the seeded `default` project; its config mirrors the
    // global, so the dispatched request carries the global single-repo ref and OpenSpec is OFF (no
    // DELIVERYLINE_RUNNER_OPENSPEC env would be emitted by the adapter).
    SubmitWorkflowResult submit =
        commandService.submit(
            new SubmitWorkflowCommand(
                "alex", ActorType.HUMAN, "idem-3c7-default-001", "corr-3c7-default", "LIN-103"));
    String runId = submit.workflowRunId();

    String expectedGlobalRef =
        RepositoryRef.normalizeRepositoryUrl(workflowProperties.repos().url());
    assertThat(projectRuntimeConfigResolver.resolveRepositoryRef(runId).orElse(null))
        .isEqualTo(expectedGlobalRef);
    assertThat(projectRuntimeConfigResolver.resolveOpenSpecEnabled(runId))
        .isEqualTo(runnerProperties.openSpecEnabled());

    // AC7 parity — the driven dispatch carries the global ref and (flag off) openspecEnabled=false,
    // so the adapter emits NO DELIVERYLINE_RUNNER_OPENSPEC env (DockerRunnerAdapterUnitTest pins
    // the
    // env-omission half).
    RunnerDispatchRequest request = driveDispatchAndCapture(runId, "idem-3c7-default-dispatch");
    assertThat(request.repositoryRef()).isEqualTo(expectedGlobalRef);
    assertThat(request.openspecEnabled())
        .as("default project openspec off => no env var emitted")
        .isEqualTo(runnerProperties.openSpecEnabled());
  }

  /**
   * Drives the real {@link RunnerBroker#dispatch} for the INVESTIGATION (spec) stage — the natural
   * first dispatch of a freshly-submitted run — with the git clone stubbed, and returns the {@link
   * RunnerDispatchRequest} the broker hands to the runner adapter.
   */
  private RunnerDispatchRequest driveDispatchAndCapture(String runId, String idempotencyKey) {
    RepositoryWorkspaceService.RepositoryMount mount =
        new RepositoryWorkspaceService.RepositoryMount(
            Paths.get("/tmp/repo"), "/workspace/repo", "main", "deliveryline/spec/stage-1");
    RepositoryContextSummary summary =
        new RepositoryContextSummary(
            "/workspace/repo", java.util.List.of(), "README.md", java.util.List.of(), "config:v1");
    when(repositoryWorkspaceService.prepareWorkspace(any(), any(), any(), any(), any(), any()))
        .thenReturn(mount);
    when(repositoryWorkspaceService.summarize(any(), any())).thenReturn(summary);
    when(runnerAdapter.dispatch(any())).thenReturn(new RunnerDispatchAck("mock:exec"));

    runnerBroker.dispatch(runId, RunnerStage.INVESTIGATION, idempotencyKey, ACTOR);

    ArgumentCaptor<RunnerDispatchRequest> captor =
        ArgumentCaptor.forClass(RunnerDispatchRequest.class);
    org.mockito.Mockito.verify(runnerAdapter).dispatch(captor.capture());
    return captor.getValue();
  }
}
