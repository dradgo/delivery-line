package org.dradgo.adapters.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.RunnerLogDownloadAuditService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.RedactedRunnerLogView;
import org.dradgo.domain.registry.ActorType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 4.4 (AC5/AC10) — slice contract for {@code GET
 * /api/v1/runner-executions/&#123;rexId&#125;/logs/download}: the redacted {@code text/plain}
 * attachment, the {@code view_runner_logs} gate, the best-effort {@code audit.logDownloaded}
 * append, and the 404 surfaces. Assertions on status / headers / {@code code} only (never human
 * text).
 */
@WebMvcTest(controllers = RunnerExecutionController.class)
class RunnerExecutionControllerTest {

  private static final String REX = "rex_download123456";
  private static final String RUN = "run_download123456";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowInspectionService inspection;
  @MockitoBean private RunnerLogDownloadAuditService audit;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

  private void stubAvailable() {
    when(inspection.getRedactedRunnerLog(REX))
        .thenReturn(
            RedactedRunnerLogView.available(
                REX, RUN, "stdout body", "stderr body", false, "shareable-redacted", 128L));
    when(localActorIdentityResolver.resolve(any())).thenReturn("local-operator");
  }

  @Test
  void servesRedactedLogAsTextPlainAttachmentAndAppendsAudit() throws Exception {
    stubAvailable();
    when(inspection.isActionAllowed(eq(RUN), any(), eq("view_runner_logs"))).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/runner-executions/{rexId}/logs/download", REX))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")))
        .andExpect(
            header()
                .string("Content-Disposition", Matchers.containsString("runner-" + REX + ".log")))
        .andExpect(content().string(Matchers.containsString("stdout body")))
        .andExpect(content().string(Matchers.containsString("stderr body")));

    verify(audit).recordLogDownloaded(eq(RUN), eq(REX), eq("local-operator"), eq(ActorType.HUMAN));
  }

  @Test
  void returns404WhenLogUnavailable() throws Exception {
    when(inspection.getRedactedRunnerLog(REX))
        .thenReturn(RedactedRunnerLogView.unavailable(REX, "logsNotCaptured"));

    mockMvc
        .perform(get("/api/v1/runner-executions/{rexId}/logs/download", REX))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUNNER_EXECUTION_NOT_FOUND"));

    verify(audit, never()).recordLogDownloaded(any(), any(), any(), any());
  }

  @Test
  void returns404WhenViewRunnerLogsNotAllowed() throws Exception {
    stubAvailable();
    when(inspection.isActionAllowed(eq(RUN), any(), eq("view_runner_logs"))).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/runner-executions/{rexId}/logs/download", REX))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RUNNER_EXECUTION_NOT_FOUND"));

    verify(audit, never()).recordLogDownloaded(any(), any(), any(), any());
  }

  @Test
  void downloadSucceedsEvenWhenAuditAppendFails() throws Exception {
    stubAvailable();
    when(inspection.isActionAllowed(eq(RUN), any(), eq("view_runner_logs"))).thenReturn(true);
    org.mockito.Mockito.doThrow(new RuntimeException("audit boom"))
        .when(audit)
        .recordLogDownloaded(any(), any(), any(), any());

    mockMvc
        .perform(get("/api/v1/runner-executions/{rexId}/logs/download", REX))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("stdout body")));
  }
}
