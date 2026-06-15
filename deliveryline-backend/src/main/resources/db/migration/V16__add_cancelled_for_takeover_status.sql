-- Story 3.22 (AC5) — developer-takeover dispatch cancellation.
-- Widens the runner_executions status CHECK to admit the new 'cancelled_for_takeover' terminal
-- state. When a developer takes over a run, every {queued, pending, running} runner_executions row
-- for the run is flipped to 'cancelled_for_takeover' inside the takeover transaction — the
-- authoritative "stop dispatch" signal (invisible to the worker pool's dequeueNext, which leases
-- status='queued', AND the broker's ACTIVE_STATUSES = {pending, running}).
--
-- Version note: V15__add_batch_submissions.sql (story 3.18) is the highest migration on disk, so the
-- next free version is V16 (the story draft's "V15" predates 3.18 landing).
--
-- No column changes — this migration only re-states the status CHECK predicate to add the new value.

-- Drop-then-re-add is the Postgres idiom for changing a CHECK predicate (mirrors V12's 'queued'
-- widening). The widened set keeps every existing status value and inserts 'cancelled_for_takeover'.
alter table runner_executions
    drop constraint ck_runner_executions_status;
alter table runner_executions
    add constraint ck_runner_executions_status check (
        status in (
            'pending', 'running', 'queued', 'completed', 'failed', 'timed_out', 'orphaned',
            'cancelled_for_takeover'
        )
    );

-- cancelled_for_takeover is a TERMINAL status that stamps completed_at (markCancelledForTakeover),
-- so it must join the completed↔correlation biconditional's terminal-status list. (V12 left this
-- constraint untouched because 'queued' is non-terminal and carries no completed_at; the takeover
-- status differs — it completes the row.) Without this widening the status flip fails
-- ck_runner_executions_completed_correlation.
alter table runner_executions
    drop constraint ck_runner_executions_completed_correlation;
alter table runner_executions
    add constraint ck_runner_executions_completed_correlation check (
        (status in (
            'completed', 'failed', 'timed_out', 'orphaned', 'cancelled_for_takeover'
        )) = (completed_at is not null)
    );
