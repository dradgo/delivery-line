package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 2.13 logging-instrumentation pin for the three new mutation endpoints. Each entry log MUST
 * carry {@code workflowRunId} and {@code actorIdentity} so a log scrape correlating across services
 * doesn't have to JOIN against the audit table to identify who triggered the mutation. Free-form
 * answer text is forbidden in log output (story 2.11 trap T12) — only {@code answerTextLength} is
 * emitted.
 */
@WebMvcTest(controllers = WorkflowController.class)
@Import(ApprovalReviewerRoleResolver.class)
class WorkflowControllerLoggingContractTest {

  private static final String RUN_ID = "run_logging_a";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private WorkflowCommandService workflowCommandService;
  @MockitoBean private WorkflowInspectionService workflowInspectionService;
  @MockitoBean private LocalActorIdentityResolver localActorIdentityResolver;

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    when(localActorIdentityResolver.resolve(any()))
        .thenAnswer(
            invocation -> {
              String supplied = invocation.getArgument(0);
              if (supplied == null || supplied.isBlank()) {
                return "local-operator";
              }
              return supplied.trim();
            });

    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(WorkflowController.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void approveSpecEntryLogIncludesActorIdentityAndWorkflowRunId() throws Exception {
    when(workflowCommandService.approveSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.EXECUTING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/approve-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-logging-a-aaaaaaaaaa")
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "art_logging_a",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1
                    }
                    """))
        .andExpect(status().isOk());

    assertThat(
            appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList())
        .anyMatch(
            line ->
                line.contains("REST approve-spec received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex"));
  }

  @Test
  void rejectSpecEntryLogIncludesActorIdentityAndWorkflowRunId() throws Exception {
    // Round-3 review follow-up: the production controller already emits actorIdentity in the
    // entry log for all three mutation endpoints; this pin locks the reject-spec arm so a future
    // refactor can't silently drop the field on one verb.
    when(workflowCommandService.rejectSpec(any()))
        .thenReturn(new WorkflowStateChangeResult(RUN_ID, WorkflowState.INVESTIGATING, null));

    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/reject-spec", RUN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-logging-r-aaaaaaaaaa")
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "art_logging_r",
                      "expectedArtifactVersion": 1,
                      "expectedContextBundleVersion": 1,
                      "taggedFeedback": "MISSING_SCOPE",
                      "reasonText": "Scope unclear."
                    }
                    """))
        .andExpect(status().isOk());

    assertThat(
            appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList())
        .anyMatch(
            line ->
                line.contains("REST reject-spec received")
                    && line.contains("workflowRunId=" + RUN_ID)
                    && line.contains("actorIdentity=alex"));
  }

  @Test
  void answerClarificationEntryLogEmitsLengthOnlyTelemetry() throws Exception {
    when(workflowCommandService.answerClarification(any()))
        .thenReturn(
            new WorkflowStateChangeResult(
                RUN_ID, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, "answered"));

    String secretAnswer = "TOP-SECRET-PRODUCT-DETAILS-12345";
    String secretSubstring = "TOP-SECRET";
    mockMvc
        .perform(
            post("/api/v1/workflows/{runId}/clarifications/{clarId}/answer", RUN_ID, "clr_log_a")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-logging-clar-aaaaaaaa")
                .header("X-Actor-Identity", "alex")
                .content(
                    """
                    {
                      "artifactId": "art_logging_a",
                      "expectedArtifactVersion": 1,
                      "answerText": "%s"
                    }
                    """
                        .formatted(secretAnswer)))
        .andExpect(status().isOk());

    String entryLine =
        appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(line -> line.contains("REST answer-clarification received"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected REST answer-clarification entry log"));

    assertThat(entryLine).contains("answerTextLength=" + secretAnswer.length());
    assertThat(entryLine)
        .as("answerText payload must never appear verbatim in logs (story 2.11 trap T12)")
        .doesNotContain(secretAnswer)
        .as("unambiguous substring must also be absent so truncated/escaped partial leaks fail")
        .doesNotContain(secretSubstring);

    // Round-3 review follow-up: extend the no-leak guarantee to the success log line so a future
    // change adding answerText to the success log surfaces as a contract regression here rather
    // than slipping past the entry-log-only check.
    List<String> infoLines =
        appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    assertThat(infoLines)
        .as("answerText payload must not appear verbatim in any INFO log line, including success")
        .noneMatch(line -> line.contains(secretAnswer))
        .as("substring of answerText must not appear in any INFO log line either")
        .noneMatch(line -> line.contains(secretSubstring));
  }
}
