package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.dradgo.application.runner.spi.RunnerLogShipmentSink;
import org.dradgo.domain.registry.DataClassification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3.7 (AC4 / Decision D5) — the fail-closed shipping decision. Verifies that {@code
 * local-only} is never written to the ingest sink, that shippable logs are written with a top-level
 * {@code classification} envelope and the (already-redacted) content, and that the shipping log
 * line carries metadata ONLY — never the log content (Trap T1 / logging contract).
 */
class RunnerLogShippingServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Captures envelopes instead of touching disk. */
  private static final class CapturingSink implements RunnerLogShipmentSink {
    private final List<String> ids = new ArrayList<>();
    private final List<byte[]> envelopes = new ArrayList<>();

    @Override
    public void write(String runnerExecutionId, byte[] envelopeNdjson) {
      ids.add(runnerExecutionId);
      envelopes.add(envelopeNdjson);
    }
  }

  private CapturingSink sink;
  private RunnerLogShippingService service;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    sink = new CapturingSink();
    service = new RunnerLogShippingService(sink);
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(RunnerLogShippingService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(RunnerLogShippingService.class)).detachAppender(appender);
  }

  @Test
  void localOnlyLogIsNeverShipped() {
    RunnerLogShipment shipment =
        new RunnerLogShipment(
            "rex_localonly01", "run_abc12345", DataClassification.LOCAL_ONLY, "secret-ish", "");

    RunnerLogShipmentOutcome outcome = service.shipIfPermitted(shipment);

    assertThat(outcome).isEqualTo(RunnerLogShipmentOutcome.SKIPPED_NOT_SHIPPABLE);
    assertThat(sink.envelopes).as("nothing reaches the ingest sink for local-only").isEmpty();
    ILoggingEvent decision = onlyInfoEvent();
    assertThat(decision.getFormattedMessage())
        .contains("not shipped")
        .contains("classification=local-only")
        .contains("byteSize=");
  }

  @Test
  void shareableRedactedLogIsShippedWithClassificationEnvelopeAndContent() throws Exception {
    RunnerLogShipment shipment =
        new RunnerLogShipment(
            "rex_shippable01",
            "run_abc12345",
            DataClassification.SHAREABLE_REDACTED,
            "build ok [REDACTED_GITHUB_TOKEN]",
            "warning: low disk");

    RunnerLogShipmentOutcome outcome = service.shipIfPermitted(shipment);

    assertThat(outcome).isEqualTo(RunnerLogShipmentOutcome.SHIPPED);
    assertThat(sink.ids).containsExactly("rex_shippable01");

    String[] lines = new String(sink.envelopes.get(0), StandardCharsets.UTF_8).split("\\R");
    assertThat(lines).hasSize(2);
    JsonNode stdout = MAPPER.readTree(lines[0]);
    assertThat(stdout.get("classification").asText()).isEqualTo("shareable-redacted");
    assertThat(stdout.get("runnerExecutionId").asText()).isEqualTo("rex_shippable01");
    assertThat(stdout.get("workflowRunId").asText()).isEqualTo("run_abc12345");
    assertThat(stdout.get("stream").asText()).isEqualTo("stdout");
    assertThat(stdout.get("message").asText()).isEqualTo("build ok [REDACTED_GITHUB_TOKEN]");
    JsonNode stderr = MAPPER.readTree(lines[1]);
    assertThat(stderr.get("stream").asText()).isEqualTo("stderr");
    assertThat(stderr.get("message").asText()).isEqualTo("warning: low disk");
  }

  @Test
  void shippingLogLineCarriesMetadataOnlyNeverContent() {
    RunnerLogShipment shipment =
        new RunnerLogShipment(
            "rex_shippable02",
            "run_abc12345",
            DataClassification.SHAREABLE_REDACTED,
            "super-secret-build-output-token-xyz",
            "");

    service.shipIfPermitted(shipment);

    ILoggingEvent decision = onlyInfoEvent();
    assertThat(decision.getFormattedMessage())
        .contains("shipped")
        .contains("runnerExecutionId=rex_shippable02")
        .contains("classification=shareable-redacted")
        .contains("byteSize=")
        .doesNotContain("super-secret-build-output-token-xyz");
  }

  private ILoggingEvent onlyInfoEvent() {
    List<ILoggingEvent> infos =
        appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
    assertThat(infos).hasSize(1);
    return infos.get(0);
  }
}
