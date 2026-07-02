-- V31: Per-execution token accounting columns on runner_executions (story 3g-3, FR74).
--
-- Each runner execution can record the agent's input/output/total token counts when the agent
-- reports them. This is genuinely net-new data: RunnerExecutionSnapshot carried no token fields and
-- the runner-result contract carried no `usage` block before 3g-3. Distinct from the 3d-7
-- provider_usage_snapshots table (rolling subscription-quota window status) — these are per-execution
-- counts persisted as columns on the execution row itself, mirroring the 3.6 raw_output_* metadata
-- columns.
--
-- All three columns are NULLABLE with NO default and NO CHECK constraint. Capture is best-effort:
--   * pre-3g rows and any no-usage / command-only / no-LLM execution stay NULL (3g-4 renders NULL as
--     "not reported", never 0);
--   * a CHECK would surface a bad count as a swallowed DataIntegrityViolation inside the broker's
--     best-effort capture — validation stays in the JS sanitizer + the Java capture path instead.
-- Each count is persisted independently as reported (totalTokens is NOT synthesized from input+output).

alter table runner_executions
    add column input_tokens integer,
    add column output_tokens integer,
    add column total_tokens integer;
