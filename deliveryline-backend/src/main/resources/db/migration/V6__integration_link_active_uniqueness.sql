-- V6: Cross-run uniqueness for active Linear integration links.
--
-- AC5 (story 1.14): "same ticket already linked to a different active run" must raise
-- INTEGRATION_LINK_CONFLICT. The V1 uq_integration_links_type_external_ref_run_id index only
-- prevents double-linking the same ticket to the SAME run; this partial unique index is the
-- belt-and-braces backstop that prevents linking the same active ticket across DIFFERENT runs.
--
-- The application path normally raises INTEGRATION_LINK_CONFLICT via a SELECT … FOR UPDATE on
-- the active-link row before this index fires. When two transactions race past the application
-- check, this index produces a DataIntegrityViolationException that the persistence adapter
-- translates back to the same typed INTEGRATION_LINK_CONFLICT — both paths produce identical
-- surface for callers.
--
-- "Active" = archived_at IS NULL AND sync_status != 'superseded'. A failed link can be retried
-- (failed -> linked) so it stays in the active set. A superseded link is excluded so a fresh
-- link can take its place.
--
-- Standard CREATE INDEX (non-CONCURRENTLY) is acceptable here: the integration_links table is
-- empty on the foundation slice (no rows have been written yet). Flyway runs migrations inside
-- a transaction by default and CONCURRENTLY cannot run inside a transaction.

create unique index uq_integration_links_active_linear_ref
    on integration_links (integration_type, external_ref)
    where archived_at is null and sync_status != 'superseded';
