package org.dradgo.application.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.domain.registry.DataClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SensitivePayloadAnalyzer {

  private static final Logger LOG = LoggerFactory.getLogger(SensitivePayloadAnalyzer.class);

  /**
   * Story 2.24 AC13(d) — single source of truth for secret-like JSON / YAML / env field names.
   * Drives {@link #JSON_SECRET_FIELD_PATTERN}, {@link #YAML_SECRET_FIELD_PATTERN}, {@link
   * #ENV_SECRET_VALUE_PATTERN}, and {@link #looksSecretLikeKey(String)} — kills three drift sites
   * at once.
   */
  static final Set<String> SECRET_FIELD_NAMES =
      Set.of(
          "secret",
          "token",
          "apiKey",
          "api_key",
          "accessToken",
          "access_token",
          "password",
          "credential",
          "linearApiKey",
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
          "auth_token");

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

  private static final Pattern ENVIRONMENT_BLOCK_PATTERN =
      Pattern.compile("(?ms)(Environment:\\R(?:[A-Z0-9_]+=.*(?:\\R|$))+)");
  // SSH-private-key pattern — strictly OpenSSH-format blocks (the only form
  // produced by `ssh-keygen` without `-m PEM`). Standalone RSA PEM blocks
  // are NOT covered here — they're handled by PEM_PRIVATE_KEY_PATTERN
  // below with the more accurate `PEM_PRIVATE_KEY` category. Code review
  // D8 (2026-05-26) split RSA out so operator-facing exports show the
  // taxonomically-correct placeholder.
  private static final Pattern PRIVATE_KEY_PATTERN =
      Pattern.compile(
          "(?s)-----BEGIN OPENSSH PRIVATE KEY-----.*?-----END OPENSSH PRIVATE KEY-----");

  /**
   * Story 2.24 AC13(a) — PEM private-key blocks. Covers {@code RSA PRIVATE KEY}, {@code EC PRIVATE
   * KEY}, {@code DSA PRIVATE KEY}, {@code ENCRYPTED PRIVATE KEY}, and the generic PKCS#8 {@code
   * PRIVATE KEY} block. The capture group pins the label so the matching END marker has to use the
   * same label (defends against malformed mixed-label payloads). Code review D8 added {@code RSA} —
   * previously RSA blocks were redacted by the SSH pattern with a misleading {@code
   * [REDACTED_SSH_PRIVATE_KEY]} placeholder.
   */
  private static final Pattern PEM_PRIVATE_KEY_PATTERN =
      Pattern.compile(
          "(?s)-----BEGIN (RSA PRIVATE KEY|EC PRIVATE KEY|DSA PRIVATE KEY|ENCRYPTED PRIVATE KEY|PRIVATE KEY)-----.*?-----END \\1-----");

  /**
   * Story 2.24 AC13(a) — one or more {@code CERTIFICATE} blocks paired with a sibling {@code
   * PRIVATE KEY} block (any label) in the same payload. Standalone {@code CERTIFICATE} blocks
   * remain visible — they're public material. The pairing detector redacts ALL certificates plus
   * the private key to avoid revealing operator/instance attribution via the cert subject. Code
   * review P4 (2026-05-26) made the cert prefix repeatable so real full-chain bundles (leaf +
   * intermediate + key) are caught — previously the intermediate broke the adjacency match and left
   * the leaf cert visible.
   */
  private static final Pattern PEM_CERTIFICATE_WITH_PRIVATE_KEY_PATTERN =
      Pattern.compile(
          "(?s)(?:-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----\\s*)+-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");

  private static final Pattern SSH_PUBLIC_KEY_PATTERN =
      Pattern.compile("(?m)\\bssh-(?:rsa|ed25519)\\s+[A-Za-z0-9+/=._-]+(?:\\s+\\S+)?");
  private static final Pattern AUTHORIZATION_HEADER_PATTERN =
      Pattern.compile("(?im)(Authorization\\s*:\\s*(?:Bearer|Basic)\\s+)([^\\s\\r\\n]+)");

  /**
   * Story 2.24 AC13(c) — {@code Idempotency-Key: ...} HTTP header in any logged or exported request
   * shape. Cross-reference: ProblemDetailsMapper already sanitizes idempotency keys in 4xx Problem
   * Details; this closes the gap for free-text logged request shapes.
   */
  // Matches the header literal anywhere — even mid-line. Production logs
  // emit the header AFTER a Logback timestamp/level prefix, so a line-start
  // anchor breaks `RedactingMessageConverter` (D5 was attempted with
  // `^(\\s*Idempotency-Key…)` and regressed the log-redaction surface;
  // reverted 2026-05-26 in the code-review P9 phase). Over-redaction of
  // prose mid-paragraph mentions ("when you set Idempotency-Key: my-id,
  // ...") is accepted — missing a real credential in a log line is a
  // strictly worse failure mode.
  private static final Pattern IDEMPOTENCY_KEY_HEADER_PATTERN =
      Pattern.compile("(?im)(Idempotency-Key\\s*:\\s*)([^\\s\\r\\n]+)");

  private static final Pattern GITHUB_PAT_PATTERN =
      Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b");
  private static final Pattern GITHUB_TOKEN_PATTERN = Pattern.compile("\\bghp_[A-Za-z0-9]{20,}\\b");
  private static final Pattern LINEAR_API_KEY_PATTERN =
      Pattern.compile("\\blin_api_[A-Za-z0-9]{20,}\\b");
  private static final Pattern QUERY_SECRET_PATTERN =
      Pattern.compile("([?&](?:token|apikey|access_token)=)([^&#\\s]+)");

  /**
   * Story 2.24 AC13(b)/(d) — JSON field-name allowlist derived from {@link #SECRET_FIELD_NAMES}.
   * Single source of truth; the runner-contracts redaction-policy.json mirrors {@code
   * SECRET_FIELD_NAMES} for the frontend filter (Trap T6 — backend stays the implementation source
   * of truth, JSON mirrors Java).
   */
  private static final Pattern JSON_SECRET_FIELD_PATTERN =
      Pattern.compile(
          "(?i)(\"(?:secret|token|apiKey|api_key|accessToken|access_token|password|credential|linearApiKey|bearer|private|private_key|privateKey|refresh_token|refreshToken|client_secret|clientSecret|sessionToken|authToken|auth_token)\"\\s*:\\s*\")([^\"]+)(\")");

  private static final Pattern YAML_SECRET_FIELD_PATTERN =
      Pattern.compile(
          "(?im)(\\b(?:secret|token|api_key|access_token|password|credential|bearer|private|private_key|refresh_token|client_secret|session_token|auth_token)\\b\\s*:\\s*)([^\\s\\r\\n]+)");
  private static final Pattern ENV_SECRET_VALUE_PATTERN =
      Pattern.compile(
          "(?im)^([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|CREDENTIAL|API_KEY|ACCESS_KEY|PRIVATE_KEY|BEARER|REFRESH_TOKEN|CLIENT_SECRET|SESSION_TOKEN|AUTH_TOKEN)[A-Z0-9_]*=)(.+)$");
  private static final Pattern WINDOWS_LOCAL_PATH_PATTERN =
      Pattern.compile("(?i)\\b[A-Z]:\\\\Users\\\\[^\\\\\\r\\n]+\\\\[^\\r\\n]*");
  private static final Pattern UNIX_LOCAL_PATH_PATTERN =
      Pattern.compile("(?:(?:/Users)|(?:/home))/[^/\\s]+/[^\\s]*");

  SensitiveAnalysis analyzeText(String rawPayload, String claimedClassificationValue) {
    DataClassification claimedClassification =
        parseClaimedClassification(claimedClassificationValue);
    String sanitized = rawPayload;
    Set<RedactionCategory> detected = EnumSet.noneOf(RedactionCategory.class);

    List<ReplacementRule> rules =
        List.of(
            new ReplacementRule(
                ENVIRONMENT_BLOCK_PATTERN,
                RedactionCategory.ENVIRONMENT_BLOCK,
                ignored -> RedactionCategory.ENVIRONMENT_BLOCK.placeholder()),
            // Paired certificate + private key MUST run BEFORE the standalone PEM
            // private-key pattern — otherwise the private-key rule would match
            // first and leave the certificate visible.
            new ReplacementRule(
                PEM_CERTIFICATE_WITH_PRIVATE_KEY_PATTERN,
                RedactionCategory.PEM_CERTIFICATE_WITH_PRIVATE_KEY,
                ignored -> RedactionCategory.PEM_CERTIFICATE_WITH_PRIVATE_KEY.placeholder()),
            new ReplacementRule(
                PRIVATE_KEY_PATTERN,
                RedactionCategory.SSH_PRIVATE_KEY,
                ignored -> RedactionCategory.SSH_PRIVATE_KEY.placeholder()),
            new ReplacementRule(
                PEM_PRIVATE_KEY_PATTERN,
                RedactionCategory.PEM_PRIVATE_KEY,
                ignored -> RedactionCategory.PEM_PRIVATE_KEY.placeholder()),
            new ReplacementRule(
                SSH_PUBLIC_KEY_PATTERN,
                RedactionCategory.SSH_PUBLIC_KEY,
                ignored -> RedactionCategory.SSH_PUBLIC_KEY.placeholder()),
            new ReplacementRule(
                AUTHORIZATION_HEADER_PATTERN,
                RedactionCategory.AUTHORIZATION_HEADER,
                matcher -> matcher.group(1) + RedactionCategory.AUTHORIZATION_HEADER.placeholder()),
            new ReplacementRule(
                IDEMPOTENCY_KEY_HEADER_PATTERN,
                RedactionCategory.IDEMPOTENCY_KEY,
                matcher -> matcher.group(1) + RedactionCategory.IDEMPOTENCY_KEY.placeholder()),
            new ReplacementRule(
                GITHUB_PAT_PATTERN,
                RedactionCategory.GITHUB_TOKEN,
                ignored -> RedactionCategory.GITHUB_TOKEN.placeholder()),
            new ReplacementRule(
                GITHUB_TOKEN_PATTERN,
                RedactionCategory.GITHUB_TOKEN,
                ignored -> RedactionCategory.GITHUB_TOKEN.placeholder()),
            new ReplacementRule(
                LINEAR_API_KEY_PATTERN,
                RedactionCategory.LINEAR_API_KEY,
                ignored -> RedactionCategory.LINEAR_API_KEY.placeholder()),
            new ReplacementRule(
                QUERY_SECRET_PATTERN,
                RedactionCategory.QUERY_SECRET,
                matcher ->
                    alreadyRedacted(matcher.group(2))
                        ? matcher.group(0)
                        : matcher.group(1) + RedactionCategory.QUERY_SECRET.placeholder()),
            new ReplacementRule(
                JSON_SECRET_FIELD_PATTERN,
                RedactionCategory.SECRET_FIELD,
                matcher ->
                    alreadyRedacted(matcher.group(2))
                        ? matcher.group(0)
                        : matcher.group(1)
                            + RedactionCategory.SECRET_FIELD.placeholder()
                            + matcher.group(3)),
            new ReplacementRule(
                YAML_SECRET_FIELD_PATTERN,
                RedactionCategory.SECRET_FIELD,
                matcher ->
                    alreadyRedacted(matcher.group(2))
                        ? matcher.group(0)
                        : matcher.group(1) + RedactionCategory.SECRET_FIELD.placeholder()),
            new ReplacementRule(
                ENV_SECRET_VALUE_PATTERN,
                RedactionCategory.ENV_VALUE,
                matcher ->
                    alreadyRedacted(matcher.group(2))
                        ? matcher.group(0)
                        : matcher.group(1) + RedactionCategory.ENV_VALUE.placeholder()),
            new ReplacementRule(
                WINDOWS_LOCAL_PATH_PATTERN,
                RedactionCategory.LOCAL_PATH,
                ignored -> RedactionCategory.LOCAL_PATH.placeholder()),
            new ReplacementRule(
                UNIX_LOCAL_PATH_PATTERN,
                RedactionCategory.LOCAL_PATH,
                ignored -> RedactionCategory.LOCAL_PATH.placeholder()));

    for (ReplacementRule rule : rules) {
      ReplacementOutcome outcome = applyRule(sanitized, rule);
      sanitized = outcome.sanitized();
      if (outcome.matched()) {
        detected.add(rule.category());
      }
    }

    if (!detected.contains(RedactionCategory.ENV_VALUE)) {
      ReplacementOutcome heuristicOutcome = applySecretLikeEntropyHeuristic(sanitized);
      sanitized = heuristicOutcome.sanitized();
      if (heuristicOutcome.matched()) {
        detected.add(RedactionCategory.ENV_VALUE);
      }
    }

    DataClassification effectiveClassification =
        determineEffectiveClassification(claimedClassification, detected.isEmpty());
    // Story 2.24 AC18 — count-only DEBUG line, never the matched text or field
    // names. Production INFO/WARN/ERROR stays silent (no signal to attackers
    // that redaction fired); MDC carries correlationId/workflowRunId from the
    // calling site via the existing Logback layout.
    if (LOG.isDebugEnabled() && !detected.isEmpty()) {
      LOG.debug("redaction applied categories={} count={}", detected.size(), detected.size());
    }
    return new SensitiveAnalysis(
        rawPayload, sanitized, claimedClassification, effectiveClassification, detected);
  }

  SensitiveAnalysis analyzeStructured(Object payload, String claimedClassificationValue) {
    return analyzeStructured(payload, claimedClassificationValue, Set.of());
  }

  /**
   * Structural (JSON-tree) analysis with a caller-nominated set of categories suppressed at the
   * VALUE level. A category in {@code valueLevelSuppressed} that a string VALUE would trip is
   * dropped before it counts as a finding — so security-conscious PROSE inside a JSON string
   * ("…without password:", "password=&lt;invalid&gt;") is no longer misread by the fuzzy
   * YAML/env/SECRET_FIELD heuristics as a credential (the {@code RunnerSecretScanService}
   * false-positive fix). The suppression is scoped to string VALUES only: the secret-named-KEY
   * check below still fires (a real {@code "password":"…"} field remains a leak), and precise
   * credential shapes (tokens, PEM/SSH keys, auth headers) are never placed in the suppressed set
   * by the caller, so they stay leak-worthy in any context.
   */
  SensitiveAnalysis analyzeStructured(
      Object payload,
      String claimedClassificationValue,
      Set<RedactionCategory> valueLevelSuppressed) {
    try {
      JsonNode node =
          payload instanceof JsonNode jsonNode ? jsonNode : OBJECT_MAPPER.valueToTree(payload);
      DataClassification claimedClassification =
          parseClaimedClassification(claimedClassificationValue);
      Set<RedactionCategory> detected = EnumSet.noneOf(RedactionCategory.class);
      JsonNode sanitizedJson = sanitizeJsonNode(node, null, detected, valueLevelSuppressed);
      String sanitizedText = OBJECT_MAPPER.writeValueAsString(sanitizedJson);
      DataClassification effectiveClassification =
          determineEffectiveClassification(claimedClassification, detected.isEmpty());
      return new SensitiveAnalysis(
          OBJECT_MAPPER.writeValueAsString(node),
          sanitizedText,
          claimedClassification,
          effectiveClassification,
          detected,
          sanitizedJson);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Failed to normalize structured payload for redaction", exception);
    }
  }

  private DataClassification parseClaimedClassification(String claimedClassificationValue) {
    if (claimedClassificationValue == null) {
      return null;
    }
    return DataClassification.fromValue(
        claimedClassificationValue, "security.claimedClassification");
  }

  private DataClassification determineEffectiveClassification(
      DataClassification claimedClassification, boolean noSensitiveFindings) {
    if (!noSensitiveFindings) {
      return DataClassification.SHAREABLE_REDACTED;
    }
    if (claimedClassification != null) {
      return claimedClassification;
    }
    return DataClassification.SHAREABLE_FULL;
  }

  private ReplacementOutcome applyRule(String source, ReplacementRule rule) {
    Matcher matcher = rule.pattern().matcher(source);
    if (!matcher.find()) {
      return new ReplacementOutcome(source, false);
    }

    StringBuffer buffer = new StringBuffer();
    do {
      String replacement = Matcher.quoteReplacement(rule.replacement().apply(matcher));
      matcher.appendReplacement(buffer, replacement);
    } while (matcher.find());
    matcher.appendTail(buffer);
    return new ReplacementOutcome(buffer.toString(), true);
  }

  private ReplacementOutcome applySecretLikeEntropyHeuristic(String source) {
    List<String> lines = source.lines().toList();
    boolean matched = false;
    List<String> sanitizedLines = new ArrayList<>(lines.size());
    for (String line : lines) {
      int separatorIndex = line.indexOf('=');
      if (separatorIndex > 0) {
        String key = line.substring(0, separatorIndex);
        String value = line.substring(separatorIndex + 1);
        if (!alreadyRedacted(value) && looksSecretLikeKey(key) && looksHighEntropy(value)) {
          sanitizedLines.add(key + "=" + RedactionCategory.ENV_VALUE.placeholder());
          matched = true;
          continue;
        }
      }
      sanitizedLines.add(line);
    }
    return new ReplacementOutcome(String.join("\n", sanitizedLines), matched);
  }

  private boolean looksSecretLikeKey(String key) {
    String normalized = key.toLowerCase(java.util.Locale.ROOT);
    // Substring heuristic catches composite env-var names like
    // `DATABASE_PASSWORD` or `AWS_SECRET_ACCESS_KEY`. Tightened during
    // code-review D4 (2026-05-26): the original `contains("private")` and
    // `endsWith("key")` rules produced false positives on `privateNotes` /
    // `privateRoomId` / `monkey` / `donkey` and silently over-redacted
    // legitimate UI content. Those two rules are now removed; substring
    // coverage for the remaining unambiguous secret tokens is preserved.
    if (normalized.contains("secret")
        || normalized.contains("token")
        || normalized.contains("password")
        || normalized.contains("credential")
        || normalized.contains("api_key")
        || normalized.contains("bearer")) {
      return true;
    }
    // Exact-set fallback catches canonical names not picked up by the
    // substring pass (e.g., `privateKey`, `private_key`, `clientSecret`).
    // SECRET_FIELD_NAMES remains the single auditable source of truth for
    // canonical secret field names (Trap T6).
    for (String canonical : SECRET_FIELD_NAMES) {
      if (canonical.toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
        return true;
      }
    }
    return false;
  }

  private boolean looksHighEntropy(String value) {
    String trimmed = value.trim();
    if (trimmed.length() < 16) {
      return false;
    }
    Set<Character> distinct = new LinkedHashSet<>();
    for (int i = 0; i < trimmed.length(); i++) {
      char current = trimmed.charAt(i);
      if (Character.isLetterOrDigit(current)
          || current == '-'
          || current == '_'
          || current == '+'
          || current == '/'
          || current == '=') {
        distinct.add(current);
      }
    }
    return distinct.size() >= 12;
  }

  private JsonNode sanitizeJsonNode(
      JsonNode node,
      String fieldName,
      Set<RedactionCategory> detected,
      Set<RedactionCategory> valueLevelSuppressed) {
    if (node.isObject()) {
      ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
      node.fields()
          .forEachRemaining(
              entry ->
                  objectNode.set(
                      entry.getKey(),
                      sanitizeJsonNode(
                          entry.getValue(), entry.getKey(), detected, valueLevelSuppressed)));
      return objectNode;
    }
    if (node.isArray()) {
      ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
      for (JsonNode child : node) {
        arrayNode.add(sanitizeJsonNode(child, fieldName, detected, valueLevelSuppressed));
      }
      return arrayNode;
    }
    if (!node.isTextual()) {
      return node.deepCopy();
    }

    String value = node.textValue();
    SensitiveAnalysis analysis = analyzeText(value, null);
    // Value-level suppression (see analyzeStructured): a caller may pardon the fuzzy heuristic
    // categories on string VALUES so PROSE that merely mentions a secret field name is not treated
    // as a leak. Precise shapes are never in the suppressed set; the secret-named-KEY check below
    // is unaffected.
    Set<RedactionCategory> valueCategories = analysis.detectedCategories();
    if (!valueLevelSuppressed.isEmpty() && !valueCategories.isEmpty()) {
      EnumSet<RedactionCategory> retained = EnumSet.noneOf(RedactionCategory.class);
      retained.addAll(valueCategories);
      retained.removeAll(valueLevelSuppressed);
      valueCategories = retained;
    }
    if (!valueCategories.isEmpty()) {
      detected.addAll(valueCategories);
      return TextNode.valueOf(analysis.sanitizedText());
    }
    if (fieldName != null && looksSecretLikeKey(fieldName) && !alreadyRedacted(value)) {
      detected.add(RedactionCategory.SECRET_FIELD);
      return TextNode.valueOf(RedactionCategory.SECRET_FIELD.placeholder());
    }
    return TextNode.valueOf(value);
  }

  private boolean alreadyRedacted(String value) {
    return value.contains("[REDACTED_");
  }

  record SensitiveAnalysis(
      String originalText,
      String sanitizedText,
      DataClassification claimedClassification,
      DataClassification effectiveClassification,
      Set<RedactionCategory> detectedCategories,
      JsonNode sanitizedJson) {
    SensitiveAnalysis(
        String originalText,
        String sanitizedText,
        DataClassification claimedClassification,
        DataClassification effectiveClassification,
        Set<RedactionCategory> detectedCategories) {
      this(
          originalText,
          sanitizedText,
          claimedClassification,
          effectiveClassification,
          Set.copyOf(detectedCategories),
          null);
    }

    boolean redacted() {
      return !detectedCategories.isEmpty();
    }

    SensitiveAnalysis withSanitizedJson(JsonNode jsonNode) {
      return new SensitiveAnalysis(
          originalText,
          sanitizedText,
          claimedClassification,
          effectiveClassification,
          detectedCategories,
          jsonNode);
    }
  }

  private record ReplacementRule(
      Pattern pattern, RedactionCategory category, Function<Matcher, String> replacement) {}

  private record ReplacementOutcome(String sanitized, boolean matched) {}
}
