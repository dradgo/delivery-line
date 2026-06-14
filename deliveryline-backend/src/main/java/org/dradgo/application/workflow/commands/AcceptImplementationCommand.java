package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3.20 — the technical-approval twin of {@link ApproveSpecCommand}. Carries a developer's
 * "accept implementation" decision binding a specific artifact version + context-bundle version +
 * actor identity + reviewer role ({@code developer}) to an {@code implementationPlan} or {@code
 * prOutput} artifact (NEVER a {@code spec} — {@link
 * org.dradgo.application.approval.TechnicalApprovalService} guards that).
 *
 * <p>Fingerprint fields (beyond the shared envelope) are {@code workflowRunId}, {@code artifactId},
 * {@code artifactVersion}, {@code contextVersion}, {@code reviewerRole}. {@code reason} is
 * intentionally <strong>excluded</strong> — free-form reviewer wording edits on the same review
 * must replay idempotently (mirrors {@link ApproveSpecCommand#reason}). {@code reviewerRole} IS in
 * the fingerprint: asserting a different role is a semantic shift.
 *
 * <p>The short field names {@code artifactVersion} / {@code contextVersion} ARE the expected
 * versions the reviewer reviewed against (Trap T1) — kept short to mirror {@link
 * ApproveSpecCommand} / {@link RejectSpecCommand} and the {@link
 * org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory} switch. The verbose {@code
 * expectedArtifactVersion} / {@code expectedContextBundleVersion} names belong to the REST DTO
 * introduced by story 3.23.
 */
public record AcceptImplementationCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotNull @Positive Integer contextVersion,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @NotBlank @Size(max = 128) String reviewerRole,
    @Size(max = 1024) String reason)
    implements WorkflowCommand {}
