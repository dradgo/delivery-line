package org.dradgo.application.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.junit.jupiter.api.Test;

class IntegrationLinkStateMachineTest {

  private static final String ILK = "ilk_unit12345678";

  @Test
  void linkedAllowsSyncedStaleAndFailed() {
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.LINKED, IntegrationSyncStatus.SYNCED));
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.LINKED, IntegrationSyncStatus.STALE));
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.LINKED, IntegrationSyncStatus.FAILED));
  }

  @Test
  void linkedRejectsDirectSupersededOrNoOp() {
    assertIllegal(
        IntegrationSyncStatus.LINKED, IntegrationSyncStatus.SUPERSEDED, "transition_not_allowed");
    assertIllegal(IntegrationSyncStatus.LINKED, IntegrationSyncStatus.LINKED, "no_op_transition");
  }

  @Test
  void syncedAllowsStaleFailedAndSuperseded() {
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.SYNCED, IntegrationSyncStatus.STALE));
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.SYNCED, IntegrationSyncStatus.FAILED));
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.SYNCED, IntegrationSyncStatus.SUPERSEDED));
  }

  @Test
  void syncedRejectsBackToLinkedAndNoOp() {
    assertIllegal(
        IntegrationSyncStatus.SYNCED, IntegrationSyncStatus.LINKED, "transition_not_allowed");
    assertIllegal(IntegrationSyncStatus.SYNCED, IntegrationSyncStatus.SYNCED, "no_op_transition");
  }

  @Test
  void staleAllowsSyncedFailedAndSuperseded() {
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.STALE, IntegrationSyncStatus.SYNCED));
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.STALE, IntegrationSyncStatus.FAILED));
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.STALE, IntegrationSyncStatus.SUPERSEDED));
  }

  @Test
  void staleRejectsBackToLinkedAndNoOp() {
    assertIllegal(
        IntegrationSyncStatus.STALE, IntegrationSyncStatus.LINKED, "transition_not_allowed");
    assertIllegal(IntegrationSyncStatus.STALE, IntegrationSyncStatus.STALE, "no_op_transition");
  }

  @Test
  void failedAllowsLinkedAndSuperseded() {
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.FAILED, IntegrationSyncStatus.LINKED));
    assertDoesNotThrow(
        () ->
            IntegrationLinkStateMachine.assertCanTransition(
                ILK, IntegrationSyncStatus.FAILED, IntegrationSyncStatus.SUPERSEDED));
  }

  @Test
  void failedRejectsDirectSyncedStaleAndNoOp() {
    // Recovery path is failed → linked → synced/stale; a direct failed→synced jump must be
    // rejected.
    assertIllegal(
        IntegrationSyncStatus.FAILED, IntegrationSyncStatus.SYNCED, "transition_not_allowed");
    assertIllegal(
        IntegrationSyncStatus.FAILED, IntegrationSyncStatus.STALE, "transition_not_allowed");
    assertIllegal(IntegrationSyncStatus.FAILED, IntegrationSyncStatus.FAILED, "no_op_transition");
  }

  @Test
  void supersededIsTerminal() {
    assertTrue(IntegrationLinkStateMachine.isTerminal(IntegrationSyncStatus.SUPERSEDED));
    assertFalse(IntegrationLinkStateMachine.isTerminal(IntegrationSyncStatus.LINKED));
    assertFalse(IntegrationLinkStateMachine.isTerminal(IntegrationSyncStatus.SYNCED));
    assertFalse(IntegrationLinkStateMachine.isTerminal(IntegrationSyncStatus.STALE));
    assertFalse(IntegrationLinkStateMachine.isTerminal(IntegrationSyncStatus.FAILED));

    assertEquals(
        Set.<IntegrationSyncStatus>of(),
        IntegrationLinkStateMachine.allowedTargets(IntegrationSyncStatus.SUPERSEDED));
    for (IntegrationSyncStatus to : EnumSet.allOf(IntegrationSyncStatus.class)) {
      assertIllegal(
          IntegrationSyncStatus.SUPERSEDED,
          to,
          to == IntegrationSyncStatus.SUPERSEDED ? "no_op_transition" : "transition_not_allowed");
    }
  }

  @Test
  void rejectionExceptionCarriesIntegrationLinkContext() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                IntegrationLinkStateMachine.assertCanTransition(
                    ILK, IntegrationSyncStatus.LINKED, IntegrationSyncStatus.SUPERSEDED));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals(ILK, error.details().get("integrationLinkId"));
    assertEquals(IntegrationSyncStatus.LINKED.value(), error.details().get("fromStatus"));
    assertEquals(IntegrationSyncStatus.SUPERSEDED.value(), error.details().get("toStatus"));
    assertEquals("transition_not_allowed", error.details().get("reason"));
  }

  private static void assertIllegal(
      IntegrationSyncStatus from, IntegrationSyncStatus to, String expectedReason) {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> IntegrationLinkStateMachine.assertCanTransition(ILK, from, to));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals(expectedReason, error.details().get("reason"));
    assertEquals(from.value(), error.details().get("fromStatus"));
    assertEquals(to.value(), error.details().get("toStatus"));
  }
}
