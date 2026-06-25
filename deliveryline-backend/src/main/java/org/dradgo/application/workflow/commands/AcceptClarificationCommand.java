package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3e-2 (AC1): command for accepting an answered clarification (answered -&gt; accepted, story
 * 2.12 lifecycle). The structural twin of {@link SubmitClarificationCommand}, but carries NO
 * artifact-version binding: {@link
 * org.dradgo.application.clarification.ClarificationLifecycleService#markAccepted} acts on the
 * already-answered row (its semantic identity is {@code workflowRunId + clarificationId}), so there
 * is no spec version to pin (trap: pinning a version here would reject a valid accept after an
 * unrelated spec re-dispatch bumped the version).
 *
 * <p>Accepting is an explicit PM judgment that the answer is ready to drive a spec rebuild — the
 * sweep ({@code ClarificationLifecycleOrchestrator}) acts ONLY on {@code accepted} rows.
 * Re-accepting an already-{@code accepted} row replays idempotently (lifecycle {@code
 * ALREADY_AT_TARGET}).
 */
public record AcceptClarificationCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String clarificationId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId)
    implements WorkflowCommand {}
