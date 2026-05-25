package org.dradgo.application.clarification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.observability.MdcKeys;
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
 * sufficient for fixture-driven testing but NOT shipping-ready: a false positive arises if the
 * spec mentions the questionId in any context (e.g. "Q-AUTH-001 was deferred"). Epic 3's runner
 * result schema will gain a structured {@code clarification_acknowledgements} block and the
 * orchestrator will switch to consuming that. The seam is the {@code acknowledgesQuestion} method.
 *
 * <h3>OQ-5 — payload-read failure handling</h3>
 *
 * <p>If the {@code ArtifactPayloadStore} cannot return bytes for the new spec, the orchestrator
 * logs WARN and marks each {@code accepted} clarification {@code superseded} with {@code
 * noEffectReason = payload_read_failed}. This keeps the new spec version persisted (the
 * clarification-side failure does not punish the spec rebuild) while preserving the
 * make-or-break invariant (every answered clarification must end with a downstream lifecycle
 * event).
 */
@Service
public class ClarificationLifecycleOrchestrator {

  private static final Logger log =
      LoggerFactory.getLogger(ClarificationLifecycleOrchestrator.class);

  private static final String NO_EFFECT_NOT_ADDRESSED = "clarification_not_addressed";
  private static final String NO_EFFECT_PAYLOAD_READ_FAILED = "payload_read_failed";

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
        return new LifecycleSweepResult(0, List.copyOf(decisions));
      }

      // Read the new spec's payload bytes ONCE; the sweep tests every accepted clarification
      // against the same content. OQ-5 — on read failure, every accepted clarification is
      // marked superseded with `payload_read_failed`.
      Optional<byte[]> payloadBytes = loadSpecPayload(newSpecArtifactPublicId);
      if (payloadBytes.isEmpty()) {
        log.warn(
            "sweepAfterSpecRebuild payload-read-failed workflowRunId={} newSpecArtifactId={} acceptedCount={}",
            workflowRunPublicId,
            newSpecArtifactPublicId,
            accepted.size());
        for (Clarification c : accepted) {
          clarificationLifecycleService.markSuperseded(
              workflowRunPublicId,
              c.publicId(),
              newSpecArtifactPublicId,
              NO_EFFECT_PAYLOAD_READ_FAILED,
              actor);
          decisions.add(
              new ClarificationDecision(c.publicId(), c.questionId(), Outcome.SUPERSEDED));
        }
        log.info(
            "sweepAfterSpecRebuild exit workflowRunId={} consideredCount={} incorporatedCount=0 supersededCount={}",
            workflowRunPublicId,
            accepted.size(),
            accepted.size());
        return new LifecycleSweepResult(accepted.size(), List.copyOf(decisions));
      }

      byte[] bytes = payloadBytes.get();
      int incorporatedCount = 0;
      int supersededCount = 0;
      for (Clarification c : accepted) {
        if (acknowledgesQuestion(bytes, c.questionId())) {
          clarificationLifecycleService.markIncorporated(
              workflowRunPublicId, c.publicId(), newSpecArtifactPublicId, actor);
          decisions.add(
              new ClarificationDecision(c.publicId(), c.questionId(), Outcome.INCORPORATED));
          incorporatedCount++;
        } else {
          clarificationLifecycleService.markSuperseded(
              workflowRunPublicId,
              c.publicId(),
              newSpecArtifactPublicId,
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
      return new LifecycleSweepResult(accepted.size(), List.copyOf(decisions));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorMdc);
    }
  }

  private Optional<byte[]> loadSpecPayload(String artifactPublicId) {
    Optional<ArtifactRecordSnapshot> snapshot = artifactRecordPort.findByPublicId(artifactPublicId);
    if (snapshot.isEmpty()) {
      log.warn(
          "sweepAfterSpecRebuild artifact-record-missing artifactId={}", artifactPublicId);
      return Optional.empty();
    }
    String storageRef = snapshot.get().storageRef();
    if (storageRef == null || storageRef.isBlank()) {
      log.warn(
          "sweepAfterSpecRebuild storage-ref-missing artifactId={} version={}",
          artifactPublicId,
          snapshot.get().version());
      return Optional.empty();
    }
    Optional<byte[]> bytes = artifactPayloadStore.readBytes(storageRef);
    if (bytes.isEmpty()) {
      log.warn(
          "sweepAfterSpecRebuild payload-bytes-unavailable artifactId={} storageRef={}",
          artifactPublicId,
          storageRef);
    }
    return bytes;
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

  public record LifecycleSweepResult(int consideredCount, List<ClarificationDecision> decisions) {}

  public record ClarificationDecision(String clarificationId, String questionId, Outcome outcome) {}

  public enum Outcome {
    INCORPORATED,
    SUPERSEDED,
    SKIPPED_NON_ACCEPTED
  }
}
