package org.dradgo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.infrastructure.observability.RedactingMessageConverter;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.LoggerFactory;

/**
 * Story 1.19 AC5: every log message is routed through {@link RedactionPolicyService} via the {@link
 * RedactingMessageConverter} (the production layout converter registered in {@code
 * logback-spring.xml} as the {@code redactedMsg} conversion word). This adversarial sweep emits
 * each {@code redaction-fixtures/*.{txt,json,env,pem,yaml}} payload through SLF4J as {@code
 * log.info("payload {}", fixture)}, captures the {@code ILoggingEvent} via {@link ListAppender},
 * then runs the captured event through {@code RedactingMessageConverter} — i.e. it exercises the
 * same converter the production XML wires.
 *
 * <p>Reworked under P7 of the story 1.19 review: the previous version called {@code
 * RedactionLayoutHolder.redact(raw)} directly, so a regression in the converter wiring would have
 * been invisible.
 */
class LoggingRedactionContractTest {

  private static final Path FIXTURE_DIR =
      Paths.get("src", "test", "resources", "redaction-fixtures");

  private static RedactionPolicyService priorService;
  private ListAppender<ILoggingEvent> appender;
  private RedactingMessageConverter converter;

  @BeforeAll
  static void wireHolder() {
    priorService = RedactionLayoutHolder.currentForTesting();
    RedactionLayoutHolder.setRedactionService(
        new RedactionPolicyService(new DataClassificationService()));
  }

  @AfterAll
  static void unwireHolder() {
    if (priorService == null) {
      RedactionLayoutHolder.clearForTesting();
    } else {
      RedactionLayoutHolder.setRedactionService(priorService);
    }
  }

  @BeforeEach
  void setUp() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(LoggingRedactionContractTest.class)).addAppender(appender);
    converter = new RedactingMessageConverter();
    converter.start();
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(LoggingRedactionContractTest.class)).detachAppender(appender);
    converter.stop();
  }

  @TestFactory
  Stream<DynamicTest> redactingLayoutScrubsSecretsFromAllAdversarialFixtures() throws Exception {
    List<Path> fixtures;
    try (var stream = Files.list(FIXTURE_DIR)) {
      fixtures =
          stream
              .filter(Files::isRegularFile)
              .filter(p -> !p.getFileName().toString().equals("fixtures-manifest.json"))
              .filter(p -> !p.getFileName().toString().equalsIgnoreCase("README.md"))
              .toList();
    }
    assertThat(fixtures).as("fixture directory must contain adversarial files").isNotEmpty();
    return fixtures.stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture.getFileName().toString(), () -> assertFixtureRedacted(fixture)));
  }

  private void assertFixtureRedacted(Path fixture) throws Exception {
    String raw = Files.readString(fixture);
    appender.list.clear();
    LoggerFactory.getLogger(LoggingRedactionContractTest.class).info("payload {}", raw);

    assertThat(appender.list)
        .as("emit should produce exactly one captured event for fixture %s", fixture.getFileName())
        .hasSize(1);
    ILoggingEvent event = appender.list.get(0);
    String rendered = converter.convert(event);

    // All secret-shaped tokens — lines containing typical credential markers — must be scrubbed.
    // Use a strict subset check: split the raw fixture into lines, then assert that no line
    // containing a recognisable secret token survives verbatim in the rendered output.
    String[] lines = raw.split("\\r?\\n");
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.length() < 16) {
        continue;
      }
      if (looksSecret(trimmed) && rendered.contains(trimmed)) {
        throw new AssertionError(
            "Fixture "
                + fixture.getFileName()
                + " left a verbatim secret-shaped line in the rendered output. Line="
                + abbreviate(trimmed));
      }
    }
  }

  private static boolean looksSecret(String line) {
    String lower = line.toLowerCase();
    return lower.contains("password")
        || lower.contains("secret")
        || lower.contains("token")
        || lower.contains("bearer")
        || lower.contains("api_key")
        || lower.contains("apikey")
        || lower.contains("private key")
        || lower.contains("authorization")
        || lower.contains("ghp_")
        || lower.contains("sk-")
        || lower.contains("aws_access_key")
        || lower.contains("aws_secret");
  }

  private static String abbreviate(String s) {
    return s.length() > 120 ? s.substring(0, 120) + "..." : s;
  }
}
