package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Governed failure-taxonomy registry for operator triage of failed runs (story 4.9, FR37/FR38).
 * Human-applied, run-scoped, post-hoc classification stored on {@code
 * workflow_runs.failure_classification} for cross-run pattern analysis. Separate from {@link
 * FailureCategory} which is runner-scoped — taxonomy values record the operator's judgment of WHY a
 * run failed, not what the runner execution did; both can be set on the same run.
 *
 * <p>Wire form is the {@code value()} (snake_case). Renaming an enum constant must keep the wire
 * value identical or it is a wire-breaking change. The six canonical values mirror PRD § Technical
 * Success.
 *
 * <p>Governance (NFR33, ADR 0035): values are NEVER hard-removed — a retired value is marked
 * deprecated by setting {@code deprecatedReplacementValue} to the replacement's wire value, so
 * historical {@code failure_classification} rows stay interpretable. Reads are total: {@link
 * #fromValue} accepts deprecated values and {@link #displayLabel()} renders a {@code (deprecated)}
 * affix; only the write path rejects them (see {@code FailureTaxonomyPolicy}). Adding a value
 * requires an ADR, an append to the frozen stability set in {@code FailureTaxonomyValueTest}, and a
 * widening of the {@code ck_workflow_runs_failure_classification} CHECK.
 */
public enum FailureTaxonomyValue implements RegistryValue {
  SPECIFICATION_GAP("specification_gap", null),
  CONTEXT_GAP("context_gap", null),
  AGENT_EXECUTION_FAILURE("agent_execution_failure", null),
  REVIEW_REJECTION("review_rejection", null),
  INTEGRATION_OR_MERGE_FAILURE("integration_or_merge_failure", null),
  TOOLING_OR_INFRASTRUCTURE_FAILURE("tooling_or_infrastructure_failure", null);

  private static final Map<String, FailureTaxonomyValue> LOOKUP = RegistryParsers.index(values());

  private final String value;

  // Replacement WIRE value, null == active. A wire String (not an enum ref) sidesteps Java's
  // enum forward-reference restriction when a value is deprecated in favour of a later constant.
  private final String deprecatedReplacementValue;

  FailureTaxonomyValue(String value, String deprecatedReplacementValue) {
    this.value = value;
    this.deprecatedReplacementValue = deprecatedReplacementValue;
  }

  @Override
  public String value() {
    return value;
  }

  public boolean deprecated() {
    return deprecatedReplacementValue != null;
  }

  public String deprecatedReplacementValue() {
    return deprecatedReplacementValue;
  }

  public String displayLabel() {
    return displayLabel(value, deprecatedReplacementValue);
  }

  static String displayLabel(String wireValue, String replacementWireValue) {
    return replacementWireValue == null ? wireValue : wireValue + " (deprecated)";
  }

  public static FailureTaxonomyValue fromValue(String rawValue, String field) {
    return RegistryParsers.parse("FailureTaxonomyValue", rawValue, field, LOOKUP);
  }

  public static FailureTaxonomyValue fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("FailureTaxonomyValue", rawValue, field, LOOKUP);
  }
}
