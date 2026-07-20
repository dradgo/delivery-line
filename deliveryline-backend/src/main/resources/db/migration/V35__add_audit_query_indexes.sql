-- V35: Add the two indexes backing the story-4.3 audit-history query surface
-- (`deliveryline audit query --ticket|--run`, GET /api/v1/audit/by-ticket|by-run).
--
-- 1. idx_workflow_events_event_type_created_at — the `--event-type` filter (AC2) has no covering
--    index today: workflow_events.event_type is unindexed, and the by-ticket / by-run keyset pages
--    order by (created_at DESC, id DESC). This composite serves the event-type predicate AND the
--    keyset ordering (created_at, id) so a filtered page is index-only on the sort key.
--
-- 2. idx_integration_links_external_ref_type — the by-ticket query (AC3) resolves all runs for a
--    ticket via `WHERE external_ref = ? AND integration_type = 'linear'` (no active-only filter, so
--    superseded links to earlier retried runs are included — story 4.3 Reconciliation 7). The only
--    existing integration_links index (idx_integration_links_run_id_type_created_at) is run-id-FIRST,
--    so an external_ref-first lookup is NOT index-covered; without this index the AC8 5s/200-event
--    target is at risk on a large integration_links table.
--
-- NOT created here (story 4.3 Reconciliation 1/4/5):
--   * No idx_workflow_events_correlation_id — there is NO correlation_id COLUMN on workflow_events;
--     correlation lives in details->>'correlationId' (JSONB), already covered by the V1
--     idx_workflow_events_details_gin. An index on a non-existent column is a hard error.
--   * The by-run event query rides the existing V1 idx_workflow_events_workflow_run_id_created_at —
--     no new index needed for it.
--
-- Plain (non-CONCURRENTLY) CREATE INDEX: Flyway wraps each migration in a transaction and Postgres
-- forbids CONCURRENTLY inside one (no non-transactional-migration precedent in this repo — see V6 /
-- V21 / V30 / V32). The MVP tables are small, so lock contention is a non-issue. IF NOT EXISTS keeps
-- the migration replay-safe / idempotent.

create index if not exists idx_workflow_events_event_type_created_at
    on workflow_events (event_type, created_at desc, id desc);

create index if not exists idx_integration_links_external_ref_type
    on integration_links (external_ref, integration_type);
