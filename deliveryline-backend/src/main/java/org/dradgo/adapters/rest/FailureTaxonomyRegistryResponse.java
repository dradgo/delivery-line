package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.recovery.FailureTaxonomyMetadataView;

/**
 * Story 4.24 (AC1/AC3) — response body for {@code GET /api/v1/registries/failure-taxonomy}. The
 * governed failure-taxonomy registry enriched with the operator-facing prose the classification
 * dialog renders (one radio card per {@link TaxonomyValue}). Idempotent public read — no {@code
 * Idempotency-Key}, no actor, no {@code role}.
 *
 * <p>{@code value}/{@code deprecated}/{@code replacementValue} are sourced from {@link
 * org.dradgo.domain.registry.FailureTaxonomyValue}; {@code humanReadableName}/{@code
 * description}/{@code examples} from the curated {@code FailureTaxonomyCatalog}. Adapters never
 * import {@code application.recovery} internals beyond the flat {@link
 * FailureTaxonomyMetadataView}, mapped 1:1 by {@link TaxonomyValue#from}.
 */
public record FailureTaxonomyRegistryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TaxonomyValue> values) {

  /**
   * One governed taxonomy value with its curated prose. {@code replacementValue} is present only
   * for a deprecated value (zero shipped today, ADR 0035); {@code examples} is never null and never
   * empty.
   */
  @Schema(name = "TaxonomyValue")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TaxonomyValue(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "agent_execution_failure")
          String value,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Agent Execution Failure")
          String humanReadableName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> examples,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean deprecated,
      @Schema(
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              nullable = true,
              description = "Replacement wire value when this value is deprecated, else absent.")
          String replacementValue) {

    static TaxonomyValue from(FailureTaxonomyMetadataView view) {
      return new TaxonomyValue(
          view.value(),
          view.humanReadableName(),
          view.description(),
          view.examples(),
          view.deprecated(),
          view.replacementValue());
    }
  }

  public static FailureTaxonomyRegistryResponse from(List<FailureTaxonomyMetadataView> views) {
    return new FailureTaxonomyRegistryResponse(views.stream().map(TaxonomyValue::from).toList());
  }
}
