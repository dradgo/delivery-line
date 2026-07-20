-- Story 4.17 (FR41/FR43) — DETECTION half of Epic-4's integration-conflict pair.
-- Additive integration_conflicts table: the queryable record of a detected disagreement between
-- internal workflow state and the cached-vs-fresh EXTERNAL (Linear ticket / GitHub PR) state,
-- written by the scheduled IntegrationConflictDetectionService sweep — NEVER a silent overwrite
-- (NFR19). This is a PURE PRODUCER: the conflict is recorded here, an integration.conflictDetected
-- event is appended, and 4.6 (RecoveryService.reconcile) / 4.18 (operator surfacing) later resolve
-- it (resolved_at / resolved_by_action_id ship nullable + UNWRITTEN by this story — the
-- "column-defined-now, written-by-a-later-story" precedent of split_proposals.loop_count).
--
-- Additive + replay-safe by construction (Flyway never re-runs an applied migration); no backfill,
-- no destructive change. The integration_link / workflow_run / recovery_action references are opaque
-- text public_ids keyed on each parent's public_id with ON DELETE RESTRICT ON UPDATE CASCADE (the
-- 3f-2/3f-3 convention — opaque public_id refs, never surrogate ids).

create table integration_conflicts (
    id bigserial primary key,
    public_id text not null,
    integration_link_id text not null,
    workflow_run_id text not null,
    conflict_category text not null,
    detected_at timestamptz not null default now(),
    internal_state_snapshot jsonb not null default '{}'::jsonb,
    external_state_snapshot jsonb not null default '{}'::jsonb,
    resolved_at timestamptz null,
    resolved_by_action_id text null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_integration_conflicts_public_id unique (public_id),
    constraint ck_integration_conflicts_public_id_format
        check (public_id ~ '^icf_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_integration_conflicts_conflict_category
        check (conflict_category in (
            'external_state_advanced',
            'external_state_reverted',
            'external_resource_removed',
            'metadata_drift',
            'link_broken')),
    constraint fk_integration_conflicts_integration_links foreign key (integration_link_id)
        references integration_links (public_id) on delete restrict on update cascade,
    constraint fk_integration_conflicts_workflow_runs foreign key (workflow_run_id)
        references workflow_runs (public_id) on delete restrict on update cascade,
    -- resolved_by_action_id is populated later by story 4.6 (RecoveryService.reconcile); nullable +
    -- unwritten by 4.17. recovery_actions already exists (V1); the FK is defined now so the resolve
    -- path needs no schema change.
    constraint fk_integration_conflicts_recovery_actions foreign key (resolved_by_action_id)
        references recovery_actions (public_id) on delete restrict on update cascade
);

-- MANDATORY dedup: without this the 5-minute sweep would insert a fresh conflict row + emit a fresh
-- integration.conflictDetected event every tick for the same standing conflict. At most one
-- UNRESOLVED, non-archived conflict per (link, category); the sweep inserts ON CONFLICT DO NOTHING
-- and emits the event ONLY on a real insert. Mirrors 3f-4's one-open-proposal-per-run partial index.
create unique index uq_integration_conflicts_unresolved
    on integration_conflicts (integration_link_id, conflict_category)
    where resolved_at is null and archived_at is null;

-- Category + recency lookup (the listUnresolvedConflicts read surface + the "Integration Conflicts"
-- Grafana panel filter by category, newest-first).
create index idx_integration_conflicts_category_detected_at
    on integration_conflicts (conflict_category, detected_at);

-- Dependent-direction lookup (find a run's conflicts).
create index idx_integration_conflicts_workflow_run_id
    on integration_conflicts (workflow_run_id);
