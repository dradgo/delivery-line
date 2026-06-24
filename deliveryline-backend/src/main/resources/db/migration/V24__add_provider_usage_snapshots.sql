-- Story 3d-7 (FR69, ADR 0025 redaction/localhost posture) — post-execution provider usage/limit
-- snapshot. One additive, replay-safe migration adding a single new table; no existing table is
-- touched, no data backfill, no destructive change.
--
-- Version note: V23__add_workflow_runs_archived_at_index.sql (story 3d-8) is the highest migration
-- on disk, so the next free version is V24. (The story spec cited V21, written when V20 was head;
-- the repo has since absorbed V21–V23. Same stale-version drift the repo already tolerated:
-- V5->V15, V14->V17, V19->V20.)
--
-- Per-credential, NON-SECRET attribution (Trap T1): account_reference is the runner-emitted
-- non-secret accountLabel (e.g. 'claude:oauth', 'codex:subscription'), NOT a project_credentials
-- FK and NEVER a token-derived value. NO secret/token column exists on this table by construction —
-- only window numbers, reset/as-of timestamps, and the non-secret label (AC3/AC4).
--
-- signal_state 'not_exposed' is a first-class state: the provider does not surface the 5h/weekly
-- window in headless mode (spike outcome), so the window columns are all NULL and the UI/CLI degrade
-- to the documented "not exposed by provider" indicator — never a fabricated number.

create table provider_usage_snapshots (
    id                       bigserial primary key,
    public_id                text not null,
    workflow_run_id          text not null,
    runner_execution_id      text null,
    account_reference        text not null,
    signal_state             text not null,
    five_hour_used_fraction  numeric null,
    five_hour_used           integer null,
    five_hour_limit          integer null,
    five_hour_resets_at      timestamptz null,
    weekly_used_fraction     numeric null,
    weekly_used              integer null,
    weekly_limit             integer null,
    weekly_resets_at         timestamptz null,
    as_of                    timestamptz null,
    created_at               timestamptz not null default now(),
    archived_at              timestamptz null,
    constraint uq_provider_usage_snapshots_public_id unique (public_id),
    constraint ck_provider_usage_snapshots_public_id_format
        check (public_id ~ '^pul_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_provider_usage_snapshots_signal_state
        check (signal_state in ('available', 'not_exposed'))
);

-- Hot read path (story 3d-7 AC5): the REST/CLI surface reads the LATEST non-archived snapshot for a
-- run. Index by (workflow_run_id, created_at desc) over the live rows so that lookup is a cheap
-- index scan. Mirrors the partial-index posture used elsewhere for archived_at-scoped reads.
create index idx_provider_usage_snapshots_run_latest
    on provider_usage_snapshots (workflow_run_id, created_at desc)
    where archived_at is null;
