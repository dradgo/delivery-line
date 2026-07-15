package org.dradgo.application.compare;

/**
 * Story 4.19 (AC4, Reconciliation 6) — an implementation-plan step-level change. Emitted by {@link
 * PlanStepDiffer}, which LCS-aligns the two {@code steps: [string, …]} arrays.
 *
 * <p><strong>There is no persisted {@code stepId}</strong> — persisted plan steps are plain strings
 * (story 3b-6 {@code parseSteps} returns {@code List<String>}). {@code stepId} here is POSITIONAL:
 * it is bound to the step's 0-based array index in the revision that carries it (revision B's index
 * for {@code added}/{@code reordered}/{@code modified}; revision A's index for {@code removed}).
 *
 * @param stepId the positional step id (0-based index, as a string) in the revision that carries
 *     the step. Never {@code null}.
 * @param changeKind one of {@link ChangeKind#ADDED} / {@link ChangeKind#REMOVED} / {@link
 *     ChangeKind#REORDERED} / {@link ChangeKind#MODIFIED}. Never {@code null}.
 * @param priorStepText the step text in revision A; {@code null} for an {@code added} step.
 *     Redacted before serialization (AC6).
 * @param currentStepText the step text in revision B; {@code null} for a {@code removed} step.
 *     Redacted before serialization (AC6).
 * @param priorStepOrder the 0-based index in revision A; {@code null} for an {@code added} step.
 * @param currentStepOrder the 0-based index in revision B; {@code null} for a {@code removed} step.
 */
public record PlanStepChangeBlock(
    String stepId,
    String changeKind,
    String priorStepText,
    String currentStepText,
    Integer priorStepOrder,
    Integer currentStepOrder)
    implements ChangeBlock {}
