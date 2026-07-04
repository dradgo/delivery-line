package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RejectionTaxonomy;

/**
 * Story 3.21 — the technical-rejection twin of {@link RejectSpecCommand}. Carries a developer's
 * "reject implementation" decision binding a specific artifact version + context-bundle version +
 * actor identity + reviewer role ({@code developer}) to an {@code implementationPlan} or {@code
 * prOutput} artifact (NEVER a {@code spec} — {@link
 * org.dradgo.application.approval.TechnicalApprovalService} guards that), plus a required {@code
 * taggedFeedback} value from the developer-rejection taxonomy and free-form {@code reasonText}.
 *
 * <p>Fingerprint fields (beyond the shared envelope) are {@code workflowRunId}, {@code artifactId},
 * {@code artifactVersion}, {@code contextVersion}, {@code reviewerRole}, {@code taggedFeedback}.
 * {@code reasonText} is intentionally <strong>excluded</strong> — free-form reviewer wording edits
 * on the same review must replay idempotently (symmetric with {@link
 * RejectSpecCommand#reasonText}). {@code reviewerRole} + {@code taggedFeedback} ARE in the
 * fingerprint: asserting a different role or revising the structured taxonomy on the same review is
 * a different command.
 *
 * <p>The short field names {@code artifactVersion} / {@code contextVersion} ARE the expected
 * versions the reviewer reviewed against (Trap T1) — kept short to mirror {@link RejectSpecCommand}
 * / {@link AcceptImplementationCommand} and the {@link
 * org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory} switch. The verbose {@code
 * expectedArtifactVersion} / {@code expectedContextBundleVersion} names belong to the REST DTO
 * introduced by story 3.24.
 */
public record RejectImplementationCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotNull @Positive Integer contextVersion,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @NotBlank @Size(max = 128) String reviewerRole,
    @NotNull RejectionTaxonomy taggedFeedback,
    @NotBlank @Size(max = 16384) String reasonText)
    implements WorkflowCommand {}
