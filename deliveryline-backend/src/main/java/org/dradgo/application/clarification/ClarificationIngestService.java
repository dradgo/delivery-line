package org.dradgo.application.clarification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.clarification.spi.ClarificationWritePort.NewClarification;
import org.dradgo.application.clarification.spi.SpecClarificationAcknowledgementWritePort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3e-1 (FR10) — the CREATE half of the clarification loop: turns a spec runner result's open
 * questions ({@code specArtifact.questions}) into first-class {@code status = 'open'} {@code
 * clarifications} rows so the existing read model (2.14) + {@code ClarificationRegion} (2.18) can
 * surface them and the existing {@code /answer} endpoint (2.11) has something to answer.
 *
 * <p>This back-half always existed; the create caller never did — {@link
 * ClarificationWritePort#insertOpen} had zero production callers. The broker delegates here (rather
 * than inlining) so {@code RunnerBroker} does not grow a clarification-write dependency, mirroring
 * how it delegates the spec-ready transition to {@code WorkflowOrchestrationService}.
 *
 * <p><strong>{@code @Transactional(propagation = MANDATORY)}</strong> — like {@link
 * ClarificationService#submitAnswer} it runs INSIDE the broker's per-result transaction so each row
 * insert + its {@code clarification.raised} event commit or roll back together. It must NOT open a
 * {@code REQUIRES_NEW} boundary (trap T7 family) — that would not roll the inserts back with the
 * outer transaction.
 *
 * <p><strong>Idempotency:</strong> the caller supplies a DETERMINISTIC key per ({@code
 * runnerExecutionId}, {@code questionId}) so a legitimate re-harvest / scratch-replay does not
 * double-insert. Each question is pre-flighted via {@link
 * ClarificationReadPort#existsByIdempotencyKey} so a replay never reaches {@code insertOpen}'s
 * {@code saveAndFlush} — flushing a conflict would poison the broker's shared transaction and
 * strand the completed run (review D1). The per-question {@code IDEMPOTENCY_KEY_CONFLICT} {@link
 * DomainException} catch (the DB-level {@code uq_clarifications_idempotency_key} backstop) remains
 * as final defense-in-depth for the vanishing concurrent-TOCTOU window and is logged at INFO as a
 * benign duplicate — the remaining questions still proceed.
 *
 * <p><strong>Logging discipline (trap T12):</strong> NEVER logs {@code questionText} — only ids,
 * counts, and lengths, mirroring {@link ClarificationService}.
 *
 * <p>Two-constructor pattern: production wiring uses {@link Clock#systemUTC()}; unit tests inject a
 * fixed {@link Clock} so {@code raisedAt} assertions are deterministic.
 */
@Service
public class ClarificationIngestService {

  private static final Logger log = LoggerFactory.getLogger(ClarificationIngestService.class);

  /**
   * Deterministic idempotency-key prefix: {@code runner-result-clarification:<rex>:<questionId>}.
   */
  private static final String IDEMPOTENCY_KEY_PREFIX = "runner-result-clarification:";

  private static final String SYSTEM_ACTOR = "system";

  private final ClarificationWritePort clarificationWritePort;
  private final ClarificationReadPort clarificationReadPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  // Story 3e-2 (AC6) — the V25 acknowledgements side-store, written alongside the spec ingest.
  private final SpecClarificationAcknowledgementWritePort acknowledgementWritePort;
  private final Clock clock;

  @Autowired
  public ClarificationIngestService(
      ClarificationWritePort clarificationWritePort,
      ClarificationReadPort clarificationReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      SpecClarificationAcknowledgementWritePort acknowledgementWritePort) {
    this(
        clarificationWritePort,
        clarificationReadPort,
        workflowEventWritePort,
        acknowledgementWritePort,
        Clock.systemUTC());
  }

  // Visible-for-tests constructor: lets unit tests inject a fixed Clock so raisedAt assertions are
  // deterministic without resorting to time-windowed greater-than checks.
  ClarificationIngestService(
      ClarificationWritePort clarificationWritePort,
      ClarificationReadPort clarificationReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      SpecClarificationAcknowledgementWritePort acknowledgementWritePort,
      Clock clock) {
    this.clarificationWritePort = clarificationWritePort;
    this.clarificationReadPort = clarificationReadPort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.acknowledgementWritePort = acknowledgementWritePort;
    this.clock = clock;
  }

  /**
   * Create one {@code open} clarification per emitted question, each pinned to the just-ingested
   * spec artifact + version, and append a {@code clarification.raised} event per created row.
   * Returns the number of rows actually created (duplicates skipped). Runs in the caller's
   * transaction (MANDATORY).
   *
   * @param workflowRunId the run the spec belongs to ({@code run_…})
   * @param specArtifactId the just-ingested spec artifact ({@code art_…})
   * @param specArtifactVersion the spec artifact version the clarification is bound to
   * @param questions the questions lifted from {@code specArtifact.questions}
   * @param runnerExecutionId the producing runner execution ({@code rex_…}) — anchors the
   *     deterministic idempotency key so replay/re-harvest is safe
   * @param correlationId optional correlation id for the event audit (may be {@code null}/blank)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int createOpenFromSpec(
      String workflowRunId,
      String specArtifactId,
      int specArtifactVersion,
      List<RaisedQuestion> questions,
      String runnerExecutionId,
      String correlationId) {
    if (questions == null || questions.isEmpty()) {
      return 0;
    }
    // Review 3e-1 (CRITICAL): collapse duplicate questionIds BEFORE inserting. The deterministic
    // idempotency key is (runnerExecutionId, questionId); two questions sharing a questionId in ONE
    // result would make the second insertOpen flush a uq_clarifications_idempotency_key violation
    // INSIDE the broker's shared transaction. On PostgreSQL a failed flush leaves the Hibernate
    // session dirty — catching the translated DomainException does NOT heal it, so the next flush
    // throws AssertionFailure("null identifier ... session flushed after an exception") and the
    // whole
    // completion is rolled back, stranding the run in Investigating (re-harvested forever). The
    // runner-result schema does not enforce uniqueItems on questions[], so de-dup here (first wins)
    // so a conflicting insert is never flushed. Cross-call replay/re-harvest is handled by the
    // per-question pre-flight existence probe below (review D1); the DB-level conflict catch is the
    // final defense-in-depth backstop for the vanishing concurrent-TOCTOU window.
    List<RaisedQuestion> uniqueQuestions = dedupeByQuestionId(questions);
    int collapsedCount = questions.size() - uniqueQuestions.size();
    // Review 3e-1 (P3): stamp correlationId into the MDC so EVERY ingest log line on this
    // INVESTIGATION branch (the summary + any benign-duplicate lines) is correlatable, not just the
    // event-detail map. beginScope no-ops on null/blank, so a missing correlationId is harmless.
    String priorCorrelationId = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, correlationId);
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorArtifactId = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, specArtifactId);
    try {
      int createdCount = 0;
      int duplicateCount = 0;
      OffsetDateTime raisedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      for (RaisedQuestion question : uniqueQuestions) {
        String clarificationId = PublicIdPrefixes.CLARIFICATION.next();
        String idempotencyKey =
            IDEMPOTENCY_KEY_PREFIX + runnerExecutionId + ":" + question.questionId();
        // Review 3e-1 (D1): pre-flight existence probe so a cross-call replay / re-harvest of the
        // SAME (runnerExecutionId, questionId) never reaches insertOpen's saveAndFlush — a flushed
        // uq_clarifications_idempotency_key conflict poisons the broker's shared Hibernate session
        // and strands the completed run (catching the translated DomainException does NOT heal it).
        // This makes the IDEMPOTENCY_KEY_CONFLICT catch below a genuine DB-level backstop (it only
        // fires on the vanishing TOCTOU window of two truly-concurrent batches inserting the same
        // key) rather than a guaranteed strander. The probe joins the caller's RW tx.
        if (clarificationReadPort.existsByIdempotencyKey(idempotencyKey)) {
          duplicateCount++;
          log.info(
              "clarification ingest benign duplicate (idempotency key exists) workflowRunId={} specArtifactId={} questionId={}",
              workflowRunId,
              specArtifactId,
              question.questionId());
          continue;
        }
        try {
          Clarification created =
              clarificationWritePort.insertOpen(
                  new NewClarification(
                      clarificationId,
                      workflowRunId,
                      specArtifactId,
                      specArtifactVersion,
                      question.questionId(),
                      question.questionText(),
                      idempotencyKey));
          workflowEventWritePort.append(
              new WorkflowEventRecord(
                  PublicIdPrefixes.WORKFLOW_EVENT.next(),
                  workflowRunId,
                  WorkflowEventType.CLARIFICATION_RAISED,
                  null,
                  null,
                  SYSTEM_ACTOR,
                  ActorType.SYSTEM,
                  "clarification raised",
                  null,
                  false,
                  raisedAt,
                  buildEventDetails(created, normalizeOptional(correlationId))));
          createdCount++;
        } catch (DomainException ex) {
          if (ex.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
            duplicateCount++;
            log.info(
                "clarification ingest benign duplicate (idempotency key conflict) workflowRunId={} specArtifactId={} questionId={}",
                workflowRunId,
                specArtifactId,
                question.questionId());
          } else {
            throw ex;
          }
        }
      }
      log.info(
          "clarification ingest workflowRunId={} specArtifactId={} version={} questionCount={} uniqueCount={} collapsedCount={} createdCount={} duplicateCount={}",
          workflowRunId,
          specArtifactId,
          specArtifactVersion,
          questions.size(),
          uniqueQuestions.size(),
          collapsedCount,
          createdCount,
          duplicateCount);
      return createdCount;
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactId);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationId);
    }
  }

  private static Map<String, Object> buildEventDetails(
      Clarification created, String correlationId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", created.publicId());
    details.put("artifactId", created.artifactId());
    details.put("questionId", created.questionId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  // Review 3e-1 (CRITICAL): first-wins de-duplication by questionId so two questions sharing an id
  // in one result never flush a conflicting insert into the broker's shared transaction.
  private static List<RaisedQuestion> dedupeByQuestionId(List<RaisedQuestion> questions) {
    Map<String, RaisedQuestion> byId = new LinkedHashMap<>();
    for (RaisedQuestion question : questions) {
      byId.putIfAbsent(question.questionId(), question);
    }
    return new ArrayList<>(byId.values());
  }

  private static String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Story 3e-2 (AC6/R5) — persist the rebuilt spec's structured {@code
   * clarificationAcknowledgements} into the V25 side-store, keyed by the just-ingested spec
   * artifact's public id, so the clarification sweep (which runs inside {@code markAvailable} and
   * sees only the artifact) can read them. The broker calls this BEFORE {@code
   * markArtifactAvailable} so the rows exist when the synchronous sweep reads them.
   *
   * <p>Runs in the caller's transaction (MANDATORY). De-dups by questionId (first-wins) and
   * pre-flight-probes the {@code (spec_artifact_id, question_id)} UNIQUE so a re-harvest never
   * flushes a conflicting insert into the shared broker transaction (the same session-poison trap
   * as {@link #createOpenFromSpec}'s questions path — review 3e-1 CRITICAL).
   *
   * @param specArtifactId the just-ingested spec artifact ({@code art_…})
   * @param acknowledgements the acknowledgements lifted from {@code
   *     specArtifact.clarificationAcknowledgements}
   * @param correlationId optional correlation id for the audit log (may be {@code null}/blank)
   * @return the number of rows actually inserted (duplicates skipped)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int persistSpecClarificationAcknowledgements(
      String specArtifactId, List<RaisedAcknowledgement> acknowledgements, String correlationId) {
    if (acknowledgements == null || acknowledgements.isEmpty()) {
      return 0;
    }
    List<RaisedAcknowledgement> unique = dedupeAcknowledgementsByQuestionId(acknowledgements);
    int collapsedCount = acknowledgements.size() - unique.size();
    String priorCorrelationId = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, correlationId);
    String priorArtifactId = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, specArtifactId);
    try {
      int insertedCount = 0;
      int duplicateCount = 0;
      for (RaisedAcknowledgement acknowledgement : unique) {
        if (acknowledgementWritePort.existsBySpecArtifactPublicIdAndQuestionId(
            specArtifactId, acknowledgement.questionId())) {
          duplicateCount++;
          log.info(
              "spec acknowledgement ingest benign duplicate specArtifactId={} questionId={} addressed={}",
              specArtifactId,
              acknowledgement.questionId(),
              acknowledgement.addressed());
          continue;
        }
        acknowledgementWritePort.insert(
            PublicIdPrefixes.SPEC_CLARIFICATION_ACKNOWLEDGEMENT.next(),
            specArtifactId,
            acknowledgement.questionId(),
            acknowledgement.addressed());
        insertedCount++;
      }
      log.info(
          "spec acknowledgement ingest specArtifactId={} acknowledgementCount={} uniqueCount={} collapsedCount={} insertedCount={} duplicateCount={}",
          specArtifactId,
          acknowledgements.size(),
          unique.size(),
          collapsedCount,
          insertedCount,
          duplicateCount);
      return insertedCount;
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactId);
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationId);
    }
  }

  // Story 3e-2: first-wins de-dup by questionId — the twin of dedupeByQuestionId for the
  // acknowledgements side-store (same shared-tx-poison defense).
  private static List<RaisedAcknowledgement> dedupeAcknowledgementsByQuestionId(
      List<RaisedAcknowledgement> acknowledgements) {
    Map<String, RaisedAcknowledgement> byId = new LinkedHashMap<>();
    for (RaisedAcknowledgement acknowledgement : acknowledgements) {
      byId.putIfAbsent(acknowledgement.questionId(), acknowledgement);
    }
    return new ArrayList<>(byId.values());
  }

  /** A single open question lifted from a spec result's {@code specArtifact.questions}. */
  public record RaisedQuestion(String questionId, String questionText) {}

  /**
   * Story 3e-2 — a single structured acknowledgement lifted from a spec result's {@code
   * specArtifact.clarificationAcknowledgements}.
   */
  public record RaisedAcknowledgement(String questionId, boolean addressed) {}
}
