package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.workflow.BlockedDependencyView;
import org.dradgo.application.workflow.RunDependencyGraphView;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.LinkedTicketView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;

/**
 * Single-run detail for {@code GET /api/v1/workflows/{workflowRunId}} (story 6.9, AC1/AC2). Direct
 * resource shape (no envelope), camelCase, ISO-8601 UTC timestamps, public-prefixed ids. Faithful
 * projection of {@link WorkflowStatusView} so the UI can answer "what happened / what's current /
 * who owns it / next safe action" from backend-reported state (architecture quality gate).
 */
@Schema(name = "WorkflowDetail", description = "Current status detail of a workflow run.")
public record WorkflowDetailResponse(
    @Schema(example = "run_abc123") String workflowRunId,
    @Schema(example = "WaitingForSpecApproval") String currentState,
    String currentActorIdentity,
    String currentActorType,
    String lastEventType,
    OffsetDateTime lastEventAt,
    List<LatestArtifact> latestArtifacts,
    LinkedTicket linkedTicket,
    String failedStage,
    String lastSuccessfulStage,
    OffsetDateTime failureTimestamp,
    String failureCategory,
    OffsetDateTime lastActivityTimestamp,
    @Schema(description = "Next safe operator action for the current state.", example = "view_only")
        String nextSafeAction,
    @Schema(
            description =
                "How many spec rejections this run has accumulated. Increments on every successful"
                    + " rejectSpec call (story 2.10).",
            example = "0")
        int specRejectionLoopCount,
    @Schema(
            description =
                "True once specRejectionLoopCount has crossed the configured escalation threshold."
                    + " Informational — does NOT terminate the workflow (FR13).",
            example = "false")
        boolean escalationMarker,
    @Schema(
            description = "Parent workflow run public id, when this run is a split child.",
            nullable = true)
        String parentRunId,
    @Schema(description = "Child workflow run public ids for this parent run.")
        List<String> childRunIds,
    @Schema(
            description = "Project public id for the run.",
            nullable = true,
            example = "prj_default")
        String projectId,
    @Schema(
            description = "Project display name for the run.",
            nullable = true,
            example = "Default project")
        String projectName,
    @Schema(description = "Project slug for the run.", nullable = true, example = "default")
        String projectSlug,
    @Schema(
            description =
                "This run's position in the run-dependency DAG (story 3f-3): prerequisites,"
                    + " dependents, the unfinished blocking subset, and a blocked boolean.")
        RunDependencies dependencies,
    @Schema(
            description =
                "Story 3f-7 (AC6): for a Split parent, the decomposition progress (\"decomposed — N"
                    + " of M descendants complete\"); null for any non-Split run. Flips to a"
                    + " Completed currentState when the parent rolls up.",
            nullable = true,
            example = "decomposed — 1 of 2 descendants complete")
        String decompositionStatus,
    @Schema(
            description =
                "Run-level token consumption: sum of per-step totalTokens where reported (story"
                    + " 3g-4, FR74). Null when no step reported tokens.",
            nullable = true,
            example = "12345")
        Integer totalTokens) {

  public static WorkflowDetailResponse from(WorkflowStatusView view) {
    return new WorkflowDetailResponse(
        view.workflowRunId(),
        view.currentState() == null ? null : view.currentState().value(),
        view.currentActorIdentity(),
        view.currentActorType(),
        view.lastEventType(),
        toUtc(view.lastEventAt()),
        view.latestArtifacts() == null
            ? List.of()
            : view.latestArtifacts().stream().map(LatestArtifact::from).toList(),
        view.linkedTicket() == null ? null : LinkedTicket.from(view.linkedTicket()),
        view.failedStage(),
        view.lastSuccessfulStage(),
        toUtc(view.failureTimestamp()),
        view.failureCategory(),
        toUtc(view.lastActivityTimestamp()),
        view.nextSafeAction(),
        view.specRejectionLoopCount(),
        view.escalationMarker(),
        view.parentRunId(),
        view.childRunIds() == null ? List.of() : view.childRunIds(),
        view.projectId(),
        view.projectName(),
        view.projectSlug(),
        RunDependencies.from(view.dependencyGraph()),
        view.decompositionStatus(),
        view.totalTokens());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** Latest artifact of a given type for the run. */
  @Schema(name = "LatestArtifact")
  public record LatestArtifact(
      @Schema(example = "spec") String artifactType,
      int version,
      String status,
      @Schema(
              description =
                  "Public id of this latest artifact. Resolves the artifact-read endpoint "
                      + "(GET .../artifacts/{artifactId}) and the spec approval/decision bar "
                      + "(story 2.19 resolveSpecArtifactId).",
              example = "art_abc123")
          String artifactId) {

    static LatestArtifact from(LatestArtifactView view) {
      return new LatestArtifact(
          view.artifactType(), view.version(), view.status(), view.artifactId());
    }
  }

  /** Linked external ticket for the run. */
  @Schema(name = "LinkedTicket")
  public record LinkedTicket(
      @Schema(example = "linear") String integrationType,
      @Schema(example = "DEL-1234") String externalRef,
      String syncStatus,
      @Schema(
              description =
                  "Originating ticket title snapshotted at link time (story 3g-1, FR73). Null for a"
                      + " pre-3g link.",
              nullable = true,
              example = "Fix flaky checkout test")
          String title,
      @Schema(
              description =
                  "Link-back URL to the source ticket, snapshotted at link time (story 3g-1, FR73)."
                      + " Null when the connector cannot build one or for a pre-3g link.",
              nullable = true,
              example = "https://linear.app/issue/DEL-1234")
          String url) {

    static LinkedTicket from(LinkedTicketView view) {
      return new LinkedTicket(
          view.integrationType(), view.externalRef(), view.syncStatus(), view.title(), view.url());
    }
  }

  /** Story 3f-3 (AC8) — this run's run-dependency DAG neighborhood. */
  @Schema(
      name = "RunDependencies",
      description =
          "A run's prerequisites, dependents, and blocked-on subset in the dependency DAG.")
  public record RunDependencies(
      @Schema(description = "Runs this run depends on (its prerequisites).")
          List<RunDependencyRef> prerequisites,
      @Schema(description = "Runs that depend on this run.") List<RunDependencyRef> dependents,
      @Schema(
              description =
                  "Prerequisites that are not yet Completed — the runs still blocking this run.")
          List<RunDependencyRef> blockedOn,
      @Schema(
              description = "True when at least one prerequisite has not yet completed.",
              example = "false")
          boolean blockedByDependencies) {

    static RunDependencies from(RunDependencyGraphView view) {
      if (view == null) {
        return new RunDependencies(List.of(), List.of(), List.of(), false);
      }
      return new RunDependencies(
          refs(view.prerequisites()),
          refs(view.dependents()),
          refs(view.blockedOn()),
          view.blockedByDependencies());
    }

    private static List<RunDependencyRef> refs(List<BlockedDependencyView> views) {
      return views == null ? List.of() : views.stream().map(RunDependencyRef::from).toList();
    }
  }

  /** A run referenced by a dependency edge, with its current state. */
  @Schema(name = "RunDependencyRef")
  public record RunDependencyRef(
      @Schema(example = "run_abc123") String runId, @Schema(example = "Executing") String state) {

    static RunDependencyRef from(BlockedDependencyView view) {
      return new RunDependencyRef(view.runId(), view.state() == null ? null : view.state().value());
    }
  }
}
