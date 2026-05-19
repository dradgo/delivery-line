package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Foundation contract #9 — {@link RedactionPolicyService} strips every adversarial secret declared
 * by the test corpus at {@code deliveryline-backend/src/test/resources/redaction-fixtures/}.
 *
 * <p>The contract reads the existing {@code fixtures-manifest.json} (story 1.10 convention) rather
 * than introducing the parallel {@code .secrets} sidecar format named by story 1.23 AC2 #9. Per
 * Open Clarification #3 ("use the existing convention"), the manifest is the source of truth for
 * forbidden snippets.
 *
 * <p>Silent-fixture invariant: every {@code .txt}/{@code .json}/{@code .env}/{@code .pem}/{@code .yaml}
 * file under {@code redaction-fixtures/} (except the manifest and README) MUST be enumerated in
 * the manifest. Drop-in fixtures without a declared forbidden-snippet set hide contract drift.
 */
@Tag("foundation-gate")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"test", "linear-mock"})
class RedactionAdversarialFoundationContract {

  private static final Path FIXTURE_ROOT =
      Path.of("src", "test", "resources", "redaction-fixtures");
  private static final Path MANIFEST = FIXTURE_ROOT.resolve("fixtures-manifest.json");
  private static final Set<String> NON_FIXTURE_FILES = Set.of("README.md", "fixtures-manifest.json");

  @Autowired private RedactionPolicyService redactionPolicyService;

  @Test
  void everyAdversarialFixtureStripsItsDeclaredForbiddenSnippets() throws IOException {
    if (!Files.isDirectory(FIXTURE_ROOT)) {
      fail(
          FoundationGateAssertions.tagged(
              "1.10",
              "redaction-fixtures directory missing at " + FIXTURE_ROOT.toAbsolutePath()));
      return;
    }
    if (!Files.isRegularFile(MANIFEST)) {
      fail(
          FoundationGateAssertions.tagged(
              "1.10",
              "fixtures-manifest.json missing at " + MANIFEST.toAbsolutePath()));
      return;
    }

    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest = mapper.readTree(MANIFEST.toFile());
    JsonNode entries = manifest.path("fixtures");
    if (!entries.isArray() || entries.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.10", "fixtures-manifest.json fixtures array is missing or empty"));
      return;
    }

    List<String> violations = new ArrayList<>();
    Set<String> manifestFilenames = new LinkedHashSet<>();
    for (JsonNode entry : entries) {
      String filename = entry.path("file").asText("");
      String claimedClassification = entry.path("minimumClassification").asText("shareable-redacted");
      JsonNode forbiddenSnippetsNode = entry.path("forbiddenSnippets");
      if (filename.isEmpty()) {
        violations.add("manifest entry missing 'file' field: " + entry);
        continue;
      }
      manifestFilenames.add(filename);
      if (!forbiddenSnippetsNode.isArray() || forbiddenSnippetsNode.isEmpty()) {
        violations.add(
            "manifest entry for "
                + filename
                + " has empty forbiddenSnippets — silent fixture hides contract drift");
        continue;
      }
      Path fixture = FIXTURE_ROOT.resolve(filename);
      if (!Files.isRegularFile(fixture)) {
        violations.add("manifest references missing fixture file: " + fixture);
        continue;
      }
      String payload;
      try {
        payload = readFixtureAsUtf8WithReplacement(fixture);
      } catch (IOException ioException) {
        violations.add(
            FoundationGateAssertions.tagged(
                "1.10",
                "could not read fixture "
                    + filename
                    + " as UTF-8 (even with replacement) — "
                    + ioException.getClass().getSimpleName()
                    + ": "
                    + ioException.getMessage()));
        continue;
      }
      RedactionResult result = redactionPolicyService.redact(payload, claimedClassification);
      String sanitized = result.sanitizedText();
      if (sanitized == null) {
        violations.add("fixture " + filename + " produced null sanitizedText");
        continue;
      }
      for (JsonNode snippetNode : forbiddenSnippetsNode) {
        String forbidden = snippetNode.asText("");
        if (forbidden.isEmpty()) {
          continue;
        }
        if (sanitized.contains(forbidden)) {
          violations.add(
              "fixture "
                  + filename
                  + " leaked forbidden snippet '"
                  + previewSnippet(forbidden)
                  + "' in sanitized output");
        }
      }
    }

    // Silent-fixture invariant: every fixture file in the directory must appear in the manifest.
    try (Stream<Path> stream = Files.list(FIXTURE_ROOT)) {
      stream
          .filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .filter(name -> !NON_FIXTURE_FILES.contains(name))
          .filter(name -> !manifestFilenames.contains(name))
          .forEach(
              name ->
                  violations.add(
                      "fixture file "
                          + name
                          + " is not enumerated in fixtures-manifest.json — silent fixtures"
                          + " hide contract drift"));
    }

    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.10",
              "RedactionPolicyService adversarial sweep failed ("
                  + violations.size()
                  + " issue"
                  + (violations.size() == 1 ? "" : "s")
                  + "): "
                  + String.join("; ", violations)));
    }
  }

  /**
   * Reads a fixture as UTF-8, but tolerates non-UTF-8 bytes (e.g. binary {@code .pem} payloads)
   * by replacing them with the Unicode replacement character. The redaction sweep still surfaces
   * any embedded ASCII secrets without {@link Files#readString} throwing
   * {@code CharacterCodingException} mid-fixture and burying the real contract assertion.
   */
  private static String readFixtureAsUtf8WithReplacement(Path fixture) throws IOException {
    byte[] raw = Files.readAllBytes(fixture);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(java.nio.ByteBuffer.wrap(raw))
        .toString();
  }

  private static String previewSnippet(String snippet) {
    // Avoid leaking the full forbidden text to the test log (defense-in-depth even though these
    // are fixture-only synthetic secrets).
    if (snippet.length() <= 12) {
      return snippet.substring(0, Math.min(snippet.length(), 4)) + "***";
    }
    return snippet.substring(0, 6) + "***" + snippet.substring(snippet.length() - 4);
  }
}
