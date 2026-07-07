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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.audit.AuditQueryService;
import org.dradgo.application.audit.AuditQueryService.AuditEventRow;
import org.dradgo.application.audit.AuditQueryService.AuditQueryFilter;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
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
 * Story 4.3 (AC6) — thin-adapter contract for {@code GET /api/v1/audit/by-ticket/{ticketRef}} and
 * {@code /by-run/{workflowRunId}}. Covers query-param → {@link AuditQueryFilter} mapping
 * (repeatable {@code eventType}, defaults), enum → wire string response mapping, {@code nextCursor}
 * echo, and the typed 400/404 surfaces. The controller mocks the service, so filter/cursor
 * resolution is not exercised here (that lives in {@code AuditQueryServiceIT}). Assertions on
 * machine-readable {@code code} only — never human title/detail.
 */
@WebMvcTest(controllers = AuditController.class)
class AuditControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuditQueryService auditQueryService;

  private static AuditQueryResult resultFixture(String nextCursor) {
    OffsetDateTime t = OffsetDateTime.of(2026, 7, 6, 10, 0, 0, 0, ZoneOffset.UTC);
    AuditEventRow row =
        new AuditEventRow(
            "evt_abc123",
            WorkflowEventType.RUNNER_FAILED.value(),
            "run_abc123",
            "system",
            ActorType.SYSTEM,
            t,
            WorkflowState.EXECUTING,
            WorkflowState.FAILED,
            FailureCategory.ORPHAN,
            "runner orphaned",
            "cor_xyz",
            "art_100");
    return new AuditQueryResult(List.of(row), 42, nextCursor);
  }

  @Test
  void byRunHappyPathMapsEnumsToWireStringsAndEchoesCursor() throws Exception {
    when(auditQueryService.queryByRun(eq("run_abc123"), any()))
        .thenReturn(resultFixture("cursor_next_page"));

    mockMvc
        .perform(get("/api/v1/audit/by-run/run_abc123").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.totalCount").value(42))
        .andExpect(jsonPath("$.nextCursor").value("cursor_next_page"))
        .andExpect(jsonPath("$.events[0].eventId").value("evt_abc123"))
        .andExpect(jsonPath("$.events[0].eventType").value("runner.failed"))
        .andExpect(jsonPath("$.events[0].workflowRunId").value("run_abc123"))
        .andExpect(jsonPath("$.events[0].actorType").value("system"))
        .andExpect(jsonPath("$.events[0].priorState").value("Executing"))
        .andExpect(jsonPath("$.events[0].resultingState").value("Failed"))
        .andExpect(jsonPath("$.events[0].failureCategory").value("orphan"))
        .andExpect(jsonPath("$.events[0].correlationId").value("cor_xyz"))
        .andExpect(jsonPath("$.events[0].linkedArtifactId").value("art_100"));
  }

  @Test
  void nullNextCursorSerializesAsJsonNull() throws Exception {
    when(auditQueryService.queryByTicket(any(), any())).thenReturn(resultFixture(null));

    mockMvc
        .perform(get("/api/v1/audit/by-ticket/LIN-123").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").value(Matchers.nullValue()));
  }

  @Test
  void repeatableEventTypeParamsMapToFilterTokens() throws Exception {
    when(auditQueryService.queryByTicket(any(), any())).thenReturn(resultFixture(null));

    mockMvc
        .perform(
            get("/api/v1/audit/by-ticket/LIN-123")
                .param("eventType", "workflow.stateChanged")
                .param("eventType", "runner.failed")
                .param("actor", "system")
                .param("limit", "25")
                .param("cursor", "opaque_cursor")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    ArgumentCaptor<AuditQueryFilter> captor = ArgumentCaptor.forClass(AuditQueryFilter.class);
    verify(auditQueryService).queryByTicket(eq("LIN-123"), captor.capture());
    AuditQueryFilter filter = captor.getValue();
    assertThat(filter.eventTypeTokens()).containsExactly("workflow.stateChanged", "runner.failed");
    assertThat(filter.actor()).isEqualTo("system");
    assertThat(filter.limit()).isEqualTo(25);
    assertThat(filter.cursor()).isEqualTo("opaque_cursor");
  }

  @Test
  void absentParamsDefaultToEmptyFiltersAndLimit50() throws Exception {
    when(auditQueryService.queryByRun(any(), any())).thenReturn(resultFixture(null));

    mockMvc
        .perform(get("/api/v1/audit/by-run/run_abc123").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    ArgumentCaptor<AuditQueryFilter> captor = ArgumentCaptor.forClass(AuditQueryFilter.class);
    verify(auditQueryService).queryByRun(eq("run_abc123"), captor.capture());
    AuditQueryFilter filter = captor.getValue();
    assertThat(filter.eventTypeTokens()).isEmpty();
    assertThat(filter.actor()).isNull();
    assertThat(filter.since()).isNull();
    assertThat(filter.until()).isNull();
    assertThat(filter.limit()).isEqualTo(50);
    assertThat(filter.cursor()).isNull();
  }

  @Test
  void invalidAuditFilterSurfaces400() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("eventType", "bogus");
    when(auditQueryService.queryByRun(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_AUDIT_FILTER,
                "Unknown --event-type value: bogus",
                details));

    mockMvc
        .perform(
            get("/api/v1/audit/by-run/run_abc123")
                .param("eventType", "bogus")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_AUDIT_FILTER"));
  }

  @Test
  void runNotFoundSurfaces404() throws Exception {
    when(auditQueryService.queryByRun(any(), any()))
        .thenThrow(new DomainException(DomainErrorCode.RUN_NOT_FOUND, "no such run", Map.of()));

    mockMvc
        .perform(get("/api/v1/audit/by-run/run_missing1").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void noIdempotencyKeyRequiredForRead() throws Exception {
    when(auditQueryService.queryByRun(any(), any())).thenReturn(resultFixture(null));

    mockMvc
        .perform(get("/api/v1/audit/by-run/run_abc123").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }
}
