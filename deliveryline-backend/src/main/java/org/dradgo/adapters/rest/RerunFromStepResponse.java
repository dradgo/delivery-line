package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.recovery.RerunFromStepRecoveryResult;

/**
 * Story 4.12 (AC8) — response body for {@code POST
 * /api/v1/workflows/{workflowRunId}/rerun-from-step}. Rich recovery-result DTO mapped from the
 * {@code done} story 4.7 {@link RerunFromStepRecoveryResult}, carrying the resolved safe-boundary
 * state, the {@code recovery_actions} row id ({@code rcv_} prefix), the {@code
 * recovery.rerunFromStep} event id, the audit lists of superseded artifacts + invalidated
 * approvals, the re-enqueued runner-execution id, the stamped correlation id, and the
 * idempotent-replay flag.
 *
 * <p><strong>{@code currentState} is NON-NULL → {@code requiredMode=REQUIRED} — like {@link
 * ReconcileResponse#currentState}, a deliberate DIVERGENCE from {@link ResumeResponse#currentState}
 * (which is nullable).</strong> Both rerun code paths resolve {@code resultingState} to a concrete
 * {@link org.dradgo.domain.registry.WorkflowState}: the fresh path returns {@code targetState} (the
 * resolved {@code SafeRerunStep} → {@code WorkflowState}, never null); the replay path reads the
 * stored {@code recovery.rerunFromStep} event's resulting state (appended with {@code targetState},
 * so also never null). There is no {@code orElse(null)} here (resume had one — story 4.5's
 * finding), so {@link #from} calls {@code result.resultingState().value()} directly with NO
 * null-guard — a {@code NOT_REQUIRED} here would under-specify a field that is always present.
 *
 * <p>Nullability of the remaining fields (mirrors {@link RerunFromStepRecoveryResult}):
 *
 * <ul>
 *   <li>{@code recoveryActionId} — {@code rcv_...} id of the recovery_actions row; REQUIRED (always
 *       present, fresh or replay).
 *   <li>{@code rerunEventId} carries the {@code recovery.rerunFromStep} event id on a fresh rerun;
 *       on replay it carries the prior row's resulting-event id. Marked NOT_REQUIRED for defensive
 *       tolerance.
 *   <li>{@code supersededArtifactIds} + {@code invalidatedApprovalIds} are documented never-null
 *       (empty when the run has nothing at/beyond the step) — REQUIRED so generated TS clients can
 *       rely on the arrays existing (Reconciliation 7).
 *   <li>{@code runnerExecutionId} ({@code rex_...}) is {@code null} on an idempotent replay (no
 *       re-dispatch) AND on a fresh rerun when auto-dispatch is off (the shared test profile) —
 *       NOT_REQUIRED.
 *   <li>{@code correlationId} is {@code null} in {@code @WebMvcTest} slices that don't register
 *       {@code CorrelationIdFilter}.
 * </ul>
 *
 * <p>The structural fields ({@code workflowRunId}, {@code currentState}, {@code recoveryActionId},
 * the two id lists, {@code replayed}) are marked {@code requiredMode=REQUIRED} so generated TS
 * clients can rely on them.
 */
public record RerunFromStepResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String rerunEventId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> supersededArtifactIds,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> invalidatedApprovalIds,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String runnerExecutionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  /**
   * Maps the story 4.7 {@link RerunFromStepRecoveryResult} onto the wire DTO. {@code workflowRunId}
   * is passed explicitly from the {@code @PathVariable} because {@link RerunFromStepRecoveryResult}
   * — like {@code ResumeRecoveryResult}/{@code ReconcileRecoveryResult} — carries no {@code
   * workflowRunId} field. Unlike {@code ResumeResponse.from}, {@code resultingState()} is
   * dereferenced directly with NO null-guard — both rerun paths resolve a concrete state
   * (Reconciliation 8). The two id lists are mapped straight through (never null — Reconciliation
   * 7).
   */
  public static RerunFromStepResponse from(
      String workflowRunId, RerunFromStepRecoveryResult result) {
    return new RerunFromStepResponse(
        workflowRunId,
        result.resultingState().value(),
        result.recoveryActionPublicId(),
        result.rerunEventPublicId(),
        result.supersededArtifactIds(),
        result.invalidatedApprovalIds(),
        result.newRunnerExecutionPublicId(),
        result.correlationId(),
        result.replayed());
  }
}
