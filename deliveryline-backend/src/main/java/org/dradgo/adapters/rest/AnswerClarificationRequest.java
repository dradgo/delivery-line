package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/clarifications/&#123;clarificationId&#125;/answer}
 * (story 2.13).
 *
 * <p>Field semantics mirror {@link
 * org.dradgo.application.workflow.commands.SubmitClarificationCommand}: {@code
 * expectedArtifactVersion} pins the spec version the answer is bound to (story 2.11 trap T3 —
 * version mismatch surfaces as {@code CLARIFICATION_ARTIFACT_VERSION_MISMATCH}); {@code answerText}
 * is the reviewer's free-form clarification answer and is intentionally excluded from the
 * idempotency fingerprint so retries with edited text replay as the same operation.
 *
 * <p>Actor identity, actor type, idempotency key, and correlation id are all transported as headers
 * (story 2.13 AC3, AC4): {@code X-Actor-Identity} (optional, falls back to {@code
 * deliveryline.security.local-actor-identity}), {@code Idempotency-Key} (required), and {@code
 * X-Correlation-Id} (resolved by {@code CorrelationIdFilter}).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AnswerClarificationRequest(
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer expectedArtifactVersion,
    @NotBlank @Size(max = 8192) String answerText) {}
