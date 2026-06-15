package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3.18 — request body for {@code POST /api/v1/workflows/batch}. Body-carried actor convention
 * matching the {@code submit-workflow} sibling (OQ-3) — the batch-level {@code Idempotency-Key}
 * travels as a header. {@code @JsonIgnoreProperties(ignoreUnknown = false)} rejects unexpected
 * fields so a typo surfaces as a 400 rather than being silently dropped.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record BatchSubmissionRequest(
    @NotEmpty @Size(max = 1000) List<@NotBlank @Size(max = 128) String> linearTicketReferences,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @Size(max = 128) String correlationId) {}
