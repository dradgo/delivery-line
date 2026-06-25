package org.dradgo.application.clarification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.SpecClarificationAcknowledgementReadPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sweeps {@code accepted} clarifications attached to a workflow run after a new SPEC artifact
 * version has been persisted, calling {@link ClarificationLifecycleService#markIncorporated} when
 * the new spec acknowledges the question, or {@link ClarificationLifecycleService#markSuperseded}
 * when it does not (story 2.12 AC4).
 *
 * <p>Hooked into {@code ArtifactOperationService.markAvailable(...)} (NOT {@code newVersion} — the
 * sweep needs the spec to be AVAILABLE so its lineage is final) AFTER the {@code
 * artifact.available} event is appended AND only when {@code artifact.artifactType() == SPEC} (Trap
 * T5 — non-spec lineages have no clarifications to sweep). The sweep runs in the SAME outer
 * transaction as {@code markAvailable} so row mutations + lifecycle events commit atomically.
 * (Story 3e-2 corrected the stale "hooked into newVersion" javadoc — that was always the
 * documented-but-wrong hook point.)
 *
 * <h3>Story 3e-2 — structured acknowledgements oracle</h3>
 *
 * <p>A question is "addressed" iff the rebuilt spec's structured {@code
 * specArtifact.clarificationAcknowledgements} carried {@code addressed:true} for that questionId.
 * The runner emits those acknowledgements; the broker persists them at ingest into the V25 {@code
 * spec_clarification_acknowledgements} side-store keyed by the spec artifactId (the sweep sees only
 * the artifact, not the runner result); {@link #sweepAfterSpecRebuild} reads them via {@link
 * SpecClarificationAcknowledgementReadPort}. This replaced 3e-1's brittle substring-scan stub (a
 * spec merely *mentioning* a questionId no longer counts as addressing it). An ABSENT
 * acknowledgement (the runner never mentioned the question) OR an explicit {@code addressed:false}
 * both supersede.
 *
 * <h3>Fail-loud on a missing spec record</h3>
 *
 * <p>If the new spec artifact RECORD cannot be found (needed for the lineage parent-walk), the
 * orchestrator logs ERROR and raises {@code DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE)}, which
 * propagates out of the outer {@code markAvailable} transaction and rolls back the new spec version
 * alongside the unread sweep — the operator sees the failure loudly rather than every accepted
 * clarification being silently terminal-superseded. (The payload BYTES are no longer read — the
 * oracle is structured, not a substring scan over the spec text.)
 */
@Service
public class ClarificationLifecycleOrchestrator {

  private static final Logger log =
      LoggerFactory.getLogger(ClarificationLifecycleOrchestrator.class);

  private static final String NO_EFFECT_NOT_ADDRESSED = "clarification_not_addressed";

  private final ClarificationReadPort clarificationReadPort;
  private final ClarificationLifecycleService clarificationLifecycleService;
  private final ArtifactRecordPort artifactRecordPort;
  // Story 3e-2 (AC6) — structured spec-runner acknowledgements persisted at ingest (V25
  // side-store),
  // replacing the substring-scan stub over the spec payload bytes.
  private final SpecClarificationAcknowledgementReadPort acknowledgementReadPort;

  public ClarificationLifecycleOrchestrator(
      ClarificationReadPort clarificationReadPort,
      ClarificationLifecycleService clarificationLifecycleService,
      ArtifactRecordPort artifactRecordPort,
      SpecClarificationAcknowledgementReadPort acknowledgementReadPort) {
    this.clarificationReadPort = clarificationReadPort;
    this.clarificationLifecycleService = clarificationLifecycleService;
    this.artifactRecordPort = artifactRecordPort;
    this.acknowledgementReadPort = acknowledgementReadPort;
  }

  public LifecycleSweepResult sweepAfterSpecRebuild(
      String workflowRunPublicId,
      String newSpecArtifactPublicId,
      int newSpecArtifactVersion,
      ActorContext actor) {
    // P23 — prefix validation at the orchestrator entry so a blank/whitespace id can't silently
    // turn into an empty-list "no-op sweep" that bypasses the clarification audit trail.
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(newSpecArtifactPublicId, PublicIdPrefixes.ARTIFACT);
    String priorMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "sweepAfterSpecRebuild entry workflowRunId={} newSpecArtifactId={} newSpecArtifactVersion={}",
          workflowRunPublicId,
          newSpecArtifactPublicId,
          newSpecArtifactVersion);

      List<Clarification> all = clarificationReadPort.listByWorkflowRunId(workflowRunPublicId);
      List<Clarification> accepted = new ArrayList<>();
      for (Clarification c : all) {
        if (Clarification.STATUS_ACCEPTED.equals(c.status())) {
          accepted.add(c);
        }
      }
      List<ClarificationDecision> decisions = new ArrayList<>(accepted.size());

      if (accepted.isEmpty()) {
        log.info(
            "sweepAfterSpecRebuild exit workflowRunId={} consideredCount=0 incorporatedCount=0 supersededCount=0",
            workflowRunPublicId);
        return new LifecycleSweepResult(0, 0, 0, List.copyOf(decisions));
      }

      // Story 3e-2 (AC6) — load the new spec artifact RECORD (for the lineage parent-walk). A
      // missing record is a loud failure (ARTIFACT_PAYLOAD_UNAVAILABLE rolls back the outer
      // markAvailable transaction), preserving the prior fail-loud posture. The payload BYTES are
      // no
      // longer read: the oracle is now the structured acknowledgements side-store, not a substring
      // scan over the spec text.
      ArtifactRecordSnapshot newSpec =
          artifactRecordPort
              .findByPublicId(newSpecArtifactPublicId)
              .orElseThrow(() -> specArtifactMissing(newSpecArtifactPublicId));
      Set<String> sweepLineageArtifactIds = lineageArtifactIds(newSpec);
      // Story 3e-2 (AC6/R5) — the acknowledgements the spec runner emitted, persisted at broker
      // ingest keyed by this spec artifactId (the sweep sees only the artifact, not the runner
      // result). addressed:true => incorporated; absent OR addressed:false => not-addressed =>
      // superseded.
      Map<String, Boolean> addressedByQuestionId = new HashMap<>();
      for (SpecClarificationAcknowledgement ack :
          acknowledgementReadPort.findBySpecArtifactPublicId(newSpecArtifactPublicId)) {
        addressedByQuestionId.put(ack.questionId(), ack.addressed());
      }
      log.info(
          "sweepAfterSpecRebuild acknowledgements newSpecArtifactId={} acknowledgementCount={}",
          newSpecArtifactPublicId,
          addressedByQuestionId.size());
      int incorporatedCount = 0;
      int supersededCount = 0;
      for (Clarification c : accepted) {
        if (!sweepLineageArtifactIds.contains(c.artifactId())) {
          continue;
        }
        if (wasAddressed(addressedByQuestionId, c.questionId())) {
          // P9 — pass the artifact version directly so the lifecycle service does NOT re-fetch
          // the artifact record on each loop iteration (was N×4 round trips for a sweep over N
          // accepted clarifications).
          clarificationLifecycleService.markIncorporated(
              workflowRunPublicId,
              c.publicId(),
              newSpecArtifactPublicId,
              newSpecArtifactVersion,
              actor);
          decisions.add(
              new ClarificationDecision(c.publicId(), c.questionId(), Outcome.INCORPORATED));
          incorporatedCount++;
        } else {
          clarificationLifecycleService.markSuperseded(
              workflowRunPublicId,
              c.publicId(),
              newSpecArtifactPublicId,
              newSpecArtifactVersion,
              NO_EFFECT_NOT_ADDRESSED,
              actor);
          decisions.add(
              new ClarificationDecision(c.publicId(), c.questionId(), Outcome.SUPERSEDED));
          supersededCount++;
        }
      }
      log.info(
          "sweepAfterSpecRebuild exit workflowRunId={} consideredCount={} incorporatedCount={} supersededCount={}",
          workflowRunPublicId,
          accepted.size(),
          incorporatedCount,
          supersededCount);
      return new LifecycleSweepResult(
          accepted.size(), incorporatedCount, supersededCount, List.copyOf(decisions));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorMdc);
    }
  }

  /**
   * Story 3e-2 — the new spec artifact record must exist for the lineage parent-walk; a missing
   * record is a loud failure (mirrors the prior payload-unavailable fail-loud posture, P32/D5). The
   * {@code DomainException} propagates out of the outer {@code markAvailable} transaction so the
   * new spec version is rolled back alongside the unread sweep rather than silently superseding
   * every accepted clarification.
   */
  private DomainException specArtifactMissing(String artifactPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", artifactPublicId);
    details.put("reason", "artifact_record_missing");
    log.error("sweepAfterSpecRebuild artifact-record-missing artifactId={}", artifactPublicId);
    return new DomainException(
        DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
        "Spec artifact not found while sweeping clarifications: " + artifactPublicId,
        details);
  }

  /**
   * Story 3e-2 (AC6) — the structured oracle that replaced the substring-scan stub: a question is
   * "addressed" only when the rebuilt spec's acknowledgements carry an explicit {@code
   * addressed:true} for that questionId. An absent acknowledgement (the runner never mentioned it)
   * OR an explicit {@code addressed:false} both mean not-addressed => superseded.
   */
  private static boolean wasAddressed(
      Map<String, Boolean> addressedByQuestionId, String questionId) {
    return Boolean.TRUE.equals(addressedByQuestionId.get(questionId));
  }

  private Set<String> lineageArtifactIds(ArtifactRecordSnapshot latest) {
    Set<String> lineage = new LinkedHashSet<>();
    ArtifactRecordSnapshot cursor = latest;
    while (cursor != null && lineage.add(cursor.publicId())) {
      String parentArtifactId = cursor.parentArtifactId();
      if (parentArtifactId == null || parentArtifactId.isBlank()) {
        break;
      }
      lineage.add(parentArtifactId);
      cursor = artifactRecordPort.findByPublicId(parentArtifactId).orElse(null);
    }
    return Set.copyOf(lineage);
  }

  /**
   * P26 — pre-computed {@code incorporatedCount} / {@code supersededCount} on the result so
   * downstream loggers (e.g. {@code ArtifactOperationService.newVersion}) don't repeat the
   * filter+count passes over {@code decisions()}.
   */
  public record LifecycleSweepResult(
      int consideredCount,
      int incorporatedCount,
      int supersededCount,
      List<ClarificationDecision> decisions) {}

  public record ClarificationDecision(String clarificationId, String questionId, Outcome outcome) {}

  public enum Outcome {
    INCORPORATED,
    SUPERSEDED,
    SKIPPED_NON_ACCEPTED
  }
}
