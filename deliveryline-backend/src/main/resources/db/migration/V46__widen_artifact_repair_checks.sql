-- Story 4.16 (AC2, AC3) — REPAIR half of Epic-4's artifact-reconciliation split (detection = 4.15,
-- lineage/fork = 4.16a). Operator-driven repair actions on ArtifactReconciliationService resolve a
-- detected artifact_drift_detected row through explicit, auditable decisions (NFR19: no silent
-- overwrite). Each repair appends ONE recovery_actions row with action_type='artifact_repair' and
-- (for markCorrupted) flips an artifact to the new 'corrupted' status.
--
-- This migration does ZERO new tables/columns — the resolved_at / resolved_by_action_id columns and
-- the fk_artifact_drift_detected_recovery_actions FK already shipped nullable + unwritten in V45.
-- It performs exactly TWO CHECK widenings, using the Postgres DROP-then-re-ADD idiom (a CHECK
-- predicate cannot be altered in place); each widened CHECK re-states the FULL prior value set and
-- appends the new value (mirror V44:55-62).
--
-- Version note: V45__add_artifact_drift_detected.sql (story 4.15) is the highest migration on disk,
-- so the next free version is V46 ([[flyway-v31-cross-branch-collision]] — re-confirmed against the
-- target branch's db/migration/ immediately before authoring). Additive + replay-safe by
-- construction (Flyway never re-runs an applied migration).

-- 'artifact_repair' is NOT a pre-reserved V1 action_type slot (contrast 4.7's 'rerun' / 4.8's
-- 'pause') — the CHECK must be widened or the recovery_actions insert every repair appends is
-- rejected at flush time (RecoveryActionPersistenceAdapter does not pre-validate action_type in
-- Java). Restate the FULL V1 set + V44's 'classify_failure' + the new 'artifact_repair'.
alter table recovery_actions
    drop constraint ck_recovery_actions_action_type;
alter table recovery_actions
    add constraint ck_recovery_actions_action_type check (
        action_type in (
            'retry', 'rerun', 'resume', 'takeover', 'pause', 'reconcile', 'classify_failure',
            'artifact_repair'
        )
    );

-- 'corrupted' is the ONLY new ArtifactStatus (story 4.16 markCorrupted: AVAILABLE -> CORRUPTED for a
-- checksum-mismatch drift on an available artifact). Restate the FULL V1 set + 'corrupted'. NFR33:
-- values are never removed from this CHECK. Kept three-way aligned with the ArtifactStatus enum +
-- registry-api-schema-placeholders.json artifactStatuses by
-- RegistryContractTest.actorTypesAndStatusRegistriesStayAlignedWithSqlChecksAndApiManifest.
alter table artifacts
    drop constraint ck_artifacts_status;
alter table artifacts
    add constraint ck_artifacts_status check (
        status in ('pending', 'available', 'failed', 'late_or_stale', 'corrupted')
    );
