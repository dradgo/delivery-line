package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Structured rework taxonomy attached to every spec rejection (story 2.10, FR9, AR34a).
 *
 * <p>Wire values intentionally match the V1 {@code ck_workflow_events_rejection_taxonomy} and
 * {@code ck_approvals_decision_taxonomy_paired} CHECK constraint substrings — renaming an enum
 * constant must keep {@code wireValue} identical or the database CHECK rejects the row.
 *
 * <p>Persisted in {@code approvals.rejection_taxonomy} and emitted in {@code APPROVAL_REJECTED}
 * event details. Surfaces measurement aggregation (Epic 5 story 5.5) for rework rates.
 */
public enum RejectionTaxonomy implements RegistryValue {
  MISSING_SCOPE("missing_scope"),
  UNCLEAR_SPECIFICATION("unclear_specification"),
  MISUNDERSTOOD_IMPLEMENTATION("misunderstood_implementation");

  private static final Map<String, RejectionTaxonomy> LOOKUP = RegistryParsers.index(values());

  private final String wireValue;

  RejectionTaxonomy(String wireValue) {
    this.wireValue = wireValue;
  }

  @Override
  public String value() {
    return wireValue;
  }

  static RejectionTaxonomy fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static RejectionTaxonomy fromValue(String rawValue, String field) {
    return RegistryParsers.parse("RejectionTaxonomy", rawValue, field, LOOKUP);
  }
}
