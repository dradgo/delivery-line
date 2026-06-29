-- Story 3f-4 — advisory LLM split-proposal channel (operator-facing FRONT HALF of split).
-- Additive split_proposals table carrying the persisted, redacted decomposition proposal
-- (subtasks[] + dependencies[]) produced by a reviewer-style LLM call at the spec/review gate,
-- plus the lifecycle (open -> superseded / dismissed / approved) and a per-run re-propose loop
-- counter. The proposal is an advisory OVERLAY: NO new workflow state, event, or transition edge
-- (ADR-0029 decision 4). step_reviews (V19) is verdict-shaped (outcome/rationale, no payload,
-- no lifecycle), so the proposal needs its own home.
--
-- Additive + replay-safe by construction (Flyway never re-runs an applied migration); no backfill,
-- no destructive change. The workflow_run / artifact references are opaque text public_ids, keyed
-- on workflow_runs.public_id with ON DELETE RESTRICT ON UPDATE CASCADE (the 3f-2/3f-3 convention).

create table split_proposals (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id text not null,
    reviewed_artifact_id text null,
    reviewed_artifact_version integer null,
    status text not null,
    loop_count integer not null default 0,
    proposal_json text not null,
    reviewer_model_identity text null,
    producer_model_identity text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_split_proposals_public_id unique (public_id),
    constraint ck_split_proposals_public_id_format
        check (public_id ~ '^splprop_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_split_proposals_status
        check (status in ('open', 'superseded', 'dismissed', 'approved')),
    constraint ck_split_proposals_loop_count_nonneg check (loop_count >= 0),
    constraint fk_split_proposals_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (public_id) on delete restrict on update cascade
);

-- One LIVE proposal per run: a partial unique index over the open rows only (a run may accumulate
-- many superseded/dismissed/approved rows over its re-propose loop, but at most one `open`).
create unique index uq_split_proposals_open_per_run
    on split_proposals (workflow_run_id)
    where status = 'open';

-- Dependent-direction lookup (find a run's proposals).
create index idx_split_proposals_workflow_run_id
    on split_proposals (workflow_run_id);

-- Re-propose operator feedback, materialized BY REFERENCE (R3): the free-text feedback the
-- operator gives at repropose is persisted here (redacted), keyed by the reviewer execution that
-- carries the re-propose dispatch, and referenced from the context bundle as a
-- {referenceId, kind:'split.feedback'} priorFeedbackReferences entry — never inlined into the
-- bundle JSON. Keyed by runner_execution_id (the run is derivable from it), so this carries no
-- workflow_run_id FK. Core-table shape (id/public_id/created_at/archived_at retention).
create table split_proposal_feedback (
    id bigserial primary key,
    public_id text not null,
    runner_execution_id text not null,
    feedback_text text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_split_proposal_feedback_public_id unique (public_id),
    constraint ck_split_proposal_feedback_public_id_format
        check (public_id ~ '^splfb_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_split_proposal_feedback_runner_executions foreign key (runner_execution_id)
        references runner_executions (public_id) on delete restrict on update cascade
);

create index idx_split_proposal_feedback_runner_execution_id
    on split_proposal_feedback (runner_execution_id);

-- Re-propose loop counter on the run itself — mirrors spec_rejection_loop_count (V7). It exists
-- primarily to make each re-propose dispatch a DISTINCT idempotency key (split-proposal:<run>:<n>).
-- Additive, replay-safe; NO workflow-state CHECK widening (no new state — ADR-0029 decision 4).
alter table workflow_runs
    add column split_proposal_loop_count integer not null default 0;

alter table workflow_runs
    add constraint ck_workflow_runs_split_proposal_loop_count_nonneg
        check (split_proposal_loop_count >= 0);
