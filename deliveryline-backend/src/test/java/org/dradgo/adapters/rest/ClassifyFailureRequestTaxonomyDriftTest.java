package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.dradgo.domain.registry.DomainRegistry;
import org.dradgo.domain.registry.FailureTaxonomyValue;
import org.junit.jupiter.api.Test;

/**
 * Story 4.14 review finding (OQ-5) — drift guard for the {@code taxonomyValue}
 * {@code @Schema(allowableValues = {...})} on {@link ClassifyFailureRequest}.
 *
 * <p>The inline OpenAPI enum is a hand-copied duplicate of the {@link FailureTaxonomyValue}
 * registry with NO compile-time link (Reconciliation 4 chose an inline {@code allowableValues}
 * constraint over a named component). Without this test, a future registry addition/deprecation
 * governed by ADR 0035 could silently drift the wire enum out of sync with the domain registry,
 * breaking the story 4.24 taxonomy dropdown.
 *
 * <p>The DTO's {@code allowableValues} is the set of ACTIVE (non-deprecated) wire values: per
 * Reconciliation 4 / the DTO javadoc, a retired value is DROPPED from {@code allowableValues}
 * (while {@link DomainRegistry#failureTaxonomyValues()} keeps deprecated values so historical rows
 * stay readable). Today no value is deprecated, so the active set also equals {@code
 * failureTaxonomyValues()} — asserted as a secondary invariant. Asserting against the active set
 * (not the full registry) keeps this test correct the day a value is first deprecated.
 */
class ClassifyFailureRequestTaxonomyDriftTest {

  @Test
  void schemaAllowableValuesMatchActiveFailureTaxonomyRegistry() throws NoSuchFieldException {
    Set<String> schemaAllowableValues =
        Arrays.stream(
                ClassifyFailureRequest.class
                    .getDeclaredField("taxonomyValue")
                    .getAnnotation(Schema.class)
                    .allowableValues())
            .collect(Collectors.toSet());

    Set<String> activeRegistryWireValues =
        Arrays.stream(FailureTaxonomyValue.values())
            .filter(v -> !v.deprecated())
            .map(FailureTaxonomyValue::value)
            .collect(Collectors.toSet());

    assertThat(schemaAllowableValues)
        .as(
            "ClassifyFailureRequest.taxonomyValue @Schema(allowableValues) must list exactly the"
                + " active FailureTaxonomyValue wire values — add/deprecate a registry value under"
                + " ADR 0035 and this list must be updated in lockstep")
        .isEqualTo(activeRegistryWireValues);

    // Secondary invariant: no value is deprecated today, so the active set also equals the full
    // registry surface. This will legitimately diverge once a value is first deprecated; when it
    // does, the primary assertion above (active-only) remains the source of truth.
    assertThat(activeRegistryWireValues).isEqualTo(DomainRegistry.failureTaxonomyValues());
  }
}
