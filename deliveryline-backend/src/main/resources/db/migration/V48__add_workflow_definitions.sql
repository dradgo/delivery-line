-- Story 3m-2 — WorkflowDefinition + StepDefinition schema + per-project step-override + run cursor.
--
-- The schema/registry foundation of Epic 3m (Configurable Workflow Definitions + BMAD-Method
-- Preset), the structural twin of 3d-1 (V19). This migration creates DORMANT structure only —
-- nothing executes it yet: the definition-driven run engine is 3m-3, the executor-binding writes
-- are 3m-4, the BMAD preset seed is 3m-5.
--
-- Version note: V47__add_artifact_lineage_reconcile_action.sql (story 4.16a) is the highest
-- migration on disk, so the next free version is V48 ([[flyway-v31-cross-branch-collision]] — a
-- sibling could still claim it; confirm at merge).
--
-- Shape: workflow_definitions / workflow_definition_steps / workflow_definition_step_overrides all
-- follow the universal core-table invariant (bigserial id PK + public_id text + format CHECK +
-- created_at/archived_at). The wfd_/wfs_/wso_ prefixes live on public_id, never on the PK.
--
-- DD-1 (mirrors 3d-1 reviewer_model_kind): step_key and runner_kind are FREE/OPAQUE text with NO DB
-- CHECK. step_key is authored freely by custom definitions; runner_kind is validated at 3m-4 bind
-- time via RunnerKind.fromValue (a DB CHECK would couple this table to the RunnerKind set, which
-- future stories mutate). Only kind (-> definition_kind) and produces_artifact_kind (-> artifact_kind)
-- get DB CHECKs.
--
-- ADR 0036 (run-model): the run reuses workflow_runs + WorkflowState. This migration adds ONLY the
-- two nullable cursor columns (workflow_definition_id + current_step_index). It adds NO new
-- WorkflowState value and NO transition-table edge — those EDGES (EXECUTING->EXECUTING advance) are
-- 3m-3's job. A null-definition run never reads the cursor and is byte-identical to pre-3m.

-- The workflow definition: an ordered, named pipeline of steps. `kind` distinguishes the immutable
-- shipped `builtin` preset (3m-5) from user-authored `custom` definitions (3m-9). One ACTIVE
-- definition per key (a partial unique index frees the slot when a definition is archived).
create table workflow_definitions (
    id bigserial primary key,
    public_id text not null,
    key text not null,
    name text not null,
    kind text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_workflow_definitions_public_id unique (public_id),
    constraint ck_workflow_definitions_public_id_format
        check (public_id ~ '^wfd_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_workflow_definitions_kind check (kind in ('builtin', 'custom')),
    -- Code review D2 — `not null` does NOT reject '' or '   ', but WorkflowDefinition's compact
    -- constructor throws IllegalArgumentException on blank. Without these CHECKs a blank row is
    -- persistable but un-hydratable, and an unchecked IAE out of a read path is an opaque 500.
    -- Keep the DB and the domain invariant in agreement rather than coercing blanks in the mapper.
    constraint ck_workflow_definitions_key_not_blank check (length(btrim(key)) > 0),
    constraint ck_workflow_definitions_name_not_blank check (length(btrim(name)) > 0)
);

create unique index uq_workflow_definitions_active_key
    on workflow_definitions (key)
    where archived_at is null;

-- The ordered steps of a definition. Each step names its position (step_index), a free-text
-- step_key (DD-1), an OPTIONAL executor binding (runner_kind free text + bmad_role prompt nudge,
-- both written by 3m-4/3m-5), a human_gated flag, and the OPTIONAL typed artifact kind it produces
-- (CHECK-constrained to the artifact_kind value set when non-null). One step per (definition,
-- index).
create table workflow_definition_steps (
    id bigserial primary key,
    public_id text not null,
    definition_id bigint not null,
    step_index int not null,
    step_key text not null,
    runner_kind text null,
    bmad_role text null,
    human_gated boolean not null default false,
    produces_artifact_kind text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_workflow_definition_steps_public_id unique (public_id),
    constraint ck_workflow_definition_steps_public_id_format
        check (public_id ~ '^wfs_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_workflow_definition_steps_artifact_kind check (
        produces_artifact_kind is null or produces_artifact_kind in (
            'brief', 'prd', 'ux_design', 'architecture', 'epics', 'story', 'code', 'review', 'retro'
        )
    ),
    -- Code review D2 — mirrors the workflow_definitions blank guards: step_key is free text (DD-1)
    -- but StepDefinition rejects blank, and step_index >= 0 is a domain invariant the DB must share.
    constraint ck_workflow_definition_steps_step_key_not_blank check (length(btrim(step_key)) > 0),
    constraint ck_workflow_definition_steps_step_index_non_negative check (step_index >= 0),
    constraint fk_workflow_definition_steps_definition foreign key (definition_id)
        references workflow_definitions (id) on delete restrict on update cascade
);

-- Code review D1 — PARTIAL on archived_at, matching uq_workflow_definitions_active_key and
-- uq_wf_step_overrides_active. This table carries archived_at, so a full unique index would let an
-- archived tombstone permanently burn its (definition_id, step_index) slot: 3m-9's archive-and-
-- replace edit would then raise an opaque DataIntegrityViolationException/500 instead of the
-- DEFINITION_STEP_INDEX_CONFLICT (409) this story registers ahead-of-use for exactly that case.
-- (Amends AC3's literal "unique index on (definition_id, step_index)" — see Review Findings D1.)
create unique index uq_workflow_definition_steps_index
    on workflow_definition_steps (definition_id, step_index)
    where archived_at is null;

-- DD-2 (ADR 0037 §4) — the per-project executor override for a shared preset step, created DORMANT
-- (no writer until 3m-4). Lets two projects run the shared `builtin` preset with different
-- executors: a row overrides one step's runner_kind / bmad_role for one project. One ACTIVE
-- override per (project, step). The project_id / step_id FKs use the bigint surrogate keys (an
-- internal join table, unlike project_credentials which references projects.public_id).
create table workflow_definition_step_overrides (
    id bigserial primary key,
    public_id text not null,
    project_id bigint not null,
    step_id bigint not null,
    runner_kind text null,
    bmad_role text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_workflow_definition_step_overrides_public_id unique (public_id),
    -- NOTE: this format-CHECK name is table-derived (ck_<table>_public_id_format), NOT the story's
    -- shortened ck_wf_step_overrides_public_id_format — FlywaySchemaContractTest derives the
    -- expected name from the table name, and 54 chars fits the 63-char identifier limit. The
    -- PublicIdPrefixes.WORKFLOW_STEP_OVERRIDE.constraintName() matches this exact string.
    constraint ck_workflow_definition_step_overrides_public_id_format
        check (public_id ~ '^wso_[A-Za-z0-9_-]{4,64}$'),
    constraint fk_wf_step_overrides_projects foreign key (project_id)
        references projects (id) on delete restrict on update cascade,
    constraint fk_wf_step_overrides_step foreign key (step_id)
        references workflow_definition_steps (id) on delete restrict on update cascade
);

create unique index uq_wf_step_overrides_active
    on workflow_definition_step_overrides (project_id, step_id)
    where archived_at is null;

-- AC5 — the project's CONFIG: which definition to run. Nullable ⇒ the legacy hardcoded pipeline
-- (selecting BMAD is opt-in). A project with null is byte-identical to pre-3m.
alter table projects
    add column workflow_definition_id bigint null;
alter table projects
    add constraint fk_projects_workflow_definition foreign key (workflow_definition_id)
        references workflow_definitions (id) on delete restrict on update cascade;
-- Code review — Postgres does NOT auto-index the referencing side of a FK. Without this, every
-- delete/purge on workflow_definitions must sequentially scan projects under lock to prove the
-- `on delete restrict` is satisfied.
create index ix_projects_workflow_definition_id
    on projects (workflow_definition_id)
    where workflow_definition_id is not null;

-- AC6 (ADR 0036) — the run INSTANCE: the snapshotted definition + the cursor. Both nullable and
-- additive; a null-definition run never reads the cursor and is byte-identical to pre-3m. Written
-- by 3m-3 (the snapshot at run start + the EXECUTING->EXECUTING cursor advance) — 3m-2 only creates
-- the columns.
alter table workflow_runs
    add column workflow_definition_id bigint null;
alter table workflow_runs
    add constraint fk_workflow_runs_workflow_definition foreign key (workflow_definition_id)
        references workflow_definitions (id) on delete restrict on update cascade;
alter table workflow_runs
    add column current_step_index int null;
-- Same FK-index rationale as projects above, and materially more important here: workflow_runs is
-- the largest table in the schema, so an unindexed RESTRICT check degrades silently with run volume.
create index ix_workflow_runs_workflow_definition_id
    on workflow_runs (workflow_definition_id)
    where workflow_definition_id is not null;
