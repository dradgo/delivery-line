-- Story 2.11: Backend Clarification domain entity + ClarificationService.submitAnswer writer.
--
-- This migration is V8, NOT V2. The epic AC for story 2.11 says the migration bundles
-- two workflow_runs columns (spec_rejection_loop_count + escalation_marker_set) alongside
-- the clarifications table under "V2__add_spec_loop_and_clarifications.sql", but that
-- wording is obsolete:
--   * V2 is taken by V2__artifact_failure_columns.sql (existing history is
--     V1 -> V1_1 -> V2 -> V3 -> V4 -> V5 -> V6 -> V7).
--   * The two workflow_runs columns already shipped in V7__add_spec_rejection_loop_columns.sql
--     with story 2.10 (the migration sequencing was resolved as OQ-1 there).
-- Story 2.11 therefore ships clarifications-only as V8. Re-adding the workflow_runs
-- columns here would fail with duplicate_column.

create table clarifications (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    artifact_id bigint not null,
    artifact_version integer not null,
    question_id text not null,
    question_text text not null,
    status text not null,
    answer_text text null,
    answered_by_actor text null,
    answered_by_actor_type text null,
    answered_at timestamptz null,
    incorporation_event_id bigint null,
    idempotency_key text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_clarifications_public_id unique (public_id),
    constraint ck_clarifications_public_id_format check (public_id ~ '^clr_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_clarifications_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    -- Composite FK pins the clarification to the exact (artifact, version) it was asked against,
    -- mirroring the approvals.(artifact_id, artifact_version) -> artifacts(id, version) pattern
    -- (V1:188-190). Backed by uq_artifacts_id_version (V1:134).
    constraint fk_clarifications_artifacts foreign key (artifact_id, artifact_version)
        references artifacts (id, version) on delete restrict on update cascade,
    -- incorporation_event_id is nullable until story 2.12's ClarificationLifecycleService fills it
    -- on the visible-incorporation transition; ON DELETE SET NULL so workflow_events tombstoning
    -- doesn't orphan the FK.
    constraint fk_clarifications_incorporation_event foreign key (incorporation_event_id)
        references workflow_events (id) on delete set null on update cascade,
    constraint ck_clarifications_question_id_format check (question_id ~ '^[A-Za-z0-9._-]{1,128}$'),
    constraint ck_clarifications_status check (
        status in ('open', 'answered', 'accepted', 'incorporated', 'superseded', 'rejected_invalid')
    ),
    constraint ck_clarifications_artifact_version check (artifact_version > 0),
    constraint ck_clarifications_answered_by_actor_type check (
        answered_by_actor_type is null
        or answered_by_actor_type in ('human', 'agent', 'system', 'service_account')
    ),
    -- All three answer fields are populated iff the status has left 'open' (story 2.11 AC1).
    -- Story 2.11 trap T9 defense-in-depth backstop; ClarificationService.submitAnswer writes all
    -- three in one UPDATE so the application layer never trips this CHECK in normal operation.
    constraint ck_clarifications_answered_fields_paired check (
        (status = 'open') = (answer_text is null and answered_by_actor is null and answered_at is null)
    ),
    -- Defense-in-depth for executeIdempotent: a fingerprint-conflict scenario that bypasses the
    -- IdempotencyService reservation path (or a future fixture-seed bug) still hits this UNIQUE
    -- before persisting a duplicate. Mirrored mapping to IDEMPOTENCY_KEY_CONFLICT lives in
    -- ClarificationWritePersistenceAdapter.
    constraint uq_clarifications_idempotency_key unique (idempotency_key)
);

create index idx_clarifications_workflow_run_id_status_created_at
    on clarifications (workflow_run_id, status, created_at);
create index idx_clarifications_artifact_id_created_at
    on clarifications (artifact_id, created_at);
create index idx_clarifications_archived_at
    on clarifications (archived_at) where archived_at is not null;
