package org.dradgo.adapters.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dradgo.application.recovery.FailureTaxonomyCatalog;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.24 (AC1/AC3, Task 2) — contract test for {@code GET /api/v1/registries/failure-taxonomy}.
 * Imports the REAL {@link FailureTaxonomyCatalog} (not a mock) so the endpoint shape is asserted
 * against the shipped six governed values + their curated prose. Verifies: 200; all six wire values
 * present (a wire-level drift guard alongside {@code FailureTaxonomyCatalogTest}); every entry
 * carries a non-blank {@code humanReadableName}/{@code description} + at least one example; the
 * shipped registry is all-active ({@code deprecated=false}, {@code replacementValue} omitted).
 */
@WebMvcTest(controllers = RegistryController.class)
@Import(FailureTaxonomyCatalog.class)
class RegistryControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void returnsAllSixGovernedValuesWithProse() throws Exception {
    mockMvc
        .perform(get("/api/v1/registries/failure-taxonomy").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.values", Matchers.hasSize(6)))
        .andExpect(
            jsonPath(
                "$.values[*].value",
                Matchers.containsInAnyOrder(
                    "specification_gap",
                    "context_gap",
                    "agent_execution_failure",
                    "review_rejection",
                    "integration_or_merge_failure",
                    "tooling_or_infrastructure_failure")))
        // every entry carries operator-facing prose (non-blank — a whitespace-only string is a gap,
        // so `blankOrNullString()` rejects it, not just the empty string).
        .andExpect(
            jsonPath(
                "$.values[*].humanReadableName",
                Matchers.everyItem(Matchers.not(Matchers.blankOrNullString()))))
        .andExpect(
            jsonPath(
                "$.values[*].description",
                Matchers.everyItem(Matchers.not(Matchers.blankOrNullString()))))
        .andExpect(
            jsonPath(
                "$.values[*].examples",
                Matchers.everyItem(Matchers.hasItem(Matchers.notNullValue()))))
        // the shipped registry has zero deprecated values (ADR 0035).
        .andExpect(jsonPath("$.values[*].deprecated", Matchers.everyItem(Matchers.is(false))))
        .andExpect(jsonPath("$.values[*].replacementValue").doesNotExist());
  }

  @Test
  void agentExecutionFailureCarriesItsCuratedNameAndExamples() throws Exception {
    mockMvc
        .perform(get("/api/v1/registries/failure-taxonomy").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.values[?(@.value=='agent_execution_failure')].humanReadableName")
                .value(Matchers.hasItem("Agent Execution Failure")))
        .andExpect(
            jsonPath("$.values[?(@.value=='agent_execution_failure')].examples[0]")
                .value(Matchers.hasItem(Matchers.notNullValue())));
  }
}
