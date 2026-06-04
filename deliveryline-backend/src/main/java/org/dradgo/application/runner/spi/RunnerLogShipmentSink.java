package org.dradgo.application.runner.spi;

/**
 * Story 3.7 (AC3b / AC4 / Decision D5) — application-owned port for the fail-closed runner-log
 * shipping seam.
 *
 * <p>Story 3.6 writes plain-text {@code runner.stdout}/{@code runner.stderr} that carry NO inline
 * classification, so a Logstash {@code file} input cannot read a file's classification from the
 * file itself. The resolution (Decision D5, the story's headline reconciliation) is fail-closed:
 * the backend exposes a redacted log to the Logstash-watched <b>shippable ingest location ONLY when
 * {@link org.dradgo.application.runner.RunnerLogShippingPolicy#isShippable}</b> is true. {@code
 * local-only} logs are never written here, so they are never visible to the file input and never
 * indexed.
 *
 * <p>Each shipped document carries a top-level {@code classification} envelope field, so the
 * Logstash pipeline's {@code if [classification] == "local-only"} filter is uniform across the
 * TCP/JSON (story 1.19 appender) and file (this seam) ingest paths.
 *
 * <p>Implemented by {@code adapters.files.LocalRunnerLogShipmentSink}, which writes atomically
 * under {@code {deliveryline.home}/runner-logs-ingest/} (a root distinct from {@code
 * runner-logs/}).
 */
public interface RunnerLogShipmentSink {

  /**
   * Atomically write the already-redacted, shippable NDJSON envelope for {@code runnerExecutionId}
   * to the Logstash-watched ingest location. The caller ({@code RunnerLogShippingService}) has
   * already enforced the shipping policy — the sink performs IO only and never sees a {@code
   * local-only} document.
   */
  void write(String runnerExecutionId, byte[] envelopeNdjson);
}
