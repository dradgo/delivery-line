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

final class SensitivePayloadAnalyzer {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

  private static final Pattern ENVIRONMENT_BLOCK_PATTERN =
      Pattern.compile("(?ms)(Environment:\\R(?:[A-Z0-9_]+=.*(?:\\R|$))+)");
  private static final Pattern PRIVATE_KEY_PATTERN =
      Pattern.compile(
          "(?s)-----BEGIN (?:OPENSSH|RSA) PRIVATE KEY-----.*?-----END (?:OPENSSH|RSA) PRIVATE KEY-----");
  private static final Pattern SSH_PUBLIC_KEY_PATTERN =
      Pattern.compile("(?m)\\bssh-(?:rsa|ed25519)\\s+[A-Za-z0-9+/=._-]+(?:\\s+\\S+)?");
  private static final Pattern AUTHORIZATION_HEADER_PATTERN =
      Pattern.compile("(?im)(Authorization\\s*:\\s*(?:Bearer|Basic)\\s+)([^\\s\\r\\n]+)");
  private static final Pattern GITHUB_PAT_PATTERN =
      Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b");
  private static final Pattern GITHUB_TOKEN_PATTERN = Pattern.compile("\\bghp_[A-Za-z0-9]{20,}\\b");
  private static final Pattern LINEAR_API_KEY_PATTERN =
      Pattern.compile("\\blin_api_[A-Za-z0-9]{20,}\\b");
  private static final Pattern QUERY_SECRET_PATTERN =
      Pattern.compile("([?&](?:token|apikey|access_token)=)([^&#\\s]+)");
  private static final Pattern JSON_SECRET_FIELD_PATTERN =
      Pattern.compile(
          "(?i)(\"(?:secret|token|apiKey|api_key|accessToken|access_token|password|credential|linearApiKey)\"\\s*:\\s*\")([^\"]+)(\")");
  private static final Pattern YAML_SECRET_FIELD_PATTERN =
      Pattern.compile(
          "(?im)(\\b(?:secret|token|api_key|access_token|password|credential)\\b\\s*:\\s*)([^\\s\\r\\n]+)");
  private static final Pattern ENV_SECRET_VALUE_PATTERN =
      Pattern.compile(
          "(?im)^([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|CREDENTIAL|API_KEY|ACCESS_KEY|PRIVATE_KEY)[A-Z0-9_]*=)(.+)$");
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
            new ReplacementRule(
                PRIVATE_KEY_PATTERN,
                RedactionCategory.SSH_PRIVATE_KEY,
                ignored -> RedactionCategory.SSH_PRIVATE_KEY.placeholder()),
            new ReplacementRule(
                SSH_PUBLIC_KEY_PATTERN,
                RedactionCategory.SSH_PUBLIC_KEY,
                ignored -> RedactionCategory.SSH_PUBLIC_KEY.placeholder()),
            new ReplacementRule(
                AUTHORIZATION_HEADER_PATTERN,
                RedactionCategory.AUTHORIZATION_HEADER,
                matcher -> matcher.group(1) + RedactionCategory.AUTHORIZATION_HEADER.placeholder()),
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
    return new SensitiveAnalysis(
        rawPayload, sanitized, claimedClassification, effectiveClassification, detected);
  }

  SensitiveAnalysis analyzeStructured(Object payload, String claimedClassificationValue) {
    try {
      JsonNode node =
          payload instanceof JsonNode jsonNode ? jsonNode : OBJECT_MAPPER.valueToTree(payload);
      DataClassification claimedClassification =
          parseClaimedClassification(claimedClassificationValue);
      Set<RedactionCategory> detected = EnumSet.noneOf(RedactionCategory.class);
      JsonNode sanitizedJson = sanitizeJsonNode(node, null, detected);
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
    return normalized.contains("secret")
        || normalized.contains("token")
        || normalized.contains("password")
        || normalized.contains("credential")
        || normalized.endsWith("key")
        || normalized.contains("api_key");
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
      JsonNode node, String fieldName, Set<RedactionCategory> detected) {
    if (node.isObject()) {
      ObjectNode objectNode = OBJECT_MAPPER.createObjectNode();
      node.fields()
          .forEachRemaining(
              entry ->
                  objectNode.set(
                      entry.getKey(),
                      sanitizeJsonNode(entry.getValue(), entry.getKey(), detected)));
      return objectNode;
    }
    if (node.isArray()) {
      ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
      for (JsonNode child : node) {
        arrayNode.add(sanitizeJsonNode(child, fieldName, detected));
      }
      return arrayNode;
    }
    if (!node.isTextual()) {
      return node.deepCopy();
    }

    String value = node.textValue();
    SensitiveAnalysis analysis = analyzeText(value, null);
    detected.addAll(analysis.detectedCategories());
    if (!analysis.detectedCategories().isEmpty()) {
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
