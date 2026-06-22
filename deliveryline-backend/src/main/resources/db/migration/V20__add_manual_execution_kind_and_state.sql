-- Story 3d-3 — `manual` runner kind + WaitingForManualExecution state + awaiting_manual runner
-- status. One additive, replay-safe migration carrying all three DDL concerns (AC1/AC2/AC5) so the
-- Flyway head advances by exactly one.
--
-- Version note: V19__add_reviewer_model_and_step_reviews.sql (story 3d-1) is the highest migration
-- on disk, so the next free version is V20. The story-key slug keeps its name (synced to
-- sprint-status.yaml). Same stale-version drift the repo already absorbed (V5->V15, V14->V17).
--
-- Coordinates with 3d-1's project-model migration (V19 added reviewer_model_kind /
-- reviewer_gating_enabled to projects); this migration adds the third per-project column,
-- runner_kind. Additive + replay-safe by construction (Flyway never re-runs an applied migration);
-- no data backfill, no destructive change.

-- AC1 — per-project runner-kind override. Nullable text (NULL = "no override, use the global
-- per-stage RunnerProperties kind", preserving pre-3d behavior — R7 parity). The CHECK pins it to
-- the RunnerKind value set (codex | claude | manual); NULL is always allowed. Unlike 3d-1's
-- reviewer_model_kind (deliberately CHECK-free, DD-1), runner_kind IS bounded by the registry value
-- set because the dispatch chokepoint branches on it directly.
alter table projects add column runner_kind text null;
alter table projects add constraint ck_projects_runner_kind
    check (runner_kind is null or runner_kind in ('codex', 'claude', 'manual'));

-- AC2 — widen the three workflow-state CHECKs to admit 'WaitingForManualExecution'. Drop-then-re-add
-- is the Postgres idiom for changing a CHECK predicate (mirrors V12/V16/V18/V19). Each widened set
-- keeps every existing state value and inserts the new parked state.
alter table workflow_runs
    drop constraint ck_workflow_runs_current_state;
alter table workflow_runs
    add constraint ck_workflow_runs_current_state check (
        current_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'Completed', 'Failed', 'Paused',
            'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_prior_state;
alter table workflow_events
    add constraint ck_workflow_events_prior_state check (
        prior_state is null or prior_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'Completed', 'Failed', 'Paused',
            'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_resulting_state;
alter table workflow_events
    add constraint ck_workflow_events_resulting_state check (
        resulting_state is null or resulting_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'Completed', 'Failed', 'Paused',
            'TakenOver', 'Reconciled'
        )
    );

-- AC5 — widen ck_runner_executions_status to admit the new non-terminal 'awaiting_manual' status.
-- Drop-then-re-add, mirroring V12 ('queued') / V16 ('cancelled_for_takeover'). The widened set keeps
-- every existing status value.
alter table runner_executions
    drop constraint ck_runner_executions_status;
alter table runner_executions
    add constraint ck_runner_executions_status check (
        status in (
            'pending', 'running', 'queued', 'completed', 'failed', 'timed_out', 'orphaned',
            'cancelled_for_takeover', 'awaiting_manual'
        )
    );

-- DELIBERATELY NOT TOUCHED: ck_runner_executions_completed_correlation. awaiting_manual is
-- NON-TERMINAL and carries NO completed_at, exactly as 'queued' is excluded (V12 comment). Adding it
-- to the terminal-status biconditional would force a parked row to stamp completed_at. On 3d-4
-- submission the row finalizes to 'completed' (terminal, stamps completed_at) — that's 3d-4's leg.
