package org.dradgo.application.workflow;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Story 3.18 — aggregate result of a batch submission.
 *
 * <p>Standalone result carrier (not a {@link DomainResult} — batch is an orchestrator, not a single
 * workflow command, so it has no {@code currentState}). {@code batchId} carries the {@code bat_}
 * public id; {@code submittedAt} is the persisted {@code created_at} of the {@code
 * batch_submissions} row so the value is identical on an idempotent replay. {@code tickets}
 * preserves per-ticket order and outcome (queued + rejected) for the CLI table / REST body / replay
 * reconstruction.
 */
public record BatchSubmissionResult(
    String batchId,
    OffsetDateTime submittedAt,
    String actorIdentity,
    int total,
    int queuedCount,
    int rejectedCount,
    List<TicketBatchResult> tickets) {}
