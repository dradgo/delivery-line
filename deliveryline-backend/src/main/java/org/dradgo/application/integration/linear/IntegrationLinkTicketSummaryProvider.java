package org.dradgo.application.integration.linear;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.TicketSummaryProjection;
import org.dradgo.application.runner.TicketSummary;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Production {@link TicketSummaryProvider} that resolves the ticket summary projection from
 * {@code integration_links} (story 1.14 Task 5). Reads the active integration link's
 * {@link TicketSummaryProjection} through the application-owned SPI — no direct dependency on
 * the persistence adapter or JPA repositories, keeping the layered-boundary ArchUnit rules
 * clean.
 *
 * <p>Marked {@link Primary} so it wins over the Epic-1 {@code StubTicketSummaryProvider} fallback
 * whenever both beans are present (Q5 default per story 1.14 open questions). When no active
 * integration_link exists for the run, falls back to a deterministic placeholder so tests that
 * have not yet wired a real link don't break — story 1.15 closes that gap by always creating an
 * integration link at submit time.
 */
@Component
@Primary
public class IntegrationLinkTicketSummaryProvider implements TicketSummaryProvider {

	private static final Logger log = LoggerFactory.getLogger(IntegrationLinkTicketSummaryProvider.class);

	private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

	private final IntegrationLinkRecordPort integrationLinkRecordPort;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public IntegrationLinkTicketSummaryProvider(IntegrationLinkRecordPort integrationLinkRecordPort) {
		this.integrationLinkRecordPort = Objects.requireNonNull(integrationLinkRecordPort, "integrationLinkRecordPort");
	}

	@Override
	public TicketSummary fetchByWorkflowRun(String workflowRunPublicId) {
		Optional<TicketSummaryProjection> projection =
			integrationLinkRecordPort.findActiveTicketSummaryByWorkflowRun(workflowRunPublicId);
		if (projection.isEmpty()) {
			log.debug("ticket_summary fallback workflowRunId={} reason=no_active_integration_link",
				workflowRunPublicId);
			return placeholderFor(workflowRunPublicId);
		}
		TicketSummaryProjection active = projection.get();
		Map<String, Object> metadata = parseMetadata(active.externalMetadata());
		String title = stringOrFallback(metadata, "title", "Pending ticket summary");
		String summary = stringOrFallback(metadata, "summary",
			"Ticket summary unavailable on the integration_links row.");
		return new TicketSummary(active.externalRef(), title, summary);
	}

	private Map<String, Object> parseMetadata(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(bytes, METADATA_TYPE);
		} catch (java.io.IOException error) {
			log.warn("ticket_summary metadata_parse_failed reason={}", error.getClass().getSimpleName());
			return Map.of();
		}
	}

	private static String stringOrFallback(Map<String, Object> metadata, String key, String fallback) {
		Object value = metadata.get(key);
		if (value == null) {
			return fallback;
		}
		String text = value.toString();
		return text.isBlank() ? fallback : text;
	}

	private static TicketSummary placeholderFor(String workflowRunPublicId) {
		return new TicketSummary(
			"ticket-" + workflowRunPublicId,
			"Pending ticket summary",
			"No active Linear integration link for this run; story 1.15 wires CLI submit to always create one.");
	}
}
