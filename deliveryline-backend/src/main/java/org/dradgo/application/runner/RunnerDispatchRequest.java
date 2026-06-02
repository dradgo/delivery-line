package org.dradgo.application.runner;

import java.nio.file.Path;
import java.util.Objects;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;

/**
 * Application-owned dispatch envelope handed to {@link
 * org.dradgo.application.runner.spi.RunnerAdapter#dispatch(RunnerDispatchRequest)}.
 *
 * <p>Story 3.1: {@code runnerKind} is added as a new required field. The broker resolves the kind
 * from {@code RunnerProperties.docker().defaultKind()} and threads it through; the adapter never
 * reads kind information from the (untrusted) context bundle (trap T3).
 *
 * <p>Story 3.9 (Decision D0): {@code repositoryRef}, {@code linearTicketRef}, and {@code
 * linearTicketSummary} are added as <strong>nullable</strong> fields carrying the optional
 * repository-workspace seam. When {@code repositoryRef} is present, {@code DockerRunnerAdapter}
 * calls {@code RepositoryWorkspaceService.prepareWorkspace(...)} and mounts {@code
 * /workspace/repo}; when null (every mock + no-repo dispatch today) the dispatch is byte-for-byte
 * unchanged. The back-compat 7-arg constructor keeps existing construction sites identical.
 */
public record RunnerDispatchRequest(
    String runnerExecutionId,
    String workflowRunId,
    RunnerStage stage,
    RunnerKind runnerKind,
    Path contextBundlePath,
    ExecutionConstraints executionConstraints,
    DataClassification classification,
    String repositoryRef,
    String linearTicketRef,
    String linearTicketSummary) {

  public RunnerDispatchRequest {
    if (runnerExecutionId == null || runnerExecutionId.isBlank()) {
      throw new IllegalArgumentException("runnerExecutionId must not be blank");
    }
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new IllegalArgumentException("workflowRunId must not be blank");
    }
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(runnerKind, "runnerKind");
    Objects.requireNonNull(contextBundlePath, "contextBundlePath");
    Objects.requireNonNull(executionConstraints, "executionConstraints");
    Objects.requireNonNull(classification, "classification");
    // repositoryRef + ticket fields are intentionally nullable (the no-repo seam, Decision D0).
  }

  /**
   * Back-compat constructor for repository-bearing callers that do not yet have a ticket summary.
   */
  public RunnerDispatchRequest(
      String runnerExecutionId,
      String workflowRunId,
      RunnerStage stage,
      RunnerKind runnerKind,
      Path contextBundlePath,
      ExecutionConstraints executionConstraints,
      DataClassification classification,
      String repositoryRef,
      String linearTicketRef) {
    this(
        runnerExecutionId,
        workflowRunId,
        stage,
        runnerKind,
        contextBundlePath,
        executionConstraints,
        classification,
        repositoryRef,
        linearTicketRef,
        null);
  }

  /**
   * Back-compat constructor for all existing (mock + no-repo) dispatches — leaves the story-3.9
   * repository seam null so current call sites and tests construct identically.
   */
  public RunnerDispatchRequest(
      String runnerExecutionId,
      String workflowRunId,
      RunnerStage stage,
      RunnerKind runnerKind,
      Path contextBundlePath,
      ExecutionConstraints executionConstraints,
      DataClassification classification) {
    this(
        runnerExecutionId,
        workflowRunId,
        stage,
        runnerKind,
        contextBundlePath,
        executionConstraints,
        classification,
        null,
        null,
        null);
  }
}
