package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsView;
import org.dradgo.domain.registry.AllowedAction;

/**
 * Wire shape for {@code GET /api/v1/workflows/{workflowRunId}/allowed-actions} (story 2.14, AC6).
 * Direct resource shape — no {@code {data: …}} envelope.
 *
 * <p>This DTO is the ONLY class in {@code org.dradgo.adapters.rest} permitted to import {@link
 * AllowedAction} — pinned by the {@code
 * allowed_action_derivation_lives_only_in_workflow_inspection_service} ArchUnit rule. Wire mapping
 * renders each enum constant via its {@link AllowedAction#value()} stable wire string (e.g. {@code
 * "approve_spec"}).
 */
@Schema(
    name = "AllowedActions",
    description = "Backend-derived list of allowed actions for the current state + actor role.")
public record AllowedActionsResponse(
    @Schema(
            description =
                "Typed action wire values. Primary action first (e.g. approve_spec before "
                    + "reject_spec), then passive views. UI must gracefully ignore unknown "
                    + "values for forward compatibility (UX-DR12, UX-DR6).",
            example = "[\"approve_spec\", \"reject_spec\", \"answer_clarification\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> actions,
    @Schema(
            description =
                "Version stamp the UI echoes back as expectedAllowedActionsVersionStamp on the "
                    + "next mutation so a stale UI surfaces APPROVAL_VERSION_MISMATCH.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        AllowedActionsVersionStampResponse versionStamp) {

  public static AllowedActionsResponse from(AllowedActionsView view) {
    List<String> wireActions = view.actions().stream().map(AllowedAction::value).toList();
    return new AllowedActionsResponse(
        wireActions, AllowedActionsVersionStampResponse.from(view.versionStamp()));
  }
}
