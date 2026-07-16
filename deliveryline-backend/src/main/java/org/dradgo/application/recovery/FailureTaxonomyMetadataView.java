package org.dradgo.application.recovery;

import java.util.List;

/**
 * Story 4.24 (AC3) — one governed failure-taxonomy value enriched with the operator-facing prose
 * the dialog renders. Produced by {@link FailureTaxonomyCatalog#allValues()} by joining the domain
 * registry ({@code value}/{@code deprecated}/{@code replacementValue} sourced from {@link
 * org.dradgo.domain.registry.FailureTaxonomyValue}) to a curated presentation map ({@code
 * humanReadableName}/{@code description}/{@code examples}).
 *
 * <p>Carries NO persistence identity and is never written to a table — it is a pure read view
 * mapped 1:1 into the REST {@code TaxonomyValue} DTO by {@code RegistryController} (adapters never
 * import {@code application.recovery}, so the DTO's {@code from} maps this view, not vice-versa).
 *
 * @param value the snake_case wire value (from the domain registry — never re-listed here)
 * @param humanReadableName the title-cased operator-facing name (curated)
 * @param description a 1–2 sentence explanation of what the category means (curated)
 * @param examples 1–2 concrete example scenarios, never null and never empty (curated)
 * @param deprecated whether the value is retired (from the domain registry)
 * @param replacementValue the replacement's wire value when {@code deprecated}, else {@code null}
 *     (from the domain registry)
 */
public record FailureTaxonomyMetadataView(
    String value,
    String humanReadableName,
    String description,
    List<String> examples,
    boolean deprecated,
    String replacementValue) {}
