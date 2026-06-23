package org.dradgo.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dradgo.adapters.cli.WorkflowCommands;
import org.dradgo.adapters.rest.WorkflowController;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.ManualArtifactSubmissionService.ManualArtifactSubmissionCommand;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ManualBundleLookupResult;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RejectionTaxonomy;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 2.13 AC7 — CLI ↔ REST equivalence pin for the three new spec-loop command surfaces
 * rebuilt/added in this story: {@code approve-spec}, {@code reject-spec}, and {@code
 * answer-clarification}.
 *
 * <p>For each surface this contract feeds the same logical payload to both the REST controller
 * (header-derived {@code X-Actor-Identity} + MDC correlation, wire DTO with renamed {@code
 * expectedArtifactVersion}/{@code expectedContextBundleVersion} fields) and the Spring Shell CLI
 * (flag-supplied {@code --actor-identity} + {@code --correlation-id}, native parameter names), then
 * asserts the resulting {@code WorkflowCommand} record captured by the mocked {@link
 * WorkflowCommandService} is structurally equal across the two surfaces. Locking record equality
 * pins the fingerprint surface that {@code IdempotencyService} hashes against — drift on either
 * side (a missing field, a swapped order, a typo in the actor fallback) breaks the gate
 * deterministically.
 *
 * <p>The MDC trick — explicit {@code MDC.put(CORRELATION_ID, ...)} before each {@code mockMvc} call
 * — substitutes for the real {@code CorrelationIdFilter} which is not registered in this
 * {@code @WebMvcTest} slice. Pairing it with {@code --correlation-id} on the CLI side keeps both
 * captures stamped with the same string, so the equality assertion exercises the actor + version +
 * idempotency-key + reviewer-role + correlation-id mapping symmetry, not just defaults.
 *
 * <p>Tagged {@code @Tag("foundation-gate")} and named {@code *ContractTest} so the dedicated
 * foundation-gate Maven profile (which clears the {@code foundation-gate} exclusion and adds a
 * {@code <groups>} filter) routes this contract through Failsafe alongside the rest of the Epic-1
 * contract suite. Outside the profile, the {@code foundation-gate} tag keeps this out of the
 * unit-test tier so day-to-day Surefire runs stay quick.
 */
@Tag("foundation-gate")
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class CliRestEquivalenceContractTest {

  private static final String RUN_ID = "run_cli_rest_equiv_a";
  private static final String ARTIFACT_ID = "art_spec_equiv_a";
  private static final String CORRELATION_ID = "corr-cli-rest-equiv-a";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private ManualArtifactSubmissionService manualArtifactSubmissionService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;
  @MockitoBean private DeveloperTakeoverService developerTakeoverService;
  @MockitoBean private WorkflowArchiveService workflowArchiveService;

  @TempDir private Path tempDir;

  @BeforeEach
  void stubActorResolverAndSeedMdc() {
    // Story 2.13 round-4 P-R4-12: delegate to a real LocalActorIdentityResolver so the
    // equivalence assertion exercises the production length/charset gates (requireSafe + resolve)
    // rather than a trim-only stub that would silently mask CLI/REST divergence on unsafe-input
    // rejection.
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

  @Test
  void approveSpecCommandRecordIsEqualAcrossRestAndCliForTheSamePayload() throws Exception {
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, CORRELATION_ID));

    // REST surface: header-derived actor identity, MDC-seeded correlation, renamed wire fields.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-approve-equiv-aaaaaa")
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1,
                      "reviewerRole": "product_reviewer"
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<ApproveSpecCommand> restCaptor =
        ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(restCaptor.capture());
    ApproveSpecCommand restCommand = restCaptor.getValue();

    clearInvocations(workflowCommandService);

    // CLI surface: flag-supplied actor identity + correlation id, same payload contents.
    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService,
            null,
            null,
            () -> false,
            () -> "generated-idempotency-key-unused",
            () -> "generated-correlation-id-unused",
            new IdempotencyKeyValidator(),
            null,
            new ApprovalReviewerRoleResolver("product_reviewer"),
            new LocalActorIdentityResolver("local-operator"),
            null,
            null,
            null);
    cli.approveSpec(
        RUN_ID,
        ARTIFACT_ID,
        1,
        1,
        "product_reviewer",
        null,
        "idem-approve-equiv-aaaaaa",
        "alex",
        CORRELATION_ID,
        false);

    ArgumentCaptor<ApproveSpecCommand> cliCaptor =
        ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(cliCaptor.capture());
    ApproveSpecCommand cliCommand = cliCaptor.getValue();

    assertThat(cliCommand)
        .as(
            "CLI and REST built different ApproveSpecCommand records for the same logical payload"
                + " — fingerprint symmetry broken")
        .isEqualTo(restCommand);
  }

  @Test
  void acceptImplementationCommandRecordIsEqualAcrossRestAndCliForTheSamePayload()
      throws Exception {
    // Story 3.23: accept-implementation requires reviewerRole=developer on BOTH surfaces (validated
    // at the controller boundary, not resolver-defaulted). Feeding the same developer payload to
    // REST and CLI must build structurally-equal AcceptImplementationCommand records.
    when(workflowCommandService.acceptImplementation(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, CORRELATION_ID));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/accept-implementation", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-accept-equiv-dddddd")
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 3,
                      "expectedContextBundleVersion": 2,
                      "reviewerRole": "developer",
                      "reason": "Implementation looks correct."
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<AcceptImplementationCommand> restCaptor =
        ArgumentCaptor.forClass(AcceptImplementationCommand.class);
    verify(workflowCommandService).acceptImplementation(restCaptor.capture());
    AcceptImplementationCommand restCommand = restCaptor.getValue();

    clearInvocations(workflowCommandService);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService,
            null,
            null,
            () -> false,
            () -> "generated-idempotency-key-unused",
            () -> "generated-correlation-id-unused",
            new IdempotencyKeyValidator(),
            null,
            new ApprovalReviewerRoleResolver("product_reviewer"),
            new LocalActorIdentityResolver("local-operator"),
            null,
            null,
            null);
    cli.acceptImplementation(
        RUN_ID,
        ARTIFACT_ID,
        3,
        2,
        "developer",
        "Implementation looks correct.",
        "idem-accept-equiv-dddddd",
        "alex",
        CORRELATION_ID,
        false);

    ArgumentCaptor<AcceptImplementationCommand> cliCaptor =
        ArgumentCaptor.forClass(AcceptImplementationCommand.class);
    verify(workflowCommandService).acceptImplementation(cliCaptor.capture());
    AcceptImplementationCommand cliCommand = cliCaptor.getValue();

    assertThat(cliCommand)
        .as(
            "CLI and REST built different AcceptImplementationCommand records for the same logical"
                + " payload — fingerprint symmetry broken")
        .isEqualTo(restCommand);
  }

  @Test
  void rejectSpecCommandRecordIsEqualAcrossRestAndCliForTheSamePayload() throws Exception {
    when(workflowCommandService.rejectSpec(any()))
        .thenReturn(
            new WorkflowStateChangeResult(RUN_ID, WorkflowState.INVESTIGATING, CORRELATION_ID));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-reject-equiv-bbbbbb")
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 2,
                      "expectedContextBundleVersion": 1,
                      "reviewerRole": "product_reviewer",
                      "taggedFeedback": "UNCLEAR_SPECIFICATION",
                      "reasonText": "Spec missing the negative-path criteria."
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<RejectSpecCommand> restCaptor = ArgumentCaptor.forClass(RejectSpecCommand.class);
    verify(workflowCommandService).rejectSpec(restCaptor.capture());
    RejectSpecCommand restCommand = restCaptor.getValue();

    clearInvocations(workflowCommandService);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService,
            null,
            null,
            () -> false,
            () -> "generated-idempotency-key-unused",
            () -> "generated-correlation-id-unused",
            new IdempotencyKeyValidator(),
            null,
            new ApprovalReviewerRoleResolver("product_reviewer"),
            new LocalActorIdentityResolver("local-operator"),
            null,
            null,
            null);
    cli.rejectSpec(
        RUN_ID,
        ARTIFACT_ID,
        2,
        1,
        RejectionTaxonomy.UNCLEAR_SPECIFICATION,
        "Spec missing the negative-path criteria.",
        "product_reviewer",
        "idem-reject-equiv-bbbbbb",
        "alex",
        CORRELATION_ID,
        false);

    ArgumentCaptor<RejectSpecCommand> cliCaptor = ArgumentCaptor.forClass(RejectSpecCommand.class);
    verify(workflowCommandService).rejectSpec(cliCaptor.capture());
    RejectSpecCommand cliCommand = cliCaptor.getValue();

    assertThat(cliCommand)
        .as(
            "CLI and REST built different RejectSpecCommand records for the same logical payload"
                + " — fingerprint symmetry broken")
        .isEqualTo(restCommand);
  }

  @Test
  void answerClarificationCommandRecordIsEqualAcrossRestAndCliForTheSamePayload() throws Exception {
    // Trap T6: answering a clarification does not advance workflow state.
    when(workflowCommandService.answerClarification(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                RUN_ID, WorkflowState.WAITING_FOR_SPEC_APPROVAL, CORRELATION_ID, "answered"));

    String clarificationId = "clr_equiv_a";
    String answerText = "Confirmed: the rate limit applies per-user, not per-org.";

    mockMvc
        .perform(
            post(
                    "/api/v1/workflows/{runId}/clarifications/{clarId}/answer",
                    RUN_ID,
                    clarificationId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-answer-equiv-cccccc")
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "answerText": "%s"
                    }
                    """
                        .formatted(ARTIFACT_ID, answerText)))
        .andExpect(status().isOk());

    ArgumentCaptor<SubmitClarificationCommand> restCaptor =
        ArgumentCaptor.forClass(SubmitClarificationCommand.class);
    verify(workflowCommandService).answerClarification(restCaptor.capture());
    SubmitClarificationCommand restCommand = restCaptor.getValue();

    clearInvocations(workflowCommandService);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService,
            null,
            null,
            () -> false,
            () -> "generated-idempotency-key-unused",
            () -> "generated-correlation-id-unused",
            new IdempotencyKeyValidator(),
            null,
            new ApprovalReviewerRoleResolver("product_reviewer"),
            new LocalActorIdentityResolver("local-operator"),
            null,
            null,
            null);
    cli.answerClarification(
        RUN_ID,
        clarificationId,
        ARTIFACT_ID,
        1,
        answerText,
        "idem-answer-equiv-cccccc",
        "alex",
        CORRELATION_ID,
        false);

    ArgumentCaptor<SubmitClarificationCommand> cliCaptor =
        ArgumentCaptor.forClass(SubmitClarificationCommand.class);
    verify(workflowCommandService).answerClarification(cliCaptor.capture());
    SubmitClarificationCommand cliCommand = cliCaptor.getValue();

    assertThat(cliCommand)
        .as(
            "CLI and REST built different SubmitClarificationCommand records for the same logical"
                + " payload — fingerprint symmetry broken")
        .isEqualTo(restCommand);
  }

  @Test
  void approveSpecActorIdentityFallbackIsSymmetricBetweenRestHeaderAndCliFlag() throws Exception {
    // Pin Trap T12 + the matching CLI fallback: when X-Actor-Identity is omitted and
    // --actor-identity is omitted, both surfaces fall back to the configured
    // deliveryline.security.local-actor-identity ("local-operator").
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, CORRELATION_ID));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-approve-fallback-aaaaa")
                .content(
                    """
                    {
                      "artifactId": "%s",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """
                        .formatted(ARTIFACT_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<ApproveSpecCommand> restCaptor =
        ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(restCaptor.capture());
    ApproveSpecCommand restCommand = restCaptor.getValue();

    clearInvocations(workflowCommandService);

    WorkflowCommands cli =
        new WorkflowCommands(
            workflowCommandService,
            null,
            null,
            () -> false,
            () -> "generated-idempotency-key-unused",
            () -> "generated-correlation-id-unused",
            new IdempotencyKeyValidator(),
            null,
            new ApprovalReviewerRoleResolver("product_reviewer"),
            new LocalActorIdentityResolver("local-operator"),
            null,
            null,
            null);
    cli.approveSpec(
        RUN_ID,
        ARTIFACT_ID,
        1,
        1,
        null,
        null,
        "idem-approve-fallback-aaaaa",
        null,
        CORRELATION_ID,
        false);

    ArgumentCaptor<ApproveSpecCommand> cliCaptor =
        ArgumentCaptor.forClass(ApproveSpecCommand.class);
    verify(workflowCommandService).approveSpec(cliCaptor.capture());
    ApproveSpecCommand cliCommand = cliCaptor.getValue();

    assertThat(restCommand.actorIdentity())
        .as("REST X-Actor-Identity fallback must resolve to configured local-operator")
        .isEqualTo("local-operator");
    assertThat(cliCommand.actorIdentity())
        .as("CLI --actor-identity fallback must resolve to configured local-operator")
        .isEqualTo("local-operator");
    assertThat(cliCommand)
        .as(
            "CLI and REST built different ApproveSpecCommand records when actor identity was"
                + " omitted on both sides — fallback symmetry broken")
        .isEqualTo(restCommand);
  }

  // Story 3d-4 review follow-up 2026-06-23: CLI ↔ REST equivalence for the two manual-execution
  // command surfaces. ManualArtifactSubmissionCommand carries a byte[] payload (identity-based
  // record equals), and the two channels deliver the payload differently — REST re-serializes the
  // parsed `result` node (compact), the CLI forwards the operator's raw `--file` bytes (here
  // pretty-printed + reordered keys). So this asserts field-by-field equivalence plus payload JSON
  // content-equality. Because the service canonicalizes the payload before fingerprinting, equal
  // logical JSON ⇒ equal idempotency fingerprint ⇒ no false IDEMPOTENCY_KEY_CONFLICT across
  // channels.
  @Test
  void manualArtifactCommandIsEquivalentAcrossRestAndCliForTheSameLogicalPayload()
      throws Exception {
    when(manualArtifactSubmissionService.submit(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                RUN_ID, WorkflowState.WAITING_FOR_REVIEW, CORRELATION_ID));

    String idempotencyKey = "idem-manual-equiv-eeeeee";
    // REST body: the artifact JSON lives under `result`; Jackson parses then the controller
    // re-serializes it compactly.
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/manual-artifact", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "result": {
                        "schemaVersion": 1,
                        "workflowRunId": "%s",
                        "runnerExecutionId": "rex_equiv_manual",
                        "artifactReferences": []
                      }
                    }
                    """
                        .formatted(RUN_ID)))
        .andExpect(status().isOk());

    ArgumentCaptor<ManualArtifactSubmissionCommand> restCaptor =
        ArgumentCaptor.forClass(ManualArtifactSubmissionCommand.class);
    verify(manualArtifactSubmissionService).submit(restCaptor.capture());
    ManualArtifactSubmissionCommand restCommand = restCaptor.getValue();

    org.mockito.Mockito.clearInvocations(manualArtifactSubmissionService);

    // CLI file: the SAME logical artifact, pretty-printed with reordered keys (raw bytes differ).
    Path artifactFile = tempDir.resolve("manual-artifact.json");
    Files.writeString(
        artifactFile,
        """
        {
          "runnerExecutionId": "rex_equiv_manual",
          "artifactReferences": [],
          "workflowRunId": "%s",
          "schemaVersion": 1
        }
        """
            .formatted(RUN_ID),
        StandardCharsets.UTF_8);

    WorkflowCommands cli = manualCommands();
    cli.manualArtifact(
        RUN_ID, artifactFile.toString(), "alex", idempotencyKey, CORRELATION_ID, false);

    ArgumentCaptor<ManualArtifactSubmissionCommand> cliCaptor =
        ArgumentCaptor.forClass(ManualArtifactSubmissionCommand.class);
    verify(manualArtifactSubmissionService).submit(cliCaptor.capture());
    ManualArtifactSubmissionCommand cliCommand = cliCaptor.getValue();

    assertThat(cliCommand.workflowRunId()).isEqualTo(restCommand.workflowRunId());
    assertThat(cliCommand.idempotencyKey()).isEqualTo(restCommand.idempotencyKey());
    assertThat(cliCommand.actorIdentity()).isEqualTo(restCommand.actorIdentity());
    assertThat(cliCommand.actorType()).isEqualTo(restCommand.actorType());
    assertThat(cliCommand.correlationId()).isEqualTo(restCommand.correlationId());
    // NOTE (3d-4 re-review): `artifactContents` is REST-only — the CLI `manual-artifact` command
    // hardcodes an empty map (a CLI operator cannot submit a spec's contentReference content). Both
    // channels therefore carry an EMPTY map for this no-content submission; assert that explicitly
    // rather than a vacuous empty==empty symmetric compare. The CLI/REST parity gap for
    // content-bearing (spec) submissions is tracked in deferred-work.md.
    assertThat(restCommand.artifactContents()).isEmpty();
    assertThat(cliCommand.artifactContents()).isEmpty();

    ObjectMapper mapper = new ObjectMapper();
    JsonNode restPayload = mapper.readTree(restCommand.payloadBytes());
    JsonNode cliPayload = mapper.readTree(cliCommand.payloadBytes());
    assertThat(cliPayload)
        .as(
            "CLI and REST delivered different logical manual-artifact payloads — fingerprint"
                + " (idempotency) symmetry broken")
        .isEqualTo(restPayload);
  }

  @Test
  void manualBundleReadIsRoutedToTheSameInspectionServiceAcrossRestAndCli() throws Exception {
    byte[] redacted = "redacted-bundle".getBytes(StandardCharsets.UTF_8);
    ContextBundle bundle =
        new ContextBundle(
            RUN_ID,
            RunnerStage.EXECUTION,
            "rex_equiv_bundle",
            1,
            DataClassification.SHAREABLE_REDACTED,
            redacted);
    when(workflowInspectionService.getManualBundle(RUN_ID))
        .thenReturn(ManualBundleLookupResult.available(RUN_ID, bundle));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/workflows/{runId}/manual-bundle", RUN_ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
    verify(workflowInspectionService).getManualBundle(RUN_ID);

    org.mockito.Mockito.clearInvocations(workflowInspectionService);

    WorkflowCommands cli = manualCommands();
    cli.manualBundle(RUN_ID, null, CORRELATION_ID);
    // Both channels resolve the parked bundle through the identical run-scoped read.
    verify(workflowInspectionService).getManualBundle(RUN_ID);
  }

  private WorkflowCommands manualCommands() {
    return new WorkflowCommands(
        workflowCommandService,
        workflowInspectionService,
        null,
        () -> false,
        () -> "generated-idempotency-key-unused",
        () -> "generated-correlation-id-unused",
        new IdempotencyKeyValidator(),
        null,
        new ApprovalReviewerRoleResolver("product_reviewer"),
        new LocalActorIdentityResolver("local-operator"),
        null,
        null,
        null,
        null,
        manualArtifactSubmissionService);
  }
}
