package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.recovery.ClassifyFailureResult;

/**
 * Story 4.14 (AC8) — response body for {@code POST
 * /api/v1/workflows/{workflowRunId}/classify-failure}. Metadata-only recovery-result DTO mapped
 * from the {@code done} story 4.9 {@link ClassifyFailureResult}, carrying the applied taxonomy wire
 * value, the value this classification overwrote (on re-classification), the {@code
 * recovery_actions} row id ({@code rcv_} prefix), the {@code recovery.failureClassified} event id,
 * the stamped correlation id, and the idempotent-replay flag.
 *
 * <p><strong>There is NO workflow-state field — classify is the ONLY sibling whose response carries
 * none (Reconciliation 6).</strong> Classify performs no state transition (epic 4.9 AC10 — pure
 * metadata), so {@link ClassifyFailureResult} carries no {@code resultingState}/{@code priorState},
 * and this DTO deliberately diverges from {@link ReconcileResponse#currentState}, {@code
 * ResumeResponse.currentState}, and {@code PauseResponse.currentState}/{@code priorState}: there is
 * nothing to report. Do NOT add a {@code currentState} field.
 *
 * <p>Nullability of the fields:
 *
 * <ul>
 *   <li>{@code taxonomyValue} is the applied wire value — always present on both fresh + replay
 *       paths → REQUIRED, mapped directly ({@code result.taxonomyValue()}).
 *   <li>{@code priorTaxonomyValue} is the value this classification overwrote — {@code null} on a
 *       first classify, non-null on re-classification (epic AC8 renders "classified as X
 *       (previously Y)") → NOT_REQUIRED, mapped directly ({@code result.priorTaxonomyValue()} —
 *       already a nullable {@code String}, no enum unwrap).
 *   <li>{@code recoveryActionId} matches {@code ^rcv_} → REQUIRED.
 *   <li>{@code classifiedEventId} carries the {@code recovery.failureClassified} {@code evt_} id
 *       (fresh) or the prior row's resulting-event id (replay) → NOT_REQUIRED for defensive
 *       tolerance.
 *   <li>{@code correlationId} is {@code null} in {@code @WebMvcTest} slices that don't register
 *       {@code CorrelationIdFilter}.
 * </ul>
 */
public record ClassifyFailureResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String taxonomyValue,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String priorTaxonomyValue,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String classifiedEventId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  /**
   * Maps the story 4.9 {@link ClassifyFailureResult} onto the wire DTO. {@code workflowRunId} is
   * passed explicitly from the {@code @PathVariable} because {@link ClassifyFailureResult} — like
   * every recovery-result record — carries no {@code workflowRunId} field. Both {@code
   * taxonomyValue} and {@code priorTaxonomyValue} are already nullable {@code String}s on the
   * result (no enum unwrap); {@code priorTaxonomyValue} is {@code null} on a first classify.
   */
  public static ClassifyFailureResponse from(String workflowRunId, ClassifyFailureResult result) {
    return new ClassifyFailureResponse(
        workflowRunId,
        result.taxonomyValue(),
        result.priorTaxonomyValue(),
        result.recoveryActionPublicId(),
        result.classifiedEventPublicId(),
        result.correlationId(),
        result.replayed());
  }
}
