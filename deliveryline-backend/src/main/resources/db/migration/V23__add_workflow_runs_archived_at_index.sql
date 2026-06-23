-- Story 3d-8 (FR67, AC1, ADR 0027) — soft-hide / archive obsolete executions.
--
-- The workflow_runs.archived_at marker column already exists (V1, line 19); this migration adds ONLY
-- the partial index that backs the include-archived list path (GET /api/v1/workflows?includeArchived
-- =true) and the future Epic 5 retention sweep over hidden runs. It mirrors the V1 precedent for
-- workflow_events / artifacts / recovery_actions (V1 lines 352-354): a partial index over the small
-- set of rows that actually carry a non-null archived_at, leaving the hot default-queue path (which
-- filters archived_at IS NULL) to the existing created_at ordering. Replay-safe and cheap.
create index idx_workflow_runs_archived_at
    on workflow_runs (archived_at)
    where archived_at is not null;
