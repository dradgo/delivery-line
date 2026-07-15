package org.dradgo.application.recovery;

import java.util.List;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Result returned by {@link RecoveryService#previewRerunFromStep(String, String)} (story 4.22 AC5).
 *
 * <p>The <strong>non-mutating</strong> preview of a rerun-from-step: what state the run would land
 * in and which artifacts / approvals a fresh rerun to {@code resultingState} would supersede /
 * invalidate. It reuses the exact pure reads from the write path ({@link
 * RecoveryService#resolveSupersededArtifactIds} + the read half of {@code
 * ApprovalService.invalidateCurrentApproval}) so the Decision Bar's "Show what will be superseded"
 * section (AC5) matches what the mutating {@code rerunFromStep} would actually do — WITHOUT writing
 * a {@code recovery_actions} row, appending an event, or invalidating an approval.
 *
 * @param resultingState the safe step boundary the run would be rerun into (Investigating or
 *     Executing)
 * @param supersededArtifactIds the active-leaf artifact ids at/beyond the target step (never null —
 *     empty when the run has no artifacts at/beyond the step)
 * @param invalidatedApprovalIds the approval id(s) a rerun would invalidate (never null — empty
 *     when the run has no current approval at the invalidated stage; at most one)
 */
public record RerunFromStepPreviewResult(
    WorkflowState resultingState,
    List<String> supersededArtifactIds,
    List<String> invalidatedApprovalIds) {}
