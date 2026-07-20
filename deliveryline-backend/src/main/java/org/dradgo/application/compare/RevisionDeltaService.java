package org.dradgo.application.compare;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactCompareSource;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.19 (AC1) — the Compare Mode backend. Computes a typed {@link RevisionDelta} between two
 * artifact versions of one lineage (spec / implementationPlan / prOutput), backing UX-DR13.
 *
 * <p><strong>Collaborators (AC9):</strong> ONLY the (extended) {@link ArtifactService} and {@link
 * RedactionPolicyService} are injected. All persistence access is via {@code ArtifactService}; JSON
 * payload parsing lives in the java-only {@link ComparePayloads} helper; the three diff algorithms
 * are pure classes instantiated here (never injected, never persistence-aware).
 *
 * <p>Direction: {@code artifactIdA} is baseline/prior, {@code artifactIdB} is target/current;
 * {@code changeKind}s are computed B-relative-to-A and the ids are honored as-is (no auto-swap).
 */
@Service
public class RevisionDeltaService {

  private static final Logger log = LoggerFactory.getLogger(RevisionDeltaService.class);

  private final ArtifactService artifactService;
  private final RedactionPolicyService redactionPolicyService;

  // Pure, stateless diff algorithms — instantiated (not injected) so the injected collaborator
  // surface stays ArtifactService + RedactionPolicyService (AC9).
  private final MarkdownSectionDiffer markdownDiffer = new DefaultMarkdownSectionDiffer();
  private final PlanStepDiffer planDiffer = new DefaultPlanStepDiffer();
  private final FileLevelDiffer fileDiffer = new DefaultFileLevelDiffer();

  public RevisionDeltaService(
      ArtifactService artifactService, RedactionPolicyService redactionPolicyService) {
    this.artifactService = artifactService;
    this.redactionPolicyService = redactionPolicyService;
  }

  /**
   * Computes the delta between two artifact ids of the same lineage. Both must be {@code AVAILABLE}
   * (else {@code ARTIFACT_PAYLOAD_UNAVAILABLE}); both must belong to one lineage — same {@code
   * (workflowRunId, artifactType)} AND connected by the {@code parent_artifact_id} chain — else
   * {@code ARTIFACT_LINEAGE_MISMATCH}. A malformed id raises {@code INVALID_ID_PREFIX}; an unknown
   * id raises {@code ARTIFACT_RECORD_NOT_FOUND}.
   */
  @Transactional(readOnly = true)
  public RevisionDelta computeDelta(String artifactIdA, String artifactIdB) {
    long startNanos = System.nanoTime();
    log.info(
        "computeDelta received artifactIdA={} artifactIdB={}",
        MdcKeys.sanitizeForLog(artifactIdA),
        MdcKeys.sanitizeForLog(artifactIdB));

    ArtifactCompareSource a = artifactService.loadCompareSource(artifactIdA);
    ArtifactCompareSource b = artifactService.loadCompareSource(artifactIdB);
    requireSameLineage(a.snapshot(), b.snapshot(), artifactIdA, artifactIdB);

    ArtifactType type = a.snapshot().artifactType();
    ArtifactSummary revisionA = toSummary(a);
    ArtifactSummary revisionB = toSummary(b);

    boolean checksumEqual = checksumsEqual(a.snapshot(), b.snapshot());
    List<ChangeBlock> changes;
    List<String> linkedDiffReferences = null;
    if (checksumEqual) {
      changes = List.of();
    } else if (type == ArtifactType.SPEC) {
      changes =
          redactMarkdown(
              markdownDiffer.diff(
                  ComparePayloads.markdownBody(a.payloadBytes()),
                  ComparePayloads.markdownBody(b.payloadBytes())));
    } else if (type == ArtifactType.IMPLEMENTATION_PLAN) {
      changes =
          redactPlan(
              planDiffer.diff(
                  ComparePayloads.parseSteps(a.payloadBytes()),
                  ComparePayloads.parseSteps(b.payloadBytes())));
    } else if (type == ArtifactType.PR_OUTPUT) {
      changes =
          new ArrayList<>(
              fileDiffer.diff(
                  ComparePayloads.prOutputDiff(a.payloadBytes()),
                  ComparePayloads.prOutputDiff(b.payloadBytes())));
      // Always populate so the UI (4.20) lazy-loads the full diff via the per-artifact read even
      // when the file-level summary is empty (diff absent — Reconciliation 8).
      linkedDiffReferences = List.of(artifactIdA, artifactIdB);
    } else {
      throw new IllegalStateException("Unhandled artifactType " + type.value());
    }

    // Byte-equal ⇒ no meaningful diff. Otherwise a *positive* whitespace-only test per type — an
    // empty change list alone is NOT enough, because a spec section reorder (differ aligns by
    // heading, order-blind) or two different-but-unparseable plan payloads (parse failure swallowed
    // to an empty step list) also yield zero blocks yet are genuine differences. For prOutput an
    // empty summary never implies "no change" (the diff is frequently absent — Reconciliation 8).
    boolean noMeaningfulDiff;
    if (checksumEqual) {
      noMeaningfulDiff = true;
    } else if (type == ArtifactType.SPEC) {
      // Order-preserving whitespace-only equality — a reorder changes line order ⇒ false.
      noMeaningfulDiff =
          markdownDiffer.isWhitespaceOnlyDifference(
              ComparePayloads.markdownBody(a.payloadBytes()),
              ComparePayloads.markdownBody(b.payloadBytes()));
    } else if (type == ArtifactType.IMPLEMENTATION_PLAN) {
      // Zero blocks means identical step sequences (a reorder emits blocks), BUT only count it as
      // no-meaningful-diff when both payloads actually parsed — else a swallowed parse failure
      // would
      // masquerade as equivalence.
      noMeaningfulDiff =
          changes.isEmpty()
              && ComparePayloads.stepsPayloadParseable(a.payloadBytes())
              && ComparePayloads.stepsPayloadParseable(b.payloadBytes());
    } else {
      noMeaningfulDiff = false;
    }
    DeltaSummary summary = summarize(changes);

    RevisionDelta delta =
        new RevisionDelta(
            type.value(),
            revisionA,
            revisionB,
            summary,
            List.copyOf(changes),
            noMeaningfulDiff,
            linkedDiffReferences);
    log.info(
        "computeDelta success artifactIdA={} artifactIdB={} artifactType={} changedRegionCount={}"
            + " noMeaningfulDiff={} durationMs={}",
        MdcKeys.sanitizeForLog(artifactIdA),
        MdcKeys.sanitizeForLog(artifactIdB),
        type.value(),
        summary.changedRegionCount(),
        noMeaningfulDiff,
        (System.nanoTime() - startNanos) / 1_000_000);
    return delta;
  }

  private void requireSameLineage(
      ArtifactRecordSnapshot a, ArtifactRecordSnapshot b, String idA, String idB) {
    if (!a.workflowRunId().equals(b.workflowRunId()) || a.artifactType() != b.artifactType()) {
      throw lineageMismatch(idA, idB, "different_run_or_type");
    }
    // version is unique per (workflowRunId, artifactType), so higher/lower is well-defined; walk
    // the
    // parent chain from the higher-version snapshot — the other must appear on it (Reconciliation
    // 2).
    ArtifactRecordSnapshot higher = a.version() >= b.version() ? a : b;
    String targetId = higher == a ? b.publicId() : a.publicId();
    if (!isReachableByParentWalk(higher, targetId)) {
      throw lineageMismatch(idA, idB, "not_on_parent_chain");
    }
  }

  private boolean isReachableByParentWalk(ArtifactRecordSnapshot start, String targetId) {
    Set<String> visited = new HashSet<>();
    ArtifactRecordSnapshot cursor = start;
    while (cursor != null) {
      if (cursor.publicId().equals(targetId)) {
        return true;
      }
      if (!visited.add(cursor.publicId())) {
        break; // cycle guard (defense-in-depth; schema forbids real cycles)
      }
      String parentId = cursor.parentArtifactId();
      if (parentId == null || parentId.isBlank()) {
        break;
      }
      cursor = artifactService.findSnapshot(parentId).orElse(null);
    }
    return false;
  }

  private ArtifactSummary toSummary(ArtifactCompareSource source) {
    ArtifactRecordSnapshot snapshot = source.snapshot();
    return new ArtifactSummary(
        snapshot.version(),
        snapshot.createdAt(),
        source.producedByActor(),
        shortChecksum(snapshot.checksumAlgorithm(), snapshot.checksumValue()));
  }

  private static boolean checksumsEqual(ArtifactRecordSnapshot a, ArtifactRecordSnapshot b) {
    return a.checksumValue() != null && a.checksumValue().equalsIgnoreCase(b.checksumValue());
  }

  private List<ChangeBlock> redactMarkdown(List<MarkdownChangeBlock> blocks) {
    List<ChangeBlock> redacted = new ArrayList<>(blocks.size());
    for (MarkdownChangeBlock block : blocks) {
      redacted.add(
          new MarkdownChangeBlock(
              block.sectionPath(),
              block.changeKind(),
              redact(block.priorText()),
              redact(block.currentText())));
    }
    return redacted;
  }

  private List<ChangeBlock> redactPlan(List<PlanStepChangeBlock> blocks) {
    List<ChangeBlock> redacted = new ArrayList<>(blocks.size());
    for (PlanStepChangeBlock block : blocks) {
      redacted.add(
          new PlanStepChangeBlock(
              block.stepId(),
              block.changeKind(),
              redact(block.priorStepText()),
              redact(block.currentStepText()),
              block.priorStepOrder(),
              block.currentStepOrder()));
    }
    return redacted;
  }

  /** Defense-in-depth redaction (AC6): {@code redact(...)}, NEVER {@code redactForExport(...)}. */
  private String redact(String text) {
    if (text == null) {
      return null;
    }
    return redactionPolicyService
        .redact(text, DataClassification.SHAREABLE_REDACTED.value())
        .sanitizedText();
  }

  private static DeltaSummary summarize(List<ChangeBlock> changes) {
    int added = 0;
    int removed = 0;
    int modified = 0;
    for (ChangeBlock change : changes) {
      switch (change.changeKind()) {
        case ChangeKind.ADDED -> added++;
        case ChangeKind.REMOVED -> removed++;
        case ChangeKind.MODIFIED -> modified++;
        default -> {
          // reordered — counted only in changedRegionCount.
        }
      }
    }
    return new DeltaSummary(changes.size(), added, removed, modified);
  }

  /** Short-form checksum {@code <algorithm>:<first 12 hex>}; never the full digest. */
  private static String shortChecksum(String algorithm, String value) {
    if (algorithm == null || value == null || value.isBlank()) {
      return null;
    }
    String canonical = ArtifactChecksum.canonicalAlgorithm(algorithm);
    String head = value.length() <= 12 ? value : value.substring(0, 12);
    return canonical + ":" + head;
  }

  private static DomainException lineageMismatch(String idA, String idB, String reason) {
    log.warn(
        "computeDelta lineage mismatch artifactIdA={} artifactIdB={} reason={}",
        MdcKeys.sanitizeForLog(idA),
        MdcKeys.sanitizeForLog(idB),
        reason);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactIdA", idA);
    details.put("artifactIdB", idB);
    details.put("reason", reason);
    return new DomainException(
        DomainErrorCode.ARTIFACT_LINEAGE_MISMATCH,
        "Artifacts do not belong to the same lineage",
        details);
  }
}
