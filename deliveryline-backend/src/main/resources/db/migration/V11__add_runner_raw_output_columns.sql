-- Story 3.6 AC3 — link a runner execution to its durable, redacted log store (NFR24).
-- Four nullable columns carry the RunnerLogCaptureService capture reference + metrics. They are
-- populated by a metadata-only update (recordRawOutput) that never changes `status` and tolerates
-- an already-terminal row (Trap T10), so they stay NULL for executions captured before this story
-- and for the mock/no-capture paths.
--
-- raw_output_reference        : absolute path to {DELIVERYLINE_HOME}/runner-logs/{rex}/ (the redacted store).
-- raw_output_classification   : DataClassification wire value of the captured logs (AC4).
-- raw_output_byte_size        : total redacted byte size across both streams.
-- redaction_count             : number of [REDACTED_*] placeholders across both redacted streams (OQ-1).
--
-- The CHECK on raw_output_classification is the first classification CHECK in the schema
-- (artifacts.classification is code-enforced only — OQ-7). It allows NULL and otherwise restricts
-- to the four DataClassification wire values; capture only ever writes local-only / shareable-redacted.
ALTER TABLE runner_executions
    ADD COLUMN raw_output_reference      TEXT    NULL,
    ADD COLUMN raw_output_classification TEXT    NULL,
    ADD COLUMN raw_output_byte_size      BIGINT  NULL,
    ADD COLUMN redaction_count           INTEGER NULL;

ALTER TABLE runner_executions
    ADD CONSTRAINT ck_runner_executions_raw_output_classification
        CHECK (raw_output_classification IS NULL
            OR raw_output_classification IN (
                'local-only', 'shareable-redacted', 'shareable-full', 'derived-public-safe'));

ALTER TABLE runner_executions
    ADD CONSTRAINT ck_runner_executions_raw_output_metrics_non_negative
        CHECK ((raw_output_byte_size IS NULL OR raw_output_byte_size >= 0)
            AND (redaction_count IS NULL OR redaction_count >= 0));

ALTER TABLE runner_executions
    ADD CONSTRAINT ck_runner_executions_raw_output_all_or_none
        CHECK ((raw_output_reference IS NULL
                AND raw_output_classification IS NULL
                AND raw_output_byte_size IS NULL
                AND redaction_count IS NULL)
            OR (raw_output_reference IS NOT NULL
                AND raw_output_classification IS NOT NULL
                AND raw_output_byte_size IS NOT NULL
                AND redaction_count IS NOT NULL));
