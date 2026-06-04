package org.dradgo.application.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.dradgo.application.runner.spi.RunnerLogShipmentSink;
import org.dradgo.domain.registry.DataClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3.7 (AC3b / AC4 / Decision D5) — the fail-closed runner-log shipping decision point.
 *
 * <p>Given a redacted {@link RunnerLogShipment}, this service ships it to the observability ingest
 * (via {@link RunnerLogShipmentSink}) <b>only when</b> {@link RunnerLogShippingPolicy#isShippable}
 * is true for the shipment's classification. {@code local-only} logs are never exposed to the
 * Logstash file input — the single most important guarantee of the story (Trap T5).
 *
 * <p>The shipped NDJSON carries a top-level {@code classification} field per document so the
 * Logstash {@code if [classification] == "local-only"} filter is uniform across the TCP and file
 * ingest paths. This service logs METADATA ONLY (ids, classification, byte size) — never the
 * redacted log content it ships (Trap T1 / the story's logging contract).
 *
 * <p>This is a forward seam: it reuses the story-3.6 {@link RunnerLogShippingPolicy} predicate and
 * is available to whichever path elevates a runner log to {@code shareable-redacted} (gated behind
 * {@code deliveryline.runner.allow-shareable-logs}, default false — so by default nothing ships).
 */
@Service
public class RunnerLogShippingService {

  private static final Logger log = LoggerFactory.getLogger(RunnerLogShippingService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RunnerLogShipmentSink sink;

  public RunnerLogShippingService(RunnerLogShipmentSink sink) {
    this.sink = sink;
  }

  /**
   * Ship the redacted log to the observability ingest iff its classification permits it. Returns
   * the decision taken; never throws on a non-shippable input (it simply skips). A sink IO failure
   * propagates as an {@link IllegalStateException} to the caller.
   */
  public RunnerLogShipmentOutcome shipIfPermitted(RunnerLogShipment shipment) {
    Objects.requireNonNull(shipment, "shipment");
    DataClassification classification = shipment.classification();
    long byteSize = shipment.byteSize();
    if (!RunnerLogShippingPolicy.isShippable(classification)) {
      log.info(
          "runner log not shipped to observability ingest (not shippable) runnerExecutionId={}"
              + " workflowRunId={} classification={} byteSize={}",
          shipment.runnerExecutionId(),
          shipment.workflowRunId(),
          classificationValue(classification),
          byteSize);
      return RunnerLogShipmentOutcome.SKIPPED_NOT_SHIPPABLE;
    }
    byte[] envelope = buildEnvelope(shipment, classification);
    sink.write(shipment.runnerExecutionId(), envelope);
    log.info(
        "runner log shipped to observability ingest runnerExecutionId={} workflowRunId={}"
            + " classification={} byteSize={}",
        shipment.runnerExecutionId(),
        shipment.workflowRunId(),
        classificationValue(classification),
        byteSize);
    return RunnerLogShipmentOutcome.SHIPPED;
  }

  private byte[] buildEnvelope(RunnerLogShipment shipment, DataClassification classification) {
    String classificationValue = classificationValue(classification);
    StringBuilder ndjson = new StringBuilder();
    ndjson
        .append(documentLine(shipment, classificationValue, "stdout", shipment.redactedStdout()))
        .append('\n')
        .append(documentLine(shipment, classificationValue, "stderr", shipment.redactedStderr()))
        .append('\n');
    return ndjson.toString().getBytes(StandardCharsets.UTF_8);
  }

  private String documentLine(
      RunnerLogShipment shipment, String classificationValue, String stream, String message) {
    ObjectNode node = MAPPER.createObjectNode();
    // Top-level classification — the field the uniform Logstash drop filter keys on (AC4/D4).
    node.put("classification", classificationValue);
    node.put("runnerExecutionId", shipment.runnerExecutionId());
    node.put("workflowRunId", shipment.workflowRunId());
    node.put("stream", stream);
    node.put("message", message);
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize runner-log shipment envelope", e);
    }
  }

  private static String classificationValue(DataClassification classification) {
    return classification == null ? "null" : classification.value();
  }
}
