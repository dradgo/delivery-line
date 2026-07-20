package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.integration.conflict.ConflictFilter;
import org.dradgo.application.integration.conflict.ConflictReconciliationSuggester;
import org.dradgo.application.integration.conflict.ConflictReconciliationSuggester.SuggestedDecision;
import org.dradgo.application.integration.conflict.ConflictResolutionView;
import org.dradgo.application.integration.conflict.ConflictSummary;
import org.dradgo.application.integration.conflict.IntegrationConflictService;
import org.dradgo.application.integration.conflict.IntegrationConflictService.ConflictDetail;
import org.dradgo.application.integration.conflict.IntegrationConflictService.ConflictListResult;
import org.dradgo.domain.registry.ReconciliationDecision;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.18 (AC2/AC3/AC10) — thin-adapter contract for {@code GET /api/v1/integration-conflicts}
 * and {@code /{conflictId}}. The controller mocks the service, so filter/cursor resolution is not
 * exercised here (that lives in the service unit test + real-PG IT).
 */
@WebMvcTest(controllers = IntegrationConflictController.class)
class IntegrationConflictControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private IntegrationConflictService integrationConflictService;

  private static ConflictListResult listFixture(String nextCursor) {
    ConflictSummary summary =
        new ConflictSummary(
            "icf_1",
            "ilk_1",
            "run_abc123",
            "external_state_advanced",
            "github_pr",
            "octo/repo#7",
            Instant.parse("2026-07-14T10:00:00Z"));
    return new ConflictListResult(
        List.of(summary),
        3L,
        12L,
        Map.of("external_state_advanced", 2L, "link_broken", 1L),
        Map.of("github", 2L, "linear", 1L),
        nextCursor);
  }

  @Test
  void listMapsCountsAndConflictRow() throws Exception {
    when(integrationConflictService.listConflicts(any())).thenReturn(listFixture("cursor_next"));

    mockMvc
        .perform(get("/api/v1/integration-conflicts").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.totalUnresolved").value(3))
        .andExpect(jsonPath("$.totalResolved").value(12))
        .andExpect(jsonPath("$.totalUnresolvedByCategory.external_state_advanced").value(2))
        .andExpect(jsonPath("$.totalUnresolvedByIntegration.github").value(2))
        .andExpect(jsonPath("$.nextCursor").value("cursor_next"))
        .andExpect(jsonPath("$.conflicts[0].conflictId").value("icf_1"))
        .andExpect(jsonPath("$.conflicts[0].workflowRunId").value("run_abc123"))
        .andExpect(jsonPath("$.conflicts[0].conflictCategory").value("external_state_advanced"))
        .andExpect(jsonPath("$.conflicts[0].integrationType").value("github_pr"))
        .andExpect(jsonPath("$.conflicts[0].externalRef").value("octo/repo#7"));
  }

  @Test
  void listMapsGithubTokenToGithubPrAndThreadsFilters() throws Exception {
    when(integrationConflictService.listConflicts(any())).thenReturn(listFixture(null));

    mockMvc
        .perform(
            get("/api/v1/integration-conflicts")
                .param("category", "external_state_advanced")
                .param("integration", "github")
                .param("workflowRunId", "run_abc123")
                .param("resolved", "false")
                .param("limit", "25")
                .param("cursor", "opaque")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    ArgumentCaptor<ConflictFilter> captor = ArgumentCaptor.forClass(ConflictFilter.class);
    verify(integrationConflictService).listConflicts(captor.capture());
    ConflictFilter filter = captor.getValue();
    assertThat(filter.conflictCategory()).isEqualTo("external_state_advanced");
    assertThat(filter.integrationType()).isEqualTo("github_pr");
    assertThat(filter.workflowRunId()).isEqualTo("run_abc123");
    assertThat(filter.resolved()).isFalse();
    assertThat(filter.limit()).isEqualTo(25);
    assertThat(filter.cursor()).isEqualTo("opaque");
  }

  @Test
  void nullNextCursorSerializesAsJsonNull() throws Exception {
    when(integrationConflictService.listConflicts(any())).thenReturn(listFixture(null));

    mockMvc
        .perform(get("/api/v1/integration-conflicts").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void detailReturnsSnapshotsAndSuggestions() throws Exception {
    ConflictResolutionView view =
        new ConflictResolutionView(
            "icf_1",
            "run_abc123",
            "ilk_1",
            "github_pr",
            "external_state_advanced",
            "octo/repo#7",
            OffsetDateTime.of(2026, 7, 14, 11, 0, 0, 0, ZoneOffset.UTC),
            "{\"freshPrState\":\"merged\"}",
            "{\"currentState\":\"Executing\"}");
    List<SuggestedDecision> suggestions =
        List.of(
            new SuggestedDecision(
                ReconciliationDecision.ACCEPT_EXTERNAL_STATE,
                ConflictReconciliationSuggester.SAFETY_SAFE),
            new SuggestedDecision(
                ReconciliationDecision.ACCEPT_INTERNAL_STATE,
                ConflictReconciliationSuggester.SAFETY_RISKY));
    when(integrationConflictService.getConflictDetail(eq("icf_1")))
        .thenReturn(Optional.of(new ConflictDetail(view, suggestions)));

    mockMvc
        .perform(get("/api/v1/integration-conflicts/icf_1").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conflictId").value("icf_1"))
        .andExpect(jsonPath("$.conflictCategory").value("external_state_advanced"))
        .andExpect(jsonPath("$.externalStateSnapshot").value("{\"freshPrState\":\"merged\"}"))
        .andExpect(jsonPath("$.internalStateSnapshot").value("{\"currentState\":\"Executing\"}"))
        .andExpect(jsonPath("$.suggestedDecisions[0].decision").value("accept_external_state"))
        .andExpect(jsonPath("$.suggestedDecisions[0].safety").value("safe"))
        .andExpect(jsonPath("$.suggestedDecisions[1].safety").value("risky"));
  }

  @Test
  void detailNotFoundSurfacesConflictNotFound404() throws Exception {
    when(integrationConflictService.getConflictDetail(eq("icf_missing")))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/api/v1/integration-conflicts/icf_missing").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("CONFLICT_NOT_FOUND"));
  }
}
