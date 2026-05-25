package org.dradgo.application.clarification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.clarification.spi.ClarificationWritePort.RecordAnswer;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical executor for the {@link org.dradgo.domain.registry.AllowedAction#ANSWER_CLARIFICATION}
 * action (story 2.11). Orchestrates the four-step submitAnswer pipeline inside the caller's
 * transaction (the outer {@link
 * org.dradgo.application.workflow.WorkflowCommandService#answerClarification @Transactional}
 * boundary):
 *
 * <ol>
 *   <li>Load the clarification — {@code CLARIFICATION_NOT_FOUND} if missing OR in a sibling run
 *       (cross-run leak guard — trap T6).
 *   <li>Version-binding check ({@code CLARIFICATION_ARTIFACT_VERSION_MISMATCH}) — surfaced BEFORE
 *       the terminal-state check so a reviewer with a stale version learns about it first (trap
 *       T5).
 *   <li>Terminal-state guard ({@code CLARIFICATION_TERMINAL_STATE}) — answering an {@code
 *       incorporated} / {@code rejected_invalid} clarification is forbidden. {@code superseded}
 *       also triggers this guard. Re-answering an {@code answered} or {@code accepted} row is
 *       allowed (AC8).
 *   <li>Update the row via {@link ClarificationWritePort#recordAnswer} (writes all three answer
 *       fields in one UPDATE — trap T9) and append the {@code clarification.answered} event
 *       carrying {@code priorAnswerText} when present (AC8 history preservation).
 * </ol>
 *
 * <p><strong>No {@code @Transactional} annotation on the public method.</strong> Adding one
 * (especially {@code REQUIRES_NEW}) would create a nested boundary that would NOT roll the row
 * UPDATE back if the surrounding outer transaction fails — trap T7. The transactionality
 * integration test pins the rollback shape.
 *
 * <p>Two-constructor pattern: production wiring uses {@link Clock#systemUTC()}; unit tests inject a
 * fixed {@link Clock} so {@code answeredAt} assertions are deterministic without time windows.
 */
@Service
public class ClarificationService {

  private static final Logger log = LoggerFactory.getLogger(ClarificationService.class);

  private final ClarificationReadPort clarificationReadPort;
  private final ClarificationWritePort clarificationWritePort;
  private final ArtifactRecordPort artifactRecordPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final Clock clock;

  @Autowired
  public ClarificationService(
      ClarificationReadPort clarificationReadPort,
      ClarificationWritePort clarificationWritePort,
      ArtifactRecordPort artifactRecordPort,
      WorkflowEventWritePort workflowEventWritePort) {
    this(
        clarificationReadPort,
        clarificationWritePort,
        artifactRecordPort,
        workflowEventWritePort,
        Clock.systemUTC());
  }

  // Visible-for-tests constructor: lets unit tests inject a fixed Clock so answeredAt assertions
  // are deterministic without resorting to time-windowed greater-than checks.
  ClarificationService(
      ClarificationReadPort clarificationReadPort,
      ClarificationWritePort clarificationWritePort,
      ArtifactRecordPort artifactRecordPort,
      WorkflowEventWritePort workflowEventWritePort,
      Clock clock) {
    this.clarificationReadPort = clarificationReadPort;
    this.clarificationWritePort = clarificationWritePort;
    this.artifactRecordPort = artifactRecordPort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public ClarificationResult submitAnswer(SubmitClarificationCommand command) {
    PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(command.clarificationId(), PublicIdPrefixes.CLARIFICATION);
    PublicIdPrefixes.require(command.artifactId(), PublicIdPrefixes.ARTIFACT);

    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    String priorArtifactId = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, command.artifactId());
    try {
      // Trap T12 forbids answerText / questionText in logs — log lengths and identities only.
      log.info(
          "submitAnswer entry workflowRunId={} clarificationId={} artifactId={} expectedArtifactVersion={} actorIdentity={} actorType={} answerText.length={}",
          command.workflowRunId(),
          command.clarificationId(),
          command.artifactId(),
          command.artifactVersion(),
          command.actorIdentity(),
          command.actorType().value(),
          command.answerText() == null ? 0 : command.answerText().length());

      // (1) Load clarification — CLARIFICATION_NOT_FOUND if missing OR in a sibling run.
      Clarification clarification =
          clarificationReadPort
              .findByPublicId(command.clarificationId())
              .orElseThrow(() -> clarificationNotFound(command, "missing"));
      if (!clarification.workflowRunId().equals(command.workflowRunId())) {
        // Trap T6 cross-run leak guard: same shape as the missing-row case so a probe cannot
        // discover whether a clr_… exists in a sibling run.
        throw clarificationNotFound(command, "cross_run");
      }

      // (2) Trap T5: version-binding BEFORE terminal-state check. Stale-version reviewer gets the
      // actionable mismatch error first.
      ArtifactRecordSnapshot artifact =
          artifactRecordPort
              .findByPublicId(command.artifactId())
              .orElseThrow(() -> clarificationNotFound(command, "artifact_mismatch"));
      if (!artifact.publicId().equals(clarification.artifactId())) {
        // Defense-in-depth: the clarification is pinned to a specific artifact; refuse mismatched
        // ids. Surface as CLARIFICATION_NOT_FOUND (not a leaky 'wrong artifact' error).
        throw clarificationNotFound(command, "artifact_mismatch");
      }
      if (artifact.version() != command.artifactVersion()) {
        throw versionMismatch(command, clarification, artifact.version());
      }

      // (3) AC7: terminal-state guard. incorporated / superseded / rejected_invalid all reject the
      // answer; answered / accepted both allow re-answer (AC8).
      if (clarification.isTerminal()) {
        throw terminalState(command, clarification);
      }

      // (4) AC8: capture priorAnswerText for the event audit. Row holds latest, event log holds
      // the prior value. May be null on first answer.
      String priorAnswerText = clarification.answerText();

      // (5) AC6: update row + append event in one transaction. recordAnswer writes all three
      // answer fields in one UPDATE so ck_clarifications_answered_fields_paired never trips.
      OffsetDateTime answeredAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      Clarification updated =
          clarificationWritePort.recordAnswer(
              new RecordAnswer(
                  command.clarificationId(),
                  command.answerText(),
                  command.actorIdentity(),
                  command.actorType(),
                  answeredAt));

      Map<String, Object> details = buildEventDetails(command, updated, priorAnswerText);
      workflowEventWritePort.append(
          new WorkflowEventRecord(
              PublicIdPrefixes.WORKFLOW_EVENT.next(),
              command.workflowRunId(),
              WorkflowEventType.CLARIFICATION_ANSWERED,
              null,
              null,
              command.actorIdentity(),
              command.actorType(),
              "clarification answered",
              null,
              false,
              answeredAt,
              details));

      log.info(
          "submitAnswer success clarificationId={} workflowRunId={} artifactId={} status={} priorAnswer={}",
          updated.publicId(),
          updated.workflowRunId(),
          updated.artifactId(),
          updated.status(),
          priorAnswerText == null ? "absent" : "present");

      return new ClarificationResult(
          updated.publicId(),
          updated.workflowRunId(),
          updated.artifactId(),
          updated.artifactVersion(),
          updated.status(),
          answeredAt,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactId);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private static Map<String, Object> buildEventDetails(
      SubmitClarificationCommand command, Clarification updated, String priorAnswerText) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", updated.publicId());
    details.put("artifactId", updated.artifactId());
    details.put("artifactVersion", updated.artifactVersion());
    details.put("questionId", updated.questionId());
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    if (priorAnswerText != null) {
      // AC8: preserve the prior answer in the event audit so the history is reconstructable from
      // the event log even though the row holds only the latest. Forbidden in log output (T12).
      details.put("priorAnswerText", priorAnswerText);
    }
    return details;
  }

  private DomainException clarificationNotFound(SubmitClarificationCommand command, String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", command.clarificationId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "submitAnswer rejected CLARIFICATION_NOT_FOUND clarificationId={} workflowRunId={} reason={}",
        command.clarificationId(),
        command.workflowRunId(),
        reason);
    return new DomainException(
        DomainErrorCode.CLARIFICATION_NOT_FOUND,
        "Clarification not found: " + command.clarificationId(),
        details);
  }

  private DomainException versionMismatch(
      SubmitClarificationCommand command, Clarification clarification, int currentArtifactVersion) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("expectedArtifactVersion", command.artifactVersion());
    details.put("currentArtifactVersion", currentArtifactVersion);
    details.put("clarificationId", command.clarificationId());
    details.put("artifactId", command.artifactId());
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "submitAnswer rejected CLARIFICATION_ARTIFACT_VERSION_MISMATCH clarificationId={} workflowRunId={} artifactId={} expectedArtifactVersion={} currentArtifactVersion={}",
        command.clarificationId(),
        command.workflowRunId(),
        command.artifactId(),
        command.artifactVersion(),
        currentArtifactVersion);
    return new DomainException(
        DomainErrorCode.CLARIFICATION_ARTIFACT_VERSION_MISMATCH,
        "Clarification answer rejected: artifact version is stale",
        details);
  }

  private DomainException terminalState(
      SubmitClarificationCommand command, Clarification clarification) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", clarification.publicId());
    details.put("currentStatus", clarification.status());
    details.put("terminalReason", "clarification_in_terminal_state");
    details.put("workflowRunId", command.workflowRunId());
    log.warn(
        "submitAnswer rejected CLARIFICATION_TERMINAL_STATE clarificationId={} workflowRunId={} currentStatus={}",
        clarification.publicId(),
        command.workflowRunId(),
        clarification.status());
    return new DomainException(
        DomainErrorCode.CLARIFICATION_TERMINAL_STATE,
        "Clarification is in a terminal state: " + clarification.status(),
        details);
  }

  private static String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
