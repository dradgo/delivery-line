-- Story 2.10: backend spec rejection writer with structured feedback + escalation marker.
-- These two columns were originally planned for story 2.11's V2 migration per the epic AC,
-- but V2 is already taken by V2__artifact_failure_columns.sql (existing Flyway history:
-- V1 -> V1_1 -> V2 -> V3 -> V4 -> V5 -> V6), and story 2.11 (clarifications domain) has
-- not started. To break the 2.10 -> 2.11 chicken-and-egg, these columns ship in V7 here;
-- story 2.11 renumbers its clarifications migration to V8 (or whatever is next) when it
-- ships. Resolution surfaced in this story's PR description as Open Question OQ-1.

alter table workflow_runs
    add column spec_rejection_loop_count integer not null default 0,
    add column escalation_marker_set boolean not null default false;

alter table workflow_runs
    add constraint ck_workflow_runs_spec_rejection_loop_count_nonneg
        check (spec_rejection_loop_count >= 0);
