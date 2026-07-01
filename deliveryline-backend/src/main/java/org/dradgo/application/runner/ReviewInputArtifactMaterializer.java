package org.dradgo.application.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.DataClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 3d-2/3f-4 (2026-07-01 review-materialization fix) — materializes the artifact content a
 * REVIEW/split dispatch references into the runner's mounted {@code input/} directory.
 *
 * <p>The review/split context bundle references the reviewed artifact BY PATH only ({@code
 * ContextBundleService.assembleForReview}: "the reviewed artifact is the SOLE reference; the runner
 * reads its (already-redacted) content from the mounted input dir"). But the dispatch wrote only
 * {@code context-bundle.v1.json} to {@code input/}, so the runner saw a dangling {@code
 * referencePath} and emitted a blocking-defect fail-verdict — which then failed the {@code
 * split-proposal.v1} contract ({@code RUNNER_CONTRACT_VIOLATION}) or left the advisory reviewer
 * "unavailable". This service reads each {@code referenceAvailable} reference's bytes from the
 * artifact payload store, runs them through the redaction pass at the bundle's claimed
 * classification (honouring the "already-redacted" contract before the bytes cross into the
 * container), and writes them under {@code input/} at the same {@code referencePath} the bundle
 * advertises.
 *
 * <p>Best-effort per reference: a missing/unreadable payload is logged and skipped (the harvest
 * still degrades gracefully) rather than failing the whole dispatch. NEVER logs artifact content —
 * only the reference path + a byte count.
 */
@Component
public class ReviewInputArtifactMaterializer {

  private static final Logger log = LoggerFactory.getLogger(ReviewInputArtifactMaterializer.class);

  private final ArtifactPayloadStore artifactPayloadStore;
  private final RedactionPolicyService redactionPolicyService;
  private final RunnerWorkspaceStore workspaceStore;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public ReviewInputArtifactMaterializer(
      ArtifactPayloadStore artifactPayloadStore,
      RedactionPolicyService redactionPolicyService,
      RunnerWorkspaceStore workspaceStore) {
    this.artifactPayloadStore =
        Objects.requireNonNull(artifactPayloadStore, "artifactPayloadStore");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
  }

  /**
   * Materialize every {@code referenceAvailable} artifact reference in the (already-written) input
   * bundle into {@code input/<referencePath>}. Returns the number of references materialized.
   */
  public int materialize(String runnerExecutionId, byte[] bundleBytes) {
    if (bundleBytes == null || bundleBytes.length == 0) {
      return 0;
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(bundleBytes);
    } catch (java.io.IOException parseError) {
      log.warn(
          "review artifact materialize skipped runnerExecutionId={} reason=bundle_unparseable",
          runnerExecutionId);
      return 0;
    }
    String classification =
        root.path("classification").asText(DataClassification.SHAREABLE_REDACTED.value());

    List<JsonNode> references = new ArrayList<>();
    JsonNode artifactRefs = root.get("artifactReferences");
    if (artifactRefs != null && artifactRefs.isArray()) {
      artifactRefs.forEach(references::add);
    }
    // The singular reference slots (spec/plan) are the SAME shape; materialize them too so any
    // review/execution bundle that leans on them also finds the content in the mounted input dir.
    addIfObject(references, root.get("approvedSpecificationReference"));
    addIfObject(references, root.get("approvedImplementationPlanReference"));

    Set<String> seen = new LinkedHashSet<>();
    int materialized = 0;
    for (JsonNode ref : references) {
      if (ref == null || !ref.isObject()) {
        continue;
      }
      if (!ref.path("referenceAvailable").asBoolean(false)) {
        continue;
      }
      JsonNode pathNode = ref.get("referencePath");
      if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) {
        continue;
      }
      String referencePath = pathNode.asText();
      if (!seen.add(referencePath)) {
        continue;
      }
      Optional<byte[]> payload = artifactPayloadStore.readBytes(referencePath);
      if (payload.isEmpty()) {
        log.warn(
            "review artifact payload unreadable runnerExecutionId={} referencePath={} — skipped",
            runnerExecutionId,
            referencePath);
        continue;
      }
      String redacted =
          redactionPolicyService
              .redact(new String(payload.get(), StandardCharsets.UTF_8), classification)
              .sanitizedText();
      workspaceStore.writeInputArtifact(
          runnerExecutionId, referencePath, redacted.getBytes(StandardCharsets.UTF_8));
      materialized++;
    }
    log.info(
        "review input artifacts materialized runnerExecutionId={} count={}",
        runnerExecutionId,
        materialized);
    return materialized;
  }

  private static void addIfObject(List<JsonNode> target, JsonNode node) {
    if (node != null && node.isObject()) {
      target.add(node);
    }
  }
}
