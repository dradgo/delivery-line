-- V34: CPU Lint Gate config + lint findings store + non-terminal WaitingForLintApproval state
-- (story 3h-2, FR76).
--
-- Head on disk is V33__add_build_stage_columns.sql, so V34 is the next free version. (Re-confirmed
-- the head at implementation time — flyway-v31-cross-branch-collision trap: no V34 exists in src or
-- target/classes on this branch. Sibling unmerged stories 3i-1/3i-3/4-x also claim V34+; each takes
-- next-free at its own merge.)
--
-- Structural twin of V33: two per-project config columns (default DISABLED so pre-3h-2 projects are
-- byte-identical) + one per-run loop counter for the operator-driven lint fix loop + a nullable
-- jsonb findings column on runner_executions + the state CHECK widening for the new gate state.

-- Per-project lint config on the projects aggregate.
--   * lint_commands is nullable opaque text: the lint command list serialized newline-delimited.
--     NULL/empty = "no lint commands" -> LINT stage skipped entirely (parity). Mirrors build_command
--     (V33) — NO CHECK: the authoritative "is lint configured" decision is
--     ProjectRuntimeConfigResolver at execution time, not a DB constraint.
--   * lint_stage_enabled mirrors build_stage_enabled (V33): plain boolean, NOT NULL DEFAULT false, so
--     a project must opt in. A project with lint_stage_enabled=false skips LINT (pre-3h-2 parity).
alter table projects add column lint_commands text null;
alter table projects add column lint_stage_enabled boolean not null default false;

-- Per-run operator-driven lint fix loop counter (mirrors V33 build_fix_loop_count). Reuses V7's
-- escalation_marker_set column (one escalation marker per run, shared across the spec-rejection /
-- implementation-rejection / build-fix / lint-fix loops). Only the per-stage counter is new. Unlike
-- the build loop the lint loop NEVER auto-FAILs the run (operator-driven request_lint_fix); the cap
-- flips the shared escalation marker for visibility only.
alter table workflow_runs
    add column lint_fix_loop_count integer not null default 0;

alter table workflow_runs
    add constraint ck_workflow_runs_lint_fix_loop_count_nonneg
        check (lint_fix_loop_count >= 0);

-- Severity-classified lint findings persisted on the LINT runner_executions row (story 3h-2
-- Decision 5). Nullable jsonb: NULL for non-LINT rows and for a LINT row before findings are
-- recorded. step_reviews does not suffice (hard outcome CHECK + mandatory artifact FK + no payload
-- column), so the findings live here on the row that already exists and is redaction-captured.
alter table runner_executions add column lint_findings jsonb null;

-- Widen all three workflow-state CHECK constraints to admit the non-terminal WaitingForLintApproval
-- gating state (drop-and-readd, copying the V28 precedent). The lint gate parks a run here before
-- any LLM review or push; it leaves only on approve_lint (-> WaitingForReview), request_lint_fix
-- (-> Executing), or a recovery/safety edge (TakenOver / Reconciled). No -> Failed edge (Decision 3).
alter table workflow_runs
    drop constraint ck_workflow_runs_current_state;
alter table workflow_runs
    add constraint ck_workflow_runs_current_state check (
        current_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies',
            'WaitingForLintApproval', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_prior_state;
alter table workflow_events
    add constraint ck_workflow_events_prior_state check (
        prior_state is null or prior_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies',
            'WaitingForLintApproval', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );

alter table workflow_events
    drop constraint ck_workflow_events_resulting_state;
alter table workflow_events
    add constraint ck_workflow_events_resulting_state check (
        resulting_state is null or resulting_state in (
            'Inbox', 'Planned', 'Investigating', 'WaitingForSpecApproval', 'Executing',
            'WaitingForReview', 'WaitingForManualExecution', 'WaitingForDependencies',
            'WaitingForLintApproval', 'Split',
            'Completed', 'Failed', 'Paused', 'TakenOver', 'Reconciled'
        )
    );
