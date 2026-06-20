package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.adapters.files.LocalRunnerScratchStore;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.adapters.runner.DockerRunnerAdapter;
import org.dradgo.adapters.runner.docker.CreateContainerSpec;
import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.RecordArtifactOperationCommand;
import org.dradgo.application.artifact.RecordArtifactOperationResult;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.1 review follow-up (open finding "Docker lifecycle acceptance coverage is incomplete" →
 * the named {@code RunnerBrokerDockerHandoffContractTest}) — pins the broker → {@link
 * DockerRunnerAdapter} handoff end-to-end with the Docker engine mocked, so it runs in the fast
 * tier with no real daemon. Drives {@link RunnerBroker#dispatch} with a real {@link
 * LocalRunnerScratchStore} + {@link LocalRunnerWorkspaceStore} (rooted at a {@link TempDir}) and a
 * real {@code DockerRunnerAdapter}, and asserts the contract the two pieces share:
 *
 * <ol>
 *   <li>the broker reserves a {@code rex_} id and writes the redacted context bundle to scratch;
 *   <li>the adapter reads those exact scratch bytes and copies them byte-identically into the
 *       workspace {@code input/context-bundle.v1.json} mount;
 *   <li>the adapter launches the container ({@code --network=none}, read-only input mount) and
 *       returns a {@code docker:{containerId}} ack the broker surfaces.
 * </ol>
 *
 * <p>The real-runner contract (broker → adapter → a real Codex/Claude container that classifies an
 * invalid / schema-mismatched result) stays deferred to story 3-8 per the AC10 caveat + the
 * 2026-05-26 sprint-change-proposal §3.7.
 */
class RunnerBrokerDockerHandoffContractTest {

  private static final String RUN_ID = "run_handoff012345";
  private static final String CONTAINER_ID = "container_handoff01";
  private static final byte[] BUNDLE_BYTES =
      "{\"schemaVersion\":1,\"handoff\":\"marker\"}".getBytes(StandardCharsets.UTF_8);
  private static final ActorContext ACTOR =
      new ActorContext("human-pm", ActorType.HUMAN, "corr-handoff");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-28T12:00:00Z"), ZoneOffset.UTC);

  @TempDir Path tempHome;

  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private ContextBundleService contextBundleService;
  private IdempotencyService idempotencyService;
  private ArtifactOperationService artifactOperationService;
  private DockerEngineGateway gateway;
  private LocalRunnerScratchStore scratchStore;
  private LocalRunnerWorkspaceStore workspaceStore;
  private DockerRunnerAdapter adapter;
  private RunnerBroker broker;

  @BeforeEach
  void setUp() {
    recordPort = mock(RunnerExecutionRecordPort.class);
    eventPort = mock(RunnerExecutionEventPort.class);
    contextBundleService = mock(ContextBundleService.class);
    idempotencyService = mock(IdempotencyService.class);
    artifactOperationService = mock(ArtifactOperationService.class);
    gateway = mock(DockerEngineGateway.class);

    RunnerProperties properties = RunnerProperties.defaults();
    scratchStore = new LocalRunnerScratchStore(tempHome.toAbsolutePath().toString());
    workspaceStore = new LocalRunnerWorkspaceStore(tempHome.toAbsolutePath().toString());
    RunnerSecretsService secretsService =
        new RunnerSecretsService(
            new MockEnvironment()
                .withProperty("CODEX_API_KEY", "sk-codex-handoff")
                .withProperty("ANTHROPIC_API_KEY", "sk-ant-handoff"),
            properties);
    adapter =
        new DockerRunnerAdapter(
            scratchStore,
            workspaceStore,
            gateway,
            properties,
            secretsService,
            mock(RunnerLogCaptureService.class),
            mock(RunnerExecutionService.class));

    broker =
        new RunnerBroker(
            recordPort,
            eventPort,
            mock(RunnerExecutionService.class),
            contextBundleService,
            idempotencyService,
            mock(WorkflowTransitionService.class),
            artifactOperationService,
            adapter,
            scratchStore,
            new RunnerContractValidator(),
            properties,
            cleanScanService(),
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK);
  }

  @Test
  void brokerDispatchWritesBundleToScratchAndAdapterCopiesItIntoWorkspaceThenReturnsDockerAck()
      throws Exception {
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(
            eq("idem-handoff"), eq("RunnerBroker.dispatch"), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    ContextBundle bundle =
        new ContextBundle(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            "rex_placeholder1234",
            1,
            DataClassification.SHAREABLE_REDACTED,
            BUNDLE_BYTES);
    when(contextBundleService.createForSpecInvestigation(
            eq(RUN_ID), any(), eq(1), any(), any(), eq(ACTOR), any()))
        .thenReturn(bundle);
    when(recordPort.insertPending(any(), eq(RUN_ID), eq(RunnerStage.INVESTIGATION), eq(1), any()))
        .thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);

    RunnerDispatchResult result =
        broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-handoff", ACTOR);

    RunnerDispatchResult.Dispatched dispatched =
        assertInstanceOf(RunnerDispatchResult.Dispatched.class, result);
    assertThat(dispatched.ack().adapterRef()).isEqualTo("docker:" + CONTAINER_ID);

    ArgumentCaptor<CreateContainerSpec> specCaptor =
        ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(specCaptor.capture());
    verify(gateway).startContainer(CONTAINER_ID);
    CreateContainerSpec spec = specCaptor.getValue();

    String rexId = spec.labels().get("deliveryline.runnerExecutionId");
    assertThat(rexId)
        .as("dispatch reserves a rex_ id and labels the container with it")
        .isNotNull();

    // (1) the broker wrote the redacted bundle to scratch (the adapter's source of truth).
    Optional<byte[]> scratchBundle = scratchStore.tryReadContextBundle(rexId);
    assertThat(scratchBundle).isPresent();
    assertThat(scratchBundle.get()).isEqualTo(BUNDLE_BYTES);

    // (2) the adapter copied the scratch bytes byte-identically into the workspace input mount.
    Path workspaceInput =
        workspaceStore
            .workspaceRoot()
            .resolve(rexId)
            .resolve("input")
            .resolve("context-bundle.v1.json");
    assertThat(Files.exists(workspaceInput)).isTrue();
    assertThat(Files.readAllBytes(workspaceInput)).isEqualTo(BUNDLE_BYTES);

    // (3) the launch posture: --network=none + read-only input mount.
    // Look the input bind up by container path rather than asserting it is first by list position
    // (the bind List carries no documented ordering contract) — mirrors the sibling
    // DockerRunnerAdapterContainerLifecycleIT mount-mode assertion.
    assertThat(spec.networkMode()).isEqualTo("none");
    var inputBind =
        spec.binds().stream()
            .filter(bind -> "/workspace/input".equals(bind.containerPath()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no /workspace/input bind present"));
    assertThat(inputBind.readOnly()).as("input mount is read-only").isTrue();
  }

  @Test
  void brokerValidatesAndIngestsResultHarvestedByTheRealAdapter() throws Exception {
    // AC10(a) — the "broker validates and ingests" half: the REAL DockerRunnerAdapter harvests a
    // valid result envelope from the workspace output mount, and the broker then validates it via
    // RunnerContractValidator and ingests its artifact via ArtifactOperationService. Docker-free
    // (mocked gateway), so it runs in the fast tier. The dispatch-only sibling test above pins the
    // bundle→workspace handoff; this one closes the validate+ingest gap that left AC10(a) half-
    // covered. Real-runner content classification (b/e/f) stays deferred to story 3-8.
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(
            eq("idem-ingest"), eq("RunnerBroker.dispatch"), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    ContextBundle bundle =
        new ContextBundle(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            "rex_placeholder1234",
            1,
            DataClassification.SHAREABLE_REDACTED,
            BUNDLE_BYTES);
    when(contextBundleService.createForSpecInvestigation(
            eq(RUN_ID), any(), eq(1), any(), any(), eq(ACTOR), any()))
        .thenReturn(bundle);
    when(recordPort.insertPending(any(), eq(RUN_ID), eq(RunnerStage.INVESTIGATION), eq(1), any()))
        .thenAnswer(invocation -> snapshot(invocation.getArgument(0)));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);

    RunnerDispatchResult result =
        broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-ingest", ACTOR);
    assertInstanceOf(RunnerDispatchResult.Dispatched.class, result);
    ArgumentCaptor<CreateContainerSpec> specCaptor =
        ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(specCaptor.capture());
    String rexId = specCaptor.getValue().labels().get("deliveryline.runnerExecutionId");
    assertThat(rexId).isNotNull();

    // The runner's artifact content lands in scratch; stage it so the broker's happy-path artifact
    // read resolves to the real bytes (not the envelope JSON).
    byte[] artifactBytes = "spec-payload-bytes".getBytes(StandardCharsets.UTF_8);
    scratchStore.writeArtifactContent(rexId, "spec/v1.json", artifactBytes);

    // The runner image wrote a valid result envelope into the read-write output mount; harvest it
    // through the REAL adapter (tryReadResult reads the output file directly).
    byte[] resultBytes =
        (
"""
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {"artifactId": "art_test01234567", "artifactType": "spec", \
"contentReference": "spec/v1.json"}
          ],
          "normalizedOutput": {"summary": "ok", "outcome": "success"},
          "checksum": {"algorithm": "SHA-256", \
"hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """)
            .formatted(RUN_ID, rexId)
            .getBytes(StandardCharsets.UTF_8);
    Path workspaceOutput =
        workspaceStore
            .workspaceRoot()
            .resolve(rexId)
            .resolve("output")
            .resolve("runner-result.v1.json");
    Files.write(workspaceOutput, resultBytes);

    Optional<byte[]> harvested = adapter.tryReadResult(rexId);
    assertThat(harvested)
        .as("the real adapter harvests the result from the output mount")
        .isPresent();
    assertThat(harvested.get()).isEqualTo(resultBytes);

    // The broker validates (RunnerContractValidator) and ingests (ArtifactOperationService) the
    // harvested bytes — the AC10(a) half the dispatch-only handoff test does not reach.
    when(recordPort.findByPublicId(rexId)).thenReturn(Optional.of(runningSnapshot(rexId)));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_test01234567",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_test01234567",
                      command.workflowRunId(),
                      "art_test01234567",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });

    broker.onResult(rexId, harvested.get());

    ArgumentCaptor<RecordArtifactOperationCommand> commandCaptor =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService, times(1)).recordOperation(commandCaptor.capture());
    // The broker ingested the REAL artifact-content bytes (read from scratch), proving it both
    // validated the envelope and resolved + forwarded the referenced artifact content.
    assertThat(new String(commandCaptor.getValue().payloadContent(), StandardCharsets.UTF_8))
        .isEqualTo("spec-payload-bytes");
  }

  private static RunnerExecutionSnapshot runningSnapshot(String rexId) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        rexId,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.RUNNING,
        1,
        now,
        now.plusSeconds(600),
        null,
        null,
        now,
        null);
  }

  private static RunnerExecutionSnapshot snapshot(String rexId) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        rexId,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.PENDING,
        1,
        now,
        now.plusSeconds(600),
        null,
        null,
        now,
        null);
  }

  private static RunnerSecretScanService cleanScanService() {
    RunnerSecretScanService scanService = mock(RunnerSecretScanService.class);
    when(scanService.scanWorkspace(any(), any(), any(), any()))
        .thenReturn(RunnerSecretScanService.ScanOutcome.clean());
    return scanService;
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    return template;
  }
}
