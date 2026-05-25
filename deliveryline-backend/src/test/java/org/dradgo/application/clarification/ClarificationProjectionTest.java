package org.dradgo.application.clarification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.Test;

/**
 * Story 2.11 trap T9 pin: the {@link Clarification} projection mirrors the V8 DB CHECK invariants
 * {@code ck_clarifications_status} and {@code ck_clarifications_answered_fields_paired}. Any caller
 * that bypasses the writer and constructs a projection directly with mismatched fields must fail
 * fast — the persistence-layer CHECK is the defense-in-depth backstop, not the source of truth.
 */
class ClarificationProjectionTest {

  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 5, 25, 10, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void openClarificationWithAllAnswerFieldsNullIsValid() {
    Clarification c =
        new Clarification(
            "clr_abc12345",
            "run_abc12345",
            "art_abc12345",
            1,
            "Q1",
            "What is the boundary?",
            Clarification.STATUS_OPEN,
            null,
            null,
            null,
            null,
            NOW);
    assertTrue(c.isOpen());
    assertFalse(c.isAnswered());
    assertFalse(c.isTerminal());
  }

  @Test
  void answeredClarificationWithAllAnswerFieldsPopulatedIsValid() {
    Clarification c =
        new Clarification(
            "clr_abc12345",
            "run_abc12345",
            "art_abc12345",
            1,
            "Q1",
            "What is the boundary?",
            Clarification.STATUS_ANSWERED,
            "The boundary is X",
            "alex",
            ActorType.HUMAN,
            NOW,
            NOW.minusMinutes(5));
    assertTrue(c.isAnswered());
    assertFalse(c.isOpen());
    assertFalse(c.isTerminal());
  }

  @Test
  void openClarificationWithPopulatedAnswerFieldThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Clarification(
                "clr_abc12345",
                "run_abc12345",
                "art_abc12345",
                1,
                "Q1",
                "What?",
                Clarification.STATUS_OPEN,
                "leaked answer",
                null,
                null,
                null,
                NOW));
  }

  @Test
  void answeredClarificationWithAllAnswerFieldsNullThrowsBiconditionalViolation() {
    // Mirrors ck_clarifications_answered_fields_paired:
    // (status = 'open') ⇔ (answer_text IS NULL AND answered_by_actor IS NULL AND answered_at IS
    // NULL).
    // status=answered + all three nulls violates the right side of the biconditional.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Clarification(
                "clr_abc12345",
                "run_abc12345",
                "art_abc12345",
                1,
                "Q1",
                "What?",
                Clarification.STATUS_ANSWERED,
                null,
                null,
                null,
                null,
                NOW));
  }

  @Test
  void unknownStatusThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Clarification(
                "clr_abc12345",
                "run_abc12345",
                "art_abc12345",
                1,
                "Q1",
                "What?",
                "bogus_status",
                null,
                null,
                null,
                null,
                NOW));
  }

  @Test
  void terminalStatesAreReportedAsTerminal() {
    for (String terminal :
        new String[] {
          Clarification.STATUS_INCORPORATED,
          Clarification.STATUS_SUPERSEDED,
          Clarification.STATUS_REJECTED_INVALID
        }) {
      Clarification c =
          new Clarification(
              "clr_abc12345",
              "run_abc12345",
              "art_abc12345",
              1,
              "Q1",
              "What?",
              terminal,
              "answer",
              "alex",
              ActorType.HUMAN,
              NOW,
              NOW.minusMinutes(5));
      assertTrue(c.isTerminal(), () -> "expected terminal status: " + terminal);
    }
  }

  @Test
  void nonPositiveArtifactVersionThrows() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Clarification(
                    "clr_abc12345",
                    "run_abc12345",
                    "art_abc12345",
                    0,
                    "Q1",
                    "What?",
                    Clarification.STATUS_OPEN,
                    null,
                    null,
                    null,
                    null,
                    NOW));
    assertEquals("artifactVersion must be positive: 0", error.getMessage());
  }
}
