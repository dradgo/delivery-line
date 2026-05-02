# ADR 0002: Idempotency Stale Reservation Policy

## Status

Accepted

## Context

DeliveryLine's foundation phase now persists idempotency reservations before command replay/conflict decisions are made. At this phase there is no recovery service, runner evidence inspection, or operator workflow that can safely prove whether a long-lived `reserved` row represents a still-active mutation or a crashed midpoint after side effects were attempted.

That gap makes stale `reserved` rows the highest-risk path to silent double execution. If the system were to treat an old reservation as safe to rerun by default, the same workflow mutation could be applied twice without trustworthy evidence.

## Decision

Use a fail-closed stale reservation policy.

- Threshold: `10 minutes`
- Governing error: `STALE_IDEMPOTENCY_RESERVATION`
- REST mapping: HTTP `409`
- CLI mapping: `201` conflict band

When a duplicate submission finds an existing `reserved` idempotency record older than the threshold, DeliveryLine returns `STALE_IDEMPOTENCY_RESERVATION` instead of replaying or silently re-executing the command.

## Consequences

- Duplicate execution remains blocked when reservation age suggests a crashed or abandoned mutation.
- Operators get a stable, machine-readable signal that the system requires intervention rather than optimistic replay.
- The current behavior is conservative by design: safety wins over convenience until later recovery stories add stronger evidence.

### Operator Runbook Stub

If `STALE_IDEMPOTENCY_RESERVATION` is returned:

1. Treat the command as potentially crashed mid-mutation.
2. Inspect `workflow_events` and `workflow_runs` for partial progress under the same `correlationId`.
3. Do not blindly resubmit until the workflow state and audit trail are understood.

## Deferred To

Epic 4 (Recovery) may replace this fail-closed default with evidence-based replay once DeliveryLine can inspect runner outcomes and recovery metadata safely.
