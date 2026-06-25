-- Story 3e-2 (FR10 close, AC6/R5) — structured spec-runner clarification acknowledgements side-store.
-- One additive, replay-safe migration adding a single new table; no existing table is touched, no
-- backfill, no destructive change.
--
-- Version note: V24__add_provider_usage_snapshots.sql (story 3d-7) is the highest migration on disk,
-- so the next free version is V25.
--
-- WHY a side-store (R5): the clarification sweep runs inside ArtifactOperationService.markAvailable,
-- which sees only the spec artifact — NOT the runner result. So the runner-emitted
-- specArtifact.clarificationAcknowledgements (addressed:true/false per questionId) are persisted HERE
-- at broker ingest, keyed by the new spec artifact's public_id, and read by
-- ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild (addressed:true => markIncorporated, else
-- markSuperseded). This replaces the brittle substring-scan oracle and keeps the redaction/payload
-- contracts clean (no acknowledgements stuffed into artifact payload bytes).
--
-- The (spec_artifact_id, question_id) UNIQUE is the dedup backstop: the broker de-dups by questionId
-- (first-wins) BEFORE insert and pre-flight-probes existence, so a re-harvest of the same result
-- never flushes a conflicting INSERT into the shared broker transaction (3e-1's session-poison trap).

create table spec_clarification_acknowledgements (
    id                  bigserial primary key,
    public_id           text not null,
    spec_artifact_id    text not null,
    question_id         text not null,
    addressed           boolean not null,
    created_at          timestamptz not null default now(),
    archived_at         timestamptz null,
    constraint uq_spec_clarification_acknowledgements_public_id unique (public_id),
    constraint ck_spec_clarification_acknowledgements_public_id_format
        check (public_id ~ '^sca_[A-Za-z0-9_-]{4,64}$'),
    constraint ck_spec_clarification_acknowledgements_question_id_format
        check (question_id ~ '^[A-Za-z0-9._-]{1,128}$'),
    constraint uq_spec_clarification_acknowledgements_artifact_question
        unique (spec_artifact_id, question_id)
);
