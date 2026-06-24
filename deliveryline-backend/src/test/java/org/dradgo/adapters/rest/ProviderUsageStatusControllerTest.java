package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.runner.ProviderUsageSnapshotView;
import org.dradgo.application.runner.ProviderUsageSnapshotView.UsageWindow;
import org.dradgo.application.runner.ProviderUsageStatusService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsVersionStamp;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsView;
import org.dradgo.domain.registry.AllowedAction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Story 3d-7 (FR69, AC5 / Trap T5) — SERVER-SIDE allowed-action enforcement + body mapping for the
 * provider-usage read endpoint: the snapshot is returned only when {@code
 * view_provider_usage_status} is present; otherwise the endpoint denies (403) WITHOUT reading the
 * snapshot. Plain unit test (no MockMvc).
 */
class ProviderUsageStatusControllerTest {

  private static final String RUN = "run_provider000001";

  private final ProviderUsageStatusService service = mock(ProviderUsageStatusService.class);
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final ProviderUsageStatusController controller =
      new ProviderUsageStatusController(service, inspection);

  @Test
  void returnsSnapshotWhenActionAllowed() {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(
            view(List.of(AllowedAction.VIEW_ONLY, AllowedAction.VIEW_PROVIDER_USAGE_STATUS)));
    when(service.getLatestForRun(RUN))
        .thenReturn(
            Optional.of(
                new ProviderUsageSnapshotView(
                    "pul_test0001",
                    RUN,
                    "rex_test0001",
                    "claude:oauth",
                    "available",
                    new UsageWindow(0.62, 62, 100, OffsetDateTime.parse("2030-01-01T05:00:00Z")),
                    new UsageWindow(0.18, 126, 700, OffsetDateTime.parse("2030-01-06T00:00:00Z")),
                    OffsetDateTime.parse("2026-06-23T09:05:00Z"),
                    OffsetDateTime.parse("2026-06-23T09:05:01Z"))));

    ResponseEntity<ProviderUsageStatusResponse> response =
        controller.getProviderUsageStatus(RUN, "workflow_owner");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ProviderUsageStatusResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.present()).isTrue();
    assertThat(body.signalState()).isEqualTo("available");
    assertThat(body.accountReference()).isEqualTo("claude:oauth");
    assertThat(body.fiveHour().used()).isEqualTo(62);
  }

  @Test
  void mapsNotExposedWindowsToNull() {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(view(List.of(AllowedAction.VIEW_PROVIDER_USAGE_STATUS)));
    when(service.getLatestForRun(RUN))
        .thenReturn(
            Optional.of(
                new ProviderUsageSnapshotView(
                    "pul_test0002",
                    RUN,
                    null,
                    "codex:subscription",
                    "not_exposed",
                    new UsageWindow(null, null, null, null),
                    new UsageWindow(null, null, null, null),
                    null,
                    OffsetDateTime.parse("2026-06-23T09:05:01Z"))));

    ProviderUsageStatusResponse body = controller.getProviderUsageStatus(RUN, null).getBody();

    assertThat(body).isNotNull();
    assertThat(body.signalState()).isEqualTo("not_exposed");
    assertThat(body.fiveHour()).isNull();
    assertThat(body.weekly()).isNull();
  }

  @Test
  void returnsPresentFalseWhenNoSnapshotCaptured() {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(view(List.of(AllowedAction.VIEW_PROVIDER_USAGE_STATUS)));
    when(service.getLatestForRun(RUN)).thenReturn(Optional.empty());

    ProviderUsageStatusResponse body =
        controller.getProviderUsageStatus(RUN, "product_reviewer").getBody();

    assertThat(body).isNotNull();
    assertThat(body.present()).isFalse();
  }

  @Test
  void deniesWithForbiddenWhenActionAbsentAndNeverReadsSnapshot() {
    when(inspection.getAllowedActions(eq(RUN), any()))
        .thenReturn(view(List.of(AllowedAction.VIEW_ONLY)));

    ResponseEntity<ProviderUsageStatusResponse> response =
        controller.getProviderUsageStatus(RUN, "product_reviewer");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    // Server-side denial: the snapshot is NEVER read when the action is absent (Trap T5).
    verify(service, never()).getLatestForRun(any());
  }

  private static AllowedActionsView view(List<AllowedAction> actions) {
    return new AllowedActionsView(
        actions, new AllowedActionsVersionStamp("Executing", null, null, null));
  }
}
