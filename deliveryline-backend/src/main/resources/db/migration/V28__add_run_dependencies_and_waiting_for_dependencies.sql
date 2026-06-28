-- Story 3f-3 - run-dependency DAG substrate + non-terminal WaitingForDependencies state.
-- Additive run_dependencies join table (a directed edge run_id -> depends_on_run_id, i.e. the
-- dependent run depends on the prerequisite run) and a CHECK widening for all persisted
-- workflow-state columns. The acyclic guard is enforced in the application service (recursive CTE);
-- the DB self-edge CHECK is a backstop, and the PK makes duplicate edges idempotent.

create table run_dependencies (
    run_id text not null,
    depends_on_run_id text not null,
    created_at timestamptz not null default now(),
    constraint pk_run_dependencies primary key (run_id, depends_on_run_id),
    constraint fk_run_dependencies_run foreign key (run_id)
        references workflow_runs (public_id) on delete restrict on update cascade,
    constraint fk_run_dependencies_depends_on_run foreign key (depends_on_run_id)
        references workflow_runs (public_id) on delete restrict on update cascade,
    constraint ck_run_dependencies_not_self check (run_id <> depends_on_run_id)
);

-- Dependent-direction lookup (find a run's prerequisites). The PK already leads with run_id, but a
-- dedicated index keeps the intent explicit and stable if the PK column order ever changes.
create index idx_run_dependencies_run_id
    on run_dependencies (run_id);

-- Prerequisite-direction lookup (find the dependents released when a prerequisite completes).
create index idx_run_dependencies_depends_on_run_id
    on run_dependencies (depends_on_run_id);

-- Widen all three workflow-state CHECK constraints to admit the non-terminal WaitingForDependencies
-- gating state (drop-and-readd, copying the V20 / V27 precedent).
alter table workflow_runs
    drop constraint ck_workflow_runs_current_state;
alter table workflow_runs
    add constraint ck_workflow_runs_current_state check (
        current_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_prior_state;
alter table workflow_events
    add constraint ck_workflow_events_prior_state check (
        prior_state is null or prior_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_resulting_state;
alter table workflow_events
    add constraint ck_workflow_events_resulting_state check (
        resulting_state is null or resulting_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );
