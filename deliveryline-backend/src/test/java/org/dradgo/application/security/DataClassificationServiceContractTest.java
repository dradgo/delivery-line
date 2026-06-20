package org.dradgo.application.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;

class DataClassificationServiceContractTest {

  private final DataClassificationService classificationService = new DataClassificationService();
  private final RedactionPolicyService redactionPolicyService =
      new RedactionPolicyService(classificationService);

  @Test
  void claimedShareableFullIsDowngradedWhenPayloadContainsGovernedSecrets() {
    ClassificationAssessment assessment =
        classificationService.assess(
            """
				{
				  "classification": "shareable-full",
				  "token": "github_pat_1234567890abcdefghijklmnopqrstuvwxyzABCDEFG",
				  "actorIdentity": "alex"
				}
				""",
            DataClassification.SHAREABLE_FULL.value());

    assertEquals(DataClassification.SHAREABLE_REDACTED, assessment.effectiveClassification());
    assertTrue(assessment.redactionRequired());
    assertFalse(assessment.detectedCategories().isEmpty());
  }

  @Test
  void unknownClaimedClassificationFailsFastUsingTheRegistryBoundary() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> classificationService.assess("plain text", "not-a-real-classification"));

    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, error.errorCode());
    assertEquals("not-a-real-classification", error.details().get("value"));
  }

  @Test
  void exportRejectsBenignPayloadThatRemainsLocalOnly() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                redactionPolicyService.redactForExport(
                    "Operator-only note with no secrets but explicit local-only handling",
                    DataClassification.LOCAL_ONLY.value()));

    assertEquals(DomainErrorCode.EXPORT_CLASSIFICATION_VIOLATION, error.errorCode());
    assertEquals(
        DataClassification.LOCAL_ONLY.value(), error.details().get("effectiveClassification"));
  }

  @Test
  void exportTimeRedactionRechecksTheRawPayloadInsteadOfTrustingEarlierClassification() {
    RedactionResult result =
        redactionPolicyService.redactForExport(
            "Authorization: Bearer ghp_1234567890abcdef1234567890abcdef1234",
            DataClassification.SHAREABLE_FULL.value());

    assertTrue(result.redacted());
    assertEquals(DataClassification.SHAREABLE_REDACTED, result.effectiveClassification());
    assertTrue(result.sanitizedText().contains("[REDACTED_AUTHORIZATION_HEADER]"));
  }

  @Test
  void exportTimeRedactionStripsAnEmbeddedProjectCredentialValue() {
    // Story 3c-5 (AC5) — a payload that EMBEDS a connector credential under a `credential` key must
    // emit no secret material on the export path. There is no export pipeline yet (R4), so this
    // proves the egress redaction layer the future epic-05 export will route through.
    String opaqueCredential = "FAKE-OPAQUE-CREDENTIAL-DO-NOT-USE-Zk9wQ2pLmN7xR4tBvH";
    String payload =
        "{ \"projectId\": \"prj_demo01\", \"credential\": \"" + opaqueCredential + "\" }";

    RedactionResult result =
        redactionPolicyService.redactForExport(payload, DataClassification.SHAREABLE_FULL.value());

    assertTrue(result.redacted());
    assertEquals(DataClassification.SHAREABLE_REDACTED, result.effectiveClassification());
    assertFalse(
        result.sanitizedText().contains(opaqueCredential),
        "export path must strip the embedded credential value");
    assertTrue(result.sanitizedText().contains("[REDACTED_SECRET_FIELD]"));
  }

  @Test
  void metadataSpoofingCannotOverrideDetectedSecrets() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("classification", DataClassification.SHAREABLE_FULL.value());
    payload.put(
        "payload",
        "-----BEGIN OPENSSH PRIVATE KEY-----\nsecret-material\n-----END OPENSSH PRIVATE KEY-----");

    ClassificationAssessment assessment =
        classificationService.assess(payload, DataClassification.SHAREABLE_FULL.value());

    assertEquals(DataClassification.SHAREABLE_REDACTED, assessment.effectiveClassification());
    assertTrue(assessment.redactionRequired());
  }

  @Test
  void suspiciousKeyValuePairsWithHighEntropyValuesRedactConservatively() {
    Random random = new Random(42L);
    for (int i = 0; i < 20; i++) {
      String payload = "session_secret=" + highEntropyValue(random, 32);

      RedactionResult result =
          redactionPolicyService.redact(payload, DataClassification.SHAREABLE_FULL.value());

      assertTrue(result.redacted(), "high-entropy secret-like values must be redacted");
      assertEquals(DataClassification.SHAREABLE_REDACTED, result.effectiveClassification());
      assertTrue(result.sanitizedText().contains("[REDACTED_ENV_VALUE]"));
    }
  }

  private String highEntropyValue(Random random, int length) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    StringBuilder builder = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return builder.toString();
  }
}
