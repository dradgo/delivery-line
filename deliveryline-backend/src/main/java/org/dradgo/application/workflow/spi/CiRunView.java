package org.dradgo.application.workflow.spi;

/**
 * Story 3h-5 (AC3) — a raw read of a run's CI-investigation columns for the detail read model. Read
 * via targeted SQL (never through {@code WorkflowRunEntity}, which does not map the {@code ci_*}
 * columns — Decision 6). {@code ciStatus} / {@code ciHeadSha} are nullable (a never-pushed / never-
 * polled run); {@code ciFixLoopCount} defaults to 0.
 */
public record CiRunView(String ciStatus, String ciHeadSha, int ciFixLoopCount) {}
