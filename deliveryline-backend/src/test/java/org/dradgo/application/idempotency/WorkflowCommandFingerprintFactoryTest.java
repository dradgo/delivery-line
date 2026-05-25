package org.dradgo.application.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RejectionTaxonomy;
import org.junit.jupiter.api.Test;

/**
 * Story 2.11 Task 5 pin: SubmitClarificationCommand fingerprint excludes {@code answerText}
 * (symmetric with {@link ApproveSpecCommand#reason()} and {@link RejectSpecCommand#reasonText()}
 * exclusion). The four cases below cover AC11(h) + AC11(i) and the symmetry parity invariant.
 */
class WorkflowCommandFingerprintFactoryTest {

  private final WorkflowCommandFingerprintFactory factory = new WorkflowCommandFingerprintFactory();

  @Test
  void answerTextEditsDoNotChangeTheClarificationFingerprint() {
    SubmitClarificationCommand a = command("clr_abc12345", "art_abc12345", 1, "answer-v1");
    SubmitClarificationCommand b = command("clr_abc12345", "art_abc12345", 1, "answer-v2");
    assertEquals(factory.fingerprintFor(a), factory.fingerprintFor(b));
  }

  @Test
  void clarificationIdShiftChangesTheClarificationFingerprint() {
    SubmitClarificationCommand a = command("clr_abc12345", "art_abc12345", 1, "answer");
    SubmitClarificationCommand b = command("clr_xyz98765", "art_abc12345", 1, "answer");
    assertNotEquals(factory.fingerprintFor(a), factory.fingerprintFor(b));
  }

  @Test
  void artifactVersionShiftChangesTheClarificationFingerprint() {
    SubmitClarificationCommand a = command("clr_abc12345", "art_abc12345", 1, "answer");
    SubmitClarificationCommand b = command("clr_abc12345", "art_abc12345", 2, "answer");
    assertNotEquals(factory.fingerprintFor(a), factory.fingerprintFor(b));
  }

  @Test
  void artifactIdShiftChangesTheClarificationFingerprint() {
    SubmitClarificationCommand a = command("clr_abc12345", "art_abc12345", 1, "answer");
    SubmitClarificationCommand b = command("clr_abc12345", "art_zzz12345", 1, "answer");
    assertNotEquals(factory.fingerprintFor(a), factory.fingerprintFor(b));
  }

  @Test
  void approveAndRejectFreeFormTextExclusionPaintsTheSameSymmetryShape() {
    // Symmetry assertion: approveSpec.reason and rejectSpec.reasonText edits do NOT shift their
    // fingerprints either — clarification.answerText exclusion is the same pattern.
    ApproveSpecCommand approveA = approve("reason-v1");
    ApproveSpecCommand approveB = approve("reason-v2");
    assertEquals(factory.fingerprintFor(approveA), factory.fingerprintFor(approveB));

    RejectSpecCommand rejectA = reject("reason-v1");
    RejectSpecCommand rejectB = reject("reason-v2");
    assertEquals(factory.fingerprintFor(rejectA), factory.fingerprintFor(rejectB));
  }

  private SubmitClarificationCommand command(
      String clarificationId, String artifactId, int artifactVersion, String answerText) {
    return new SubmitClarificationCommand(
        "run_abc12345",
        clarificationId,
        artifactId,
        artifactVersion,
        answerText,
        "alex",
        ActorType.HUMAN,
        "idem-clr-1",
        null);
  }

  private ApproveSpecCommand approve(String reason) {
    return new ApproveSpecCommand(
        "run_abc12345",
        "art_abc12345",
        1,
        1,
        "alex",
        ActorType.HUMAN,
        "idem-apr-1",
        null,
        "product_reviewer",
        reason);
  }

  private RejectSpecCommand reject(String reasonText) {
    return new RejectSpecCommand(
        "run_abc12345",
        "art_abc12345",
        1,
        1,
        "alex",
        ActorType.HUMAN,
        "idem-rej-1",
        null,
        "product_reviewer",
        RejectionTaxonomy.UNCLEAR_SPECIFICATION,
        reasonText);
  }
}
