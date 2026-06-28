package org.dradgo.application.workflow;

import java.util.List;
import org.dradgo.domain.registry.ActorType;

/**
 * Command to declare that {@code runId} depends on each id in {@code dependsOnRunIds} (story 3f-3,
 * AC9). The dependent run is parked in {@code WaitingForDependencies} when the declaration leaves
 * it with unmet prerequisites. Declaration is idempotent under {@code idempotencyKey}: edge inserts
 * use {@code on conflict do nothing} and the park transition uses a deterministic key.
 */
public record DeclareRunDependenciesCommand(
    String runId,
    List<String> dependsOnRunIds,
    String actorIdentity,
    ActorType actorType,
    String idempotencyKey,
    String correlationId) {}
