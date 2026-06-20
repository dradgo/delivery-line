package org.dradgo.adapters.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

public record SubmitWorkflowRequest(
    @NotBlank @Size(max = 128) String linearTicketReference,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @Size(max = 128) String correlationId,
    // Story 3c-7 (AC1) — optional explicit project reference (project slug or `prj_` public id).
    // Last + nullable so the OpenAPI snapshot diff is purely additive; absent => the run binds to
    // the reserved `default` project (3c-6 fallback).
    @Size(max = 128) String projectReference) {}
