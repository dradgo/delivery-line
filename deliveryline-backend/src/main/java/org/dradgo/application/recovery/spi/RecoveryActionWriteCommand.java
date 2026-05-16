package org.dradgo.application.recovery.spi;

import org.dradgo.domain.registry.ActorType;

/**
 * SPI write command for inserting a row into {@code recovery_actions} (story 1.18 Task 2).
 *
 * <p>Field set mirrors the V1 schema column set excluding server-defaults
 * ({@code id}, {@code public_id}, {@code created_at}) which the adapter generates / the database
 * supplies. {@code reviewer_role} is intentionally not exposed — Epic 4's takeover/reconcile
 * surface owns it; story 1.18 always persists NULL.
 *
 * @param workflowRunPublicId  required FK target ({@code run_…})
 * @param actionType           one of {@code retry, rerun, resume, takeover, pause, reconcile}
 * @param triggeringEventPublicId nullable FK target ({@code evt_…}) — the event that caused the recovery
 * @param resultingEventPublicId  nullable FK target ({@code evt_…}) — the event the recovery emitted
 * @param actorIdentity        non-blank operator / agent identity
 * @param actorType            one of {@link ActorType}
 * @param idempotencyKey       caller-supplied idempotency key; uniqueness enforced by V1 CHECK
 * @param resultStatus         one of {@code pending, succeeded, failed} — start with {@code pending}
 *                             and flip via {@link RecoveryActionRecordPort#markSucceeded(String)} /
 *                             {@link RecoveryActionRecordPort#markFailed(String)}.
 */
public record RecoveryActionWriteCommand(
	String workflowRunPublicId,
	String actionType,
	String triggeringEventPublicId,
	String resultingEventPublicId,
	String actorIdentity,
	ActorType actorType,
	String idempotencyKey,
	String resultStatus
) {
}
