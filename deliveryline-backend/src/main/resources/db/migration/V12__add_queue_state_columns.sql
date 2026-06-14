-- Story 3.17a — runner execution queue SUBSTRATE (part A of the epic-3.17 split).
-- Widens the runner_executions status CHECK to admit the new 'queued' state and adds the queue
-- bookkeeping columns that the RunnerExecutionQueue.enqueue / dequeue (FOR UPDATE SKIP LOCKED) path
-- reads and writes. The queue is built DORMANT here: no production code enqueues yet (the 7 dispatch
-- callers stay on the synchronous RunnerBroker.dispatch path) — story 3.17b activates it.
--
-- Version note (R1): the epic AC said "V4", but V4 was consumed long ago
-- (V4__artifact_operation_single_pending.sql). V12 was reserved for this story: see V13's header
-- ("V12 is claimed by story 3-17a") — story 3.21 deliberately took V13 and left V12 free here.
--
-- New columns (all nullable or defaulted so existing rows migrate without a backfill):
--   dispatched_at       : when dequeue() leased the row to a worker (queued -> running).
--   worker_id           : the worker lease holder set by dequeue() (the 3.17b worker pool reads it).
--   queue_priority      : ORDER BY key for dequeue (lower = sooner); default 100.
--   queue_attempt_count : incremented each time dequeue() leases the row.
--   correlation_id      : the originating story-1.19 correlationId persisted at enqueue so the
--                         3.17b worker can restore MDC deterministically (OQ-1).

alter table runner_executions
    add column dispatched_at       timestamptz null,
    add column worker_id           text        null,
    add column queue_priority      integer     not null default 100,
    add column queue_attempt_count integer     not null default 0,
    add column correlation_id      text        null;

alter table runner_executions
    add constraint ck_runner_executions_queue_attempt_count_nonneg
        check (queue_attempt_count >= 0);

-- Drop-then-re-add is the Postgres idiom for changing a CHECK predicate. The widened set keeps every
-- existing status value and inserts 'queued' (the pre-dispatch lease-pending state).
alter table runner_executions
    drop constraint ck_runner_executions_status;
alter table runner_executions
    add constraint ck_runner_executions_status check (
        status in ('pending', 'running', 'queued', 'completed', 'failed', 'timed_out', 'orphaned')
    );

-- Dequeue pickup index — backs WHERE status='queued' ORDER BY queue_priority ASC, created_at ASC.
create index idx_runner_executions_queue_pickup
    on runner_executions (status, queue_priority, created_at);
