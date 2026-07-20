package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.dradgo.domain.registry.DomainRegistry;
import org.junit.jupiter.api.Test;

/**
 * Story 4.24 (AC3, R3) — drift + completeness guard for {@link FailureTaxonomyCatalog}. Asserts the
 * curated prose covers EXACTLY the governed registry (no missing/extra values) and that every entry
 * ships a non-blank name/description and at least one example, so a future ADR-0035 registry
 * addition that forgets the prose reds the build instead of shipping a value with no description.
 */
class FailureTaxonomyCatalogTest {

  private final FailureTaxonomyCatalog catalog = new FailureTaxonomyCatalog();

  @Test
  void curatedKeySetEqualsGovernedRegistry() {
    List<FailureTaxonomyMetadataView> all = catalog.allValues();

    assertThat(all.stream().map(FailureTaxonomyMetadataView::value).collect(Collectors.toSet()))
        .isEqualTo(DomainRegistry.failureTaxonomyValues());
  }

  @Test
  void curatedProseHasNoExtraKeysBeyondRegistry() {
    // `allValues()` iterates the enum, so a stray CURATED key (prose for a value not in the
    // governed
    // registry) is never surfaced by the assertion above — assert the key set directly so an EXTRA
    // key reds the build too, not only a MISSING one.
    assertThat(FailureTaxonomyCatalog.curatedWireValues())
        .isEqualTo(DomainRegistry.failureTaxonomyValues());
  }

  @Test
  void everyEntryHasNonBlankNameDescriptionAndAtLeastOneExample() {
    for (FailureTaxonomyMetadataView view : catalog.allValues()) {
      assertThat(view.humanReadableName())
          .as("humanReadableName for %s", view.value())
          .isNotBlank();
      assertThat(view.description()).as("description for %s", view.value()).isNotBlank();
      assertThat(view.examples()).as("examples for %s", view.value()).isNotEmpty();
      assertThat(view.examples()).allSatisfy(example -> assertThat(example).isNotBlank());
    }
  }

  @Test
  void allSixShippedValuesAreActiveWithNoReplacement() {
    // The registry ships zero deprecated values today (ADR 0035); the deprecated path is exercised
    // via the FE MSW fixture, never a real enum constant.
    for (FailureTaxonomyMetadataView view : catalog.allValues()) {
      assertThat(view.deprecated()).as("deprecated for %s", view.value()).isFalse();
      assertThat(view.replacementValue()).as("replacementValue for %s", view.value()).isNull();
    }
  }

  @Test
  void valuesAreSurfacedInRegistryDeclarationOrder() {
    assertThat(catalog.allValues().stream().map(FailureTaxonomyMetadataView::value).toList())
        .containsExactly(
            "specification_gap",
            "context_gap",
            "agent_execution_failure",
            "review_rejection",
            "integration_or_merge_failure",
            "tooling_or_infrastructure_failure");
  }
}
