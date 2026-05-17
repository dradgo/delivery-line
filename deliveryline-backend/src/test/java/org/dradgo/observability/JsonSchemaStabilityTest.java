package org.dradgo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import net.logstash.logback.composite.JsonProviders;
import net.logstash.logback.composite.loggingevent.LogLevelJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggerNameJsonProvider;
import net.logstash.logback.composite.loggingevent.MdcJsonProvider;
import net.logstash.logback.composite.loggingevent.StackTraceJsonProvider;
import net.logstash.logback.composite.loggingevent.ThreadNameJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.infrastructure.observability.RedactingJsonProvider;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Story 1.19 AC10: under the {@code demo} profile, every JSON-encoded log line MUST parse as
 * valid JSON with the documented stable field set ({@code level}, {@code logger}, {@code thread},
 * {@code message}, {@code mdc}, optional {@code stack_trace}).
 *
 * <p>Programmatically wires the logstash composite encoder that mirrors the {@code demo} block
 * of {@code logback-spring.xml}, captures the encoded bytes, and asserts the shape.
 */
class JsonSchemaStabilityTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private LoggerContext context;
	private ByteArrayOutputStream stream;
	private OutputStreamAppender<ILoggingEvent> appender;
	private RedactionPolicyService priorRedactionService;

	@BeforeEach
	void setUp() {
		priorRedactionService = RedactionLayoutHolder.currentForTesting();
		RedactionLayoutHolder.setRedactionService(
			new RedactionPolicyService(new DataClassificationService()));
		context = (LoggerContext) LoggerFactory.getILoggerFactory();
		stream = new ByteArrayOutputStream();

		LoggingEventCompositeJsonEncoder encoder = new LoggingEventCompositeJsonEncoder();
		encoder.setContext(context);

		JsonProviders<ILoggingEvent> providers = encoder.getProviders();
		providers.addProvider(named(new LogLevelJsonProvider(), "level"));
		providers.addProvider(named(new LoggerNameJsonProvider(), "logger"));
		providers.addProvider(named(new ThreadNameJsonProvider(), "thread"));
		providers.addProvider(named(new MdcJsonProvider(), "mdc"));
		providers.addProvider(named(new StackTraceJsonProvider(), "stack_trace"));
		RedactingJsonProvider redactedMessage = new RedactingJsonProvider();
		redactedMessage.setFieldName("message");
		providers.addProvider(redactedMessage);
		encoder.start();

		appender = new OutputStreamAppender<>();
		appender.setContext(context);
		appender.setEncoder((Encoder<ILoggingEvent>) encoder);
		appender.setOutputStream(stream);
		appender.start();
		((Logger) LoggerFactory.getLogger(JsonSchemaStabilityTest.class)).addAppender(appender);
		MDC.clear();
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(JsonSchemaStabilityTest.class)).detachAppender(appender);
		appender.stop();
		MDC.clear();
		if (priorRedactionService == null) {
			RedactionLayoutHolder.clearForTesting();
		} else {
			RedactionLayoutHolder.setRedactionService(priorRedactionService);
		}
	}

	@Test
	void jsonLineCarriesStableDocumentedFieldSet() throws Exception {
		MDC.put(MdcKeys.CORRELATION_ID, "corr-json-schema");
		MDC.put(MdcKeys.WORKFLOW_RUN_ID, "run_json-schema");
		LoggerFactory.getLogger(JsonSchemaStabilityTest.class).info("payload {}", "value");

		String emitted = stream.toString();
		assertThat(emitted).as("encoder should produce at least one JSON line").isNotBlank();
		String firstLine = emitted.split("\\r?\\n")[0];
		JsonNode parsed = MAPPER.readTree(firstLine);

		Set<String> requiredFields = Set.of("level", "logger", "thread", "mdc", "message");
		assertThat(parsed.fieldNames()).toIterable().containsAll(requiredFields);
		assertThat(parsed.get("level").asText()).isEqualTo("INFO");
		assertThat(parsed.get("message").asText()).contains("payload");

		JsonNode mdc = parsed.get("mdc");
		assertThat(mdc.isObject()).isTrue();
		assertThat(mdc.get(MdcKeys.CORRELATION_ID).asText()).isEqualTo("corr-json-schema");
		assertThat(mdc.get(MdcKeys.WORKFLOW_RUN_ID).asText()).isEqualTo("run_json-schema");
	}

	private static <T> T named(T provider, String name) {
		try {
			provider.getClass().getMethod("setFieldName", String.class).invoke(provider, name);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(
				"Provider " + provider.getClass().getSimpleName() + " has no setFieldName(String)", e);
		}
		return provider;
	}
}
