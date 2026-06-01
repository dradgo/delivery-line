package org.dradgo.application.runner;

import org.dradgo.domain.registry.DataClassification;

/**
 * Story 3.6 — typed, content-free handle to a runner execution's durable redacted log store at
 * {@code {DELIVERYLINE_HOME}/runner-logs/{runnerExecutionId}/}.
 *
 * <p>Carries the reference path plus metrics ONLY — never log content, never a secret value (Trap
 * T1). It is the shape the {@code RunnerLogStore} returns, the broker forwards as completion-event
 * metadata (AC6), and {@code WorkflowInspectionService.getRunnerLogReference} surfaces through the
 * CLI (AC7).
 *
 * <p>When produced by the filesystem store ({@code RunnerLogStore.write/find}) only {@link
 * #referencePath()} and {@link #byteSize()} are authoritative from disk; {@link #classification()}
 * and {@link #redactionCount()} are persisted on the {@code runner_executions} row and are the
 * authoritative values read back by inspection. The store therefore defaults those two to {@link
 * DataClassification#LOCAL_ONLY} / {@code 0} (the safe, never-shipped posture) when it cannot know
 * them.
 */
public record RunnerLogReference(
    String referencePath, long byteSize, DataClassification classification, int redactionCount) {}
