package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunFilter;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunRow;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.2 (AC1/AC3/AC5/AC10) — thin-adapter contract for {@code GET /api/v1/operator/runs}.
 * Covers query-param → {@link OperatorRunFilter} mapping (csv splitting, defaults), enum → wire
 * string response mapping, {@code nextCursor} echo, a typed 4xx surface, and the read-only
 * no-Idempotency-Key contract. The controller mocks the service, so token/cursor resolution is not
 * exercised here (that lives in {@code OperatorRunCursorPaginationIT}).
 *
 * <p>Assertions on machine-readable {@code code}/{@code details} only — never human title/detail.
 */
@WebMvcTest(controllers = OperatorController.class)
class OperatorControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;

  private static OperatorRunSummary summaryFixture(String nextCursor) {
    OffsetDateTime t = OffsetDateTime.of(2026, 7, 6, 10, 0, 0, 0, ZoneOffset.UTC);
    Map<WorkflowState, Integer> byState = new LinkedHashMap<>();
    byState.put(WorkflowState.FAILED, 2);
    byState.put(WorkflowState.TAKEN_OVER, 1);
    Map<FailureCategory, Integer> byFailure = new LinkedHashMap<>();
    byFailure.put(FailureCategory.ORPHAN, 1);
    OperatorRunRow row =
        new OperatorRunRow(
            "run_abc123",
            WorkflowState.FAILED,
            "orphan",
            t,
            "system",
            "LIN-101",
            "octo/repo#7",
            true,
            t,
            "ORPHANED",
            "claude",
            2);
    return new OperatorRunSummary(3, byState, byFailure, t, List.of(row), nextCursor);
  }

  @Test
  void happyPathMapsEnumsToWireStringsAndEchoesCursor() throws Exception {
    when(workflowInspectionService.getOperatorRunSummary(any()))
        .thenReturn(summaryFixture("cursor_next_page"));

    mockMvc
        .perform(get("/api/v1/operator/runs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.byState.Failed").value(2))
        .andExpect(jsonPath("$.byState.TakenOver").value(1))
        .andExpect(jsonPath("$.byFailureCategory.orphan").value(1))
        .andExpect(jsonPath("$.nextCursor").value("cursor_next_page"))
        .andExpect(jsonPath("$.runs[0].runId").value("run_abc123"))
        .andExpect(jsonPath("$.runs[0].currentState").value("Failed"))
        .andExpect(jsonPath("$.runs[0].failureCategory").value("orphan"))
        .andExpect(jsonPath("$.runs[0].runnerKind").value("claude"))
        .andExpect(jsonPath("$.runs[0].operatorSignifier").value("ORPHANED"))
        .andExpect(jsonPath("$.runs[0].escalationMarker").value(true))
        // Story 4.18 (AC1) — the unresolved-conflict indicator count is carried on the row.
        .andExpect(jsonPath("$.runs[0].unresolvedConflictCount").value(2));
  }

  @Test
  void nullNextCursorSerializesAsJsonNull() throws Exception {
    when(workflowInspectionService.getOperatorRunSummary(any())).thenReturn(summaryFixture(null));

    mockMvc
        .perform(get("/api/v1/operator/runs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").value(Matchers.nullValue()));
  }

  @Test
  void csvQueryParamsMapToFilterTokens() throws Exception {
    when(workflowInspectionService.getOperatorRunSummary(any())).thenReturn(summaryFixture(null));

    mockMvc
        .perform(
            get("/api/v1/operator/runs")
                .param("state", "failed,stalled")
                .param("failureCategory", "orphan")
                .param("runnerKind", "codex,claude")
                .param("since", "24h")
                .param("limit", "50")
                .param("cursor", "opaque_cursor")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    ArgumentCaptor<OperatorRunFilter> captor = ArgumentCaptor.forClass(OperatorRunFilter.class);
    verify(workflowInspectionService).getOperatorRunSummary(captor.capture());
    OperatorRunFilter filter = captor.getValue();
    assertThat(filter.stateTokens()).containsExactly("failed", "stalled");
    assertThat(filter.failureCategories()).containsExactly("orphan");
    assertThat(filter.runnerKinds()).containsExactly("codex", "claude");
    assertThat(filter.since()).isEqualTo("24h");
    assertThat(filter.limit()).isEqualTo(50);
    assertThat(filter.cursor()).isEqualTo("opaque_cursor");
  }

  @Test
  void absentParamsDefaultToEmptyFiltersAndLimit100() throws Exception {
    when(workflowInspectionService.getOperatorRunSummary(any())).thenReturn(summaryFixture(null));

    mockMvc
        .perform(get("/api/v1/operator/runs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    ArgumentCaptor<OperatorRunFilter> captor = ArgumentCaptor.forClass(OperatorRunFilter.class);
    verify(workflowInspectionService).getOperatorRunSummary(captor.capture());
    OperatorRunFilter filter = captor.getValue();
    assertThat(filter.stateTokens()).isEmpty();
    assertThat(filter.failureCategories()).isEmpty();
    assertThat(filter.runnerKinds()).isEmpty();
    assertThat(filter.since()).isNull();
    assertThat(filter.limit()).isEqualTo(100);
    assertThat(filter.cursor()).isNull();
  }

  @Test
  void malformedCursorSurfacesInvalidCommandPayload400() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("cursor", "@@bad@@");
    when(workflowInspectionService.getOperatorRunSummary(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_COMMAND_PAYLOAD,
                "Malformed operator-runs cursor",
                details));

    mockMvc
        .perform(
            get("/api/v1/operator/runs")
                .param("cursor", "@@bad@@")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"));
  }

  @Test
  void noIdempotencyKeyRequiredForRead() throws Exception {
    when(workflowInspectionService.getOperatorRunSummary(any())).thenReturn(summaryFixture(null));

    // No Idempotency-Key header — a read GET must still return 200.
    mockMvc
        .perform(get("/api/v1/operator/runs").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }
}
