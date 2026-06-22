package org.dradgo.application.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.ReviewOutcome;
import org.junit.jupiter.api.Test;

/**
 * Story 3d-2 (AC4, DD-6, code-review hardening) — {@link StepReviewSnapshot#selfReview()} must be
 * resilient to image-tag drift: a {@code kind:imageTag} identity and a bare {@code kind} fallback
 * for the SAME model still count as a self-review, and an unknown (null/blank) identity never
 * fabricates a same-model claim.
 */
class StepReviewSnapshotSelfReviewTest {

  @Test
  void identicalIdentitiesAreSelfReview() {
    assertThat(snapshot("claude:latest", "claude:latest").selfReview()).isTrue();
  }

  @Test
  void tagDriftSameKindIsStillSelfReview() {
    // One side fell back to the bare kind (unresolvable tag) — naive equality would miss this.
    assertThat(snapshot("claude:latest", "claude").selfReview()).isTrue();
    assertThat(snapshot("claude", "claude:2026-06-22").selfReview()).isTrue();
  }

  @Test
  void caseAndWhitespaceAreNormalized() {
    assertThat(snapshot(" Claude:latest ", "claude").selfReview()).isTrue();
  }

  @Test
  void differentKindsAreNotSelfReview() {
    assertThat(snapshot("claude:latest", "codex:latest").selfReview()).isFalse();
  }

  @Test
  void unknownIdentityIsNotSelfReview() {
    assertThat(snapshot(null, "claude:latest").selfReview()).isFalse();
    assertThat(snapshot("claude:latest", null).selfReview()).isFalse();
    assertThat(snapshot("  ", "claude").selfReview()).isFalse();
    assertThat(snapshot(null, null).selfReview()).isFalse();
  }

  private static StepReviewSnapshot snapshot(String reviewerIdentity, String producerIdentity) {
    return new StepReviewSnapshot(
        "rev_self0001",
        "run_self0001",
        "rex_self0001",
        "art_self0001",
        1,
        ReviewOutcome.PASS,
        null,
        reviewerIdentity,
        producerIdentity,
        OffsetDateTime.parse("2026-06-22T10:00:00Z"));
  }
}
