-- Story 3d-1 — per-project reviewer-model binding + step_reviews advisory-verdict table.
--
-- This is V19 (head on disk is V18__widen_connector_kind_to_gitlab.sql, story 3c-3). The epic's
-- "V18+" phrasing resolves to this next-free number; the story-key slug keeps its name (synced to
-- sprint-status.yaml). Same stale-version drift the repo already absorbed (V5->V15, V14->V17).
--
-- Foundation only: this migration creates the durable, gating-capable HOME for the advisory
-- reviewer feature (ADR 0026). Nothing executes the reviewer yet — story 3d-2 runs the reviewer
-- through ProjectConnectorResolver, persists verdicts, and surfaces them in the WaitingForReview
-- Decision Bar. The reviewer connector role reuses the Epic 3c project_credentials/cipher model;
-- no new credential subsystem (AC2 / ADR 0013).
--
-- Additive + replay-safe by construction (Flyway never re-runs an applied migration); no data
-- backfill, no destructive change. malformedMigrationFailsFastWithSyntaxError already covers
-- fail-fast for a broken migration.

-- AC1/AC4 — widen the project model with the reviewer binding (DD-1, DD-3: per-project columns).
-- reviewer_model_kind is nullable opaque text: NULL = "no reviewer", preserving pre-3d behavior
-- (ADR 0026 Decision 1). NO DB CHECK on reviewer_model_kind (DD-1): the authoritative "which model
-- reviews" validation is the ProjectConnectorResolver at execution time (3d-2); a CHECK would couple
-- projects to the RunnerKind value set (which 3d-3 mutates by adding `manual`). reviewer_gating_enabled
-- exists so a failing verdict can block progression LATER without schema rework; it is read by NO
-- gating logic in this epic (ADR 0026 Decision 3) and DEFAULTs false.
alter table projects add column reviewer_model_kind text null;
alter table projects add column reviewer_gating_enabled boolean not null default false;

-- AC2 — widen the connector-role CHECK to add `reviewer`, using the drop-then-readd idiom
-- (precedent: V12/V16/V18). The existing partial unique index
-- uq_project_credentials_project_role (project_id, connector_role) where archived_at is null
-- already scopes "one active reviewer credential per project" automatically — no index change.
alter table project_credentials drop constraint ck_project_credentials_connector_role;
alter table project_credentials add constraint ck_project_credentials_connector_role
    check (connector_role in ('ticket_source', 'repo_host', 'reviewer'));

-- AC3 — step_reviews advisory-verdict table, following the universal core-table shape
-- (bigserial id PK + public_id text + format CHECK + created_at + archived_at retention pair).
-- The composite FK into artifacts (id, version) matches the approvals precedent
-- (uq_artifacts_id_version, V1) — it pins the verdict to the exact reviewed artifact version.
-- Every sibling FK is ON DELETE RESTRICT.
create table step_reviews (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id bigint not null,
    runner_execution_id bigint not null,
    reviewed_artifact_id bigint not null,
    reviewed_artifact_version integer not null,
    outcome text not null,
    rationale text null,
    reviewer_model_identity text null,
    producer_model_identity text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_step_reviews_public_id unique (public_id),
    constraint ck_step_reviews_public_id_format check (public_id ~ '^rev_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_step_reviews_outcome check (outcome in ('pass', 'concern', 'fail')),
    constraint fk_step_reviews_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (id) on delete restrict on update cascade,
    constraint fk_step_reviews_runner_executions foreign key (runner_execution_id)
        references runner_executions (id) on delete restrict on update cascade,
    constraint fk_step_reviews_artifacts foreign key (reviewed_artifact_id, reviewed_artifact_version)
        references artifacts (id, version) on delete restrict on update cascade
);
