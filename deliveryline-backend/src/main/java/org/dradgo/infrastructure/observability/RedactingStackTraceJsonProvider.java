package org.dradgo.infrastructure.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import net.logstash.logback.composite.AbstractFieldJsonProvider;

/**
 * Logstash composite JSON provider that emits the {@code stack_trace} field after routing the
 * fully-rendered stack trace through {@link RedactionLayoutHolder}. Replaces the default
 * {@code <stackTrace>} provider so throwable messages cannot leak credentials, bearer tokens,
 * or JDBC URLs into JSON output. See D2 of the story 1.19 review.
 */
public class RedactingStackTraceJsonProvider extends AbstractFieldJsonProvider<ILoggingEvent> {

	private static final String DEFAULT_FIELD = "stack_trace";

	public RedactingStackTraceJsonProvider() {
		setFieldName(DEFAULT_FIELD);
	}

	@Override
	public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
		IThrowableProxy throwable = event.getThrowableProxy();
		if (throwable == null) {
			return;
		}
		String rendered = ThrowableProxyUtil.asString(throwable);
		String redacted = RedactionLayoutHolder.redact(rendered);
		generator.writeStringField(getFieldName(), redacted == null ? "" : redacted);
	}
}
