-- Story 3f-2 - parent/child run lineage substrate + non-terminal Split state.
-- Additive parent_run_id column and a CHECK widening for all persisted workflow-state columns.

alter table workflow_runs
    add column parent_run_id text null;

alter table workflow_runs
    add constraint fk_workflow_runs_parent_run foreign key (parent_run_id)
        references workflow_runs (public_id) on delete restrict on update cascade;

alter table workflow_runs
    add constraint ck_workflow_runs_parent_run_not_self check (
        parent_run_id is null or parent_run_id <> public_id
    );

create index idx_workflow_runs_parent_run_id
    on workflow_runs (parent_run_id)
    where parent_run_id is not null;

alter table workflow_runs
    drop constraint ck_workflow_runs_current_state;
alter table workflow_runs
    add constraint ck_workflow_runs_current_state check (
        current_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'Split', 'Completed', 'Failed',
            'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_prior_state;
alter table workflow_events
    add constraint ck_workflow_events_prior_state check (
        prior_state is null or prior_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'Split', 'Completed', 'Failed',
            'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_resulting_state;
alter table workflow_events
    add constraint ck_workflow_events_resulting_state check (
        resulting_state is null or resulting_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'Split', 'Completed', 'Failed',
            'Paused', 'TakenOver', 'Reconciled'
        )
    );
