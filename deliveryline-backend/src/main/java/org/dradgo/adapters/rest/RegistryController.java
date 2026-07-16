package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.dradgo.application.recovery.FailureTaxonomyCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 4.24 (AC1/AC3) — the read-only registry surface. Serves governed vocabularies enriched with
 * the operator-facing prose the UI renders. The first (and today only) endpoint is the
 * failure-taxonomy registry consumed by the story 4.24 classification dialog.
 *
 * <p>Greenfield controller — there was no {@code /api/v1/registries/**} path before this story. It
 * follows the thin read-endpoint convention of {@code WorkflowController#getFailureDiagnostics} /
 * {@code #getAllowedActions}: a public, idempotent GET with NO {@code Idempotency-Key}, NO actor
 * header, and NO {@code role} gate. The controller maps the curated {@code FailureTaxonomyCatalog}
 * views into flat DTOs and does nothing else (REST_CONTROLLERS_STAY_THIN).
 */
@RestController
@RequestMapping("/api/v1/registries")
@Tag(name = "Registries", description = "Read governed vocabularies with operator-facing prose.")
public class RegistryController {

  private static final Logger log = LoggerFactory.getLogger(RegistryController.class);

  private final FailureTaxonomyCatalog failureTaxonomyCatalog;

  public RegistryController(FailureTaxonomyCatalog failureTaxonomyCatalog) {
    this.failureTaxonomyCatalog = failureTaxonomyCatalog;
  }

  /**
   * Story 4.24 (AC1/AC3) — the governed failure-taxonomy registry with curated descriptions +
   * examples + deprecation markers, one entry per {@code FailureTaxonomyValue}. Read-only +
   * idempotent (no Idempotency-Key/actor/role). The dialog renders one radio card per entry.
   */
  @GetMapping(value = "/failure-taxonomy", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getFailureTaxonomyRegistry",
      summary = "Get the governed failure-taxonomy registry with operator-facing prose")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The failure-taxonomy registry.")
  })
  public FailureTaxonomyRegistryResponse getFailureTaxonomyRegistry() {
    log.info("REST get failure-taxonomy registry received");
    FailureTaxonomyRegistryResponse response =
        FailureTaxonomyRegistryResponse.from(failureTaxonomyCatalog.allValues());
    log.info("REST get failure-taxonomy registry success valueCount={}", response.values().size());
    return response;
  }
}
