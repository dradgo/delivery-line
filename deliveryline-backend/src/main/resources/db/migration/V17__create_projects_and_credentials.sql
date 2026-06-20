-- Story 3c-1 — projects + project_credentials schema + project_id run association.
--
-- This migration is V17, NOT the epic/proposal's "V14". The head migration on disk is
-- V16__add_cancelled_for_takeover_status.sql (story 3.22), so V17 is the next free version.
-- (Same drift the codebase already absorbed: epic "V5" -> real V15; story "V15" -> real V16.)
--
-- Shape note: projects/project_credentials follow the universal core-table invariant
-- (bigserial id PK + public_id text + format CHECK + created_at/archived_at). The prj_/cred_
-- prefixes live on public_id, never on the PK. Runs/links/credentials reference a project by its
-- prj_ public_id (text FK), honoring the proposal's explicit `project_id text`.
-- Enum-likes (project_status, connector_kind) are text + CHECK with inlined value sets; the
-- ProjectStatus/ConnectorKind registries + drift tests land in story 3c-2.

create table projects (
    id bigserial primary key,
    public_id text not null,
    name text not null,
    slug text not null,
    status text not null,
    repository_url text null,
    ticket_source_kind text not null,
    repo_host_kind text not null,
    openspec_enabled boolean not null default false,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_projects_public_id unique (public_id),
    constraint uq_projects_slug unique (slug),
    constraint ck_projects_public_id_format check (public_id ~ '^prj_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_projects_status check (status in ('active', 'disabled')),
    constraint ck_projects_ticket_source_kind check (ticket_source_kind in ('linear', 'github')),
    constraint ck_projects_repo_host_kind check (repo_host_kind in ('linear', 'github'))
);

create table project_credentials (
    id bigserial primary key,
    public_id text not null,
    project_id text not null,
    connector_role text not null,
    ciphertext bytea not null,
    key_id text not null,
    algo text not null,
    created_at timestamptz not null default now(),
    archived_at timestamptz null,
    constraint uq_project_credentials_public_id unique (public_id),
    constraint ck_project_credentials_public_id_format check (public_id ~ '^cred_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_project_credentials_connector_role check (connector_role in ('ticket_source', 'repo_host')),
    constraint fk_project_credentials_projects foreign key (project_id)
        references projects (public_id) on delete restrict on update cascade
);

-- One *active* secret per (project, role): a partial unique index so an archived credential
-- (archived_at set) frees the slot for a rotated replacement. A total UNIQUE would block rotation.
-- (Same active-scoped pattern as V6 uq_integration_links_active_linear_ref ... where archived_at is null.)
create unique index uq_project_credentials_project_role
    on project_credentials (project_id, connector_role)
    where archived_at is null;

-- Run -> Project association (nullable now; story 3c-6 backfills to the seeded default project,
-- after which the application treats it as required).
alter table workflow_runs
    add column project_id text null;
alter table workflow_runs
    add constraint fk_workflow_runs_projects foreign key (project_id)
        references projects (public_id) on delete restrict on update cascade;
create index idx_workflow_runs_project_id on workflow_runs (project_id);

-- Integration link -> Project association (Epic 4 conflict-detection resolves the adapter per project).
alter table integration_links
    add column project_id text null;
alter table integration_links
    add constraint fk_integration_links_projects foreign key (project_id)
        references projects (public_id) on delete restrict on update cascade;
create index idx_integration_links_project_id on integration_links (project_id);
