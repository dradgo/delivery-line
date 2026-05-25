package org.dradgo.application.clarification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkAccepted;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkIncorporated;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkRejectedInvalid;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkSuperseded;
import org.dradgo.application.observability.MdcKeys;
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

/**
 * Owns the four visible-incorporation lifecycle transitions (story 2.12 AC1):
 *
 * <ul>
 *   <li>{@code answered → accepted} via {@link #markAccepted}.
 *   <li>{@code accepted → incorporated} via {@link #markIncorporated}.
 *   <li>{@code accepted → superseded} via {@link #markSuperseded}.
 *   <li>{@code answered → rejected_invalid} via {@link #markRejectedInvalid}.
 * </ul>
 *
 * <p>Each transition appends the matching {@code clarification.*} event in the SAME transaction as
 * the row update — {@code ArtifactOperationService.newVersion @Transactional} is the outer
 * boundary. Trap T8: no {@code @Transactional} on these methods; mistakenly adding {@code
 * REQUIRES_NEW} would break rollback shape under the outer transaction. ArchUnit boundary rule
 * {@code CLARIFICATION_LIFECYCLE_LIVES_IN_APPLICATION_CLARIFICATION} pins the package confinement.
 *
 * <p>Trap T11 cross-run leak guard: every public method accepts {@code workflowRunPublicId} +
 * {@code clarificationPublicId} so a row in a sibling run raises {@code CLARIFICATION_NOT_FOUND}
 * (same shape as missing-row) — mirror of story 2.11 trap T6.
 *
 * <p>Trap T13 FK flush-ordering: {@link #markIncorporated} appends the event row FIRST then writes
 * the clarification UPDATE referencing the event's FK. The persistence adapter resolves {@code
 * incorporation_event_id} via {@code WorkflowEventRepository.findIdByPublicId} so the application
 * never traffics in internal ids.
 *
 * <p>Two-constructor pattern: production wiring uses {@link Clock#systemUTC()}; unit tests inject a
 * fixed {@link Clock} for deterministic timestamp assertions.
 */
@Service
public class ClarificationLifecycleService {

  private static final Logger log = LoggerFactory.getLogger(ClarificationLifecycleService.class);

  /**
   * Trap T2 controlled-vocabulary regex for {@code noEffectReason}. snake_case identifier 1-64
   * chars. The application-layer cap; the DB stores {@code text} with no DDL cap. Free-form
   * override would need a separate column + future story.
   */
  private static final Pattern NO_EFFECT_REASON_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

  /** Trap T2 allowed-vocabulary set. */
  private static final Set<String> ALLOWED_NO_EFFECT_REASONS =
      Set.of(
          "clarification_not_addressed",
          "pm_marked_invalid",
          "spec_runner_skipped_question",
          "payload_read_failed",
          "superseded_by_unrelated_rebuild");

  private final ClarificationReadPort clarificationReadPort;
  private final ClarificationWritePort clarificationWritePort;
  private final ArtifactRecordPort artifactRecordPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final Clock clock;

  @Autowired
  public ClarificationLifecycleService(
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

  // Visible-for-tests constructor: fixed Clock for deterministic transitionedAt assertions.
  ClarificationLifecycleService(
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

  public ClarificationLifecycleResult markAccepted(
      String workflowRunPublicId, String clarificationPublicId, ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "markAccepted entry workflowRunId={} clarificationId={} actorIdentity={} actorType={}",
          workflowRunPublicId,
          clarificationPublicId,
          actor.actorIdentity(),
          actor.actorType().value());
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      assertTransition(row, Clarification.STATUS_ANSWERED, Clarification.STATUS_ACCEPTED);
      OffsetDateTime now = nowUtc();
      Clarification updated =
          clarificationWritePort.markAccepted(new MarkAccepted(clarificationPublicId, now));
      Map<String, Object> details = baseEventDetails(updated);
      appendEvent(
          workflowRunPublicId,
          WorkflowEventType.CLARIFICATION_ACCEPTED,
          actor,
          now,
          "clarification accepted",
          details,
          PublicIdPrefixes.WORKFLOW_EVENT.next());
      log.info(
          "markAccepted success workflowRunId={} clarificationId={} status={} transitionedAt={}",
          workflowRunPublicId,
          clarificationPublicId,
          updated.status(),
          now);
      return new ClarificationLifecycleResult(
          updated.publicId(), updated.workflowRunId(), updated.status(), now);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  public ClarificationLifecycleResult markIncorporated(
      String workflowRunPublicId,
      String clarificationPublicId,
      String newSpecArtifactPublicId,
      ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
    PublicIdPrefixes.require(newSpecArtifactPublicId, PublicIdPrefixes.ARTIFACT);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "markIncorporated entry workflowRunId={} clarificationId={} newSpecArtifactId={} actorIdentity={} actorType={}",
          workflowRunPublicId,
          clarificationPublicId,
          newSpecArtifactPublicId,
          actor.actorIdentity(),
          actor.actorType().value());
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      assertTransition(row, Clarification.STATUS_ACCEPTED, Clarification.STATUS_INCORPORATED);
      // Trap T4: orchestrator-supplied artifact missing surfaces as INTERNAL_ERROR (caller bug,
      // not user error). Different error code + log level from CLARIFICATION_NOT_FOUND so
      // operations can distinguish them.
      ArtifactRecordSnapshot newSpec =
          artifactRecordPort
              .findByPublicId(newSpecArtifactPublicId)
              .orElseThrow(
                  () -> incorporationArtifactMissing(newSpecArtifactPublicId, clarificationPublicId));
      OffsetDateTime now = nowUtc();
      // Trap T13 FK flush-ordering: append event FIRST so the adapter's findIdByPublicId lookup
      // succeeds when the clarification row UPDATE flushes.
      String incorporationEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
      Map<String, Object> details = baseEventDetails(row);
      details.put("incorporatedIntoArtifactId", newSpecArtifactPublicId);
      details.put("incorporationEventId", incorporationEventPublicId);
      appendEvent(
          workflowRunPublicId,
          WorkflowEventType.CLARIFICATION_INCORPORATED,
          actor,
          now,
          "clarification incorporated",
          details,
          incorporationEventPublicId);
      Clarification updated =
          clarificationWritePort.markIncorporated(
              new MarkIncorporated(
                  clarificationPublicId,
                  newSpecArtifactPublicId,
                  newSpec.version(),
                  incorporationEventPublicId,
                  now));
      log.info(
          "markIncorporated success workflowRunId={} clarificationId={} newSpecArtifactId={} status={} transitionedAt={}",
          workflowRunPublicId,
          clarificationPublicId,
          newSpecArtifactPublicId,
          updated.status(),
          now);
      return new ClarificationLifecycleResult(
          updated.publicId(), updated.workflowRunId(), updated.status(), now);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  public ClarificationLifecycleResult markSuperseded(
      String workflowRunPublicId,
      String clarificationPublicId,
      String supersededByArtifactPublicId,
      String noEffectReason,
      ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
    PublicIdPrefixes.require(supersededByArtifactPublicId, PublicIdPrefixes.ARTIFACT);
    requireControlledVocabularyReason(noEffectReason);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "markSuperseded entry workflowRunId={} clarificationId={} supersededByArtifactId={} noEffectReason={} actorIdentity={} actorType={}",
          workflowRunPublicId,
          clarificationPublicId,
          supersededByArtifactPublicId,
          noEffectReason,
          actor.actorIdentity(),
          actor.actorType().value());
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      assertTransition(row, Clarification.STATUS_ACCEPTED, Clarification.STATUS_SUPERSEDED);
      ArtifactRecordSnapshot supersedingSpec =
          artifactRecordPort
              .findByPublicId(supersededByArtifactPublicId)
              .orElseThrow(
                  () ->
                      incorporationArtifactMissing(
                          supersededByArtifactPublicId, clarificationPublicId));
      OffsetDateTime now = nowUtc();
      Clarification updated =
          clarificationWritePort.markSuperseded(
              new MarkSuperseded(
                  clarificationPublicId,
                  supersededByArtifactPublicId,
                  supersedingSpec.version(),
                  noEffectReason,
                  now));
      Map<String, Object> details = baseEventDetails(updated);
      details.put("supersededByArtifactId", supersededByArtifactPublicId);
      details.put("noEffectReason", noEffectReason);
      appendEvent(
          workflowRunPublicId,
          WorkflowEventType.CLARIFICATION_SUPERSEDED,
          actor,
          now,
          "clarification superseded",
          details,
          PublicIdPrefixes.WORKFLOW_EVENT.next());
      log.info(
          "markSuperseded success workflowRunId={} clarificationId={} status={} transitionedAt={}",
          workflowRunPublicId,
          clarificationPublicId,
          updated.status(),
          now);
      return new ClarificationLifecycleResult(
          updated.publicId(), updated.workflowRunId(), updated.status(), now);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  public ClarificationLifecycleResult markRejectedInvalid(
      String workflowRunPublicId,
      String clarificationPublicId,
      String noEffectReason,
      ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
    requireControlledVocabularyReason(noEffectReason);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "markRejectedInvalid entry workflowRunId={} clarificationId={} noEffectReason={} actorIdentity={} actorType={}",
          workflowRunPublicId,
          clarificationPublicId,
          noEffectReason,
          actor.actorIdentity(),
          actor.actorType().value());
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      assertTransition(row, Clarification.STATUS_ANSWERED, Clarification.STATUS_REJECTED_INVALID);
      OffsetDateTime now = nowUtc();
      Clarification updated =
          clarificationWritePort.markRejectedInvalid(
              new MarkRejectedInvalid(clarificationPublicId, noEffectReason, now));
      Map<String, Object> details = baseEventDetails(updated);
      details.put("noEffectReason", noEffectReason);
      appendEvent(
          workflowRunPublicId,
          WorkflowEventType.CLARIFICATION_REJECTED_INVALID,
          actor,
          now,
          "clarification rejected invalid",
          details,
          PublicIdPrefixes.WORKFLOW_EVENT.next());
      log.info(
          "markRejectedInvalid success workflowRunId={} clarificationId={} status={} transitionedAt={}",
          workflowRunPublicId,
          clarificationPublicId,
          updated.status(),
          now);
      return new ClarificationLifecycleResult(
          updated.publicId(), updated.workflowRunId(), updated.status(), now);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private Clarification loadAndGuardRun(String workflowRunPublicId, String clarificationPublicId) {
    Clarification row =
        clarificationReadPort
            .findByPublicId(clarificationPublicId)
            .orElseThrow(
                () -> clarificationNotFound(workflowRunPublicId, clarificationPublicId, "missing"));
    if (!row.workflowRunId().equals(workflowRunPublicId)) {
      // Trap T11 cross-run leak guard — same shape as missing-row so probes cannot discover
      // existence in a sibling run.
      throw clarificationNotFound(workflowRunPublicId, clarificationPublicId, "cross_run");
    }
    return row;
  }

  private static void assertTransition(Clarification row, String required, String target) {
    if (!required.equals(row.status())) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("clarificationId", row.publicId());
      details.put("currentStatus", row.status());
      details.put("attemptedTransition", required + " -> " + target);
      log.warn(
          "ILLEGAL_CLARIFICATION_TRANSITION clarificationId={} currentStatus={} attemptedTransition={} -> {}",
          row.publicId(),
          row.status(),
          required,
          target);
      throw new DomainException(
          DomainErrorCode.ILLEGAL_CLARIFICATION_TRANSITION,
          "Illegal clarification transition: "
              + required
              + " -> "
              + target
              + " (current: "
              + row.status()
              + ")",
          details);
    }
  }

  private static void requireControlledVocabularyReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("noEffectReason required");
    }
    if (!NO_EFFECT_REASON_PATTERN.matcher(reason).matches()) {
      throw new IllegalArgumentException(
          "noEffectReason must match " + NO_EFFECT_REASON_PATTERN.pattern() + ": " + reason);
    }
    if (!ALLOWED_NO_EFFECT_REASONS.contains(reason)) {
      throw new IllegalArgumentException(
          "noEffectReason '" + reason + "' is not in the allowed vocabulary " + ALLOWED_NO_EFFECT_REASONS);
    }
  }

  private DomainException clarificationNotFound(
      String workflowRunPublicId, String clarificationPublicId, String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", clarificationPublicId);
    details.put("workflowRunId", workflowRunPublicId);
    log.warn(
        "CLARIFICATION_NOT_FOUND clarificationId={} workflowRunId={} reason={}",
        clarificationPublicId,
        workflowRunPublicId,
        reason);
    return new DomainException(
        DomainErrorCode.CLARIFICATION_NOT_FOUND,
        "Clarification not found: " + clarificationPublicId,
        details);
  }

  private DomainException incorporationArtifactMissing(
      String artifactPublicId, String clarificationPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", clarificationPublicId);
    details.put("artifactId", artifactPublicId);
    log.error(
        "INTERNAL_ERROR orchestrator-supplied artifact missing clarificationId={} artifactId={}",
        clarificationPublicId,
        artifactPublicId);
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Orchestrator-supplied artifact not found: " + artifactPublicId,
        details);
  }

  private static Map<String, Object> baseEventDetails(Clarification clarification) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", clarification.publicId());
    details.put("questionId", clarification.questionId());
    details.put("artifactId", clarification.artifactId());
    details.put("artifactVersion", clarification.artifactVersion());
    return details;
  }

  private void appendEvent(
      String workflowRunPublicId,
      WorkflowEventType eventType,
      ActorContext actor,
      OffsetDateTime at,
      String reason,
      Map<String, Object> details,
      String eventPublicId) {
    // Defensive copy: callers pass freshly-built maps today, but a future caller passing a
    // shared/immutable map would get a surprise mutation when we inject correlationId below.
    Map<String, Object> safeDetails = new LinkedHashMap<>(details);
    if (actor.correlationId() != null && !actor.correlationId().isBlank()) {
      safeDetails.put("correlationId", actor.correlationId());
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            eventPublicId,
            workflowRunPublicId,
            eventType,
            null,
            null,
            actor.actorIdentity(),
            actor.actorType(),
            reason,
            null,
            false,
            at,
            safeDetails));
  }

  private OffsetDateTime nowUtc() {
    return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
  }
}
