-- D8: Add lineage_recovery discriminator column to artifacts table.
-- Marks artifacts that were created as a fresh lineage after a failed/stale predecessor,
-- recovering the lineage rather than continuing a healthy chain.
ALTER TABLE artifacts
    ADD COLUMN lineage_recovery BOOLEAN NOT NULL DEFAULT FALSE;
