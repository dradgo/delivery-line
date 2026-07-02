-- V32: Re-narrow the pending-clarification partial index so `superseded` no longer counts as pending.
-- (Renumbered from V31 on merge into main: main's V31 is add_runner_execution_token_columns; this
--  index migration follows it as V32. No environment recorded the old V31 form of this migration.)
--
-- The V9 index idx_clarifications_pending_by_workflow_run backs the story-2.12 approve_spec gate:
-- countPendingByWorkflowRunPublicId counts non-terminal, non-archived clarifications, and the gate
-- drops approve_spec whenever that count > 0. The V9 predicate excluded only ('incorporated',
-- 'rejected_invalid') — but the domain model (Clarification.isTerminal()) treats `superseded` as
-- terminal too. That mismatch wedges any run whose spec was regenerated: the accepted clarifications
-- that the new spec did not address are marked `superseded` (no_effect_reason
-- clarification_not_addressed), yet they kept counting as pending, so approve_spec never reappeared.
--
-- The query (ClarificationRepository.countPendingByWorkflowRunPublicId) now also excludes
-- 'superseded'; this migration re-narrows the partial index to the SAME predicate so the two stay
-- mirrored and the index does not carry rows the query never matches. Standard (non-CONCURRENTLY)
-- drop+create is fine — Flyway wraps each migration in a transaction and CONCURRENTLY cannot run
-- inside one; the index only accelerates a count on the human-paced approve_spec surface.

drop index idx_clarifications_pending_by_workflow_run;
create index idx_clarifications_pending_by_workflow_run
    on clarifications (workflow_run_id)
    where status not in ('incorporated', 'rejected_invalid', 'superseded') and archived_at is null;
