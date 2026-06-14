-- Story 3.17b — runner worker pool + queue ACTIVATION (part B of the epic-3.17 split).
-- Carries the dispatch inputs the relocated RunnerBroker.dispatch body needs onto the queued
-- runner_executions row so the 3.17b worker pool can run that body off a dequeued row.
--
-- Background: 3.17a's V12 persisted correlation_id but NOT the idempotency key or the originating
-- actor. The synchronous dispatch path took both as method arguments (RunnerBroker.dispatch(
-- workflowRunId, stage, idempotencyKey, ActorContext)). When the dispatch body relocates onto the
-- worker (it dequeues a row, it is not called with arguments), the worker must reconstruct:
--   * idempotency_key — to checkAndReserve at worker-dispatch time, preserving today's replay
--                       semantics (Decision: reserve at the worker, not at enqueue).
--   * actor_identity / actor_type — to rebuild the ActorContext the RUNNER_STARTED / RUNNER_DISPATCHED
--                       events and the idempotency reservation are stamped with. RecoveryService.retry
--                       passes a non-system actor, so this is not derivable from correlation_id alone.
--
-- All columns are nullable so existing rows (and the legacy synchronous dispatch path, which never
-- enqueues) migrate without a backfill. enqueue writes them; dequeue reads them back via the snapshot.

alter table runner_executions
    add column idempotency_key text null,
    add column actor_identity  text null,
    add column actor_type      text null;
