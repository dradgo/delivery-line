package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.Test;

class ContextBundleServiceUnitTest {

  private static final String RUN_ID = "run_test12345678";
  private static final String REX_ID = "rex_test12345678";
  private static final String ART_SPEC_ID = "art_spec123456789";
  private static final ExecutionConstraints CONSTRAINTS =
      new ExecutionConstraints(java.time.Duration.ofSeconds(600), false);
  private static final ActorContext ACTOR =
      new ActorContext("human-pm", ActorType.HUMAN, "corr-1234");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void happyPath_assemblesAndValidatesContextBundleWithSpecReference() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-101", "Migrate auth", "Move session storage."));
    ArtifactRecordSnapshot availableSpec =
        availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v1.json");
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(availableSpec));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR);

    assertEquals(RUN_ID, bundle.workflowRunId());
    assertEquals(REX_ID, bundle.runnerExecutionId());
    assertEquals(RunnerStage.INVESTIGATION, bundle.stage());
    assertEquals(1, bundle.contextBundleVersion());
    assertEquals(DataClassification.SHAREABLE_REDACTED, bundle.effectiveClassification());

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertEquals(1, parsed.get("schemaVersion").asInt());
    assertEquals(RUN_ID, parsed.get("workflowRunId").asText());
    assertEquals(REX_ID, parsed.get("runnerExecutionId").asText());
    assertEquals("ZIM-101", parsed.get("ticketSummary").get("ticketRef").asText());
    assertEquals(
        ART_SPEC_ID, parsed.get("approvedSpecificationReference").get("artifactId").asText());
    assertEquals("spec", parsed.get("approvedSpecificationReference").get("artifactType").asText());
    assertEquals(0, parsed.get("priorFeedbackReferences").size());
    assertEquals(1, parsed.get("artifactReferences").size());
    assertEquals(600, parsed.get("executionConstraints").get("timeoutSeconds").asInt());
    assertEquals(false, parsed.get("executionConstraints").get("allowRawOutput").asBoolean());
    assertEquals("shareable-redacted", parsed.get("classification").asText());
  }

  @Test
  void happyPath_collectsPriorFeedbackReferencesFromArtifactLineage() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-101", "Migrate auth", "Move session storage."));
    ArtifactRecordSnapshot latestSpec =
        availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v2.json", "art_spec_parent001");
    ArtifactRecordSnapshot priorSpec =
        availableArtifact("art_spec_parent001", ArtifactType.SPEC, "spec/v1.json", null);
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(latestSpec));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findByPublicId("art_spec_parent001"))
        .thenReturn(Optional.of(priorSpec));
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertEquals(1, parsed.get("priorFeedbackReferences").size());
    assertEquals(
        "art_spec_parent001",
        parsed.get("priorFeedbackReferences").get(0).get("referenceId").asText());
    assertEquals("spec", parsed.get("priorFeedbackReferences").get(0).get("kind").asText());
  }

  @Test
  void emptyArtifactReferencesArrayIsAcceptedAfterSchemaRelaxation() throws Exception {
    // Story 2.8 Task 5: context-bundle.v1.schema.json relaxed artifactReferences.minItems 1 -> 0.
    // A bundle with zero prior artifacts (legitimate spec-investigation bootstrap shape) must now
    // PASS validation — replacing the prior "empty-array fails validation" assertion this test
    // used to make.
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-101", "Migrate auth", "Move session storage."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertEquals(0, parsed.get("artifactReferences").size());
    assertTrue(parsed.get("approvedSpecificationReference").isNull());
  }

  @Test
  void redactionRunsBeforeValidation_resultMirrorsRedactedJsonBytes() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-101", "Migrate auth", "Move session storage."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v1.json")));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());

    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(
            invocation -> {
              JsonNode input = invocation.getArgument(0);
              com.fasterxml.jackson.databind.node.ObjectNode redacted = input.deepCopy();
              com.fasterxml.jackson.databind.node.ObjectNode ticket =
                  (com.fasterxml.jackson.databind.node.ObjectNode) redacted.get("ticketSummary");
              ticket.put("summary", "[REDACTED]");
              return new RedactionResult(
                  null,
                  redacted,
                  DataClassification.SHAREABLE_REDACTED,
                  DataClassification.SHAREABLE_REDACTED,
                  true,
                  Set.of());
            });

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR);

    JsonNode parsed = objectMapper.readTree(bundle.redactedPayload());
    assertEquals("[REDACTED]", parsed.get("ticketSummary").get("summary").asText());
  }

  @Test
  void rejectsInvalidPublicIdPrefixes() {
    ContextBundleService service =
        new ContextBundleService(
            mock(TicketSummaryProvider.class),
            mock(ArtifactRecordPort.class),
            mock(RedactionPolicyService.class),
            new RunnerContractValidator());
    assertThrows(
        DomainException.class,
        () ->
            service.create(
                "nope_12345",
                RunnerStage.INVESTIGATION,
                REX_ID,
                1,
                CONSTRAINTS,
                DataClassification.SHAREABLE_REDACTED,
                ACTOR));
    assertThrows(
        DomainException.class,
        () ->
            service.create(
                RUN_ID,
                RunnerStage.INVESTIGATION,
                "art_12345",
                1,
                CONSTRAINTS,
                DataClassification.SHAREABLE_REDACTED,
                ACTOR));
  }

  @Test
  void redactedPayloadIsDefensivelyCopied() {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-101", "Migrate auth", "Move session storage."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v1.json")));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(invocation -> redactionPassthrough(invocation.getArgument(0)));

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            redactionPolicyService,
            new RunnerContractValidator());
    ContextBundle bundle =
        service.create(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            REX_ID,
            1,
            CONSTRAINTS,
            DataClassification.SHAREABLE_REDACTED,
            ACTOR);

    byte[] first = bundle.redactedPayload();
    byte[] second = bundle.redactedPayload();
    assertTrue(first != second, "redactedPayload() must return a defensive copy each call");
    assertArrayEquals(first, second);
  }

  @Test
  void rejectsOversizedContextBundlePayloadAtValidatorBoundary() {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    RedactionPolicyService redactionPolicyService = mock(RedactionPolicyService.class);

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("ZIM-101", "Migrate auth", "Move session storage."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(Optional.of(availableArtifact(ART_SPEC_ID, ArtifactType.SPEC, "spec/v1.json")));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN_ID, "prOutput"))
        .thenReturn(Optional.empty());
    when(redactionPolicyService.redact(any(JsonNode.class), eq("shareable-redacted")))
        .thenAnswer(
            invocation -> {
              JsonNode input = invocation.getArgument(0);
              com.fasterxml.jackson.databind.node.ObjectNode inflated = input.deepCopy();
              ((com.fasterxml.jackson.databind.node.ObjectNode) inflated.get("ticketSummary"))
                  .put("summary", "x".repeat(3000));
              return redactionPassthrough(inflated);
            });

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            redactionPolicyService,
            new RunnerContractValidator());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.create(
                    RUN_ID,
                    RunnerStage.INVESTIGATION,
                    REX_ID,
                    1,
                    CONSTRAINTS,
                    DataClassification.SHAREABLE_REDACTED,
                    ACTOR));
    assertEquals(DomainErrorCode.RUNNER_CONTRACT_VIOLATION, error.errorCode());
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

  private static ArtifactRecordSnapshot availableArtifact(
      String publicId, ArtifactType type, String storageRef) {
    return availableArtifact(publicId, type, storageRef, null);
  }

  private static ArtifactRecordSnapshot availableArtifact(
      String publicId, ArtifactType type, String storageRef, String parentArtifactId) {
    return new ArtifactRecordSnapshot(
        publicId,
        RUN_ID,
        type,
        1,
        parentArtifactId,
        DataClassification.SHAREABLE_REDACTED,
        storageRef,
        "SHA-256",
        "a".repeat(64),
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false);
  }

  @SuppressWarnings("unused")
  private static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
