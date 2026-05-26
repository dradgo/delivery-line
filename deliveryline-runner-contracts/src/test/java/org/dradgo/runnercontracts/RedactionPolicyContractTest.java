package org.dradgo.runnercontracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 2.24 AC16 — schema-side contract test for the canonical {@code
 * runner-contracts/redaction-policy.json}. Asserts the file parses against {@code
 * schemas/redaction-policy.schema.json} and every declared regex compiles. The backend-side parity
 * test (asserting every {@code RedactionCategory} placeholder appears in the spec + {@code
 * SECRET_FIELD_NAMES} parity) lives in {@code deliveryline-backend} since runner-contracts must not
 * depend on backend code.
 *
 * <p>Tagged {@code contract} so the existing {@code export-redaction-verify} CI tier picks it up.
 */
@Tag("contract")
class RedactionPolicyContractTest {

  private static final Path POLICY =
      Path.of("src/main/resources/runner-contracts/redaction-policy.json");
  private static final Path SCHEMA =
      Path.of("src/main/resources/schemas/redaction-policy.schema.json");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void policyValidatesAgainstSchema() throws IOException {
    assertTrue(Files.isRegularFile(POLICY), "missing policy file " + POLICY.toAbsolutePath());
    assertTrue(Files.isRegularFile(SCHEMA), "missing schema file " + SCHEMA.toAbsolutePath());

    String policyJson = new String(Files.readAllBytes(POLICY), StandardCharsets.UTF_8);
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema schema =
        registry.getSchema(new String(Files.readAllBytes(SCHEMA), StandardCharsets.UTF_8));
    Result result = schema.walk(policyJson, InputFormat.JSON, true);
    List<Error> errors = result.getErrors();
    assertTrue(errors.isEmpty(), () -> "redaction-policy.json failed schema validation: " + errors);
  }

  @Test
  void everyDeclaredRegexCompiles() throws IOException {
    JsonNode policy = MAPPER.readTree(POLICY.toFile());
    JsonNode patterns = policy.path("patterns");
    assertFalse(patterns.isEmpty(), "patterns array empty");
    for (JsonNode pattern : patterns) {
      String regex = pattern.path("regex").asText();
      try {
        java.util.regex.Pattern.compile(regex);
      } catch (PatternSyntaxException ex) {
        throw new AssertionError(
            "Pattern for category="
                + pattern.path("category").asText()
                + " does not compile in Java: "
                + ex.getMessage(),
            ex);
      }
    }
  }

  @Test
  void secretFieldNamesContainTheStoryTwoTwentyFourExpansions() throws IOException {
    // Story 2.24 AC13(b) — closes the F19 gap by expanding the field-name
    // allowlist. The backend-side parity test pins the FULL set vs Java; this
    // module-local test pins the minimum required additions so an accidental
    // shrink in this module is caught without spinning up backend deps.
    JsonNode policy = MAPPER.readTree(POLICY.toFile());
    Set<String> declared = new LinkedHashSet<>();
    for (JsonNode name : policy.path("secretFieldNames")) {
      declared.add(name.asText());
    }
    Set<String> required =
        new HashSet<>(
            java.util.List.of(
                "bearer",
                "private",
                "private_key",
                "privateKey",
                "refresh_token",
                "refreshToken",
                "client_secret",
                "clientSecret",
                "sessionToken",
                "authToken",
                "auth_token"));
    required.removeAll(declared);
    assertTrue(required.isEmpty(), () -> "redaction-policy.json missing field names: " + required);
  }

  @Test
  void everyCategoryUsesCanonicalPlaceholderFormat() throws IOException {
    JsonNode policy = MAPPER.readTree(POLICY.toFile());
    for (JsonNode pattern : policy.path("patterns")) {
      String category = pattern.path("category").asText();
      String placeholder = pattern.path("placeholder").asText();
      String expected = "[REDACTED_" + category + "]";
      assertEquals(
          expected,
          placeholder,
          () ->
              "category "
                  + category
                  + " has non-canonical placeholder "
                  + placeholder
                  + " — expected "
                  + expected);
    }
  }
}
