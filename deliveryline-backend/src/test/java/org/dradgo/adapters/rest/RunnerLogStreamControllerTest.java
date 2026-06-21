package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.dradgo.application.workflow.StepLogStreamService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsVersionStamp;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsView;
import org.dradgo.domain.registry.AllowedAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Story 3d-5 (AC6 / Trap T5) — SERVER-SIDE allowed-action enforcement on the SSE stream endpoint:
 * the stream is opened only when {@code view_runner_logs} is present in the run's allowed actions.
 * Plain unit test (no MockMvc/SSE plumbing) asserting the gate decides BEFORE the follow/replay is
 * ever engaged.
 */
class RunnerLogStreamControllerTest {

  private static final String RUN = "run_capture0000001";

  private final StepLogStreamService stepLogStreamService = mock(StepLogStreamService.class);
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final RunnerLogStreamController controller =
      new RunnerLogStreamController(stepLogStreamService, inspection);

  @AfterEach
  void tearDown() {
    controller.shutdownExecutor();
  }

  @Test
  void opensStreamWhenViewRunnerLogsAllowed() throws Exception {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(view(List.of(AllowedAction.VIEW_ONLY, AllowedAction.VIEW_RUNNER_LOGS)));

    SseEmitter emitter = controller.streamRunnerLogs(RUN, "workflow_owner");

    assertThat(emitter).isNotNull();
    // The follow/replay is engaged (asynchronously on the bounded executor).
    verify(stepLogStreamService, timeout(2000)).streamRunnerLogs(eq(RUN), any());
  }

  @Test
  void deniesStreamWhenViewRunnerLogsAbsent() {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(view(List.of(AllowedAction.VIEW_ONLY)));

    SseEmitter emitter = controller.streamRunnerLogs(RUN, "product_reviewer");

    assertThat(emitter).isNotNull();
    // Server-side denial: the follow/replay is NEVER engaged when the action is absent.
    verify(stepLogStreamService, never()).streamRunnerLogs(any(), any());
  }

  @Test
  void streamEndpointAddsNoOwnBindingAndInheritsTheLoopbackGuard() throws Exception {
    // AC8 + Task 5 — localhost-only posture. The endpoint introduces NO binding of its own: it is a
    // plain MVC mapping under the shared /api/v1/workflows path, so it is served only over the
    // application-wide loopback binding (server.address=127.0.0.1 enforced by RestBindingGuard,
    // story 6.9 — authoritatively tested in RestBindingGuardTest, which covers EVERY endpoint
    // including this one). This test pins the "no new binding" invariant so a future edit that adds
    // a host/port-specific mapping here is caught.
    RequestMapping classMapping =
        RunnerLogStreamController.class.getAnnotation(RequestMapping.class);
    assertThat(classMapping).isNotNull();
    assertThat(classMapping.value()).containsExactly("/api/v1/workflows");

    Method endpoint =
        RunnerLogStreamController.class.getMethod("streamRunnerLogs", String.class, String.class);
    GetMapping getMapping = endpoint.getAnnotation(GetMapping.class);
    assertThat(getMapping).isNotNull();
    assertThat(getMapping.value()).containsExactly("/{workflowRunId}/runner-logs/stream");
    // No `consumes`/`headers`/host conditions that would carve out an alternate binding surface.
    assertThat(getMapping.headers()).isEmpty();
    assertThat(getMapping.params()).isEmpty();
  }

  private static AllowedActionsView view(List<AllowedAction> actions) {
    return new AllowedActionsView(
        actions, new AllowedActionsVersionStamp("Executing", null, null, null));
  }
}
