package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.recovery.ResumeRecoveryResult;

/**
 * Story 4.10 (AC8) — response body for {@code POST /api/v1/workflows/{workflowRunId}/resume}. Rich
 * recovery-result DTO mapped from the {@code done} story 4.5 {@link ResumeRecoveryResult}, carrying
 * the recovered prior-executing state, the {@code recovery_actions} row id ({@code rcv_} prefix),
 * the {@code recovery.resumed} event id, the re-dispatched runner-execution id, the stamped
 * correlation id, and the idempotent-replay flag.
 *
 * <p>Nullability mirrors {@link ResumeRecoveryResult}:
 *
 * <ul>
 *   <li>{@code currentState} is <strong>nullable</strong> — {@code RecoveryService.resume}'s replay
 *       branch returns {@code resolvePriorExecutingStateForReplay(...).orElse(null)} as {@code
 *       resultingState} (story 4.5's deferred review finding, whose named consumer is this story).
 *       It is {@code null} only on a replay whose {@code → Paused} anchor event can no longer be
 *       resolved (re-pause / event archival); on a fresh resume it is always present.
 *   <li>{@code runnerExecutionId} is {@code null} on an idempotent replay (no re-dispatch occurs)
 *       AND on a fresh resume when auto-dispatch is off (the shared test profile).
 *   <li>{@code resumedEventId} carries the {@code recovery.resumed} event id on a fresh resume; on
 *       replay it carries the prior row's {@code resulting_event_id}.
 *   <li>{@code correlationId} is {@code null} in {@code @WebMvcTest} slices that don't register
 *       {@code CorrelationIdFilter}.
 * </ul>
 *
 * <p>The structural fields ({@code workflowRunId}, {@code recoveryActionId}, {@code replayed}) are
 * marked {@code requiredMode=REQUIRED} so generated TS clients can rely on them.
 */
public record ResumeResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String resumedEventId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String runnerExecutionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  /**
   * Maps the story 4.5 {@link ResumeRecoveryResult} onto the wire DTO. {@code workflowRunId} is
   * passed explicitly from the {@code @PathVariable} because {@link ResumeRecoveryResult} — unlike
   * {@code TakeoverResult} — carries no {@code workflowRunId} field. The {@code resultingState} is
   * null-guarded before {@code .value()} to honour the nullable replay path (Reconciliation 6).
   */
  public static ResumeResponse from(String workflowRunId, ResumeRecoveryResult result) {
    String currentState = result.resultingState() == null ? null : result.resultingState().value();
    return new ResumeResponse(
        workflowRunId,
        currentState,
        result.recoveryActionPublicId(),
        result.resumedEventPublicId(),
        result.newRunnerExecutionPublicId(),
        result.correlationId(),
        result.replayed());
  }
}
