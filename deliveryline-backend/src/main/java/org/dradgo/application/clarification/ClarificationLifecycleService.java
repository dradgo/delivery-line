package org.dradgo.application.clarification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.dradgo.application.artifact.ActorContext;
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

  /**
   * Trap T2 allowed-vocabulary set (whole-vocabulary regex+set check; specific methods further
   * restrict via per-method subsets — see {@link #SUPERSEDED_REASONS} and {@link
   * #REJECTED_INVALID_REASONS}).
   */
  private static final Set<String> ALLOWED_NO_EFFECT_REASONS =
      Set.of(
          "clarification_not_addressed",
          "pm_marked_invalid",
          "spec_runner_skipped_question",
          "payload_read_failed",
          "superseded_by_unrelated_rebuild");

  /**
   * P14 — per-method {@code noEffectReason} subset for {@link #markSuperseded}. Spec reserves the
   * vocabulary per method so {@code pm_marked_invalid} (PM judgment) cannot be smuggled into a
   * supersession event by a buggy orchestrator path.
   */
  private static final Set<String> SUPERSEDED_REASONS =
      Set.of(
          "clarification_not_addressed", "superseded_by_unrelated_rebuild", "payload_read_failed");

  /**
   * P14 — per-method {@code noEffectReason} subset for {@link #markRejectedInvalid}. Excludes
   * orchestrator-only tokens (e.g. {@code clarification_not_addressed}) so a PM/operator rejection
   * cannot reuse a supersession code by accident.
   */
  private static final Set<String> REJECTED_INVALID_REASONS =
      Set.of("pm_marked_invalid", "spec_runner_skipped_question");

  /** P5/P15 — three-way classification of an attempted lifecycle transition. */
  private enum TransitionPhase {
    /** Row is at the required precursor state; proceed with the write + event. */
    PROCEED,
    /**
     * P5 idempotent replay: row is already at the target state (e.g. outer-tx retry replaying a
     * sweep). Return the existing result without re-emitting a duplicate event.
     */
    ALREADY_AT_TARGET
  }

  private final ClarificationReadPort clarificationReadPort;
  private final ClarificationWritePort clarificationWritePort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final Clock clock;

  // P9 — ArtifactRecordPort dependency removed; callers now supply the artifact version directly
  // so the lifecycle service no longer re-fetches the artifact record on each mark* call.

  @Autowired
  public ClarificationLifecycleService(
      ClarificationReadPort clarificationReadPort,
      ClarificationWritePort clarificationWritePort,
      WorkflowEventWritePort workflowEventWritePort) {
    this(clarificationReadPort, clarificationWritePort, workflowEventWritePort, Clock.systemUTC());
  }

  // Visible-for-tests constructor: fixed Clock for deterministic transitionedAt assertions.
  ClarificationLifecycleService(
      ClarificationReadPort clarificationReadPort,
      ClarificationWritePort clarificationWritePort,
      WorkflowEventWritePort workflowEventWritePort,
      Clock clock) {
    this.clarificationReadPort = clarificationReadPort;
    this.clarificationWritePort = clarificationWritePort;
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
      TransitionPhase phase =
          assertTransition(row, Clarification.STATUS_ANSWERED, Clarification.STATUS_ACCEPTED);
      OffsetDateTime now = nowUtc();
      if (phase == TransitionPhase.ALREADY_AT_TARGET) {
        return new ClarificationLifecycleResult(
            row.publicId(), row.workflowRunId(), row.status(), now);
      }
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
      int newSpecArtifactVersion,
      ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
    PublicIdPrefixes.require(newSpecArtifactPublicId, PublicIdPrefixes.ARTIFACT);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "markIncorporated entry workflowRunId={} clarificationId={} newSpecArtifactId={} newSpecArtifactVersion={} actorIdentity={} actorType={}",
          workflowRunPublicId,
          clarificationPublicId,
          newSpecArtifactPublicId,
          newSpecArtifactVersion,
          actor.actorIdentity(),
          actor.actorType().value());
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      TransitionPhase phase =
          assertTransition(row, Clarification.STATUS_ACCEPTED, Clarification.STATUS_INCORPORATED);
      OffsetDateTime now = nowUtc();
      if (phase == TransitionPhase.ALREADY_AT_TARGET) {
        return new ClarificationLifecycleResult(
            row.publicId(), row.workflowRunId(), row.status(), now);
      }
      // P9 — caller (orchestrator + future REST adapter) supplies the artifact version directly
      // so the lifecycle service no longer re-fetches the artifact record on every sweep loop
      // iteration. Trap T4 (orchestrator-supplied artifact missing -> INTERNAL_ERROR) is now
      // structural: callers can't compose a non-existent (publicId, version) pair because they
      // come from a freshly-persisted ArtifactRecordSnapshot.
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
                  newSpecArtifactVersion,
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
      int supersededByArtifactVersion,
      String noEffectReason,
      ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
    PublicIdPrefixes.require(supersededByArtifactPublicId, PublicIdPrefixes.ARTIFACT);
    requireControlledVocabularyReason(noEffectReason, SUPERSEDED_REASONS);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "markSuperseded entry workflowRunId={} clarificationId={} supersededByArtifactId={} supersededByArtifactVersion={} noEffectReason={} actorIdentity={} actorType={}",
          workflowRunPublicId,
          clarificationPublicId,
          supersededByArtifactPublicId,
          supersededByArtifactVersion,
          noEffectReason,
          actor.actorIdentity(),
          actor.actorType().value());
      Clarification row = loadAndGuardRun(workflowRunPublicId, clarificationPublicId);
      TransitionPhase phase =
          assertTransition(row, Clarification.STATUS_ACCEPTED, Clarification.STATUS_SUPERSEDED);
      OffsetDateTime now = nowUtc();
      if (phase == TransitionPhase.ALREADY_AT_TARGET) {
        return new ClarificationLifecycleResult(
            row.publicId(), row.workflowRunId(), row.status(), now);
      }
      // P9 — caller supplies the artifact version directly (no per-call lookup).
      Clarification updated =
          clarificationWritePort.markSuperseded(
              new MarkSuperseded(
                  clarificationPublicId,
                  supersededByArtifactPublicId,
                  supersededByArtifactVersion,
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
    requireControlledVocabularyReason(noEffectReason, REJECTED_INVALID_REASONS);
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
      TransitionPhase phase =
          assertTransition(
              row, Clarification.STATUS_ANSWERED, Clarification.STATUS_REJECTED_INVALID);
      OffsetDateTime now = nowUtc();
      if (phase == TransitionPhase.ALREADY_AT_TARGET) {
        return new ClarificationLifecycleResult(
            row.publicId(), row.workflowRunId(), row.status(), now);
      }
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
    // P30/D2 — pessimistic row lock (SELECT ... FOR UPDATE). Forces serialization of conflicting
    // lifecycle transitions on the same clarification row, eliminating the duplicate-event race
    // between sweep and manual {@code markAccepted}. The caller is always inside an outer
    // {@code @Transactional} (spec-rebuild flow), so the lock is held until the outer commit.
    Clarification visible =
        clarificationReadPort
            .findByPublicId(clarificationPublicId)
            .orElseThrow(
                () -> clarificationNotFound(workflowRunPublicId, clarificationPublicId, "missing"));
    if (!workflowRunPublicId.equals(visible.workflowRunId())) {
      throw clarificationNotFound(workflowRunPublicId, clarificationPublicId, "cross_run");
    }
    Clarification row =
        clarificationReadPort
            .findByPublicIdForUpdate(workflowRunPublicId, clarificationPublicId)
            .orElseThrow(
                () -> clarificationNotFound(workflowRunPublicId, clarificationPublicId, "missing"));
    if (!workflowRunPublicId.equals(row.workflowRunId())) {
      // Trap T11 cross-run leak guard — same shape as missing-row so probes cannot discover
      // existence in a sibling run. NPE-safe comparison order (P11 pattern).
      throw clarificationNotFound(workflowRunPublicId, clarificationPublicId, "cross_run");
    }
    return row;
  }

  /**
   * P5/P15 — classify the attempted transition into one of three branches:
   *
   * <ul>
   *   <li>If the row is already at the target state, return {@link
   *       TransitionPhase#ALREADY_AT_TARGET} so the caller can short-circuit (idempotent replay
   *       during outer-tx retry).
   *   <li>If the row is at the required precursor state, return {@link TransitionPhase#PROCEED}.
   *   <li>If the row is at any other terminal state ({@code isTerminal()}), throw {@link
   *       DomainErrorCode#CLARIFICATION_TERMINAL_STATE} so problem-details surfaces the typed 409
   *       "terminal" mapping that {@link org.dradgo.adapters.rest.ProblemDetailsCatalog} registered
   *       for it (P15).
   *   <li>Otherwise throw {@link DomainErrorCode#ILLEGAL_CLARIFICATION_TRANSITION} (non-terminal
   *       precursor mismatch — e.g. {@code open -> accepted}).
   * </ul>
   */
  private static TransitionPhase assertTransition(
      Clarification row, String required, String target) {
    if (target.equals(row.status())) {
      if (row.isTerminal()) {
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
      log.info(
          "clarification transition idempotent-replay clarificationId={} currentStatus={} target={}",
          row.publicId(),
          row.status(),
          target);
      return TransitionPhase.ALREADY_AT_TARGET;
    }
    if (required.equals(row.status())) {
      return TransitionPhase.PROCEED;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", row.publicId());
    details.put("currentStatus", row.status());
    details.put("attemptedTransition", required + " -> " + target);
    if (row.isTerminal()) {
      log.warn(
          "CLARIFICATION_TERMINAL_STATE clarificationId={} currentStatus={} attemptedTransition={} -> {}",
          row.publicId(),
          row.status(),
          required,
          target);
      throw new DomainException(
          DomainErrorCode.CLARIFICATION_TERMINAL_STATE,
          "Clarification is in terminal state and cannot be re-transitioned: "
              + required
              + " -> "
              + target
              + " (current: "
              + row.status()
              + ")",
          details);
    }
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

  /**
   * P14 — vocabulary check accepts the whole {@link #ALLOWED_NO_EFFECT_REASONS} set, then narrows
   * via the per-method subset. Splitting the check this way keeps the global pattern/shape
   * validation in one place while still preventing cross-method reuse of method-specific tokens.
   */
  private static void requireControlledVocabularyReason(String reason, Set<String> methodSubset) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("noEffectReason required");
    }
    if (!NO_EFFECT_REASON_PATTERN.matcher(reason).matches()) {
      throw new IllegalArgumentException(
          "noEffectReason must match " + NO_EFFECT_REASON_PATTERN.pattern() + ": " + reason);
    }
    if (!ALLOWED_NO_EFFECT_REASONS.contains(reason)) {
      throw new IllegalArgumentException(
          "noEffectReason '"
              + reason
              + "' is not in the allowed vocabulary "
              + ALLOWED_NO_EFFECT_REASONS);
    }
    if (!methodSubset.contains(reason)) {
      throw new IllegalArgumentException(
          "noEffectReason '"
              + reason
              + "' is not allowed for this transition; allowed: "
              + methodSubset);
    }
  }

  // P27 — static for symmetry with other helpers in this class that only use the static logger.
  private static DomainException clarificationNotFound(
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
