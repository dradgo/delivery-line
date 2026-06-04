package org.dradgo.adapters.files;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.dradgo.application.runner.spi.RunnerLogShipmentSink;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Story 3.7 (Decision D5) — filesystem implementation of {@link RunnerLogShipmentSink}. Writes the
 * already-redacted, already-policy-checked NDJSON envelope to {@code
 * {deliveryline.home}/runner-logs-ingest/{runnerExecutionId}.ndjson}, which the {@code logstash}
 * compose service mounts read-only as its file input (see {@code docker-compose.yml}).
 *
 * <p>The ingest root is DISTINCT from {@code runner-logs/} (the durable redacted store, story 3.6):
 * only shippable documents ever land here, so the Logstash file input is fail-closed by
 * construction — it can never see a {@code local-only} log. Writes are atomic (temp + rename) so
 * the file input never observes a partial document. Containment guards mirror {@link
 * LocalRunnerLogStore}: the {@code rex_} prefix is validated and the resolved path must stay under
 * the ingest root.
 */
@Component
public class LocalRunnerLogShipmentSink implements RunnerLogShipmentSink {

  private static final Logger log = LoggerFactory.getLogger(LocalRunnerLogShipmentSink.class);

  static final String INGEST_ROOT_SUBDIR = "runner-logs-ingest";
  private static final String FILE_SUFFIX = ".ndjson";
  private static final String TEMP_SUFFIX = ".tmp";

  private final Path ingestRoot;

  public LocalRunnerLogShipmentSink(@Value("${deliveryline.home}") String deliverylineHome) {
    if (deliverylineHome == null || deliverylineHome.trim().isEmpty()) {
      throw new IllegalArgumentException("deliveryline.home must be configured");
    }
    Path normalized = Path.of(deliverylineHome.trim()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(normalized);
      Path home = normalized.toRealPath();
      Path root = home.resolve(INGEST_ROOT_SUBDIR);
      Files.createDirectories(root);
      this.ingestRoot = root.toAbsolutePath().normalize().toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "runner-logs-ingest root could not be created or resolved", error);
    }
  }

  @Override
  public void write(String runnerExecutionId, byte[] envelopeNdjson) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    byte[] bytes = envelopeNdjson == null ? new byte[0] : envelopeNdjson;
    Path target = ingestRoot.resolve(runnerExecutionId + FILE_SUFFIX).normalize();
    if (!target.startsWith(ingestRoot)) {
      throw new IllegalStateException(
          "Resolved runner-logs-ingest path escapes deliveryline.home for " + runnerExecutionId);
    }
    Path temp = null;
    try {
      temp = Files.createTempFile(ingestRoot, runnerExecutionId + ".", TEMP_SUFFIX);
      Files.write(temp, bytes);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException atomicUnsupported) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      temp = null;
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to write runner-log shipment envelope for " + runnerExecutionId, error);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException cleanupError) {
          log.warn(
              "Failed to remove runner-log shipment temp file cleanupError={}",
              cleanupError.getClass().getSimpleName());
        }
      }
    }
  }

  Path ingestRoot() {
    return ingestRoot;
  }
}
