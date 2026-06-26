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
import org.dradgo.application.workflow.DiagnosticConsoleService;
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
 * Story 3d-6 (AC4 / Trap T5) — SERVER-SIDE allowed-action enforcement on the diagnostic-console SSE
 * endpoint: the attach is opened only when {@code open_diagnostic_console} is present in the run's
 * allowed actions (EXECUTING or INVESTIGATING + workflow_owner). Plain unit test (no MockMvc/SSE
 * plumbing) asserting the gate decides BEFORE the attach is ever engaged, plus the localhost-only
 * no-own-binding invariant (AC5).
 */
class RunnerDiagnosticConsoleControllerTest {

  private static final String RUN = "run_console0000001";

  private final DiagnosticConsoleService diagnosticConsoleService =
      mock(DiagnosticConsoleService.class);
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final RunnerDiagnosticConsoleController controller =
      new RunnerDiagnosticConsoleController(diagnosticConsoleService, inspection);

  @AfterEach
  void tearDown() {
    controller.shutdownExecutor();
  }

  @Test
  void opensConsoleWhenOpenDiagnosticConsoleAllowed() throws Exception {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(
            view(
                List.of(
                    AllowedAction.VIEW_ONLY,
                    AllowedAction.AWAIT_OUTCOME,
                    AllowedAction.VIEW_RUNNER_LOGS,
                    AllowedAction.OPEN_DIAGNOSTIC_CONSOLE)));

    SseEmitter emitter = controller.streamDiagnosticConsole(RUN, "workflow_owner");

    assertThat(emitter).isNotNull();
    // The attach is engaged (asynchronously on the bounded executor).
    verify(diagnosticConsoleService, timeout(2000))
        .openConsole(eq(RUN), eq("workflow_owner"), any());
  }

  @Test
  void deniesConsoleWhenOpenDiagnosticConsoleAbsent() {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(view(List.of(AllowedAction.VIEW_ONLY, AllowedAction.VIEW_RUNNER_LOGS)));

    SseEmitter emitter = controller.streamDiagnosticConsole(RUN, "product_reviewer");

    assertThat(emitter).isNotNull();
    // Server-side denial (Trap T5): the attach is NEVER engaged when the action is absent.
    verify(diagnosticConsoleService, never()).openConsole(any(), any(), any());
  }

  @Test
  void consoleEndpointAddsNoOwnBindingAndInheritsTheLoopbackGuard() throws Exception {
    // AC5 — localhost-only posture. The endpoint introduces NO binding of its own: it is a plain
    // MVC
    // mapping under the shared /api/v1/workflows path, so it is served only over the
    // application-wide
    // loopback binding (server.address=127.0.0.1 enforced by RestBindingGuard, story 6.9). This
    // test
    // pins the "no new binding" invariant so a future edit that adds a host/port-specific mapping
    // is
    // caught (mirrors 3d-5's streamEndpointAddsNoOwnBindingAndInheritsTheLoopbackGuard).
    RequestMapping classMapping =
        RunnerDiagnosticConsoleController.class.getAnnotation(RequestMapping.class);
    assertThat(classMapping).isNotNull();
    assertThat(classMapping.value()).containsExactly("/api/v1/workflows");

    Method endpoint =
        RunnerDiagnosticConsoleController.class.getMethod(
            "streamDiagnosticConsole", String.class, String.class);
    GetMapping getMapping = endpoint.getAnnotation(GetMapping.class);
    assertThat(getMapping).isNotNull();
    assertThat(getMapping.value()).containsExactly("/{workflowRunId}/diagnostic-console/stream");
    // No `consumes`/`headers`/host conditions that would carve out an alternate binding surface.
    assertThat(getMapping.headers()).isEmpty();
    assertThat(getMapping.params()).isEmpty();
  }

  private static AllowedActionsView view(List<AllowedAction> actions) {
    return new AllowedActionsView(
        actions, new AllowedActionsVersionStamp("Executing", null, null, null));
  }
}
