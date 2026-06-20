-- Story 3c-3 (AC8) — widen the projects connector_kind value set with the GITLAB stub kind.
--
-- This is V18 (head on disk is V17__create_projects_and_credentials.sql, story 3c-1). The epic's
-- "Flyway V14" number is stale; the slug/key keeps its name (synced to sprint-status.yaml).
--
-- Both ck_projects_ticket_source_kind and ck_projects_repo_host_kind are re-derived with the
-- drop-then-re-add CHECK idiom (precedent: V12/V16) so the {linear,github,gitlab} set matches the
-- 3c-2 ConnectorKind enum + the connectorKinds API placeholder. The 3c-2
-- RegistryContractTest.projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest drift
-- gate enforces the three stay aligned. Flyway never re-runs an applied migration, so this is a
-- no-op on replay (FlywaySchemaContractTest.flywayMigrateIsReplaySafeAndChecksumStable).

alter table projects drop constraint ck_projects_ticket_source_kind;
alter table projects add constraint ck_projects_ticket_source_kind
    check (ticket_source_kind in ('linear', 'github', 'gitlab'));

alter table projects drop constraint ck_projects_repo_host_kind;
alter table projects add constraint ck_projects_repo_host_kind
    check (repo_host_kind in ('linear', 'github', 'gitlab'));
