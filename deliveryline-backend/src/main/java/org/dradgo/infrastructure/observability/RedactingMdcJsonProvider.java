package org.dradgo.infrastructure.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.util.Map;
import net.logstash.logback.composite.AbstractFieldJsonProvider;

/**
 * Logstash composite JSON provider that emits the {@code mdc} object after routing every value
 * through {@link RedactionLayoutHolder}. Replaces the default {@code <mdc>} provider so MDC
 * values stamped by code paths that bypass {@code MdcKeys.sanitizeForLog} (e.g., third-party
 * libraries) cannot leak unredacted content into JSON output. See D2 of the story 1.19 review.
 */
public class RedactingMdcJsonProvider extends AbstractFieldJsonProvider<ILoggingEvent> {

	private static final String DEFAULT_FIELD = "mdc";

	public RedactingMdcJsonProvider() {
		setFieldName(DEFAULT_FIELD);
	}

	@Override
	public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
		Map<String, String> mdc = event.getMDCPropertyMap();
		generator.writeObjectFieldStart(getFieldName());
		if (mdc != null) {
			for (Map.Entry<String, String> entry : mdc.entrySet()) {
				String redacted = RedactionLayoutHolder.redact(entry.getValue());
				generator.writeStringField(entry.getKey(), redacted == null ? "" : redacted);
			}
		}
		generator.writeEndObject();
	}
}
