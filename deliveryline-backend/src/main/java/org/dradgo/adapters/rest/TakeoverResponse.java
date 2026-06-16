package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.recovery.TakeoverResult;

/**
 * Response body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/takeover} (story 3.25).
 * The rich takeover result DTO (R5): unlike {@link WorkflowStateChangeResponse} (reused by
 * accept-/reject-implementation), it carries AC8's cancelled-runner counts + the preserved GitHub
 * PR reference, mapped from the {@code done} story 3.22 {@link TakeoverResult}.
 *
 * <p>Nullability mirrors {@link TakeoverResult}: {@code cancelledInFlightCount} / {@code
 * cancelledQueuedCount} / {@code preservedPrReference} are {@code null} on an idempotent replay
 * (the cancels already happened on the original call and are not recomputed) and {@code
 * preservedPrReference} is also {@code null} when the run carries no GitHub PR link; {@code
 * correlationId} is {@code null} in test slices that don't register {@code CorrelationIdFilter}.
 * The structural fields ({@code workflowRunId}, {@code currentState}, {@code recoveryActionId},
 * {@code replayed}) are marked {@code requiredMode=REQUIRED} so generated TS clients can rely on
 * them.
 */
public record TakeoverResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer cancelledInFlightCount,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer cancelledQueuedCount,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String preservedPrReference,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  public static TakeoverResponse from(TakeoverResult result) {
    String currentState = result.resultingState() == null ? null : result.resultingState().value();
    return new TakeoverResponse(
        result.workflowRunId(),
        currentState,
        result.recoveryActionPublicId(),
        result.cancelledInFlightCount(),
        result.cancelledQueuedCount(),
        result.preservedPrReference(),
        result.correlationId(),
        result.replayed());
  }
}
