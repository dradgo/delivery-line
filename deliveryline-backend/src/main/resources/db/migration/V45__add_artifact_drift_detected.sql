-- Story 4.15 (NFR2, FR47) — DETECTION half of Epic-4's artifact-reconciliation split (repair = 4.16,
-- lineage = 4.16a). artifact_drift_detected is the queryable record of a detected DB/file drift —
-- (a) orphan operation (stale pending artifact_operations), (b) missing payload (artifacts.status =
-- 'available' but LocalArtifactStore cannot resolve storage_ref), (c) checksum mismatch (payload
-- exists but its recomputed checksum <> checksum_value) — written by the scheduled
-- ArtifactDriftDetectionService sweep. PURE PRODUCER, DETECTION ONLY: the row is recorded and an
-- artifact.driftDetected event appended; the job NEVER flips artifacts.status /
-- artifact_operations.status and NEVER writes resolved_*. The repair path (story 4.16) later
-- resolves it (resolved_at / resolved_by_action_id ship nullable + UNWRITTEN by 4.15 — the
-- split_proposals.loop_count "column-defined-now, written-by-a-later-story" precedent).
--
-- Additive + replay-safe by construction (Flyway never re-runs an applied migration); no backfill,
-- no destructive change. The workflow_run / artifact / artifact_operation / recovery_action
-- references are opaque text public_ids keyed on each parent's public_id with ON DELETE RESTRICT ON
-- UPDATE CASCADE (the 3f-2/3f-3/4.17 convention — opaque public_id refs, never surrogate ids).

create table artifact_drift_detected (
    id bigserial primary key,
    public_id text not null,
    workflow_run_id text not null,
    artifact_id text null,
    artifact_operation_id text null,
    drift_category text not null,
    detected_at timestamptz not null default now(),
    last_known_state jsonb not null default '{}'::jsonb,
    resolved_at timestamptz null,
    resolved_by_action_id text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_artifact_drift_detected_public_id unique (public_id),
    constraint ck_artifact_drift_detected_public_id_format
        check (public_id ~ '^adr_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_artifact_drift_detected_drift_category
        check (drift_category in ('orphan_operation', 'missing_payload', 'checksum_mismatch')),
    -- Exactly one target FK populated: an orphan-operation drift carries artifact_operation_id; a
    -- missing-payload / checksum-mismatch drift carries artifact_id.
    constraint ck_artifact_drift_detected_one_target
        check ((artifact_id is null) <> (artifact_operation_id is null)),
    constraint fk_artifact_drift_detected_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (public_id) on delete restrict on update cascade,
    constraint fk_artifact_drift_detected_artifacts foreign key (artifact_id)
        references artifacts (public_id) on delete restrict on update cascade,
    constraint fk_artifact_drift_detected_artifact_operations foreign key (artifact_operation_id)
        references artifact_operations (public_id) on delete restrict on update cascade,
    -- resolved_by_action_id is populated later by story 4.16 (operator-driven repair); nullable +
    -- unwritten by 4.15. recovery_actions already exists (V1); the FK is defined now so the resolve
    -- path needs no schema change.
    constraint fk_artifact_drift_detected_recovery_actions foreign key (resolved_by_action_id)
        references recovery_actions (public_id) on delete restrict on update cascade
);

-- MANDATORY dedup: without this the 15-minute sweep would insert a fresh drift row + emit a fresh
-- artifact.driftDetected event every tick for the same standing drift. At most one UNRESOLVED,
-- non-archived drift per (category, artifact, operation); the sweep inserts ON CONFLICT DO NOTHING
-- and emits the event ONLY on a real insert. NULLS NOT DISTINCT so the NULL target column (orphan
-- drifts leave artifact_id NULL, payload/checksum drifts leave artifact_operation_id NULL) still
-- collapses duplicates instead of PostgreSQL's default distinct-NULL behaviour. A drift that 4.16
-- later resolves and that then recurs is allowed a NEW row (the partial index guards only the
-- unresolved slice). Mirrors 4.17's uq_integration_conflicts_unresolved.
create unique index uq_artifact_drift_detected_active
    on artifact_drift_detected (drift_category, artifact_id, artifact_operation_id)
    nulls not distinct
    where resolved_at is null and archived_at is null;

-- Category + recency lookup (the listUnresolvedDrift read surface + the "Artifact Drift" Grafana
-- panel filter by category, newest-first).
create index idx_artifact_drift_detected_category_detected_at
    on artifact_drift_detected (drift_category, detected_at);

-- Dependent-direction lookup (find a run's drift rows).
create index idx_artifact_drift_detected_workflow_run_id
    on artifact_drift_detected (workflow_run_id);

-- Supports the available-artifact scan (ArtifactRecordPort.findAvailableCreatedBefore): artifacts
-- had NO index on status before this story. Partial on status = 'available' keeps it small — only
-- the scanned slice — and orders by created_at so the bounded keyset scan is index-only.
create index idx_artifacts_status_created_at
    on artifacts (status, created_at)
    where status = 'available';
