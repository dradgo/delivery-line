package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.Test;

/**
 * Story 3e-3 (AC2/AC8) — the spec-phase advisory reviewer bundle. When the reviewed artifact is a
 * SPEC (the reviewer fired at {@code WaitingForSpecApproval}, where {@code resolveReviewedArtifact}
 * falls through to the spec), {@link ContextBundleService#createForReview} composes a spec-review
 * bundle that ALSO inlines the run's {@code open} clarifications as {@code {clarificationId,
 * questionId, questionText}} (no answerText — open rows carry none) so the reviewer can weigh
 * whether the spec leaves the open questions unresolved. A run with zero open clarifications stays
 * byte-identical to the no-clarification baseline (the field is additive-optional). The whole
 * bundle passes the same real redaction pass + contract validation as every other bundle.
 */
class ContextBundleServiceSpecReviewTest {

  private static final String RUN_ID = "run_specreview01";
  private static final String REX_ID = "rex_specreview01";
  private static final String PLANTED_SECRET = "ghp_1234567890abcdef1234567890abcdef1234";

  private final ObjectMapper objectMapper = new ObjectMapper();

  private ContextBundleService service(ClarificationReadPort clarificationReadPort) {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    RedactionPolicyService redactionPolicyService =
        new RedactionPolicyService(new DataClassificationService());

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(
            new TicketSummary("ZIM-42", "Add the widget", "Build the widget per the spec."));
    // No execution-stage artifact exists at the spec gate, so resolveReviewedArtifact falls through
    // to the spec — stub the spec as the latest AVAILABLE artifact, the others empty.
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            RUN_ID, ArtifactType.PR_OUTPUT.value()))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            RUN_ID, ArtifactType.IMPLEMENTATION_PLAN.value()))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            RUN_ID, ArtifactType.SPEC.value()))
        .thenReturn(Optional.of(specArtifact()));

    return new ContextBundleService(
        ticketProvider,
        artifactRecordPort,
        approvalReadPort,
        clarificationReadPort,
        redactionPolicyService,
        new RunnerContractValidator());
  }

  @Test
  void specReviewBundleInlinesOpenClarificationsAndIsRedactionClean() throws Exception {
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID))
        .thenReturn(
            List.of(
                openClarification("clr_open00000001", "Q-1", "Which auth provider should we use?"),
                // An open question whose text smuggles a secret — it must be redacted on egress.
                openClarification(
                    "clr_open00000002", "Q-2", "Reuse the token " + PLANTED_SECRET + "?"),
                // A non-open clarification must NOT appear in the spec-review bundle.
                acceptedClarification("clr_acc000000003", "Q-3", "answered + accepted")));

    ContextBundle bundle =
        service(clarificationReadPort)
            .createForReview(
                RUN_ID,
                REX_ID,
                1,
                new ExecutionConstraints(Duration.ofSeconds(600), false),
                DataClassification.SHAREABLE_REDACTED,
                new ActorContext("system-broker", ActorType.SYSTEM, "corr-specrev-1"));

    String payload = new String(bundle.redactedPayload(), StandardCharsets.UTF_8);
    assertFalse(
        payload.contains(PLANTED_SECRET),
        () -> "spec-review bundle leaked the planted secret before egress: " + payload);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertEquals("shareable-redacted", tree.get("classification").asText());
    // AC2 — the reviewed spec is the sole artifact reference; the spec is NOT yet approved.
    assertEquals(1, tree.get("artifactReferences").size());
    assertEquals("spec", tree.get("artifactReferences").get(0).get("artifactType").asText());
    assertTrue(tree.get("approvedSpecificationReference").isNull());
    // AC2 — exactly the two OPEN clarifications are inlined (the accepted one is excluded).
    JsonNode open = tree.get("openClarifications");
    assertEquals(2, open.size());
    assertEquals("clr_open00000001", open.get(0).get("clarificationId").asText());
    assertEquals("Q-1", open.get(0).get("questionId").asText());
    assertEquals("Which auth provider should we use?", open.get(0).get("questionText").asText());
    // Open rows carry no answer — the inline object has questionText but no answerText leaf.
    assertNull(open.get(0).get("answerText"));
    // The by-id audit half: one clarification.open feedback ref per open clarification.
    JsonNode feedback = tree.get("priorFeedbackReferences");
    assertEquals(2, feedback.size());
    assertEquals("clarification.open", feedback.get(0).get("kind").asText());
  }

  @Test
  void specReviewBundleOmitsOpenClarificationsArrayWhenNoneOpen() throws Exception {
    ClarificationReadPort clarificationReadPort = mock(ClarificationReadPort.class);
    // No open clarifications (an empty list, or only non-open rows) ⇒ the additive
    // openClarifications
    // field is absent, matching the no-clarification baseline shape.
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID))
        .thenReturn(List.of(acceptedClarification("clr_acc000000009", "Q-9", "already accepted")));

    ContextBundle bundle =
        service(clarificationReadPort)
            .createForReview(
                RUN_ID,
                REX_ID,
                1,
                new ExecutionConstraints(Duration.ofSeconds(600), false),
                DataClassification.SHAREABLE_REDACTED,
                new ActorContext("system-broker", ActorType.SYSTEM, "corr-specrev-2"));

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertNull(tree.get("openClarifications"), "no open clarifications ⇒ field omitted");
    assertEquals(0, tree.get("priorFeedbackReferences").size());
    // The reviewed spec is still carried — the reviewer reviews the spec even with no open
    // questions.
    assertEquals(1, tree.get("artifactReferences").size());
    assertEquals("spec", tree.get("artifactReferences").get(0).get("artifactType").asText());
  }

  private static ArtifactRecordSnapshot specArtifact() {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        "art_specrev00001",
        RUN_ID,
        ArtifactType.SPEC,
        1,
        null,
        DataClassification.SHAREABLE_REDACTED,
        "artifacts/run/spec.md",
        "SHA-256",
        "spec123",
        ArtifactStatus.AVAILABLE,
        null);
  }

  private static Clarification openClarification(
      String publicId, String questionId, String questionText) {
    return new Clarification(
        publicId,
        RUN_ID,
        "art_specrev00001",
        1,
        questionId,
        questionText,
        Clarification.STATUS_OPEN,
        null,
        null,
        null,
        null,
        OffsetDateTime.parse("2026-06-25T00:00:00Z"));
  }

  private static Clarification acceptedClarification(
      String publicId, String questionId, String questionText) {
    return new Clarification(
        publicId,
        RUN_ID,
        "art_specrev00001",
        1,
        questionId,
        questionText,
        Clarification.STATUS_ACCEPTED,
        "the reviewer's answer",
        "reviewer-1",
        ActorType.HUMAN,
        OffsetDateTime.parse("2026-06-25T01:00:00Z"),
        OffsetDateTime.parse("2026-06-25T00:00:00Z"));
  }
}
