package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit coverage for {@link ReviewInputArtifactMaterializer}: an available reference is read from
 * the payload store, redacted at the bundle's classification, and written under {@code input/} at
 * its {@code referencePath}; an unavailable reference (or an unreadable payload) is skipped without
 * failing the dispatch.
 */
class ReviewInputArtifactMaterializerTest {

  private static final String REX = "rex_matz000000001";
  private static final String REF = "artifacts/run_x/art_y/v1/spec.md";

  private ArtifactPayloadStore payloadStore;
  private RedactionPolicyService redaction;
  private RunnerWorkspaceStore workspaceStore;
  private ReviewInputArtifactMaterializer materializer;

  @BeforeEach
  void setUp() {
    payloadStore = mock(ArtifactPayloadStore.class);
    redaction = mock(RedactionPolicyService.class);
    workspaceStore = mock(RunnerWorkspaceStore.class);
    materializer = new ReviewInputArtifactMaterializer(payloadStore, redaction, workspaceStore);
    when(workspaceStore.writeInputArtifact(any(), any(), any())).thenReturn(Path.of("x"));
  }

  @Test
  void materializesAvailableReferenceRedactedIntoInput() {
    String bundle =
        "{\"classification\":\"shareable-redacted\",\"artifactReferences\":["
            + "{\"referenceAvailable\":true,\"referencePath\":\""
            + REF
            + "\"}]}";
    when(payloadStore.readBytes(REF))
        .thenReturn(Optional.of("secret token=abc\nspec body".getBytes(StandardCharsets.UTF_8)));
    RedactionResult result = mock(RedactionResult.class);
    when(result.sanitizedText()).thenReturn("REDACTED spec body");
    when(redaction.redact(any(String.class), eq("shareable-redacted"))).thenReturn(result);

    int count = materializer.materialize(REX, bundle.getBytes(StandardCharsets.UTF_8));

    assertThat(count).isEqualTo(1);
    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    verify(workspaceStore).writeInputArtifact(eq(REX), eq(REF), bytes.capture());
    assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8))
        .isEqualTo("REDACTED spec body");
  }

  @Test
  void skipsUnavailableReferenceAndUnreadablePayload() {
    String bundle =
        "{\"classification\":\"shareable-redacted\",\"artifactReferences\":["
            + "{\"referenceAvailable\":false,\"referencePath\":null},"
            + "{\"referenceAvailable\":true,\"referencePath\":\"artifacts/gone.md\"}]}";
    when(payloadStore.readBytes("artifacts/gone.md")).thenReturn(Optional.empty());

    int count = materializer.materialize(REX, bundle.getBytes(StandardCharsets.UTF_8));

    assertThat(count).isZero();
    verify(workspaceStore, never()).writeInputArtifact(any(), any(), any());
  }

  @Test
  void toleratesUnparseableBundle() {
    assertThat(materializer.materialize(REX, "not json".getBytes(StandardCharsets.UTF_8))).isZero();
    verify(workspaceStore, never()).writeInputArtifact(any(), any(), any());
  }

  /**
   * Regression (run_009f4595…, FIN-41): a JSON artifact was redacted as RAW TEXT before being
   * written to {@code input/}, so the fuzzy heuristics consumed the JSON's own delimiters and
   * truncated the file. Two concrete corruptions this pins:
   *
   * <ol>
   *   <li>the YAML secret-field heuristic matched {@code password:} and swallowed the following
   *       {@code ","} plus the next array element, welding two steps into one;
   *   <li>the entropy heuristic split the single-line document at its FIRST {@code =}, treating the
   *       whole prefix as a "key" (which merely CONTAINED the word password) and the entire
   *       remainder of the file as a high-entropy secret "value" — destroying every later step.
   * </ol>
   *
   * <p>The result no longer parsed as JSON, which in turn defeated the structural secret scan and
   * stranded the run with a bogus {@code runner_secret_leak}. Redacting structurally keeps the
   * document well-formed: a secret VALUE may still be replaced, but delimiters and sibling elements
   * must survive. Uses the REAL redaction engine — mocking it would not have caught this.
   */
  @Test
  void redactsJsonArtifactStructurallySoDelimitersAndSiblingStepsSurvive() throws Exception {
    ReviewInputArtifactMaterializer realMaterializer =
        new ReviewInputArtifactMaterializer(
            payloadStore,
            new RedactionPolicyService(new DataClassificationService()),
            workspaceStore);
    String planRef = "artifacts/run_x/art_plan/v1/plan.json";
    // The offending shape: ONE line of JSON whose steps[] carry security-conscious prose.
    String plan =
        "{\"artifactId\":\"art_plan\",\"artifactType\":\"implementationPlan\",\"steps\":["
            + "\"Smoke user identifier, without password:\","
            + "\"Browser or HTTP client:\","
            + "\"username=<invalid>\","
            + "\"password=<invalid>\"]}";
    String bundle =
        "{\"classification\":\"shareable-redacted\",\"artifactReferences\":["
            + "{\"referenceAvailable\":true,\"referencePath\":\""
            + planRef
            + "\"}]}";
    when(payloadStore.readBytes(planRef))
        .thenReturn(Optional.of(plan.getBytes(StandardCharsets.UTF_8)));

    assertThat(realMaterializer.materialize(REX, bundle.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(1);

    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    verify(workspaceStore).writeInputArtifact(eq(REX), eq(planRef), bytes.capture());
    String written = new String(bytes.getValue(), StandardCharsets.UTF_8);

    JsonNode root = new ObjectMapper().readTree(written); // threw: "Unterminated string"
    JsonNode steps = root.get("steps");
    assertThat(steps).isNotNull();
    assertThat(steps).hasSize(4); // was 3 — `","Browser…` got eaten into the previous element
    assertThat(steps.get(0).asText()).isEqualTo("Smoke user identifier, without password:");
    assertThat(steps.get(1).asText()).isEqualTo("Browser or HTTP client:");
    // The trailing steps survived rather than being swallowed as one giant "secret value".
    assertThat(steps.get(3).asText()).startsWith("password=");
  }
}
