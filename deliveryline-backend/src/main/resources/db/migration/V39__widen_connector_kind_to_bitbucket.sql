-- Story 3i-3 (FR82) — widen the projects connector_kind value set with the BITBUCKET kind.
--
-- Head on disk at implementation time is V38__add_delivery_gate_and_push_mode.sql (story 3h-4);
-- V33..V38 (3h-2 lint stage, 4-3 audit indexes, 4-17 integration conflicts, 3i-1 jira, 3h-4
-- delivery gate) have all merged into this branch, so V39 is the next-free head (Flyway
-- cross-branch-collision trap: re-confirmed against merged state, not the stale "V33 highest"
-- story note).
--
-- Both ck_projects_ticket_source_kind and ck_projects_repo_host_kind are re-derived with the
-- drop-then-re-add CHECK idiom (precedent: V18/V37) so the {linear,github,gitlab,jira,bitbucket}
-- set matches the ConnectorKind enum + the connectorKinds API placeholder. The 3c-2
-- RegistryContractTest.projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest drift
-- gate enforces the three stay aligned. Flyway never re-runs an applied migration, so this is a
-- no-op on replay (FlywaySchemaContractTest.flywayMigrateIsReplaySafeAndChecksumStable).

alter table projects drop constraint ck_projects_ticket_source_kind;
alter table projects add constraint ck_projects_ticket_source_kind
    check (ticket_source_kind in ('linear', 'github', 'gitlab', 'jira', 'bitbucket'));

alter table projects drop constraint ck_projects_repo_host_kind;
alter table projects add constraint ck_projects_repo_host_kind
    check (repo_host_kind in ('linear', 'github', 'gitlab', 'jira', 'bitbucket'));
