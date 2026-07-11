package org.dradgo.application.workflow.spi;

/**
 * Story 3h-5 (AC2) — a single row returned by the CI-investigation sweep's keyset scan of runs
 * still awaiting a CI verdict. Carries only what the sweep's phase-2 (lock-free) needs: the run's
 * public id, the pushed commit the checks are read for, and the raw monotonic {@code
 * workflow_runs.id} the sweep paginates by ({@code id > afterSeq}) so it advances past a batch
 * instead of re-selecting the same oldest window every tick (bare-LIMIT starvation).
 */
public record CiPollRow(String workflowRunPublicId, String ciHeadSha, long seq) {}
