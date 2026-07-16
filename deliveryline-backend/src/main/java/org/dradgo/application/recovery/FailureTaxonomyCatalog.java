package org.dradgo.application.recovery;

import java.util.List;
import java.util.Map;
import org.dradgo.domain.registry.FailureTaxonomyValue;
import org.springframework.stereotype.Component;

/**
 * Story 4.24 (AC3, R3) — the curated operator-facing prose for the governed failure taxonomy.
 *
 * <p>The domain enum {@link FailureTaxonomyValue} is deliberately minimal (story 4.9 R2 — wire
 * value + optional deprecation replacement only) and MUST stay free of presentation prose (i18n
 * concerns, display strings). This application-layer catalog supplies the {@code
 * humanReadableName}/{@code description}/{@code examples} the classification dialog renders, joined
 * at read time to the enum's {@code deprecated()}/{@code deprecatedReplacementValue()} so {@code
 * value}/{@code deprecated}/{@code replacementValue} are ALWAYS sourced from the single domain
 * registry — never re-listed.
 *
 * <p><strong>Drift guard:</strong> {@link #allValues()} iterates {@link
 * FailureTaxonomyValue#values()} and looks each wire value up in {@link #CURATED}; a missing entry
 * fails fast. {@code FailureTaxonomyCatalogTest} asserts {@code CURATED}'s key set equals {@code
 * DomainRegistry.failureTaxonomyValues()}, so a future ADR-0035-governed registry addition that
 * forgets the prose reds the build rather than shipping a value with no description.
 *
 * <p>Copy reconciled against {@code docs/adr/0035-failure-taxonomy-governance.md} (which fixes the
 * six canonical values but defines no descriptions — the prose below is the canonical source,
 * OQ-3).
 */
@Component
public class FailureTaxonomyCatalog {

  /**
   * Curated presentation prose keyed by wire value. Key set is drift-tested against the registry.
   */
  private static final Map<String, CuratedProse> CURATED =
      Map.of(
          "specification_gap",
          new CuratedProse(
              "Specification Gap",
              "The failure traces to missing, ambiguous, or incorrect requirements in the spec"
                  + " — not to execution.",
              List.of(
                  "Acceptance criteria omitted a required edge case.",
                  "The spec didn't define the error-handling behavior the reviewer expected.")),
          "context_gap",
          new CuratedProse(
              "Context Gap",
              "The agent lacked repository or domain context needed to complete the task"
                  + " correctly.",
              List.of(
                  "The runner reimplemented a helper that already existed because it wasn't in the"
                      + " context bundle.",
                  "A project convention wasn't surfaced to the agent.")),
          "agent_execution_failure",
          new CuratedProse(
              "Agent Execution Failure",
              "The agent/runner failed to produce a valid result despite adequate spec and"
                  + " context.",
              List.of(
                  "The runner produced malformed output that failed contract validation.",
                  "The agent looped without converging on a fix.")),
          "review_rejection",
          new CuratedProse(
              "Review Rejection",
              "The run failed because a human or automated review rejected the work product.",
              List.of(
                  "The reviewer rejected the spec twice for scope creep.",
                  "Automated review flagged an unaddressed security finding.")),
          "integration_or_merge_failure",
          new CuratedProse(
              "Integration or Merge Failure",
              "The failure occurred at the integration boundary — push, merge, or external ticket"
                  + " sync.",
              List.of(
                  "The push was rejected by a required status check.",
                  "A merge conflict with concurrent external changes blocked delivery.")),
          "tooling_or_infrastructure_failure",
          new CuratedProse(
              "Tooling or Infrastructure Failure",
              "The failure was caused by tooling, CI, or infrastructure rather than the work"
                  + " itself.",
              List.of(
                  "The runner image lacked a required JDK.",
                  "CI timed out during an infrastructure outage.")));

  /**
   * Every governed taxonomy value in registry declaration order, enriched with curated prose.
   * Iterates the domain registry so {@code value}/{@code deprecated}/{@code replacementValue} stay
   * single-sourced; throws {@link IllegalStateException} if a registry value has no curated prose
   * (the same invariant {@code FailureTaxonomyCatalogTest} pins).
   */
  public List<FailureTaxonomyMetadataView> allValues() {
    return java.util.Arrays.stream(FailureTaxonomyValue.values())
        .map(FailureTaxonomyCatalog::toView)
        .toList();
  }

  /**
   * The curated wire-value key set. Exposed package-private for {@code FailureTaxonomyCatalogTest}'s
   * "no EXTRA prose" assertion: {@link #allValues()} iterates the enum, so a stray {@code CURATED}
   * key that is not a governed registry value is never surfaced and would otherwise drift undetected.
   */
  static java.util.Set<String> curatedWireValues() {
    return CURATED.keySet();
  }

  private static FailureTaxonomyMetadataView toView(FailureTaxonomyValue value) {
    CuratedProse prose = CURATED.get(value.value());
    if (prose == null) {
      throw new IllegalStateException(
          "No curated failure-taxonomy prose for wire value '"
              + value.value()
              + "' — add it to FailureTaxonomyCatalog.CURATED (ADR 0035 governance).");
    }
    return new FailureTaxonomyMetadataView(
        value.value(),
        prose.humanReadableName(),
        prose.description(),
        prose.examples(),
        value.deprecated(),
        value.deprecatedReplacementValue());
  }

  /** The curated presentation triple for one wire value. */
  private record CuratedProse(
      String humanReadableName, String description, List<String> examples) {}
}
