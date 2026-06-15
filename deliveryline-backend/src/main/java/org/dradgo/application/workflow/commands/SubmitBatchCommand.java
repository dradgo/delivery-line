package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3.18 — batch submission command.
 *
 * <p><strong>Deliberately NOT a {@link WorkflowCommand}.</strong> Batch submission is an
 * <em>orchestrator over</em> the single-submit command model, not a workflow command in its own
 * right (Reconciliation 3 / Decision D-CMD). Adding it to the sealed {@code WorkflowCommand} permit
 * set would red {@code CommandModelSymmetryFoundationContract.EXPECTED_PERMITS} and obligate a
 * per-permit REST round-trip capture — both conceptually wrong. The batch-level idempotency
 * fingerprint is computed locally by {@code WorkflowBatchSubmissionService} and reserved through
 * {@code IdempotencyService.checkAndReserve} directly, so this record never needs to participate in
 * {@code WorkflowCommandFingerprintFactory}.
 *
 * <p>{@code linearTicketReferences} are bare {@code String}s — there is no {@code LinearTicketRef}
 * type in the live model (Reconciliation 2). The {@code @Size(max)} cap on the list is a hard upper
 * safety bound; the operator-configurable {@code deliveryline.workflow.batch-max-tickets} ceiling
 * (default 100) is enforced in the service so it can vary without recompiling.
 */
public record SubmitBatchCommand(
    @NotEmpty @Size(max = 1000) List<@NotBlank @Size(max = 128) String> linearTicketReferences,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId) {}
