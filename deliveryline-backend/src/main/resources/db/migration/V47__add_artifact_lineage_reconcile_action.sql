-- Story 4.16a (AC2, AC6) — LINEAGE/FORK-GOVERNANCE half of Epic-4's artifact-reconciliation split
-- (detection = 4.15, drift-repair = 4.16, lineage/fork = 4.16a THIS). Operator-driven lineage
-- recovery actions on ArtifactReconciliationService (reattach_to_existing_lineage /
-- terminate_ambiguous_lineage / create_explicit_fork) resolve ambiguous artifact history through
-- explicit, auditable decisions (NFR19: no silent overwrite). Each reconcile appends ONE
-- recovery_actions row with action_type='artifact_lineage_reconcile'.
--
-- This migration does ZERO new tables/columns — the lineage_recovery discriminator column already
-- shipped nullable-default-FALSE in V5 (finally WRITTEN by createExplicitFork this story); the
-- terminate action reuses the existing 'failed' ArtifactStatus (no ck_artifacts_status widen). It
-- performs exactly ONE CHECK widening, using the Postgres DROP-then-re-ADD idiom (a CHECK predicate
-- cannot be altered in place); the widened CHECK re-states the FULL prior value set and appends the
-- new value (mirror V46:22-30).
--
-- Version note: V46__widen_artifact_repair_checks.sql (story 4.16) is the highest migration on disk,
-- so the next free version is V47 ([[flyway-v31-cross-branch-collision]] — re-confirmed against the
-- target branch's db/migration/ immediately before authoring). Additive + replay-safe by
-- construction (Flyway never re-runs an applied migration).

-- 'artifact_lineage_reconcile' is NOT a pre-reserved V1 action_type slot — the CHECK must be widened
-- or the recovery_actions insert every lineage reconcile appends is rejected at flush time
-- (RecoveryActionPersistenceAdapter does not pre-validate action_type in Java). Restate the FULL V1
-- set + V44's 'classify_failure' + V46's 'artifact_repair' + the new 'artifact_lineage_reconcile'.
alter table recovery_actions
    drop constraint ck_recovery_actions_action_type;
alter table recovery_actions
    add constraint ck_recovery_actions_action_type check (
        action_type in (
            'retry', 'rerun', 'resume', 'takeover', 'pause', 'reconcile', 'classify_failure',
            'artifact_repair', 'artifact_lineage_reconcile'
        )
    );
