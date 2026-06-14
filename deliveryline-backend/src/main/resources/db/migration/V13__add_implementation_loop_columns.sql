-- Story 3.21: backend implementation-rejection writer with structured developer feedback.
-- Mirrors story 2.10's V7 spec-rejection columns. The epic AC said "V6", but V6 is taken
-- (V6__integration_link_active_uniqueness.sql), the latest on disk is V11, and V12 is claimed
-- by story 3-17a (V12__add_queue_state_columns.sql) — so this ships as V13 (Decision D1).
--
-- Reuses V7's escalation_marker_set column (one escalation marker per run, shared between the
-- spec-rejection loop and the implementation-rejection loop — Decision D5). Only the per-stage
-- counter is new here.

alter table workflow_runs
    add column implementation_rejection_loop_count integer not null default 0;

alter table workflow_runs
    add constraint ck_workflow_runs_implementation_rejection_loop_count_nonneg
        check (implementation_rejection_loop_count >= 0);

-- Widen the two rejection-taxonomy CHECK constraints to admit the 5 developer-rejection values
-- (Decision D4). The V1 constraints allowed only the 3 product values; the union below keeps the
-- product values and adds the developer taxonomy. Drop-then-re-add is the Postgres idiom for
-- changing a CHECK constraint's predicate.
alter table workflow_events
    drop constraint ck_workflow_events_rejection_taxonomy;
alter table workflow_events
    add constraint ck_workflow_events_rejection_taxonomy check (
        rejection_taxonomy is null
        or rejection_taxonomy in (
            'missing_scope',
            'unclear_specification',
            'misunderstood_implementation',
            'incorrect_approach',
            'incomplete_implementation',
            'quality_issue',
            'breaks_existing_functionality',
            'out_of_scope'
        )
    );

alter table approvals
    drop constraint ck_approvals_rejection_taxonomy;
alter table approvals
    add constraint ck_approvals_rejection_taxonomy check (
        rejection_taxonomy is null
        or rejection_taxonomy in (
            'missing_scope',
            'unclear_specification',
            'misunderstood_implementation',
            'incorrect_approach',
            'incomplete_implementation',
            'quality_issue',
            'breaks_existing_functionality',
            'out_of_scope'
        )
    );
