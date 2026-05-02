package org.dradgo.application.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
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
			case SubmitWorkflowCommand submit ->
				append(digest, submit.linearTicketReference());
			case ApproveSpecCommand approve -> {
				append(digest, approve.workflowRunId());
				append(digest, approve.artifactId());
				append(digest, approve.artifactVersion().toString());
				append(digest, approve.contextVersion().toString());
			}
			case RejectSpecCommand reject -> {
				append(digest, reject.workflowRunId());
				append(digest, reject.artifactId());
				append(digest, reject.artifactVersion().toString());
				append(digest, reject.contextVersion().toString());
				// Trim reasonText for cross-transport parity. RetryWorkflowCommand
				// and TakeoverWorkflowCommand already normalize via normalizeOptional;
				// reject is @NotBlank so trim is sufficient and matches the others.
				append(digest, reject.reasonText().trim());
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
