-- V1 core schema for DeliveryLine workflow audit and execution.
-- Design notes (referenced by review 2026-04-27):
--   * Primary keys are bigserial; readable prefixed identifiers live in `public_id text` per table
--     with a UNIQUE constraint and a format CHECK matching the prefix registry (story 1.4).
--   * FK actions are declared explicitly. RESTRICT for audit-critical references; SET NULL for
--     soft references where the column is nullable.
--   * State / taxonomy lists are inlined as CHECK constraints in V1; story 1.4 may refactor to
--     a workflow_states lookup table or CREATE TYPE.
--   * Append-only enforcement on workflow_events / recovery_actions is deferred to the role
--     provisioning story (no triggers in V1).
--   * Idempotency keys are documented as globally unique UUIDs across all tables; idempotency_records
--     carries an optional expires_at for retention.

create table workflow_runs (
    id bigserial primary key,
    public_id text not null,
    current_state text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_workflow_runs_public_id unique (public_id),
    constraint ck_workflow_runs_public_id_format check (public_id ~ '^run_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_workflow_runs_current_state check (
        current_state in (
            'Inbox',
            'Planned',
            'Investigating',
            'WaitingForSpecApproval',
            'Executing',
            'WaitingForReview',
            'Completed',
            'Failed',
            'Paused',
            'TakenOver',
            'Reconciled'
        )
    )
);

create table workflow_events (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    event_type text not null,
    prior_state text null,
    resulting_state text null,
    actor_identity text not null,
    actor_type text not null,
    reviewer_role text null,
    reason text null,
    failure_category text null,
    intervention_marker boolean not null default false,
    details jsonb not null default '{}'::jsonb,
    stage_duration_ms bigint null,
    rejection_taxonomy text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_workflow_events_public_id unique (public_id),
    constraint ck_workflow_events_public_id_format check (public_id ~ '^evt_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_workflow_events_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    constraint ck_workflow_events_actor_type check (actor_type in ('human', 'agent', 'system', 'service_account')),
    constraint ck_workflow_events_failure_category check (
        failure_category is null or length(failure_category) > 0
    ),
    constraint ck_workflow_events_details_size check (pg_column_size(details) < 65536),
    constraint ck_workflow_events_prior_state check (
        prior_state is null or prior_state in (
            'Inbox',
            'Planned',
            'Investigating',
            'WaitingForSpecApproval',
            'Executing',
            'WaitingForReview',
            'Completed',
            'Failed',
            'Paused',
            'TakenOver',
            'Reconciled'
        )
    ),
    constraint ck_workflow_events_resulting_state check (
        resulting_state is null or resulting_state in (
            'Inbox',
            'Planned',
            'Investigating',
            'WaitingForSpecApproval',
            'Executing',
            'WaitingForReview',
            'Completed',
            'Failed',
            'Paused',
            'TakenOver',
            'Reconciled'
        )
    ),
    constraint ck_workflow_events_stage_duration_ms check (stage_duration_ms is null or stage_duration_ms >= 0),
    constraint ck_workflow_events_rejection_taxonomy check (
        rejection_taxonomy is null
        or rejection_taxonomy in ('missing_scope', 'unclear_specification', 'misunderstood_implementation')
    )
);

create table artifacts (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    artifact_type text not null,
    version integer not null,
    parent_artifact_id bigint null,
    classification text not null,
    storage_ref text null,
    checksum_algorithm text null,
    checksum_value text null,
    status text not null,
    linked_event_id bigint not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_artifacts_public_id unique (public_id),
    constraint ck_artifacts_public_id_format check (public_id ~ '^art_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_artifacts_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    constraint fk_artifacts_artifacts foreign key (parent_artifact_id)
        references artifacts (id) on delete set null on update cascade,
    constraint fk_artifacts_workflow_events foreign key (linked_event_id)
        references workflow_events (id) on delete restrict on update cascade,
    constraint ck_artifacts_no_self_parent check (parent_artifact_id is null or parent_artifact_id <> id),
    constraint ck_artifacts_version check (version > 0),
    constraint ck_artifacts_status check (status in ('pending', 'available', 'failed', 'late_or_stale')),
    constraint ck_artifacts_checksum_paired check (
        (checksum_algorithm is null) = (checksum_value is null)
    ),
    constraint uq_artifacts_workflow_run_id_artifact_type_version unique (workflow_run_id, artifact_type, version),
    -- Composite UNIQUE so approvals.(artifact_id, artifact_version) can FK into (id, version).
    constraint uq_artifacts_id_version unique (id, version)
);

create table artifact_operations (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    artifact_id bigint not null,
    linked_event_id bigint not null,
    operation_type text not null,
    status text not null,
    idempotency_key text not null,
    failure_category text null,
    reason text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_artifact_operations_public_id unique (public_id),
    constraint ck_artifact_operations_public_id_format check (public_id ~ '^op_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_artifact_operations_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    constraint fk_artifact_operations_artifacts foreign key (artifact_id)
        references artifacts (id) on delete restrict on update cascade,
    constraint fk_artifact_operations_workflow_events foreign key (linked_event_id)
        references workflow_events (id) on delete restrict on update cascade,
    constraint ck_artifact_operations_operation_type check (operation_type in ('create', 'update', 'replace')),
    constraint ck_artifact_operations_status check (status in ('pending', 'complete', 'failed', 'failed_orphan')),
    constraint ck_artifact_operations_failure_category check (
        failure_category is null or length(failure_category) > 0
    ),
    -- Name shortened from architecture-derived form to fit the 63-byte PostgreSQL identifier limit.
    constraint uq_artifact_operations_idem_key_op_type_artifact_id unique (idempotency_key, operation_type, artifact_id)
);

create table approvals (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    artifact_id bigint not null,
    artifact_version integer not null,
    context_bundle_version integer not null,
    actor_identity text not null,
    actor_type text not null,
    reviewer_role text not null,
    decision text not null,
    reason text null,
    rejection_taxonomy text null,
    decided_at timestamptz not null default now(),
    idempotency_key text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_approvals_public_id unique (public_id),
    constraint ck_approvals_public_id_format check (public_id ~ '^apr_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_approvals_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    -- Composite FK pins approval to the exact (artifact, version) it approved; uq_artifacts_id_version above supports this.
    constraint fk_approvals_artifacts foreign key (artifact_id, artifact_version)
        references artifacts (id, version) on delete restrict on update cascade,
    constraint ck_approvals_artifact_version check (artifact_version > 0),
    constraint ck_approvals_context_bundle_version check (context_bundle_version > 0),
    constraint ck_approvals_actor_type check (actor_type in ('human', 'agent', 'system', 'service_account')),
    constraint ck_approvals_decision check (decision in ('approved', 'rejected')),
    constraint ck_approvals_rejection_taxonomy check (
        rejection_taxonomy is null
        or rejection_taxonomy in ('missing_scope', 'unclear_specification', 'misunderstood_implementation')
    ),
    constraint ck_approvals_decision_taxonomy_paired check (
        (decision = 'approved' and rejection_taxonomy is null)
        or (decision = 'rejected' and rejection_taxonomy is not null)
    ),
    constraint uq_approvals_idempotency_key unique (idempotency_key)
);

create table runner_executions (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    stage text not null,
    status text not null,
    context_bundle_version integer not null,
    last_activity_at timestamptz not null,
    timeout_at timestamptz not null,
    failure_category text null,
    completed_at timestamptz null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_runner_executions_public_id unique (public_id),
    constraint ck_runner_executions_public_id_format check (public_id ~ '^rex_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_runner_executions_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    constraint ck_runner_executions_context_bundle_version check (context_bundle_version > 0),
    constraint ck_runner_executions_status check (
        status in ('pending', 'running', 'completed', 'failed', 'timed_out', 'orphaned')
    ),
    constraint ck_runner_executions_failure_category check (
        failure_category is null or length(failure_category) > 0
    ),
    constraint ck_runner_executions_timeout_after_activity check (timeout_at >= last_activity_at),
    constraint ck_runner_executions_completed_correlation check (
        (status in ('completed', 'failed', 'timed_out', 'orphaned')) = (completed_at is not null)
    )
);

create table integration_links (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    integration_type text not null,
    external_ref text not null,
    external_metadata jsonb not null default '{}'::jsonb,
    last_sync_at timestamptz null,
    sync_status text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_integration_links_public_id unique (public_id),
    constraint ck_integration_links_public_id_format check (public_id ~ '^ilk_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_integration_links_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    constraint ck_integration_links_integration_type check (integration_type in ('linear', 'github_pr')),
    constraint ck_integration_links_sync_status check (
        sync_status in ('linked', 'synced', 'stale', 'failed', 'superseded')
    ),
    constraint ck_integration_links_external_metadata_size check (pg_column_size(external_metadata) < 65536),
    -- Cross-run uniqueness left to story 1.14 / 3.15 per review 2026-04-27 decision (deferred).
    -- Name shortened from architecture-derived form to fit the 63-byte PostgreSQL identifier limit.
    constraint uq_integration_links_type_external_ref_run_id unique (
        integration_type,
        external_ref,
        workflow_run_id
    )
);

create table recovery_actions (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    action_type text not null,
    triggering_event_id bigint null,
    resulting_event_id bigint null,
    actor_identity text not null,
    actor_type text not null,
    reviewer_role text null,
    idempotency_key text not null,
    result_status text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_recovery_actions_public_id unique (public_id),
    constraint ck_recovery_actions_public_id_format check (public_id ~ '^rcv_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_recovery_actions_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    constraint fk_recovery_actions_workflow_events foreign key (triggering_event_id)
        references workflow_events (id) on delete set null on update cascade,
    constraint fk_recovery_actions_resulting_workflow_events foreign key (resulting_event_id)
        references workflow_events (id) on delete set null on update cascade,
    constraint ck_recovery_actions_action_type check (
        action_type in ('retry', 'rerun', 'resume', 'takeover', 'pause', 'reconcile')
    ),
    constraint ck_recovery_actions_actor_type check (actor_type in ('human', 'agent', 'system', 'service_account')),
    constraint ck_recovery_actions_result_status check (result_status in ('pending', 'succeeded', 'failed')),
    constraint uq_recovery_actions_idempotency_key unique (idempotency_key)
);

create table idempotency_records (
    id bigserial primary key,
    public_id text not null,
    key text not null,
    command_type text not null,
    actor_identity text not null,
    command_fingerprint text not null,
    status text not null,
    result_ref text null,
    completed_at timestamptz null,
    -- expires_at supports retention sweeps (review decision 2026-04-27); NULL = indefinite.
    expires_at timestamptz null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_idempotency_records_public_id unique (public_id),
    constraint ck_idempotency_records_public_id_format check (public_id ~ '^idm_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_idempotency_records_status check (status in ('reserved', 'completed', 'failed')),
    constraint uq_idempotency_records_key unique (key)
);

-- Lookup indexes
create index idx_workflow_runs_current_state_created_at
    on workflow_runs (current_state, created_at);

create index idx_workflow_events_workflow_run_id_created_at
    on workflow_events (workflow_run_id, created_at);

create index idx_artifacts_workflow_run_id_artifact_type_version
    on artifacts (workflow_run_id, artifact_type, version);

create index idx_artifact_operations_artifact_id_status_created_at
    on artifact_operations (artifact_id, status, created_at);

create index idx_approvals_workflow_run_id_artifact_id_decided_at
    on approvals (workflow_run_id, artifact_id, decided_at);

create index idx_runner_executions_workflow_run_id_status_created_at
    on runner_executions (workflow_run_id, status, created_at);

-- Name shortened from architecture-derived form to fit the 63-byte PostgreSQL identifier limit.
create index idx_integration_links_run_id_type_created_at
    on integration_links (workflow_run_id, integration_type, created_at);

create index idx_recovery_actions_workflow_run_id_created_at
    on recovery_actions (workflow_run_id, created_at);

create index idx_idempotency_records_status_created_at
    on idempotency_records (status, created_at);

-- FK supporting indexes (PG does not auto-index FK child columns)
create index idx_artifacts_parent_artifact_id on artifacts (parent_artifact_id);
create index idx_artifacts_linked_event_id on artifacts (linked_event_id);
create index idx_artifact_operations_linked_event_id on artifact_operations (linked_event_id);
create index idx_recovery_actions_triggering_event_id on recovery_actions (triggering_event_id);
create index idx_recovery_actions_resulting_event_id on recovery_actions (resulting_event_id);

-- Partial indexes for retention sweeps (Epic 5)
create index idx_workflow_events_archived_at on workflow_events (archived_at) where archived_at is not null;
create index idx_artifacts_archived_at on artifacts (archived_at) where archived_at is not null;
create index idx_recovery_actions_archived_at on recovery_actions (archived_at) where archived_at is not null;

-- GIN indexes for jsonb querying (architecture allows bounded JSONB metadata; queries land here too)
create index idx_workflow_events_details_gin on workflow_events using gin (details jsonb_path_ops);
create index idx_integration_links_external_metadata_gin on integration_links using gin (external_metadata jsonb_path_ops);
