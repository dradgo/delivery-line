package org.dradgo.application.clarification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
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
 * <p>Hooked into {@code ArtifactOperationService.newVersion(parentArtifactId, payloadRef, actor)}
 * AFTER the {@code ARTIFACT_VERSION_CREATED} event is appended AND only when {@code
 * parent.artifactType() == SPEC} (Trap T5 — non-spec lineages have no clarifications to sweep). The
 * sweep runs in the SAME outer transaction as {@code newVersion} so row mutations + lifecycle
 * events commit atomically.
 *
 * <h3>Trap T6 — stub orchestrator (replaced by Epic 3 runner-contracts)</h3>
 *
 * <p>{@link #acknowledgesQuestion(byte[], String)} uses a deterministic substring scan: the new
 * spec's payload bytes are searched for the literal {@code questionId} (case-sensitive — {@code
 * questionId} matches {@code ^[A-Za-z0-9._-]{1,128}$} so case-folding is unnecessary). This is
 * sufficient for fixture-driven testing but NOT shipping-ready: a false positive arises if the spec
 * mentions the questionId in any context (e.g. "Q-AUTH-001 was deferred"). Epic 3's runner result
 * schema will gain a structured {@code clarification_acknowledgements} block and the orchestrator
 * will switch to consuming that. The seam is the {@code acknowledgesQuestion} method.
 *
 * <h3>P32/D5 — payload-read failure is fatal</h3>
 *
 * <p>If the {@code ArtifactPayloadStore} cannot return bytes for the new spec (record missing,
 * storageRef null/blank, or empty bytes), the orchestrator logs ERROR and raises {@code
 * DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE)}. The exception propagates out of the outer {@code
 * ArtifactOperationService.newVersion} transaction, rolling back the new spec version along with
 * the unread clarification sweep. The operator sees the failure loudly rather than every accepted
 * clarification being silently terminal-superseded. Code-review decision D5 (2026-05-25).
 */
@Service
public class ClarificationLifecycleOrchestrator {

  private static final Logger log =
      LoggerFactory.getLogger(ClarificationLifecycleOrchestrator.class);

  private static final String NO_EFFECT_NOT_ADDRESSED = "clarification_not_addressed";

  private final ClarificationReadPort clarificationReadPort;
  private final ClarificationLifecycleService clarificationLifecycleService;
  private final ArtifactRecordPort artifactRecordPort;
  private final ArtifactPayloadStore artifactPayloadStore;

  public ClarificationLifecycleOrchestrator(
      ClarificationReadPort clarificationReadPort,
      ClarificationLifecycleService clarificationLifecycleService,
      ArtifactRecordPort artifactRecordPort,
      ArtifactPayloadStore artifactPayloadStore) {
    this.clarificationReadPort = clarificationReadPort;
    this.clarificationLifecycleService = clarificationLifecycleService;
    this.artifactRecordPort = artifactRecordPort;
    this.artifactPayloadStore = artifactPayloadStore;
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

      // P32/D5 — fatal abort on payload-read failure. The new spec's payload bytes are read ONCE;
      // if the storageRef is missing/blank or the bytes are empty, loadSpecPayload throws a
      // DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE) so the outer newVersion transaction rolls
      // back. The spec rebuild fails loudly; the operator sees the failure rather than every
      // accepted clarification being silently terminal-superseded.
      LoadedSpecArtifact loadedSpec = loadSpecArtifact(newSpecArtifactPublicId);
      byte[] bytes = loadedSpec.payloadBytes();
      Set<String> sweepLineageArtifactIds = lineageArtifactIds(loadedSpec.snapshot());
      int incorporatedCount = 0;
      int supersededCount = 0;
      for (Clarification c : accepted) {
        if (!sweepLineageArtifactIds.contains(c.artifactId())) {
          continue;
        }
        if (acknowledgesQuestion(bytes, c.questionId())) {
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
   * P32/D5 — fatal abort variant. Returns the payload bytes for the new spec or throws {@code
   * DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE)} when the storageRef is null/blank or the payload
   * bytes are empty. The exception propagates out of the outer {@code
   * ArtifactOperationService.newVersion} transaction so the new spec version is rolled back
   * alongside the unread clarification sweep. The previous "silent supersede every accepted
   * clarification with payload_read_failed" behavior is gone.
   */
  private LoadedSpecArtifact loadSpecArtifact(String artifactPublicId) {
    ArtifactRecordSnapshot snapshot =
        artifactRecordPort
            .findByPublicId(artifactPublicId)
            .orElseThrow(
                () -> {
                  Map<String, Object> details = new LinkedHashMap<>();
                  details.put("artifactId", artifactPublicId);
                  details.put("reason", "artifact_record_missing");
                  log.error(
                      "sweepAfterSpecRebuild artifact-record-missing artifactId={}",
                      artifactPublicId);
                  return new DomainException(
                      DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
                      "Spec artifact not found while sweeping clarifications: " + artifactPublicId,
                      details);
                });
    String storageRef = snapshot.storageRef();
    if (storageRef == null || storageRef.isBlank()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("artifactId", artifactPublicId);
      details.put("artifactVersion", snapshot.version());
      details.put("reason", "storage_ref_missing");
      log.error(
          "sweepAfterSpecRebuild storage-ref-missing artifactId={} version={}",
          artifactPublicId,
          snapshot.version());
      throw new DomainException(
          DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
          "Spec artifact has no storageRef while sweeping clarifications: " + artifactPublicId,
          details);
    }
    Optional<byte[]> bytes = artifactPayloadStore.readBytes(storageRef);
    if (bytes.isEmpty()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("artifactId", artifactPublicId);
      details.put("storageRef", storageRef);
      details.put("reason", "payload_bytes_unavailable");
      log.error(
          "sweepAfterSpecRebuild payload-bytes-unavailable artifactId={} storageRef={}",
          artifactPublicId,
          storageRef);
      throw new DomainException(
          DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
          "Spec artifact payload unreadable while sweeping clarifications: " + artifactPublicId,
          details);
    }
    if (bytes.get().length == 0) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("artifactId", artifactPublicId);
      details.put("storageRef", storageRef);
      details.put("reason", "payload_bytes_empty");
      log.error(
          "sweepAfterSpecRebuild payload-bytes-empty artifactId={} storageRef={}",
          artifactPublicId,
          storageRef);
      throw new DomainException(
          DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
          "Spec artifact payload empty while sweeping clarifications: " + artifactPublicId,
          details);
    }
    return new LoadedSpecArtifact(snapshot, bytes.get());
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
   * Trap T6 stub implementation: deterministic substring scan with word-boundary guard. Returns
   * {@code true} when the payload bytes contain the literal {@code questionId} (case-sensitive
   * UTF-8) AND the match is not adjacent to another questionId-charset byte ({@code
   * [A-Za-z0-9._-]}) on either side — so {@code Q-1} does NOT match inside {@code Q-12} and a
   * mention like {@code Q-AUTH-001abc} does NOT match {@code Q-AUTH-001}.
   *
   * <p>Package-private for tests (visible-for-testing).
   *
   * <p>TODO(epic-3-runner-contracts): replace with a structured {@code
   * clarification_acknowledgements} block emitted by the spec runner.
   */
  static boolean acknowledgesQuestion(byte[] payloadBytes, String questionId) {
    if (questionId == null || questionId.isEmpty()) {
      throw new IllegalArgumentException("questionId must be non-empty");
    }
    byte[] needle = questionId.getBytes(StandardCharsets.UTF_8);
    if (needle.length > payloadBytes.length) {
      return false;
    }
    outer:
    for (int i = 0; i <= payloadBytes.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (payloadBytes[i + j] != needle[j]) {
          continue outer;
        }
      }
      if (isQuestionIdByte(byteAt(payloadBytes, i - 1))
          || isQuestionIdByte(byteAt(payloadBytes, i + needle.length))) {
        continue;
      }
      return true;
    }
    return false;
  }

  private static int byteAt(byte[] bytes, int index) {
    if (index < 0 || index >= bytes.length) {
      return -1;
    }
    return bytes[index] & 0xFF;
  }

  private static boolean isQuestionIdByte(int b) {
    if (b < 0) {
      return false;
    }
    return (b >= 'A' && b <= 'Z')
        || (b >= 'a' && b <= 'z')
        || (b >= '0' && b <= '9')
        || b == '.'
        || b == '_'
        || b == '-';
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

  private record LoadedSpecArtifact(ArtifactRecordSnapshot snapshot, byte[] payloadBytes) {}

  public record ClarificationDecision(String clarificationId, String questionId, Outcome outcome) {}

  public enum Outcome {
    INCORPORATED,
    SUPERSEDED,
    SKIPPED_NON_ACCEPTED
  }
}
