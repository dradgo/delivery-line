package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3e-2 (AC2): command for regenerating the spec so the run's accepted clarifications are
 * incorporated. Structural twin of {@link RejectSpecCommand}'s transition-then-redispatch flow, but
 * carries no approval row, no artifact-version binding, and no taxonomy — it is a forward re-run
 * request, not a decision on a specific spec version.
 *
 * <p>The canonical executor ({@code WorkflowCommandService.regenerateSpecWithClarifications})
 * performs the {@code WaitingForSpecApproval -> Investigating} transition then reuses {@code
 * WorkflowOrchestrationService.retrySpecGeneration} (re-dispatch only, Trap T8) in the SAME
 * transaction — a dispatch failure rolls back the transition.
 */
public record RegenerateSpecCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId)
    implements WorkflowCommand {}
