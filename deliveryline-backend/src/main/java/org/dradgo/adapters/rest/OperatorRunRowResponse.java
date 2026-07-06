package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunRow;

/**
 * Story 4.2 (AC2) — one operator fleet row in {@code GET /api/v1/operator/runs}. Wire shape of the
 * application view {@link OperatorRunRow}: the {@link org.dradgo.domain.registry.WorkflowState}
 * enum is rendered to its wire string, and the server-derived {@code operatorSignifier} is carried
 * verbatim so the UI renders the state badge FROM it (never re-derives from {@code currentState} —
 * story 4.1 Reconciliation 9).
 *
 * <p>{@code failureCategory} and {@code runnerKind} carry {@code allowableValues} so {@code
 * openapi-typescript} emits typed unions the operator filter sidebar consumes (story 4.2
 * Reconciliation 2/7) instead of hardcoded drift.
 */
@Schema(name = "OperatorRunRow", description = "One operator fleet-view run row.")
public record OperatorRunRowResponse(
    @Schema(description = "Run public id.", example = "run_abc123") String runId,
    @Schema(description = "Current workflow state wire string.", example = "Failed")
        String currentState,
    @Schema(
            description = "Latest Failed transition's failure category, when applicable.",
            nullable = true,
            allowableValues = {
              "runner_timeout",
              "runner_crash",
              "runner_contract_violation",
              "runner_non_zero_exit",
              "runner_late_result",
              "runner_duplicate_result",
              "runner_malformed_output",
              "runner_secret_leak",
              "runner_build_failed",
              "orphan"
            })
        String failureCategory,
    @Schema(
            description =
                "The run's project-level runner-kind override (projects.runner_kind), or null when"
                    + " the project uses the global default or the run has no project.",
            nullable = true,
            allowableValues = {"codex", "claude", "manual"})
        String runnerKind,
    @Schema(description = "Latest event timestamp (ISO-8601 UTC).", nullable = true)
        OffsetDateTime lastTransitionAt,
    @Schema(description = "Latest event actor identity.", nullable = true) String actorIdentity,
    @Schema(description = "Active linear ticket reference.", nullable = true, example = "DEL-1234")
        String linkedTicketRef,
    @Schema(description = "Active github PR reference.", nullable = true, example = "octo/repo#7")
        String linkedPrRef,
    @Schema(description = "Escalation marker set on the run.", example = "false")
        boolean escalationMarker,
    @Schema(description = "Earliest event timestamp for the run (ISO-8601 UTC).", nullable = true)
        OffsetDateTime oldestEventAt,
    @Schema(
            description =
                "Server-derived UPPERCASE display signifier (ORPHANED/FAILED/TAKENOVER/STALLED/"
                    + "OVERRIDDEN, else the uppercased state). The UI renders the badge FROM this.",
            example = "STALLED")
        String operatorSignifier) {

  public static OperatorRunRowResponse from(OperatorRunRow row) {
    return new OperatorRunRowResponse(
        row.runId(),
        row.currentState().value(),
        row.failureCategory(),
        row.runnerKind(),
        toUtc(row.lastTransitionAt()),
        row.actorIdentity(),
        row.linkedTicketRef(),
        row.linkedPrRef(),
        row.escalationMarker(),
        toUtc(row.oldestEventAt()),
        row.operatorSignifier());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
