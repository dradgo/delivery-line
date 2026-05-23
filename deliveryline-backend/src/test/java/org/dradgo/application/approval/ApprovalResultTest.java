package org.dradgo.application.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/** Story 2.9 — record-component validation pins for {@link ApprovalResult}. */
class ApprovalResultTest {

  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 5, 23, 10, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void canonicalConstructionSucceeds() {
    ApprovalResult result =
        new ApprovalResult(
            "apr_abcd1234",
            "run_abcd1234",
            "art_abcd1234",
            3,
            2,
            "product_reviewer",
            NOW,
            WorkflowState.EXECUTING,
            "corr-1");

    assertThat(result.approvalId()).isEqualTo("apr_abcd1234");
    assertThat(result.artifactVersion()).isEqualTo(3);
    assertThat(result.contextBundleVersion()).isEqualTo(2);
    assertThat(result.reviewerRole()).isEqualTo("product_reviewer");
    assertThat(result.resultingState()).isEqualTo(WorkflowState.EXECUTING);
  }

  @Test
  void rejectsNullApprovalId() {
    assertThatThrownBy(
            () ->
                new ApprovalResult(
                    null,
                    "run_x",
                    "art_x",
                    1,
                    1,
                    "product_reviewer",
                    NOW,
                    WorkflowState.EXECUTING,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("approvalId");
  }

  @Test
  void rejectsZeroOrNegativeArtifactVersion() {
    assertThatThrownBy(
            () ->
                new ApprovalResult(
                    "apr_x",
                    "run_x",
                    "art_x",
                    0,
                    1,
                    "product_reviewer",
                    NOW,
                    WorkflowState.EXECUTING,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactVersion");
  }

  @Test
  void rejectsZeroOrNegativeContextBundleVersion() {
    assertThatThrownBy(
            () ->
                new ApprovalResult(
                    "apr_x",
                    "run_x",
                    "art_x",
                    1,
                    -2,
                    "product_reviewer",
                    NOW,
                    WorkflowState.EXECUTING,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contextBundleVersion");
  }

  @Test
  void rejectsNullResultingState() {
    assertThatThrownBy(
            () ->
                new ApprovalResult(
                    "apr_x", "run_x", "art_x", 1, 1, "product_reviewer", NOW, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("resultingState");
  }
}
