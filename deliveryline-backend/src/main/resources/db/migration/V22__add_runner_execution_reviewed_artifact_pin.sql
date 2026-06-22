-- Story 3d-2 (code-review D1) — pin the reviewed artifact on the reviewer execution at enqueue.
--
-- Head on disk is V21__add_step_reviews_runner_execution_uniqueness.sql (story 3d-2); this next-free
-- number is V22. 3d-3 also targets >=V20 (V20 manual-kind); V21/V22 are 3d-2's.
--
-- WHY: the advisory reviewer is enqueued when a step's output artifact lands in WaitingForReview.
-- Previously both the compose (createForReview) and the harvest (ReviewResultHarvester) independently
-- RE-DERIVED the reviewed artifact via latestAvailable(prOutput).or(implementationPlan). If a newer
-- artifact became AVAILABLE between compose and harvest, the verdict's composite FK could be pinned to
-- an artifact the reviewer never saw. We now resolve the reviewed artifact ONCE at enqueue and pin its
-- (public id, version, type) onto the reviewer runner_executions row; the harvest reuses that exact pin
-- instead of re-deriving, so the verdict always references the artifact the reviewer reviewed.
--
-- REVIEW-only + nullable: every non-REVIEW execution (and a reviewer enqueued before this column
-- existed, or one whose enqueue-time resolve failed) leaves these null and the harvest falls back to
-- re-derivation — byte-identical to the prior behavior. Additive + replay-safe (Flyway never re-runs).
alter table runner_executions
    add column reviewed_artifact_id text,
    add column reviewed_artifact_version integer,
    add column reviewed_artifact_type text;
