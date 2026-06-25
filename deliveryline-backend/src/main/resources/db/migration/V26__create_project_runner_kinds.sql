-- Story 3e-4 (AC1) — per-step runner mapping per project.
--
-- Resolves story 3d-3's deferred Open Decision #1 (per-stage-per-project granularity). 3d-3 shipped a
-- SINGLE per-project projects.runner_kind override applied across all stages and recommended a child
-- table for true per-step selection. This is that table: a lean association/mapping table binding a
-- RunnerKind to each workflow STEP (spec / implementationPlan / prOutput) of a project, resolved more
-- specifically than the single override (kept as the project-wide default) or the global per-stage kind.
--
-- Head note: this is V26. The proposal/body hedged "V25", but V25 was taken by story 3e-2
-- (V25__add_spec_clarification_acknowledgements.sql) — a sibling 3e story landed first, exactly the
-- re-confirm-the-head case the story flagged. V20-V25 are taken, so V26 is the next free version.
--
-- Shape note: project_runner_kinds is a pure mapping table (composite PK (project_id, step)), NOT a
-- core table — it deliberately carries no bigserial id / public_id / created_at / archived_at (there is
-- nothing to retention-sweep or address by public id; the row IS its (project, step) coordinate). The
-- step and runner_kind value sets are text + CHECK with inlined values; the ProjectRunnerStep registry
-- + drift test live in the application/test layers (RegistryContractTest). Additive + replay-safe.

create table project_runner_kinds (
    project_id text not null,
    step text not null,
    runner_kind text not null,
    primary key (project_id, step),
    constraint ck_project_runner_kinds_step check (step in ('spec', 'implementationPlan', 'prOutput')),
    constraint ck_project_runner_kinds_kind check (runner_kind in ('codex', 'claude', 'manual')),
    constraint fk_project_runner_kinds_projects foreign key (project_id)
        references projects (public_id) on delete restrict on update cascade
);
