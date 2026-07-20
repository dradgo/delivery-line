-- V33: Build-Validation Stage config + bounded auto-fix loop counter (story 3h-1, FR75).
--
-- Head on disk is V32__narrow_pending_clarification_index_to_exclude_superseded.sql, so V33 is the
-- next free version. (Re-confirmed the head at implementation time — flyway-v31-cross-branch-collision
-- trap: no V33 exists in src or target/classes on this branch.)
--
-- Two per-project config columns (default DISABLED so pre-3h projects are byte-identical) + one
-- per-run loop counter for the bounded build auto-fix loop.

-- Per-project build config on the projects aggregate.
--   * build_command is nullable opaque text: NULL = "no build command" -> BUILD stage skipped
--     entirely (parity). Mirrors reviewer_model_kind (V19) — NO CHECK: the authoritative "is a build
--     configured" decision is ProjectRuntimeConfigResolver at execution time, not a DB constraint.
--   * build_stage_enabled mirrors openspec_enabled (V17): plain boolean, NOT NULL DEFAULT false, so a
--     project must opt in. A project with build_stage_enabled=false skips BUILD (pre-3h parity).
alter table projects add column build_command text null;
alter table projects add column build_stage_enabled boolean not null default false;

-- Per-run bounded build auto-fix loop counter (mirrors V13 implementation_rejection_loop_count).
-- Reuses V7's escalation_marker_set column (one escalation marker per run, shared across the
-- spec-rejection / implementation-rejection / build-fix loops). Only the per-stage counter is new.
alter table workflow_runs
    add column build_fix_loop_count integer not null default 0;

alter table workflow_runs
    add constraint ck_workflow_runs_build_fix_loop_count_nonneg
        check (build_fix_loop_count >= 0);
