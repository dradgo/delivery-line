package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/takeover} (story 3.25).
 * The developer-takeover sibling of {@link AcceptImplementationRequest} / {@link
 * RejectImplementationRequest} — same wire shape (header-derived actor + correlation identity;
 * required {@code Idempotency-Key} header), surfacing the {@code done} story 3.22 rich {@link
 * org.dradgo.application.recovery.DeveloperTakeoverService#takeoverWorkflow} service.
 *
 * <p><strong>Distinct from {@link TakeoverWorkflowRequest}</strong> (R2): that DTO belongs to the
 * pre-existing transition-only {@code POST /takeover-workflow} endpoint and carries body
 * actor/actorType (the pre-2.13 shape). This story's body carries only {@code reasonText} + {@code
 * reviewerRole}; the actor is header-derived (the 2.13 pattern).
 *
 * <p>{@code reasonText} is <strong>required</strong> (free-form, why the takeover is needed): a
 * blank value surfaces as {@code INVALID_COMMAND_PAYLOAD} via the {@code @NotBlank} bean-validation
 * mapping, and the rich service keeps its own defense-in-depth non-blank guard (R3, three layers
 * consistent). It is excluded from the takeover idempotency fingerprint by the command model, so
 * editing wording on retry still replays idempotently.
 *
 * <p>{@code reviewerRole} must be {@code developer} — the controller (story 3.25 Task 3) enforces
 * it at the boundary with the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} code (the shared
 * story 3.23 idiom), then discards the value: the rich service does NOT accept a reviewer role on
 * the command and hard-codes {@code developer} on the {@code recovery_actions} insert (R8).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TakeoverRequest(
    @NotBlank @Size(max = 512) String reasonText, @Size(max = 128) String reviewerRole) {}
