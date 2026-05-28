-- Story 3.2 AC3 sub-bullet (d) idempotency-gate for `runner.heartbeatStale` event emission.
-- The column is set when scanForStaleExecutions emits a RUNNER_HEARTBEAT_STALE event for the row,
-- and cleared on any subsequent activity observation (touchActivity / markRunning) AND on terminal
-- transitions (markCompleted / markFailed / markTimedOut / markOrphaned) so a row that later cycles
-- back into a stale window can re-emit cleanly. See OQ-1 + Trap T4.
ALTER TABLE runner_executions
    ADD COLUMN heartbeat_stale_emitted_at TIMESTAMPTZ NULL;
