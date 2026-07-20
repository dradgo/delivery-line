-- Story 4.9 (AC4, AC7) — governed failure-taxonomy classification (FR37/FR38, NFR33).
-- Adds the three operator-triage classification columns to workflow_runs and widens the
-- recovery_actions action_type CHECK with 'classify_failure'. classifyFailure is a pure metadata
-- operation (no state transition), so the columns are deliberately NOT mapped on
-- WorkflowRunEntity — they are owned by the JDBC WorkflowRunFailureClassificationPort, mirroring
-- the V33/V34 loop-counter columns (a stale full-row JPA UPDATE would otherwise null them).
--
-- Version note: V43__add_cancelled_for_pause_status.sql (story 4.8) is the highest migration on
-- disk, so the next free version is V44 (the story draft's "V40" predates 3i-3/3h-5/4.7/4.8
-- landing — [[flyway-v31-cross-branch-collision]]).

-- Human-applied, run-scoped taxonomy classification (orthogonal to the machine-emitted
-- failure_category columns; see FailureTaxonomyValue's javadoc + ADR 0035). The value set is
-- CHECK-constrained and grows in lockstep with the FailureTaxonomyValue registry enum
-- (RegistryContractTest.failureTaxonomyValuesStayAlignedWithSqlCheck). NFR33: values are never
-- removed from this CHECK — deprecation is registry metadata only, historical rows stay valid.
alter table workflow_runs
    add column failure_classification text null;
alter table workflow_runs
    add column failure_classified_at timestamptz null;
alter table workflow_runs
    add column failure_classified_by text null;

alter table workflow_runs
    add constraint ck_workflow_runs_failure_classification check (
        failure_classification is null or failure_classification in (
            'specification_gap',
            'context_gap',
            'agent_execution_failure',
            'review_rejection',
            'integration_or_merge_failure',
            'tooling_or_infrastructure_failure'
        )
    );

-- All-or-nothing invariant: a classification always carries its attribution + timestamp (AC8).
alter table workflow_runs
    add constraint ck_workflow_runs_failure_classification_complete check (
        (
            failure_classification is null
            and failure_classified_at is null
            and failure_classified_by is null
        ) or (
            failure_classification is not null
            and failure_classified_at is not null
            and failure_classified_by is not null
        )
    );

-- 'classify_failure' is NOT a pre-reserved V1 action_type slot (contrast 4.7's 'rerun' and 4.8's
-- 'pause') — the CHECK must be widened or the recovery_actions insert is rejected at flush time
-- (RecoveryActionPersistenceAdapter does not pre-validate action_type in Java). Drop-then-re-add
-- is the Postgres idiom for changing a CHECK predicate; the widened set re-states the FULL V1
-- value set and appends the new value.
alter table recovery_actions
    drop constraint ck_recovery_actions_action_type;
alter table recovery_actions
    add constraint ck_recovery_actions_action_type check (
        action_type in (
            'retry', 'rerun', 'resume', 'takeover', 'pause', 'reconcile', 'classify_failure'
        )
    );
