-- V41: CI build-error investigation columns + bounded CI fix-loop counter (story 3h-5, FR79).
--
-- Head on disk is V40__add_testcontainers_enabled.sql, so V41 is the next free version. (Re-confirmed
-- the head at implementation time — flyway-v31-cross-branch-collision trap: the story authored against
-- V38, but V39 (bitbucket connector-kind) and V40 (testcontainers) landed since. No V41 exists in src
-- or target/classes on this branch.)
--
-- Five per-run columns for the async sweep-driven CI poll (all nullable / default-0 so pre-3h-5 runs
-- are byte-identical): ci_status is a self-owned CHECKed set (the 3h-4 push_mode Decision-2 precedent);
-- ci_head_sha is the pushed commit; the poll bookkeeping (attempts + last-polled) is bounded by the
-- sweep; ci_fix_loop_count is the third referenced-feedback loop counter.
--
-- Reuses V7's escalation_marker_set column (one escalation marker per run, shared across the
-- spec-rejection / implementation-rejection / split / build-fix / lint-fix / ci-fix loops). Only the
-- per-stage counter is new — do NOT add a marker column.
--
-- RunnerStage.CI is CODE-ONLY (runner_executions.stage is an un-CHECKed text column), so no
-- runner_executions change is needed here (Decision 3).

alter table workflow_runs add column ci_status text null;
alter table workflow_runs add column ci_head_sha text null;
alter table workflow_runs add column ci_last_polled_at timestamptz null;
alter table workflow_runs add column ci_poll_attempts integer not null default 0;
alter table workflow_runs add column ci_fix_loop_count integer not null default 0;

-- Closed, self-owned status vocabulary (mirrors CiConclusion's persisted lowercase names). Unlike the
-- free-text reviewer_model_kind (V19), the CI status set is fixed and owned by this story.
alter table workflow_runs
    add constraint ck_workflow_runs_ci_status
        check (ci_status is null or ci_status in
               ('pending', 'success', 'failure', 'neutral', 'unavailable'));

alter table workflow_runs
    add constraint ck_workflow_runs_ci_fix_loop_count_nonneg
        check (ci_fix_loop_count >= 0);

alter table workflow_runs
    add constraint ck_workflow_runs_ci_poll_attempts_nonneg
        check (ci_poll_attempts >= 0);

-- Partial index: the CI-investigation sweep only ever scans rows still pending a CI verdict, so a
-- partial index keeps the keyset scan cheap and the index tiny (mirrors the "one active" partial-index
-- precedent). Keyed on id (the raw monotonic PK) — the sweep paginates by id > :afterSeq.
create index ix_workflow_runs_ci_pending on workflow_runs (id) where ci_status = 'pending';
