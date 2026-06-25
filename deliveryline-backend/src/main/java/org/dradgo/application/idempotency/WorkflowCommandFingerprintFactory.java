package org.dradgo.application.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.dradgo.application.workflow.commands.AcceptClarificationCommand;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RegenerateSpecCommand;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.commands.WorkflowCommand;
import org.springframework.stereotype.Component;

@Component
public class WorkflowCommandFingerprintFactory {

  public String fingerprintFor(WorkflowCommand command) {
    MessageDigest digest = newDigest();
    append(digest, command.commandType());
    append(digest, command.actorIdentity());
    append(digest, command.actorType().value());
    append(digest, normalizeOptional(command.correlationId()));
    switch (command) {
      case SubmitWorkflowCommand submit -> append(digest, submit.linearTicketReference());
      case ApproveSpecCommand approve -> {
        append(digest, approve.workflowRunId());
        append(digest, approve.artifactId());
        append(digest, approve.artifactVersion().toString());
        append(digest, approve.contextVersion().toString());
        // reviewerRole is part of the semantic identity of the decision — a different role
        // asserting the same approval is a different command. reason is intentionally NOT
        // fingerprinted: free-form reviewer wording edits on the same review must replay.
        append(digest, approve.reviewerRole());
      }
      case RejectSpecCommand reject -> {
        append(digest, reject.workflowRunId());
        append(digest, reject.artifactId());
        append(digest, reject.artifactVersion().toString());
        append(digest, reject.contextVersion().toString());
        // reviewerRole is part of the semantic identity of the decision (story 2.10) — symmetric
        // with ApproveSpecCommand above. taggedFeedback is the structured rework taxonomy and
        // must shift the fingerprint when changed (e.g., a reviewer revising the taxonomy on the
        // same review is a different command). reasonText is intentionally NOT fingerprinted:
        // free-form reviewer wording edits on the same review must replay idempotently — story
        // 2.10 trap T6 / OQ-2 (symmetric with ApproveSpecCommand.reason exclusion).
        append(digest, reject.reviewerRole());
        append(digest, reject.taggedFeedback().value());
      }
      case AcceptImplementationCommand accept -> {
        // Story 3.20: technical-approval twin of ApproveSpecCommand. Fingerprint fields beyond the
        // shared envelope are workflowRunId + artifactId + artifactVersion + contextVersion +
        // reviewerRole. reason is intentionally NOT fingerprinted — free-form reviewer wording
        // edits
        // on the same review must replay idempotently (symmetric with ApproveSpecCommand.reason).
        // reviewerRole IS fingerprinted: asserting a different role is a different command.
        append(digest, accept.workflowRunId());
        append(digest, accept.artifactId());
        append(digest, accept.artifactVersion().toString());
        append(digest, accept.contextVersion().toString());
        append(digest, accept.reviewerRole());
      }
      case RejectImplementationCommand reject -> {
        // Story 3.21: technical-rejection twin of RejectSpecCommand. Fingerprint fields beyond the
        // shared envelope are workflowRunId + artifactId + artifactVersion + contextVersion +
        // reviewerRole + taggedFeedback. reasonText is intentionally NOT fingerprinted — free-form
        // reviewer wording edits on the same review must replay idempotently (symmetric with
        // RejectSpecCommand.reasonText). reviewerRole + taggedFeedback ARE fingerprinted: a
        // different role or a revised structured taxonomy on the same review is a different
        // command.
        append(digest, reject.workflowRunId());
        append(digest, reject.artifactId());
        append(digest, reject.artifactVersion().toString());
        append(digest, reject.contextVersion().toString());
        append(digest, reject.reviewerRole());
        append(digest, reject.taggedFeedback().value());
      }
      case SubmitClarificationCommand clarify -> {
        // Story 2.11: canonical fingerprint fields beyond the shared envelope are
        // workflowRunId + clarificationId + artifactId + artifactVersion. answerText is
        // intentionally NOT fingerprinted — free-form reviewer wording edits on the same
        // clarification must replay idempotently (symmetric with ApproveSpecCommand.reason and
        // RejectSpecCommand.reasonText exclusion). The DB-level uq_clarifications_idempotency_key
        // UNIQUE constraint is the defense-in-depth backstop.
        append(digest, clarify.workflowRunId());
        append(digest, clarify.clarificationId());
        append(digest, clarify.artifactId());
        append(digest, clarify.artifactVersion().toString());
      }
      case AcceptClarificationCommand accept -> {
        // Story 3e-2: canonical fingerprint fields beyond the shared envelope are workflowRunId +
        // clarificationId. Acceptance carries no artifact-version binding (markAccepted acts on the
        // already-answered row), so the semantic identity is the (run, clarification) pair — a
        // second accept of the same clarification with the same key replays idempotently.
        append(digest, accept.workflowRunId());
        append(digest, accept.clarificationId());
      }
      case RegenerateSpecCommand regenerate -> {
        // Story 3e-2: the only semantic-identity field beyond the shared envelope is workflowRunId.
        // A regenerate is a forward re-run request — the same key replays idempotently; a distinct
        // key (a fresh operator-initiated rebuild) bumps the spec_rejection_loop_count for a fresh
        // dispatch key.
        append(digest, regenerate.workflowRunId());
      }
      case RetryWorkflowCommand retry -> {
        append(digest, retry.workflowRunId());
        append(digest, normalizeOptional(retry.reasonText()));
      }
      case TakeoverWorkflowCommand takeover -> {
        append(digest, takeover.workflowRunId());
        append(digest, normalizeOptional(takeover.reasonText()));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available in JDK 21", exception);
    }
  }

  private void append(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? "" : trimmed;
  }
}
