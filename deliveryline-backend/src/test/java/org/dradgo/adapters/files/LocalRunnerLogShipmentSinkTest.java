package org.dradgo.adapters.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 3.7 (Decision D5) — {@link LocalRunnerLogShipmentSink} writes shippable envelopes under a
 * dedicated {@code runner-logs-ingest/} root (distinct from the durable {@code runner-logs/}
 * store), validates the {@code rex_} prefix, and keeps resolved paths inside the ingest root.
 */
class LocalRunnerLogShipmentSinkTest {

  @Test
  void writesEnvelopeUnderDedicatedIngestRoot(@TempDir Path home) {
    LocalRunnerLogShipmentSink sink = new LocalRunnerLogShipmentSink(home.toString());
    byte[] envelope =
        "{\"classification\":\"shareable-redacted\"}\n".getBytes(StandardCharsets.UTF_8);

    sink.write("rex_write0001", envelope);

    Path expected = home.resolve("runner-logs-ingest").resolve("rex_write0001.ndjson");
    assertThat(Files.exists(expected)).isTrue();
    assertThat(expected.toString()).contains("runner-logs-ingest");
    assertThat(sink.ingestRoot().endsWith("runner-logs-ingest")).isTrue();
  }

  @Test
  void overwriteReplacesPreviousEnvelopeAtomically(@TempDir Path home) throws Exception {
    LocalRunnerLogShipmentSink sink = new LocalRunnerLogShipmentSink(home.toString());
    sink.write("rex_write0002", "first\n".getBytes(StandardCharsets.UTF_8));
    sink.write("rex_write0002", "second\n".getBytes(StandardCharsets.UTF_8));

    Path target = home.resolve("runner-logs-ingest").resolve("rex_write0002.ndjson");
    assertThat(Files.readString(target)).isEqualTo("second\n");
    // No stray temp files left behind.
    try (var entries = Files.list(home.resolve("runner-logs-ingest"))) {
      assertThat(entries.map(p -> p.getFileName().toString()))
          .allMatch(name -> name.endsWith(".ndjson"));
    }
  }

  @Test
  void rejectsNonRunnerExecutionPrefix(@TempDir Path home) {
    LocalRunnerLogShipmentSink sink = new LocalRunnerLogShipmentSink(home.toString());

    assertThatThrownBy(() -> sink.write("art_notarex01", new byte[] {1}))
        .isInstanceOf(DomainException.class);
  }
}
