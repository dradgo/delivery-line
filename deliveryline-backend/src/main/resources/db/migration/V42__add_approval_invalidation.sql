-- Story 4.7 (AC7/AC9, Reconciliation 2) — approval invalidation columns.
--
-- rerun-from-step enforces obsolescence of a prior stage decision by INVALIDATING the current
-- approval (rerun-to-Investigating invalidates the current spec approval; rerun-to-Executing the
-- current implementationPlan approval), so `getCurrentApprovedSpec` returns null until the new
-- artifact version is re-approved. This is the ONLY real supersession write in story 4.7 (artifacts
-- are "superseded" by the existing lineage-graft mechanism, not by a column flip — Reconciliation
-- 1).
--
-- Two nullable columns, no default beyond NULL, so the migration is replay-safe and every existing
-- approvals row is unaffected (invalidated_at IS NULL == "still current"). The current-approved read
-- (ApprovalRepository.findLatestApprovedForArtifactLineage) adds `and a.invalidatedAt is null`.
--
-- NOTE ON NUMBERING: the story text names this migration V38, but V38 (delivery gate / push mode,
-- story 3h-4), V39 (bitbucket connector kind, story 3i-3), V40 (testcontainers-enabled) and V41 (CI
-- investigation columns, story 3h-5) were all claimed by sibling stories that merged/landed after
-- 4.7 was authored — exactly the "confirm the number is still free at authoring time" caveat in the
-- story ([[flyway-v31-cross-branch-collision]]). V42 is the next free number across BOTH the main
-- and test migration dirs.
alter table approvals
    add column invalidated_at timestamptz null,
    add column invalidated_reason text null;
