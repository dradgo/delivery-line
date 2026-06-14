package org.dradgo.domain.registry;

import java.util.Map;
import java.util.Set;

/**
 * Structured rework taxonomy attached to every rejection (stories 2.10 + 3.21, FR9/FR17, AR34a).
 *
 * <p>Role-scoped (Decision D4): the first three values are the <strong>product</strong>-rejection
 * taxonomy used by {@code ApprovalService.rejectSpec} ({@code reviewerRole=product_reviewer}); the
 * last five are the <strong>developer</strong>-rejection taxonomy used by {@code
 * TechnicalApprovalService.rejectImplementation} ({@code reviewerRole=developer}). Both rejection
 * commands type {@code taggedFeedback} as this single enum; the executing service enforces the
 * role-appropriate subset (see {@link #developerValues()} / {@link #isDeveloperValue()}).
 *
 * <p>Wire values intentionally match the {@code ck_workflow_events_rejection_taxonomy} and {@code
 * ck_approvals_rejection_taxonomy} CHECK constraint substrings (widened to the 8-value union in the
 * V13 migration) — renaming an enum constant must keep {@code wireValue} identical or the database
 * CHECK rejects the row.
 *
 * <p>Persisted in {@code approvals.rejection_taxonomy} and emitted in {@code APPROVAL_REJECTED}
 * event details. Surfaces measurement aggregation (Epic 5 story 5.5) for rework rates.
 */
public enum RejectionTaxonomy implements RegistryValue {
  // Product-rejection taxonomy (story 2.10).
  MISSING_SCOPE("missing_scope", Reviewer.PRODUCT),
  UNCLEAR_SPECIFICATION("unclear_specification", Reviewer.PRODUCT),
  MISUNDERSTOOD_IMPLEMENTATION("misunderstood_implementation", Reviewer.PRODUCT),
  // Developer-rejection taxonomy (story 3.21).
  INCORRECT_APPROACH("incorrect_approach", Reviewer.DEVELOPER),
  INCOMPLETE_IMPLEMENTATION("incomplete_implementation", Reviewer.DEVELOPER),
  QUALITY_ISSUE("quality_issue", Reviewer.DEVELOPER),
  BREAKS_EXISTING_FUNCTIONALITY("breaks_existing_functionality", Reviewer.DEVELOPER),
  OUT_OF_SCOPE("out_of_scope", Reviewer.DEVELOPER);

  /** Which review surface a taxonomy value belongs to (role-scoping, Decision D4). */
  private enum Reviewer {
    PRODUCT,
    DEVELOPER
  }

  private static final Map<String, RejectionTaxonomy> LOOKUP = RegistryParsers.index(values());

  private static final Set<RejectionTaxonomy> DEVELOPER_VALUES =
      Set.of(
          INCORRECT_APPROACH,
          INCOMPLETE_IMPLEMENTATION,
          QUALITY_ISSUE,
          BREAKS_EXISTING_FUNCTIONALITY,
          OUT_OF_SCOPE);

  private final String wireValue;
  private final Reviewer reviewer;

  RejectionTaxonomy(String wireValue, Reviewer reviewer) {
    this.wireValue = wireValue;
    this.reviewer = reviewer;
  }

  @Override
  public String value() {
    return wireValue;
  }

  /**
   * The developer-rejection subset (story 3.21) — the values valid for {@code
   * rejectImplementation}.
   */
  public static Set<RejectionTaxonomy> developerValues() {
    return DEVELOPER_VALUES;
  }

  /** True when this value belongs to the developer-rejection taxonomy (story 3.21, Decision D4). */
  public boolean isDeveloperValue() {
    return reviewer == Reviewer.DEVELOPER;
  }

  /** True when this value belongs to the product-rejection taxonomy (story 2.10, Decision D4). */
  public boolean isProductValue() {
    return reviewer == Reviewer.PRODUCT;
  }

  static RejectionTaxonomy fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static RejectionTaxonomy fromValue(String rawValue, String field) {
    return RegistryParsers.parse("RejectionTaxonomy", rawValue, field, LOOKUP);
  }
}
