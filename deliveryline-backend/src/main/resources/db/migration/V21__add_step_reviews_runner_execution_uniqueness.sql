-- Story 3d-2 (Task 3) — one advisory verdict per reviewer execution.
--
-- Head on disk is V20__add_manual_execution_kind_and_state.sql (story 3d-3); this next-free number
-- is V21. 3d-1 created step_reviews (V19) but deferred the verdict-write uniqueness decision to
-- 3d-2 (its verdict-write semantics). A reviewer runner invocation produces AT MOST ONE verdict;
-- this guards against a duplicate harvest (e.g. a late/duplicate runner result) writing two rows
-- for the same reviewer execution.
--
-- PARTIAL (where archived_at is null), NOT a total UNIQUE: a NEW reviewer execution over a NEW
-- artifact version has a distinct runner_execution_id and is always allowed, and an archived
-- verdict (retention is Epic 5) never blocks a fresh one. See the "one active per key needs a
-- partial unique index" pattern — a total UNIQUE would forbid legitimate re-review.
--
-- Additive + replay-safe by construction (Flyway never re-runs an applied migration). step_reviews
-- is empty on the slice that introduces the reviewer execution path, so a standard (non-CONCURRENTLY)
-- CREATE INDEX inside Flyway's migration transaction is fine (CONCURRENTLY cannot run in a tx).
create unique index uq_step_reviews_runner_execution
    on step_reviews (runner_execution_id)
    where archived_at is null;
