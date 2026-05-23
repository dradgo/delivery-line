package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.SpecificationArtifact;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ContextBundleLookupResult;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.SpecHistoryEntry;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Story 2.8 AC7 + OQ-3: CLI {@code workflow status --include-context-bundle} rendering tests.
 *
 * <p>Asserts:
 *
 * <ul>
 *   <li>The bundle appears as pretty-printed JSON in text mode;
 *   <li>The bundle appears as a versioned structured object in JSON mode;
 *   <li>A {@code "context-bundle: none (...)"} sentinel is emitted when no spec artifact exists;
 *   <li>Unavailable bundle reasons are surfaced verbatim instead of collapsing to "scratch
 *       evicted".
 * </ul>
 */
class WorkflowCommandsContextBundleFlagTest {

  private static final String RUN_ID = "run_status12345";
  private static final String SPEC_ART_ID = "art_spec00000001";
  private static final String REX_ID = "rex_test12345678";

  private static RedactionPolicyService priorService;

  @BeforeAll
  static void wireRedactionHolder() {
    priorService = RedactionLayoutHolder.currentForTesting();
    RedactionLayoutHolder.setRedactionService(
        new RedactionPolicyService(new DataClassificationService()));
  }

  @AfterAll
  static void unwireRedactionHolder() {
    if (priorService == null) {
      RedactionLayoutHolder.clearForTesting();
    } else {
      RedactionLayoutHolder.setRedactionService(priorService);
    }
  }

  private final WorkflowCommandService submitService = mock(WorkflowCommandService.class);
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final WorkflowCommandOutputs outputs =
      new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules());
  private final WorkflowCommands commands =
      new WorkflowCommands(
          submitService,
          inspection,
          outputs,
          () -> false,
          () -> "01964c38-1c45-7000-8000-000000000000",
          () -> "01964c38-1c45-7000-8000-000000000001",
          new IdempotencyKeyValidator(),
          recovery);

  @Test
  void textModeAppendsPrettyPrintedBundleBlock() throws Exception {
    stubStatus();
    SpecHistoryEntry latest =
        new SpecHistoryEntry(
            SpecificationArtifact.fromSnapshot(specSnapshot(SPEC_ART_ID, 1)),
            "pending",
            null,
            null,
            null);
    when(inspection.getSpecHistory(RUN_ID)).thenReturn(List.of(latest));
    when(inspection.getContextBundleLookupForArtifact(SPEC_ART_ID))
        .thenReturn(ContextBundleLookupResult.available(SPEC_ART_ID, sampleBundle()));

    String rendered = commands.status(RUN_ID, "text", "corr-1", false, true);

    assertTrue(rendered.contains("# context-bundle (artifact " + SPEC_ART_ID + "):"));
    assertTrue(
        rendered.contains("\"schemaVersion\" : 1"),
        () -> "Pretty-print should use 2-space indent with space after colon, was: " + rendered);
    assertTrue(rendered.contains("\"workflowRunId\" : \"" + RUN_ID + "\""));
  }

  @Test
  void jsonModeAppendsBundleAsNestedJsonObject() throws Exception {
    stubStatus();
    SpecHistoryEntry latest =
        new SpecHistoryEntry(
            SpecificationArtifact.fromSnapshot(specSnapshot(SPEC_ART_ID, 1)),
            "pending",
            null,
            null,
            null);
    when(inspection.getSpecHistory(RUN_ID)).thenReturn(List.of(latest));
    when(inspection.getContextBundleLookupForArtifact(SPEC_ART_ID))
        .thenReturn(ContextBundleLookupResult.available(SPEC_ART_ID, sampleBundle()));

    String rendered = commands.status(RUN_ID, "json", "corr-1", false, true);

    // Must be parseable as a single JSON document with a versioned structured contextBundle field.
    JsonNode root = new ObjectMapper().readTree(rendered);
    assertEquals(2, root.get("schemaVersion").asInt());
    assertEquals(RUN_ID, root.get("workflowRunId").asText());
    JsonNode contextBundle = root.get("contextBundle");
    assertTrue(
        contextBundle.isObject(), () -> "contextBundle must be an object, was: " + contextBundle);
    assertEquals("available", contextBundle.get("status").asText());
    assertEquals(SPEC_ART_ID, contextBundle.get("artifactId").asText());
    assertTrue(contextBundle.get("reason").isNull());
    JsonNode bundle = contextBundle.get("bundle");
    assertTrue(bundle.isObject(), () -> "contextBundle.bundle must be an object, was: " + bundle);
    assertEquals(1, bundle.get("schemaVersion").asInt());
    assertEquals(RUN_ID, bundle.get("workflowRunId").asText());
  }

  @Test
  void textModeEmitsNoneSentinelWhenNoSpecArtifactExists() {
    stubStatusNoSpec();
    when(inspection.getSpecHistory(RUN_ID)).thenReturn(List.of());

    String rendered = commands.status(RUN_ID, "text", "corr-1", false, true);

    assertTrue(rendered.contains("# context-bundle: none (no spec artifact yet)"));
  }

  @Test
  void textModeEmitsExactUnavailableReasonWhenBundleLookupMisses() {
    stubStatus();
    SpecHistoryEntry latest =
        new SpecHistoryEntry(
            SpecificationArtifact.fromSnapshot(specSnapshot(SPEC_ART_ID, 1)),
            "pending",
            null,
            null,
            null);
    when(inspection.getSpecHistory(RUN_ID)).thenReturn(List.of(latest));
    when(inspection.getContextBundleLookupForArtifact(SPEC_ART_ID))
        .thenReturn(
            ContextBundleLookupResult.unavailable(
                SPEC_ART_ID, "runnerExecutionLinkMissing"));

    String rendered = commands.status(RUN_ID, "text", "corr-1", false, true);

    assertTrue(
        rendered.contains(
            "# context-bundle: none (context bundle unavailable (runnerExecutionLinkMissing))"));
  }

  @Test
  void includeContextBundleFlagFalseSkipsAllBundleLookups() {
    stubStatus();

    String rendered = commands.status(RUN_ID, "text", "corr-1", false, false);

    assertFalse(rendered.contains("# context-bundle"));
  }

  // =============================================================================================
  // Helpers
  // =============================================================================================

  private void stubStatus() {
    WorkflowStatusView view =
        new WorkflowStatusView(
            RUN_ID,
            WorkflowState.INVESTIGATING,
            "alex",
            "human",
            "workflow.stateChanged",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            List.of(new LatestArtifactView("spec", 1, "pending")),
            null,
            null,
            null,
            null,
            null,
            null,
            "await_outcome");
    when(inspection.getStatus(RUN_ID)).thenReturn(view);
  }

  private void stubStatusNoSpec() {
    WorkflowStatusView view =
        new WorkflowStatusView(
            RUN_ID,
            WorkflowState.INVESTIGATING,
            "alex",
            "human",
            "workflow.stateChanged",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            "await_outcome");
    when(inspection.getStatus(RUN_ID)).thenReturn(view);
  }

  private static ArtifactRecordSnapshot specSnapshot(String publicId, int version) {
    return new ArtifactRecordSnapshot(
        publicId,
        RUN_ID,
        ArtifactType.SPEC,
        version,
        null,
        DataClassification.SHAREABLE_REDACTED,
        "spec.md",
        null,
        null,
        null,
        null,
        ArtifactStatus.PENDING,
        null,
        false,
        OffsetDateTime.parse("2026-05-13T10:00:00Z"));
  }

  private static ContextBundle sampleBundle() {
    String json =
        "{\"schemaVersion\":1,\"workflowRunId\":\""
            + RUN_ID
            + "\",\"runnerExecutionId\":\""
            + REX_ID
            + "\",\"classification\":\"shareable-redacted\"}";
    return new ContextBundle(
        RUN_ID,
        RunnerStage.INVESTIGATION,
        REX_ID,
        1,
        DataClassification.SHAREABLE_REDACTED,
        json.getBytes(StandardCharsets.UTF_8));
  }
}
