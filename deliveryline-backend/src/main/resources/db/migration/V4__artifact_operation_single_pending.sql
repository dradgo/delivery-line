-- Enforce the "≤1 pending operation per artifact" invariant at the DB level so
-- ArtifactOperationPersistenceAdapter.findPendingByArtifactId can never return the
-- wrong row when two pending ops coexist (the createdAt-DESC tiebreak only worked
-- because no schema rule prevented duplicates).
create unique index uq_artifact_operations_pending_per_artifact
    on artifact_operations (artifact_id)
    where status = 'pending';
