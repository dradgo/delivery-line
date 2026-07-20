package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3h-2 (AC5, FR76) — operator {@code request_lint_fix}: feed the lint findings back to the
 * implementation runner (re-dispatch EXECUTION), bump {@code lint_fix_loop_count}, and re-park at
 * {@code WaitingForLintApproval}. Never auto-fails the run (Decision 3). The optional {@code
 * reasonText} rides the redaction-policed feedback. Canonical fingerprint fields after the shared
 * envelope: {@code workflowRunId}, {@code reasonText}.
 */
public record RequestLintFixCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @Size(max = 16384) String reasonText)
    implements WorkflowCommand {}
