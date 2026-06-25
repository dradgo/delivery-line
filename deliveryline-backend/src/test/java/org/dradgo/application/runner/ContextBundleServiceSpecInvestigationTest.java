package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.runner.workspace.RepoManifestRef;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.spi.RepoTreeEntry;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.Test;

/**
 * Targeted unit tests for {@link ContextBundleService#createForSpecInvestigation} (story 2.8 AC3-5,
 * extended by story 3a-2 AC1/AC3 with the additive repo-context fields).
 *
 * <p>Validates the composition rules that diverge from the existing {@link
 * ContextBundleService#create create(...)} path:
 *
 * <ul>
 *   <li>{@code approvedSpecificationReference} is always {@code null};
 *   <li>{@code priorFeedbackReferences} are sourced from {@code approvals.decision='rejected'} rows
 *       for SPEC, NOT from the parent-walking approach used by the execution stage;
 *   <li>{@code artifactReferences} carries prior spec versions (empty on bootstrap);
 *   <li>redaction is invoked exactly once;
 *   <li>schema validation rejection surfaces {@code DomainErrorCode.RUNNER_CONTRACT_VIOLATION};
 *   <li>story 3a-2 — a non-null {@link RepositoryContextSummary} emits the five repo fields; a null
 *       summary leaves the bundle byte-identical to the story-2.8 baseline.
 * </ul>
 */
class ContextBundleServiceSpecInvestigationTest {

  private static final String RUN_ID = "run_specinv1234";
  private static final String REX_ID = "rex_specinv1234";
  private static final ExecutionConstraints CONSTRAINTS =
      new ExecutionConstraints(Duration.ofSeconds(600), false);
  private static final ActorContext ACTOR =
      new ActorContext("system-broker", ActorType.SYSTEM, "corr-spec-1234");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void bootstrapBundleHasNullApprovedSpecEmptyPriorFeedbackAndEmptyArtifactReferences()
      throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Spec investigation."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.createForSpecInvestigation(
            RUN_ID, REX_ID, 1, CONSTRAINTS, DataClassification.SHAREABLE_REDACTED, ACTOR, null);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertEquals(1, tree.get("schemaVersion").asInt());
    assertEquals(RUN_ID, tree.get("workflowRunId").asText());
    assertEquals(REX_ID, tree.get("runnerExecutionId").asText());
    assertTrue(tree.get("approvedSpecificationReference").isNull());
    assertEquals(0, tree.get("priorFeedbackReferences").size());
    assertEquals(0, tree.get("artifactReferences").size());
    assertEquals("shareable-redacted", tree.get("classification").asText());
    // Story 3a-2 (AC1) — no workspace prepared → none of the five repo fields are emitted.
    assertFalse(tree.has("repositoryWorkspaceRef"));
    assertFalse(tree.has("repositoryTreeSummary"));
    assertFalse(tree.has("repositoryReadmeRef"));
    assertFalse(tree.has("packageManifestRefs"));
    assertFalse(tree.has("ticketRepositoryMappingVersion"));
    verify(redactionPolicyService).redact(any(JsonNode.class), eq("shareable-redacted"));
  }

  @Test
  void priorFeedbackSourcedFromApprovalsRejectionsInChronologicalOrder() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Spec investigation."));
    ApprovalSnapshot rejection1 =
        rejectionRow("apr_reject0001", "art_spec1", 1, "missing_scope", "2026-05-01T10:00:00Z");
    ApprovalSnapshot rejection2 =
        rejectionRow(
            "apr_reject0002", "art_spec2", 2, "unclear_specification", "2026-05-02T11:00:00Z");
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(rejection1, rejection2));
    ArtifactRecordSnapshot priorSpec1 = specSnapshot("art_spec1", 1, null);
    ArtifactRecordSnapshot priorSpec2 = specSnapshot("art_spec2", 2, "art_spec1");
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(priorSpec1, priorSpec2));
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.createForSpecInvestigation(
            RUN_ID, REX_ID, 3, CONSTRAINTS, DataClassification.SHAREABLE_REDACTED, ACTOR, null);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());

    JsonNode priorFeedback = tree.get("priorFeedbackReferences");
    assertEquals(2, priorFeedback.size());
    assertEquals("apr_reject0001", priorFeedback.get(0).get("referenceId").asText());
    assertEquals("spec.rejection", priorFeedback.get(0).get("kind").asText());
    assertEquals("apr_reject0002", priorFeedback.get(1).get("referenceId").asText());
    assertEquals("spec.rejection", priorFeedback.get(1).get("kind").asText());

    JsonNode artifactReferences = tree.get("artifactReferences");
    assertEquals(2, artifactReferences.size());
    assertEquals("art_spec1", artifactReferences.get(0).get("artifactId").asText());
    assertEquals("spec.md", artifactReferences.get(0).get("referencePath").asText());
    assertEquals("art_spec2", artifactReferences.get(1).get("artifactId").asText());
    // Approvals approach must NOT use the parent-walking pattern from execution stage.
    assertTrue(tree.get("approvedSpecificationReference").isNull());
  }

  @Test
  void pendingOrUnreadablePriorSpecsRemainRepresentedInArtifactReferences() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Spec investigation."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    ArtifactRecordSnapshot pendingWithoutStorageRef =
        new ArtifactRecordSnapshot(
            "art_spec_pending",
            RUN_ID,
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            null,
            null,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null,
            false,
            OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    ArtifactRecordSnapshot availableWithStorageRef = specSnapshot("art_spec2", 2, "art_spec1");
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of(pendingWithoutStorageRef, availableWithStorageRef));
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.createForSpecInvestigation(
            RUN_ID, REX_ID, 2, CONSTRAINTS, DataClassification.SHAREABLE_REDACTED, ACTOR, null);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    JsonNode artifactReferences = tree.get("artifactReferences");
    assertEquals(2, artifactReferences.size());
    assertEquals("art_spec_pending", artifactReferences.get(0).get("artifactId").asText());
    assertTrue(artifactReferences.get(0).get("referencePath").isNull());
    assertEquals("pending", artifactReferences.get(0).get("artifactStatus").asText());
    assertTrue(!artifactReferences.get(0).get("referenceAvailable").asBoolean());
    assertEquals(
        "artifact_status_pending", artifactReferences.get(0).get("unavailableReason").asText());
    assertEquals("art_spec2", artifactReferences.get(1).get("artifactId").asText());
    assertEquals("spec.md", artifactReferences.get(1).get("referencePath").asText());
    assertEquals("available", artifactReferences.get(1).get("artifactStatus").asText());
    assertTrue(artifactReferences.get(1).get("referenceAvailable").asBoolean());
    assertTrue(artifactReferences.get(1).get("unavailableReason").isNull());
  }

  @Test
  void cleanInputIsStillForcedToShareableRedactedClassification() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Plain clean summary."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(
            invocation ->
                new RedactionResult(
                    null,
                    invocation.getArgument(0),
                    DataClassification.SHAREABLE_FULL,
                    DataClassification.SHAREABLE_FULL,
                    false,
                    Set.of()));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.createForSpecInvestigation(
            RUN_ID, REX_ID, 1, CONSTRAINTS, DataClassification.SHAREABLE_FULL, ACTOR, null);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertEquals(DataClassification.SHAREABLE_REDACTED, bundle.effectiveClassification());
    assertEquals("shareable-redacted", tree.get("classification").asText());
    verify(redactionPolicyService).redact(any(JsonNode.class), eq("shareable-redacted"));
  }

  @Test
  void redactsSecretsAndLocalPathsFromSpecInvestigationBundle() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService =
        new RedactionPolicyService(new DataClassificationService());

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(
            new TicketSummary(
                "ZIM-205",
                "GitHub PAT ghp_1234567890abcdef1234567890abcdef1234",
                "Linear key lin_api_1234567890abcdef1234567890abcdef and path "
                    + "C:\\Users\\alex\\secret.txt"));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.createForSpecInvestigation(
            RUN_ID, REX_ID, 1, CONSTRAINTS, DataClassification.SHAREABLE_FULL, ACTOR, null);

    String serialized =
        new String(bundle.redactedPayload(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(DataClassification.SHAREABLE_REDACTED, bundle.effectiveClassification());
    assertTrue(serialized.contains("[REDACTED_GITHUB_TOKEN]"));
    assertTrue(serialized.contains("[REDACTED_LINEAR_API_KEY]"));
    assertTrue(serialized.contains("[REDACTED_LOCAL_PATH]"));
    assertTrue(serialized.contains("shareable-redacted"));
    assertTrue(!serialized.contains("ghp_1234567890abcdef1234567890abcdef1234"));
    assertTrue(!serialized.contains("lin_api_1234567890abcdef1234567890abcdef"));
    assertTrue(!serialized.contains("C:\\Users\\alex\\secret.txt"));
  }

  @Test
  void validatorRejectionProducesRunnerContractViolation() {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Spec investigation."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    // Redaction mutates the bundle into a schema-invalid shape: drop the required
    // `approvedSpecificationReference` key. The validator runs AFTER redaction (story 1.13) so
    // this exercises the post-redaction validation gate end-to-end.
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(
            invocation -> {
              JsonNode input = invocation.getArgument(0);
              com.fasterxml.jackson.databind.node.ObjectNode tampered = input.deepCopy();
              tampered.remove("approvedSpecificationReference");
              return new RedactionResult(
                  null,
                  tampered,
                  DataClassification.SHAREABLE_REDACTED,
                  DataClassification.SHAREABLE_REDACTED,
                  false,
                  Set.of());
            });

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.createForSpecInvestigation(
                    RUN_ID,
                    REX_ID,
                    1,
                    CONSTRAINTS,
                    DataClassification.SHAREABLE_REDACTED,
                    ACTOR,
                    null));
    assertEquals(DomainErrorCode.RUNNER_CONTRACT_VIOLATION, error.errorCode());
    assertEquals("investigation", error.details().get("stage"));
    assertNotNull(error.details().get("validationErrors"));
  }

  @Test
  void rejectsInvalidPublicIdPrefixes() {
    ContextBundleService service =
        new ContextBundleService(
            mock(TicketSummaryProvider.class),
            mock(ArtifactRecordPort.class),
            mock(ApprovalReadPort.class),
            mock(ClarificationReadPort.class),
            mock(RedactionPolicyService.class),
            new RunnerContractValidator());

    assertThrows(
        DomainException.class,
        () ->
            service.createForSpecInvestigation(
                "wrong-prefix",
                REX_ID,
                1,
                CONSTRAINTS,
                DataClassification.SHAREABLE_REDACTED,
                ACTOR,
                null));
    assertThrows(
        DomainException.class,
        () ->
            service.createForSpecInvestigation(
                RUN_ID,
                "wrong-prefix",
                1,
                CONSTRAINTS,
                DataClassification.SHAREABLE_REDACTED,
                ACTOR,
                null));
  }

  // =====================================================================
  // Story 3a-2 — repo-context embedding (AC1/AC3)
  // =====================================================================

  @Test
  void repoContextSummaryEmitsTheFiveAdditiveFieldsAndStillValidates() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Spec investigation."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    RepositoryContextSummary summary =
        new RepositoryContextSummary(
            "/workspace/repo",
            List.of(
                new RepoTreeEntry("README.md", RepoTreeEntry.Type.FILE),
                new RepoTreeEntry("src", RepoTreeEntry.Type.DIR),
                new RepoTreeEntry("package.json", RepoTreeEntry.Type.FILE)),
            "README.md",
            List.of(new RepoManifestRef("package.json", "package.json")),
            "config:GH-101@1");

    ContextBundle bundle =
        service.createForSpecInvestigation(
            RUN_ID, REX_ID, 1, CONSTRAINTS, DataClassification.SHAREABLE_REDACTED, ACTOR, summary);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertEquals("/workspace/repo", tree.get("repositoryWorkspaceRef").asText());
    assertEquals(3, tree.get("repositoryTreeSummary").size());
    assertEquals("README.md", tree.get("repositoryTreeSummary").get(0).get("path").asText());
    assertEquals("file", tree.get("repositoryTreeSummary").get(0).get("type").asText());
    assertEquals("dir", tree.get("repositoryTreeSummary").get(1).get("type").asText());
    assertEquals("README.md", tree.get("repositoryReadmeRef").asText());
    assertEquals(1, tree.get("packageManifestRefs").size());
    assertEquals("package.json", tree.get("packageManifestRefs").get(0).get("path").asText());
    assertEquals("package.json", tree.get("packageManifestRefs").get(0).get("kind").asText());
    assertEquals("config:GH-101@1", tree.get("ticketRepositoryMappingVersion").asText());
    // The existing 2.8 fields are unchanged (AC1).
    assertTrue(tree.get("approvedSpecificationReference").isNull());
    assertEquals(0, tree.get("priorFeedbackReferences").size());
  }

  @Test
  void absentReadmeIsEmittedAsNullRepositoryReadmeRef() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Spec investigation."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    RepositoryContextSummary summary =
        new RepositoryContextSummary(
            "/workspace/repo",
            List.of(new RepoTreeEntry("main.go", RepoTreeEntry.Type.FILE)),
            null,
            List.of(),
            "config:GH-101@1");

    ContextBundle bundle =
        service.createForSpecInvestigation(
            RUN_ID, REX_ID, 1, CONSTRAINTS, DataClassification.SHAREABLE_REDACTED, ACTOR, summary);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertTrue(tree.has("repositoryReadmeRef"));
    assertTrue(tree.get("repositoryReadmeRef").isNull());
    assertEquals(0, tree.get("packageManifestRefs").size());
  }

  @Test
  void malformedRepoWorkspaceRefIsRejectedBySchemaValidation() {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-205", "Add export pipeline", "Spec investigation."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    // Redaction tampers the repo workspace ref into a value that violates the schema pattern
    // (^/workspace/repo$). The post-redaction validator must reject it (story 3a-2 AC2 enforces the
    // new optional fields' constraints when present).
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(
            invocation -> {
              JsonNode input = invocation.getArgument(0);
              com.fasterxml.jackson.databind.node.ObjectNode tampered = input.deepCopy();
              tampered.put("repositoryWorkspaceRef", "/etc/passwd");
              return new RedactionResult(
                  null,
                  tampered,
                  DataClassification.SHAREABLE_REDACTED,
                  DataClassification.SHAREABLE_REDACTED,
                  false,
                  Set.of());
            });

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            mock(ClarificationReadPort.class),
            redactionPolicyService,
            new RunnerContractValidator());

    RepositoryContextSummary summary =
        new RepositoryContextSummary(
            "/workspace/repo",
            List.of(new RepoTreeEntry("README.md", RepoTreeEntry.Type.FILE)),
            "README.md",
            List.of(),
            "config:GH-101@1");

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.createForSpecInvestigation(
                    RUN_ID,
                    REX_ID,
                    1,
                    CONSTRAINTS,
                    DataClassification.SHAREABLE_REDACTED,
                    ACTOR,
                    summary));
    assertEquals(DomainErrorCode.RUNNER_CONTRACT_VIOLATION, error.errorCode());
  }

  private static ApprovalSnapshot rejectionRow(
      String publicId,
      String artifactId,
      int artifactVersion,
      String rejectionTaxonomy,
      String decidedAtIso) {
    return new ApprovalSnapshot(
        publicId,
        RUN_ID,
        artifactId,
        artifactVersion,
        artifactVersion,
        "human-pm",
        ActorType.HUMAN,
        "product_owner",
        ApprovalSnapshot.DECISION_REJECTED,
        "needs more detail",
        rejectionTaxonomy,
        OffsetDateTime.parse(decidedAtIso));
  }

  private static ArtifactRecordSnapshot specSnapshot(
      String publicId, int version, String parentArtifactId) {
    return new ArtifactRecordSnapshot(
        publicId,
        RUN_ID,
        ArtifactType.SPEC,
        version,
        parentArtifactId,
        DataClassification.SHAREABLE_REDACTED,
        "spec.md",
        null,
        null,
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC));
  }

  private static RedactionResult redactionPassthrough(JsonNode input) {
    return new RedactionResult(
        null,
        input,
        DataClassification.SHAREABLE_REDACTED,
        DataClassification.SHAREABLE_REDACTED,
        false,
        Set.of());
  }
}
