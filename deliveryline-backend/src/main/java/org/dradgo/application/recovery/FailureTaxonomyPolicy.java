package org.dradgo.application.recovery;

import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;

/**
 * Story 4.9 (AC6, NFR33) — the WRITE-path deprecation guard for the governed failure taxonomy.
 * Reads over {@code FailureTaxonomyValue} are total (a deprecated value still parses and renders
 * with a {@code (deprecated)} affix so historical classifications stay interpretable); only NEW
 * classifications reject a deprecated value, pointing the caller at the replacement.
 *
 * <p>A pure static over wire strings — not over the enum constant — so the guard's semantics are
 * unit-testable with synthetic arguments today, while the registry ships with zero deprecated
 * values (see {@code FailureTaxonomyPolicyTest}). {@code RecoveryService.classifyFailure} calls it
 * as {@code requireNotDeprecated(taxonomy.value(), taxonomy.deprecatedReplacementValue())}.
 */
public final class FailureTaxonomyPolicy {

  private FailureTaxonomyPolicy() {}

  /**
   * @param wireValue the requested taxonomy wire value (already registry-validated)
   * @param replacementWireValue the registry's replacement wire value for it; {@code null} means
   *     the value is active
   * @throws DomainException with {@code DEPRECATED_TAXONOMY_VALUE} (400) carrying {@code
   *     details.provided} + {@code details.replacementValue} (the remediation hint story 4.14 AC4
   *     puts on the wire) when the value is deprecated
   */
  public static void requireNotDeprecated(String wireValue, String replacementWireValue) {
    if (replacementWireValue == null) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("provided", wireValue);
    details.put("replacementValue", replacementWireValue);
    throw new DomainException(
        DomainErrorCode.DEPRECATED_TAXONOMY_VALUE,
        "Taxonomy value '"
            + wireValue
            + "' is deprecated; classify with '"
            + replacementWireValue
            + "' instead",
        details);
  }
}
