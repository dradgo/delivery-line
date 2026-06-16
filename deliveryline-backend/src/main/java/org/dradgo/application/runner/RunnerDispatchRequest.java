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
 *
 * <p>Story 3b.1 (AC1): {@code subStage} is added as a <strong>nullable</strong> {@link
 * ExecutionSubStage} carrying the resolved execution sub-stage so {@code DockerRunnerAdapter} can
 * emit the precise {@code DELIVERYLINE_RUNNER_STAGE} token ({@code implementation-plan} / {@code
 * pr-output}) that {@code entrypoint.sh map_stage} accepts. {@code null} for every INVESTIGATION
 * dispatch and for the legacy/recovery generic path (adapter falls back to {@code "execution"} →
 * {@code prOutput}, byte-identical to pre-3b behavior). It mirrors the nullable {@code
 * repositoryRef} seam: a plain record component, not a ctor dependency, so DI wiring is unaffected.
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
    String linearTicketSummary,
    ExecutionSubStage subStage) {

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
    // subStage is intentionally nullable: null for INVESTIGATION and the legacy/recovery generic
    // path — left permissive (allowed for any stage) to match the recovery path (Story 3b.1 AC1).
  }

  /**
   * Back-compat constructor (pre-3b canonical shape) — repository seam + ticket summary, no
   * sub-stage. Defaults {@code subStage = null} so every existing 10-arg construction site keeps
   * the legacy {@code "execution"} token behavior.
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
      String linearTicketRef,
      String linearTicketSummary) {
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
        linearTicketSummary,
        null);
  }

  /**
   * Story 3b.1 (AC1) — constructor for the broker dispatch sites: repository seam + ticket ref +
   * the resolved {@link ExecutionSubStage}. Distinct from the pre-3b 10-arg back-compat constructor
   * by the trailing {@code ExecutionSubStage} parameter type (no overload ambiguity). Leaves {@code
   * linearTicketSummary = null}, matching both broker call sites today.
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
      String linearTicketRef,
      ExecutionSubStage subStage) {
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
        null,
        subStage);
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
        null,
        null);
  }

  /**
   * Back-compat constructor for all existing (mock + no-repo) dispatches — leaves the story-3.9
   * repository seam and the story-3b.1 sub-stage null so current call sites and tests construct
   * identically.
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
        null,
        null);
  }
}
