package org.dradgo.application.runner;

import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;

/**
 * Translates the application-wide {@link ActorContext} into the {@link TransitionActor} value
 * object that {@code WorkflowTransitionService} requires.
 *
 * <p>Story 1.13 forbids introducing a third {@code Actor*} type. This is the single boundary
 * adapter so both layers can stay decoupled at their preferred shape.
 */
public final class RunnerActorTranslator {

  private RunnerActorTranslator() {}

  public static TransitionActor toTransitionActor(ActorContext actor) {
    if (actor == null) {
      throw new IllegalArgumentException("actor must not be null");
    }
    return new TransitionActor(actor.actorIdentity(), actor.actorType());
  }
}
