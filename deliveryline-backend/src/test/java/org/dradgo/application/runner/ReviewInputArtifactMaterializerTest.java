package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
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
}
