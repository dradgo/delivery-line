-- Story 3.18 — Workflow Batch Submission (CLI + REST).
--
-- This migration is V15, NOT the epic AC's "V5". The latest migration on disk is
-- V14__add_queue_dispatch_carriage.sql (story 3.17b), so V15 is the next free version. One
-- migration both creates the batch_submissions aggregate AND adds the
-- runner_executions.batch_submission_id trace column.
--
-- Persistence shape (Decision D-PERSIST, result_json variant): the per-ticket outcomes — including
-- REJECTED tickets, which have no runner_executions row — are stored as a JSON document in
-- result_json so an idempotent replay reconstructs the full BatchSubmissionResult. (The normalized
-- child-items table the story sketched would, under this codebase's "every table is a core table
-- with its own public_id" contract invariant, require a second public-id prefix + full core-table
-- treatment; the architect chose the simpler snapshot column.)

create table batch_submissions (
    id bigserial primary key,
    public_id text not null,
    actor_identity text not null,
    actor_type text not null,
    idempotency_key text not null,
    total integer not null,
    queued_count integer not null,
    rejected_count integer not null,
    result_json text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_batch_submissions_public_id unique (public_id),
    constraint ck_batch_submissions_public_id_format check (public_id ~ '^bat_[A-Za-z0-9_-]{4,64}$'),
    -- Defense-in-depth behind the IdempotencyService reservation: a fingerprint-conflict scenario
    -- that bypasses the reservation path still hits this UNIQUE before persisting a duplicate batch.
    -- Mapped to IDEMPOTENCY_KEY_CONFLICT in BatchSubmissionPersistenceAdapter.
    constraint uq_batch_submissions_idempotency_key unique (idempotency_key),
    constraint ck_batch_submissions_actor_type check (
        actor_type in ('human', 'agent', 'system', 'service_account')
    ),
    constraint ck_batch_submissions_counts_nonneg check (
        total >= 0 and queued_count >= 0 and rejected_count >= 0
    )
);

create index idx_batch_submissions_idempotency_key on batch_submissions (idempotency_key);
create index idx_batch_submissions_archived_at
    on batch_submissions (archived_at) where archived_at is not null;

-- Trace a queued runner execution back to the batch that submitted it (AC4; serves story 3.19's
-- per-batch queue filtering). text reference to batch_submissions.public_id (the bat_ id), NOT a
-- bigint FK — nullable, no backfill (mirror the V14 alter style: existing rows + the single-submit
-- path that never carries a batch id migrate without change).
alter table runner_executions
    add column batch_submission_id text null;
