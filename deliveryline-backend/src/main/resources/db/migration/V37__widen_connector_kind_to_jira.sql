-- Story 3i-1 (FR80) — widen the projects connector_kind value set with the JIRA kind.
--
-- Head on disk at implementation time is V36__create_integration_conflicts.sql (story 4-17);
-- V33..V36 (3h-2 lint stage, 4-3 audit indexes, 4-17 integration conflicts) have all merged into
-- this branch, so V37 is the next-free head (Flyway cross-branch-collision trap: re-confirmed
-- against merged state, not the stale "V33 highest" memory note).
--
-- Both ck_projects_ticket_source_kind and ck_projects_repo_host_kind are re-derived with the
-- drop-then-re-add CHECK idiom (precedent: V18) so the {linear,github,gitlab,jira} set matches the
-- ConnectorKind enum + the connectorKinds API placeholder. The 3c-2
-- RegistryContractTest.projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest drift
-- gate enforces the three stay aligned. Flyway never re-runs an applied migration, so this is a
-- no-op on replay (FlywaySchemaContractTest.flywayMigrateIsReplaySafeAndChecksumStable).

alter table projects drop constraint ck_projects_ticket_source_kind;
alter table projects add constraint ck_projects_ticket_source_kind
    check (ticket_source_kind in ('linear', 'github', 'gitlab', 'jira'));

alter table projects drop constraint ck_projects_repo_host_kind;
alter table projects add constraint ck_projects_repo_host_kind
    check (repo_host_kind in ('linear', 'github', 'gitlab', 'jira'));
