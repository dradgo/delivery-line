package org.dradgo.infrastructure.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import net.logstash.logback.composite.AbstractFieldJsonProvider;

/**
 * Logstash composite JSON provider that emits a single {@code "message"} field carrying the
 * formatted (then redacted) log message. Replaces the default {@link
 * net.logstash.logback.composite.loggingevent.MessageJsonProvider} in the JSON appender configured
 * for the {@code demo} profile of {@code logback-spring.xml}.
 *
 * <p>The provider runs through {@link RedactionLayoutHolder} so the JSON output and the
 * human-readable pattern output share an identical redaction surface — both are pinned by {@code
 * LoggingRedactionContractTest}.
 */
public class RedactingJsonProvider extends AbstractFieldJsonProvider<ILoggingEvent> {

  private static final String DEFAULT_FIELD = "message";

  public RedactingJsonProvider() {
    setFieldName(DEFAULT_FIELD);
  }

  @Override
  public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
    String formatted = event.getFormattedMessage();
    String redacted = RedactionLayoutHolder.redact(formatted);
    generator.writeStringField(getFieldName(), redacted == null ? "" : redacted);
  }
}
