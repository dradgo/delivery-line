package org.dradgo.application.workflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;

/**
 * Story 3.16 (AC8 / Decision D4) — the single source of truth for the Linear completion-sync
 * summary-template placeholder grammar, shared by the startup validator ({@code
 * LinearCompletionSyncConfiguration}) and the renderer ({@code
 * WorkflowOrchestrationService.syncCompletionToLinear}) so the two can never drift.
 *
 * <p>A template is a free-text string carrying {@code {placeholder}} tokens. Validity (D4):
 *
 * <ul>
 *   <li>every {@code {token}} present MUST be a member of {@link #KNOWN_PLACEHOLDERS} (an unknown
 *       token is a config typo that would render literally), and
 *   <li>every member of {@link #REQUIRED_PLACEHOLDERS} MUST be present (a summary with no {@code
 *       {runId}} cannot identify the run).
 * </ul>
 *
 * <p>{@link #validate(String)} throws {@code DomainException(INVALID_COMPLETION_TEMPLATE)} on a
 * violation; it is invoked at startup (when completion-sync is enabled) so a malformed template
 * fails context boot rather than silently posting a broken comment. {@link #render(String, Map)}
 * substitutes the resolved values; an absent value renders as its caller-supplied fallback, never a
 * literal {@code {token}} (defensive resolution, Decision D5).
 */
public final class LinearCompletionTemplate {

  /** Known placeholders (D4) — the full documented vocabulary. */
  public static final Set<String> KNOWN_PLACEHOLDERS =
      Set.of(
          "runId",
          "prUrl",
          "specSummary",
          "specVersion",
          "pmReviewer",
          "devReviewer",
          "durationFormatted");

  /**
   * Required placeholders (D4) — at minimum the run identifier, without which the merge-ready
   * summary cannot be attributed to a run. Kept deliberately minimal so pilots may customize the
   * template freely; everything else is optional and degrades to a fallback.
   */
  public static final Set<String> REQUIRED_PLACEHOLDERS = Set.of("runId");

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

  private LinearCompletionTemplate() {}

  /** The distinct {@code {token}} names referenced by the template, in first-seen order. */
  public static Set<String> placeholdersIn(String template) {
    Set<String> found = new LinkedHashSet<>();
    if (template == null) {
      return found;
    }
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  /**
   * Validate a template against the placeholder grammar (D4). Throws {@code
   * DomainException(INVALID_COMPLETION_TEMPLATE)} when the template is blank, references an unknown
   * placeholder, or omits a required one.
   */
  public static void validate(String template) {
    if (template == null || template.isBlank()) {
      throw invalid("template must not be blank", null, null);
    }
    Set<String> present = placeholdersIn(template);
    Set<String> unknown = new LinkedHashSet<>(present);
    unknown.removeAll(KNOWN_PLACEHOLDERS);
    if (!unknown.isEmpty()) {
      throw invalid("template references unknown placeholder(s): " + unknown, unknown, null);
    }
    Set<String> missing = new LinkedHashSet<>(REQUIRED_PLACEHOLDERS);
    missing.removeAll(present);
    if (!missing.isEmpty()) {
      throw invalid("template is missing required placeholder(s): " + missing, null, missing);
    }
  }

  /**
   * Render a (pre-validated) template, substituting each {@code {token}} with the matching entry
   * from {@code values}. A token absent from {@code values} is left to the caller's discretion: the
   * caller MUST supply a fallback for every placeholder it expects (Decision D5) — a missing entry
   * renders as an empty string rather than a literal {@code {token}}, so a malformed value can
   * never leak the raw placeholder into the posted comment.
   */
  public static String render(String template, Map<String, String> values) {
    Map<String, String> safe = values == null ? Map.of() : values;
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String token = matcher.group(1);
      String replacement = safe.getOrDefault(token, "");
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static DomainException invalid(String message, Set<String> unknown, Set<String> missing) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("configKey", "deliveryline.workflow.linear-completion-sync.template");
    details.put("knownPlaceholders", KNOWN_PLACEHOLDERS);
    details.put("requiredPlaceholders", REQUIRED_PLACEHOLDERS);
    if (unknown != null) {
      details.put("unknownPlaceholders", unknown);
    }
    if (missing != null) {
      details.put("missingPlaceholders", missing);
    }
    return new DomainException(
        DomainErrorCode.INVALID_COMPLETION_TEMPLATE,
        "Invalid Linear completion-sync template: " + message,
        details);
  }
}
