package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
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
 * Story 3d-2 (AC7 leg 1, code-review #10) — the reviewer bundle the runner sees passes the SAME
 * redaction guarantee as any runner artifact BEFORE egress. Drives {@link
 * ContextBundleService#createForReview} with the REAL {@link RedactionPolicyService} and a planted
 * GitHub PAT in the embedded review context, then asserts the secret is absent from the bundle
 * bytes the runner would receive. Complements the persisted-rationale redaction (leg 2) covered by
 * {@code ReviewResultHarvesterTest}.
 */
class ContextBundleServiceReviewRedactionTest {

  private static final String RUN_ID = "run_reviewredact1";
  private static final String REX_ID = "rex_reviewredact1";
  // A GitHub PAT in the known-secret format the redaction engine catches (mirrors the
  // redaction-fixtures corpus), planted inside the review context that egresses to the runner.
  private static final String PLANTED_SECRET = "ghp_1234567890abcdef1234567890abcdef1234";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void reviewBundleRedactsPlantedSecretBeforeEgress() throws Exception {
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = mock(ApprovalReadPort.class);
    // REAL redaction engine — this is the gate under test (no passthrough mock).
    RedactionPolicyService redactionPolicyService =
        new RedactionPolicyService(new DataClassificationService());

    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(
            new TicketSummary(
                "ZIM-9",
                "Review this implementation",
                "Reviewer context — must NOT leak token " + PLANTED_SECRET + " to the runner."));
    // resolveReviewedArtifact: latest AVAILABLE prOutput is the reviewed artifact.
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            RUN_ID, ArtifactType.PR_OUTPUT.value()))
        .thenReturn(Optional.of(reviewedArtifact()));
    // No approved spec reference for this run.
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            RUN_ID, ArtifactType.SPEC.value()))
        .thenReturn(Optional.empty());

    ContextBundleService service =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            redactionPolicyService,
            new RunnerContractValidator());

    ContextBundle bundle =
        service.createForReview(
            RUN_ID,
            REX_ID,
            1,
            new ExecutionConstraints(Duration.ofSeconds(600), false),
            DataClassification.SHAREABLE_REDACTED,
            new ActorContext("system-broker", ActorType.SYSTEM, "corr-review-1"));

    String payload = new String(bundle.redactedPayload(), StandardCharsets.UTF_8);
    // AC7 leg 1 — the planted secret never reaches the runner.
    assertFalse(
        payload.contains(PLANTED_SECRET),
        () -> "reviewer bundle leaked the planted secret before egress: " + payload);
    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertEquals("shareable-redacted", tree.get("classification").asText());
    // The reviewed artifact is still the sole reference the runner reviews.
    assertEquals(1, tree.get("artifactReferences").size());
    assertTrue(bundle.redactedPayload().length > 0);
  }

  private static ArtifactRecordSnapshot reviewedArtifact() {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        "art_reviewed0001",
        RUN_ID,
        ArtifactType.PR_OUTPUT,
        2,
        null,
        DataClassification.SHAREABLE_REDACTED,
        "artifacts/run/pr.json",
        "SHA-256",
        "abc123",
        ArtifactStatus.AVAILABLE,
        null);
  }
}
