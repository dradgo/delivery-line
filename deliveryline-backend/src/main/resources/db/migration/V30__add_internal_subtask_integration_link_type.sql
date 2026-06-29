-- V30: Internal-only child→parent ticket linkage for split fan-out (story 3f-5).
--
-- When a split is committed (3f-5) a child run whose connector cannot create a real sub-ticket
-- (supportsTicketCreation = false, or the project has no ticket source) is still associated with
-- the PARENT's ticket. That association is recorded as an integration_links row of the new
-- `internal_subtask` type, whose external_ref is the parent's ticket reference.
--
-- Because every internal-only child of the same parent shares the parent's external_ref, the V6
-- cross-run uniqueness index (uq_integration_links_active_linear_ref — one active row per
-- (integration_type, external_ref)) would reject the second such child, and even the parent's own
-- `linear` link already occupies (linear, parentRef). We therefore re-type that index so it governs
-- only the externally-authoritative linkage types; `internal_subtask` rows fall back to the V1
-- per-run uniqueness uq_integration_links_type_external_ref_run_id over
-- (integration_type, external_ref, workflow_run_id), which still blocks a single child from
-- double-linking on an idempotent replay while allowing many distinct children to share one parent
-- reference.
--
-- The new type is intentionally OUTSIDE the `linear` polling/sync surface: LinearPollingHost and the
-- completion-sync writers query the `linear` / `github_pr` types only, so internal_subtask rows are
-- never polled or written back to a source. Standard (non-CONCURRENTLY) index recreation is fine —
-- Flyway runs each migration in a transaction and CONCURRENTLY cannot run inside one; the swap is a
-- drop+create of a partial index that the application's SELECT … FOR UPDATE conflict check fronts.

alter table integration_links
    drop constraint ck_integration_links_integration_type;
alter table integration_links
    add constraint ck_integration_links_integration_type
    check (integration_type in ('linear', 'github_pr', 'internal_subtask'));

drop index uq_integration_links_active_linear_ref;
create unique index uq_integration_links_active_external_ref
    on integration_links (integration_type, external_ref)
    where archived_at is null
      and sync_status != 'superseded'
      and integration_type <> 'internal_subtask';
