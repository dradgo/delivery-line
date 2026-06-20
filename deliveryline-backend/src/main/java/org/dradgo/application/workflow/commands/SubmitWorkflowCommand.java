package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Canonical fingerprint fields after the shared envelope are: {@code linearTicketReference}.
 *
 * <p>Story 3c-7 (AC1) — an <strong>optional</strong> {@code projectReference} (project slug or
 * {@code prj_} public id) is the trailing component. When present, {@code
 * WorkflowCommandService.submitInternal} resolves it (explicit reference → the named project, else
 * {@code PROJECT_NOT_FOUND}); when null the 3c-6 {@code default}-project fallback stands. It is
 * deliberately <strong>not</strong> part of the idempotency fingerprint ({@code
 * WorkflowCommandFingerprintFactory} is unchanged): a {@code default}-project submit stays
 * byte-identical to pre-3c (AC7 parity). The back-compat 5-arg constructor keeps every existing
 * construction site (REST/CLI/batch/poller/tests) unchanged (binds to {@code default}).
 */
public record SubmitWorkflowCommand(
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @NotBlank @Size(max = 128) String linearTicketReference,
    @Size(max = 128) String projectReference)
    implements WorkflowCommand {

  /**
   * Back-compat constructor (pre-3c-7 canonical shape) — no explicit project reference, so the run
   * binds to the reserved {@code default} project via the 3c-6 fallback.
   */
  public SubmitWorkflowCommand(
      String actorIdentity,
      ActorType actorType,
      String idempotencyKey,
      String correlationId,
      String linearTicketReference) {
    this(actorIdentity, actorType, idempotencyKey, correlationId, linearTicketReference, null);
  }
}
