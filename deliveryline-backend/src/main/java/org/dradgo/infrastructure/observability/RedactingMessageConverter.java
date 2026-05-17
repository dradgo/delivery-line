package org.dradgo.infrastructure.observability;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Pattern-layout message converter that routes the formatted log message through {@link
 * RedactionLayoutHolder} before write. Registered in {@code logback-spring.xml} as the {@code
 * redactedMsg} conversion word, replacing the standard {@code %msg} for the human-readable pattern
 * profile.
 *
 * <p>Logback instantiates converters reflectively; this class therefore has no Spring lifecycle.
 * The actual redaction service is injected via the static {@link RedactionLayoutHolder} which is
 * set at application bootstrap by {@link RedactionLayoutInitializer}.
 */
public class RedactingMessageConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent event) {
    String formatted = super.convert(event);
    return RedactionLayoutHolder.redact(formatted);
  }
}
