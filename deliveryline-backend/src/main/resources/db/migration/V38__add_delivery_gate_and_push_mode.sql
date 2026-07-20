-- V38: Unified delivery gate — per-project push mode + create-PR flag + non-terminal
-- WaitingForDelivery state (story 3h-4, FR78).
--
-- Head on disk is V37__widen_connector_kind_to_jira.sql, so V38 is the next free version.
-- (Re-confirmed the head at implementation time — flyway-v31-cross-branch-collision trap: no V38
-- exists in src or target/classes on this branch. Sibling unmerged story 3h-3 (ready-for-dev) will
-- claim its own next-free Vnn (step_reviews.review_findings jsonb) at merge; the two do not collide
-- on column names.)
--
-- Two per-project config columns + the state CHECK widening for the new gate state. Unlike
-- build/lint's two DISABLED-by-default columns, the delivery defaults preserve pre-3h behavior:
-- push_mode defaults 'auto' (push inline) and auto_create_pull_request defaults TRUE (create a PR),
-- so an untouched project is byte-identical to pre-3h delivery.

-- Per-project delivery config on the projects aggregate.
--   * push_mode is a NOT NULL text column defaulting 'auto', CHECK-constrained to the closed set
--     {auto,manual,approve}. This mirrors runner_kind's CHECK-column shape (a self-owned closed
--     enum), NOT reviewer_model_kind's free-text column (whose valid set defers to which reviewer
--     models are wired at execution time — push_mode has no such indirection). The entity getter
--     parses it via PushMode.fromValue (fail-fast on a bad DB value).
--   * auto_create_pull_request is a NOT NULL boolean defaulting TRUE (the pre-3h behavior created a
--     PR on push). It gates createOrUpdatePullRequest wherever the push fires.
alter table projects add column push_mode text not null default 'auto';
alter table projects
    add constraint ck_projects_push_mode check (push_mode in ('auto', 'manual', 'approve'));
alter table projects add column auto_create_pull_request boolean not null default true;

-- Widen all three workflow-state CHECK constraints to admit the non-terminal WaitingForDelivery
-- gating state (drop-and-readd, copying the V34 precedent). The delivery gate parks a run here
-- instead of pushing under a non-auto push mode; it leaves only on approve_delivery
-- (-> WaitingForReview) or a recovery/safety edge (TakenOver / Reconciled). No -> Failed edge: a
-- push failure during approve_delivery rolls the command back and leaves the run parked for retry
-- (Decision 5).
alter table workflow_runs
    drop constraint ck_workflow_runs_current_state;
alter table workflow_runs
    add constraint ck_workflow_runs_current_state check (
        current_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies',
            'WaitingForLintApproval', 'WaitingForDelivery', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_prior_state;
alter table workflow_events
    add constraint ck_workflow_events_prior_state check (
        prior_state is null or prior_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies',
            'WaitingForLintApproval', 'WaitingForDelivery', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_resulting_state;
alter table workflow_events
    add constraint ck_workflow_events_resulting_state check (
        resulting_state is null or resulting_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies',
            'WaitingForLintApproval', 'WaitingForDelivery', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );
