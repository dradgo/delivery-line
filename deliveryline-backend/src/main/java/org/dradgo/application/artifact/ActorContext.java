package org.dradgo.application.artifact;

import org.dradgo.domain.registry.ActorType;

public record ActorContext(String actorIdentity, ActorType actorType, String correlationId) {

  public ActorContext {
    if (actorIdentity == null || actorIdentity.isBlank()) {
      throw new IllegalArgumentException("actorIdentity must be non-blank");
    }
    if (actorType == null) {
      throw new IllegalArgumentException("actorType must not be null");
    }
  }

  public static final ActorContext SYSTEM = new ActorContext("system", ActorType.SYSTEM, null);
}
