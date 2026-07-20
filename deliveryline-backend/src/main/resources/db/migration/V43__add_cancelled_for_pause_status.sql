-- Story 4.8 (AC4) — manual-pause dispatch cancellation.
-- Widens the runner_executions status CHECK to admit the new 'cancelled_for_pause' terminal
-- state, the reversible sibling of V16's 'cancelled_for_takeover'. When an operator pauses a run,
-- every {queued, pending, running} runner_executions row for the run is flipped to
-- 'cancelled_for_pause' inside the pause prep transaction — the authoritative "stop dispatch"
-- signal (invisible to the worker pool's dequeueNext, which leases status='queued', AND the
-- broker's ACTIVE_STATUSES = {pending, running}). An 'awaiting_manual' parked row is deliberately
-- NOT cancelled (pause is reversible; there is no re-park path on resume — story 4.8 Rec. 5).
--
-- Version note: V42__add_approval_invalidation.sql (story 4.7) is the highest migration on disk,
-- so the next free version is V43 (the story draft's "V39" predates 3i-3/3h-5/4.7 landing —
-- [[flyway-v31-cross-branch-collision]]).
--
-- No column changes — this migration only re-states the two CHECK predicates to add the new value.

-- Drop-then-re-add is the Postgres idiom for changing a CHECK predicate (mirrors V12 'queued' /
-- V16 'cancelled_for_takeover' / V20 'awaiting_manual'). The widened set re-states the FULL value
-- set — including V20's 'awaiting_manual' — and inserts 'cancelled_for_pause'.
alter table runner_executions
    drop constraint ck_runner_executions_status;
alter table runner_executions
    add constraint ck_runner_executions_status check (
        status in (
            'pending', 'running', 'queued', 'completed', 'failed', 'timed_out', 'orphaned',
            'cancelled_for_takeover', 'awaiting_manual', 'cancelled_for_pause'
        )
    );

-- cancelled_for_pause is a TERMINAL status that stamps completed_at (markCancelledForPause,
-- exactly like V16's takeover status), so it must join the completed↔correlation biconditional's
-- terminal-status list. 'awaiting_manual' stays OUT (non-terminal, no completed_at — V20's note).
alter table runner_executions
    drop constraint ck_runner_executions_completed_correlation;
alter table runner_executions
    add constraint ck_runner_executions_completed_correlation check (
        (status in (
            'completed', 'failed', 'timed_out', 'orphaned', 'cancelled_for_takeover',
            'cancelled_for_pause'
        )) = (completed_at is not null)
    );
