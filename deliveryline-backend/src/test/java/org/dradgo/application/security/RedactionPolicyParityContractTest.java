package org.dradgo.application.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 2.24 AC16 — parity contract between {@link SensitivePayloadAnalyzer} (the implementation
 * source of truth, Trap T6) and the canonical {@code runner-contracts/redaction-policy.json}
 * consumed by the frontend defense-in-depth filter.
 *
 * <p>This test asserts the JSON mirrors the Java in the directions the runner-contracts module test
 * cannot enforce (cross-module compile-time coupling would create a dependency cycle): every {@link
 * RedactionCategory} placeholder referenced by the policy must match one of the enum constants, and
 * the {@code secretFieldNames} array equals {@link SensitivePayloadAnalyzer#SECRET_FIELD_NAMES}.
 *
 * <p>Tagged {@code contract} so the existing {@code export-redaction-verify} CI tier picks it up.
 */
@Tag("contract")
class RedactionPolicyParityContractTest {

  private static final String POLICY_RESOURCE = "runner-contracts/redaction-policy.json";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void everyPolicyPlaceholderIsAKnownRedactionCategory() throws IOException {
    JsonNode policy = loadPolicy();
    Set<String> knownPlaceholders = new LinkedHashSet<>();
    for (RedactionCategory category : EnumSet.allOf(RedactionCategory.class)) {
      knownPlaceholders.add(category.placeholder());
    }
    for (JsonNode pattern : policy.path("patterns")) {
      String placeholder = pattern.path("placeholder").asText();
      assertTrue(
          knownPlaceholders.contains(placeholder),
          () ->
              "redaction-policy.json references placeholder "
                  + placeholder
                  + " that is NOT in RedactionCategory enum — backend is the SoT, JSON must mirror");
    }
  }

  @Test
  void everyRedactionCategoryAppearsInPolicy() throws IOException {
    // Story 2.24 AC16(b) — bidirectional parity. Code review P10 (2026-05-26)
    // made this dynamic: every category the backend enum declares MUST be
    // declared in the JSON mirror. Previously the test pinned a hand-listed
    // subset, so adding a new RedactionCategory enum constant could silently
    // bypass the frontend defense-in-depth surface.
    //
    // Carve-out: categories that are legitimately backend-only because the
    // frontend defense-in-depth surface (markdown artifact rendering) does
    // not need them. Adding a new RedactionCategory enum constant outside
    // this carve-out forces an explicit JSON entry — this is the property
    // P10 (2026-05-26) introduced; the previous hand-listed required set
    // was easy to forget to update.
    //
    // - SECRET_FIELD: emitted by structural JSON traversal
    //   (`sanitizeJsonNode`) via key-name heuristics, not a regex. The
    //   frontend doesn't traverse arbitrary JSON; this category has no
    //   meaningful frontend analog.
    // - SSH_PUBLIC_KEY: public material; redacted to defend operator
    //   attribution, not because the contents are secret. Not yet wired on
    //   the frontend.
    // - QUERY_SECRET / ENV_VALUE / LOCAL_PATH / ENVIRONMENT_BLOCK: backlog
    //   for a follow-up story; deferred from 2.24's F19/F20 closure scope.
    //   When wired, remove from this set and add a JSON entry.
    Set<RedactionCategory> backendOnly =
        EnumSet.of(
            RedactionCategory.SECRET_FIELD,
            RedactionCategory.SSH_PUBLIC_KEY,
            RedactionCategory.QUERY_SECRET,
            RedactionCategory.ENV_VALUE,
            RedactionCategory.LOCAL_PATH,
            RedactionCategory.ENVIRONMENT_BLOCK);
    Set<RedactionCategory> regexBacked = EnumSet.allOf(RedactionCategory.class);
    regexBacked.removeAll(backendOnly);

    JsonNode policy = loadPolicy();
    Set<String> declaredCategories = new LinkedHashSet<>();
    for (JsonNode pattern : policy.path("patterns")) {
      declaredCategories.add(pattern.path("category").asText());
    }
    for (RedactionCategory category : regexBacked) {
      assertTrue(
          declaredCategories.contains(category.name()),
          () ->
              "redaction-policy.json missing category "
                  + category.name()
                  + " — every RedactionCategory enum value MUST have a JSON pattern "
                  + "for the frontend defense-in-depth filter (Trap T6, AC16). "
                  + "If this category is intentionally backend-only, add it to the "
                  + "carve-out list in everyRedactionCategoryAppearsInPolicy.");
    }
  }

  @Test
  void secretFieldNamesEqualsAnalyzerConstant() throws IOException {
    JsonNode policy = loadPolicy();
    Set<String> declared = new LinkedHashSet<>();
    for (JsonNode name : policy.path("secretFieldNames")) {
      declared.add(name.asText());
    }
    assertEquals(
        new LinkedHashSet<>(SensitivePayloadAnalyzer.SECRET_FIELD_NAMES),
        declared,
        "redaction-policy.json secretFieldNames must equal SensitivePayloadAnalyzer.SECRET_FIELD_NAMES — JSON mirrors Java, Trap T6");
  }

  private JsonNode loadPolicy() throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(POLICY_RESOURCE)) {
      assertNotNull(stream, () -> "missing classpath resource " + POLICY_RESOURCE);
      return objectMapper.readTree(stream);
    }
  }
}
