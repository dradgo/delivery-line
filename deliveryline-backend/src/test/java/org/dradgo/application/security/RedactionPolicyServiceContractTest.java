package org.dradgo.application.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dradgo.domain.registry.DataClassification;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class RedactionPolicyServiceContractTest {

  private static final String FIXTURE_ROOT = "redaction-fixtures/";
  private static final String MANIFEST_RESOURCE = FIXTURE_ROOT + "fixtures-manifest.json";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DataClassificationService classificationService = new DataClassificationService();
  private final RedactionPolicyService service = new RedactionPolicyService(classificationService);

  @TestFactory
  Stream<DynamicTest> governedFixtureCorpusRedactsEveryRegisteredSecretCategory()
      throws IOException {
    FixtureManifest manifest = readManifest();
    return manifest.fixtures().stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture.file(),
                    () -> {
                      String raw = readResource(FIXTURE_ROOT + fixture.file());

                      RedactionResult result =
                          service.redact(raw, DataClassification.SHAREABLE_FULL.value());

                      assertTrue(result.redacted(), "fixture should trigger redaction");
                      assertEquals(
                          DataClassification.fromValue(
                              fixture.minimumClassification(), "test.minimumClassification"),
                          result.effectiveClassification());
                      assertTrue(
                          result.sanitizedText().contains(fixture.placeholder()),
                          () -> "redacted output must include " + fixture.placeholder());
                      for (String forbiddenSnippet : fixture.forbiddenSnippets()) {
                        assertFalse(
                            result.sanitizedText().contains(forbiddenSnippet),
                            () ->
                                "redacted output leaked secret snippet `" + forbiddenSnippet + "`");
                      }
                    }));
  }

  @Test
  void structuredPayloadsPreserveJsonShapeWhileRedactingSensitiveLeafValues() {
    Map<String, Object> payload =
        Map.of(
            "authorizationHeader", "Authorization: Bearer ghp_1234567890abcdef1234567890abcdef1234",
            "homePath", "C:\\Users\\alex\\Documents\\secrets.txt",
            "metadata", Map.of("linearApiKey", "lin_api_1234567890abcdef1234567890abcdef"),
            "classification", "shareable-full");

    RedactionResult result = service.redact(payload, DataClassification.SHAREABLE_FULL.value());

    assertNotNull(result.sanitizedJson());
    assertEquals(
        "Authorization: Bearer [REDACTED_AUTHORIZATION_HEADER]",
        result.sanitizedJson().get("authorizationHeader").textValue());
    assertEquals("[REDACTED_LOCAL_PATH]", result.sanitizedJson().get("homePath").textValue());
    assertEquals(
        "[REDACTED_LINEAR_API_KEY]",
        result.sanitizedJson().path("metadata").path("linearApiKey").textValue());
  }

  @Test
  void redactionIsDeterministicForTheSameInputAcrossRepeatedRuns() {
    String raw =
        "Authorization: Bearer ghp_1234567890abcdef1234567890abcdef1234\n"
            + "path=C:\\Users\\alex\\secrets.txt";

    RedactionResult first = service.redact(raw, DataClassification.SHAREABLE_FULL.value());
    RedactionResult second = service.redact(raw, DataClassification.SHAREABLE_FULL.value());

    assertEquals(first.sanitizedText(), second.sanitizedText());
    assertEquals(first.effectiveClassification(), second.effectiveClassification());
    assertEquals(first.detectedCategories(), second.detectedCategories());
  }

  @Test
  void fixtureManifestMustCoverEveryCorpusFile() throws IOException {
    FixtureManifest manifest = readManifest();
    Set<String> declared =
        manifest.fixtures().stream()
            .map(FixtureEntry::file)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> expected =
        Set.of(
            "authorization-header.txt",
            "provider-usage-snapshot.json",
            "bundle-json-nested-credentials.json",
            "dotenv-secret.env",
            "env-dump.txt",
            "github-pat.txt",
            "github-token.txt",
            "idempotency-key-header.txt",
            "json-secret.json",
            "linear-api-key.txt",
            "metadata-spoofing.json",
            "pem-certificate-with-private-key.pem",
            "pem-ec-private-key.pem",
            "pem-pkcs8-private-key.pem",
            "pem-rsa-private-key.pem",
            // Story 3c-5 — the three AR10 project-credential adversarial fixtures.
            "project-credential-github-token.json",
            "project-credential-linear-token.json",
            "project-credential-opaque-under-key.json",
            // Story 3i-1 — the JIRA API-token project-credential adversarial fixture (Atlassian
            // tokens have no stable prefix, so the secret rides a SECRET_FIELD-covered key).
            "project-credential-jira-token.json",
            "query-secret.txt",
            "repo-context-readme-with-pem-and-path.md",
            "repo-context-tree-summary-with-secrets.json",
            "runner-bearer-http-debug-header.txt",
            "runner-claude-verbose-token-print.txt",
            "runner-codex-auth-header-echo.txt",
            "ssh-private-key.pem",
            "ssh-public-key.txt",
            "unix-local-path.txt",
            "windows-local-path.txt",
            "yaml-secret.yaml");

    assertEquals(expected, declared);
  }

  private FixtureManifest readManifest() throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing fixture manifest " + MANIFEST_RESOURCE);
      }
      return objectMapper.readValue(stream, FixtureManifest.class);
    }
  }

  private String readResource(String resource) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing fixture " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  record FixtureManifest(List<FixtureEntry> fixtures) {}

  record FixtureEntry(
      String file,
      String placeholder,
      String minimumClassification,
      List<String> forbiddenSnippets) {}
}
