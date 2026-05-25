-- Story 2.12: Backend Visible Incorporation Lifecycle States + Event Wiring.
--
-- Layers lifecycle metadata columns + CHECK + FK + partial index on top of story 2.11's V8
-- clarifications base table. Kept as V9 (not bundled into V8) so external readers of the V8 shape
-- remain migration-stable.
--
-- Five new columns:
--   accepted_at          timestamptz  -- set on answered -> accepted transition
--   incorporated_at      timestamptz  -- set on accepted -> incorporated transition
--   superseded_by_artifact_id      bigint   -- FK to artifacts.id (composite with version)
--   superseded_by_artifact_version integer  -- composite FK pair
--   no_effect_reason     text         -- controlled vocabulary (snake_case enum, app-layer enforced)
--
-- The status-derivable field-presence invariant (ck_clarifications_status_fields_paired) is the
-- defense-in-depth backstop for ClarificationLifecycleService transitions (story 2.11 trap T9
-- pattern). The application layer writes paired fields atomically; the CHECK catches a buggy
-- service path.

-- Drop the V8 fk_clarifications_incorporation_event (ON DELETE SET NULL) and re-add as RESTRICT.
-- Reason: V9's ck_clarifications_status_fields_paired requires incorporation_event_id IS NOT NULL
-- for status='incorporated'. With ON DELETE SET NULL, a workflow_events tombstone would null the
-- FK on an incorporated clarification and violate the CHECK on its next UPDATE. RESTRICT prevents
-- tombstoning an event still referenced by an incorporated clarification.
alter table clarifications drop constraint fk_clarifications_incorporation_event;
alter table clarifications
    add constraint fk_clarifications_incorporation_event
    foreign key (incorporation_event_id)
    references workflow_events (id) on delete restrict on update cascade;

alter table clarifications add column accepted_at timestamptz null;
alter table clarifications add column incorporated_at timestamptz null;
alter table clarifications add column superseded_by_artifact_id bigint null;
alter table clarifications add column superseded_by_artifact_version integer null;
alter table clarifications add column no_effect_reason text null;

-- Composite FK pinning the supersession reference to a specific (artifact, version) pair, mirroring
-- the (artifact_id, artifact_version) -> artifacts(id, version) composite FK in V8 line 38 and the
-- approvals.(artifact_id, artifact_version) FK at V1:188. Backed by uq_artifacts_id_version.
alter table clarifications
    add constraint fk_clarifications_superseded_by_artifact
    foreign key (superseded_by_artifact_id, superseded_by_artifact_version)
    references artifacts (id, version) on delete restrict on update cascade;

-- The two supersession columns travel together — partial NULLs are illegal regardless of status.
alter table clarifications
    add constraint ck_clarifications_supersedes_pair check (
        (superseded_by_artifact_id is null) = (superseded_by_artifact_version is null)
    );

-- Status-derivable field-presence invariant. Defense-in-depth for ClarificationLifecycleService.
-- AC2 state machine: open -> answered (V8) -> accepted -> incorporated | superseded, OR
--                    answered -> rejected_invalid.
alter table clarifications
    add constraint ck_clarifications_status_fields_paired check (
        case status
            when 'open' then
                accepted_at is null
                and incorporated_at is null
                and superseded_by_artifact_id is null
                and no_effect_reason is null
            when 'answered' then
                accepted_at is null
                and incorporated_at is null
                and superseded_by_artifact_id is null
                and no_effect_reason is null
            when 'accepted' then
                accepted_at is not null
                and incorporated_at is null
                and superseded_by_artifact_id is null
                and no_effect_reason is null
            when 'incorporated' then
                accepted_at is not null
                and incorporated_at is not null
                and incorporation_event_id is not null
                and superseded_by_artifact_id is null
                and no_effect_reason is null
            when 'superseded' then
                accepted_at is not null
                and incorporated_at is null
                and superseded_by_artifact_id is not null
                and no_effect_reason is not null
            when 'rejected_invalid' then
                -- State machine (AC2): only answered -> rejected_invalid; accepted_at must be null.
                accepted_at is null
                and incorporated_at is null
                and superseded_by_artifact_id is null
                and no_effect_reason is not null
            else false
        end
    );

-- Partial index accelerating ClarificationReadPort.countPendingByWorkflowRun (story 2.12 AC9 /
-- Task 3 / Task 5). Excludes terminal-decided rows (incorporated, rejected_invalid) at the index
-- level — see project memory `pendingClarifications` semantics deep dive.
create index idx_clarifications_pending_by_workflow_run
    on clarifications (workflow_run_id)
    where status not in ('incorporated', 'rejected_invalid') and archived_at is null;
