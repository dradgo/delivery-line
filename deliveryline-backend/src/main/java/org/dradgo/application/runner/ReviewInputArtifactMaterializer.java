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
      String content = new String(payload.get(), StandardCharsets.UTF_8);
      workspaceStore.writeInputArtifact(
          runnerExecutionId, referencePath, redact(content, classification));
      materialized++;
    }
    log.info(
        "review input artifacts materialized runnerExecutionId={} count={}",
        runnerExecutionId,
        materialized);
    return materialized;
  }

  /**
   * Redact one artifact payload before its bytes cross into the container. A JSON artifact MUST be
   * redacted structurally: the raw-text heuristics are line- and delimiter-oriented, so on a
   * single-line JSON document they consume the document's own punctuation — the YAML secret-field
   * rule swallows the {@code ","} separating two array elements, and the entropy rule splits at the
   * first {@code =} and treats the whole remainder of the line as one secret "value", truncating
   * the file. The result stopped parsing as JSON, which defeated the structural secret scan
   * downstream and stranded the run on a bogus {@code runner_secret_leak} (run_009f4595…).
   * Structural redaction rewrites string VALUES in place, so a real secret is still replaced while
   * delimiters and sibling elements survive. Non-JSON content (e.g. an authored {@code spec.md})
   * keeps the raw-text pass.
   */
  private byte[] redact(String content, String classification) {
    JsonNode structured = tryParseJsonContainer(content);
    String redacted =
        structured != null
            ? redactionPolicyService.redact(structured, classification).sanitizedText()
            : redactionPolicyService.redact(content, classification).sanitizedText();
    return redacted.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Parse {@code content} as a JSON object/array. Gated on a leading {@code '{'}/{@code '['} so
   * ordinary prose is never probed, and tolerant of malformed input (a parse failure returns {@code
   * null}, routing the payload to the raw-text pass). Mirrors {@code
   * RunnerSecretScanService.tryParseJsonContainer} — the scan that re-reads these very bytes.
   */
  private JsonNode tryParseJsonContainer(String content) {
    String trimmed = content.stripLeading();
    if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(content);
      return node != null && node.isContainerNode() ? node : null;
    } catch (com.fasterxml.jackson.core.JacksonException parseFailure) {
      return null;
    }
  }

  private static void addIfObject(List<JsonNode> target, JsonNode node) {
    if (node != null && node.isObject()) {
      target.add(node);
    }
  }
}
