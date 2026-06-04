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
import java.io.InputStream;
import net.logstash.logback.composite.loggingevent.LogLevelJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggerNameJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggingEventPatternJsonProvider;
import net.logstash.logback.composite.loggingevent.ThreadNameJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.infrastructure.observability.RedactingJsonProvider;
import org.dradgo.infrastructure.observability.RedactingMdcJsonProvider;
import org.dradgo.infrastructure.observability.RedactingStackTraceJsonProvider;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Story 3.7 AC7 / Decision D4 / Traps T4, T9 — the {@code LogstashTcpSocketAppender} is declared
 * ONLY inside the {@code observability} springProfile block, so it is never instantiated (and so
 * makes ZERO connection attempts) when the profile is off. The encoder reuses the story-1.19 {@code
 * Redacting*} providers so shipped logs are source-redacted, and emits a top-level {@code
 * classification} field only when the event carries one in MDC.
 *
 * <p>The structural assertions DOM-parse {@code logback-spring.xml} directly (no Spring context, no
 * Docker): "appender absence when observability is off" is proven by the appender living only
 * inside the profile-gated block. The behavioral assertions build the same encoder programmatically
 * (the pattern story 1.19's {@link JsonSchemaStabilityTest} established) and exercise redaction.
 */
class ObservabilityLogbackAppenderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String LOGSTASH_APPENDER_CLASS =
      "net.logstash.logback.appender.LogstashTcpSocketAppender";

  // ---- Structural: gating of the Logstash appender (T4, T9) ----

  @Test
  void logstashAppenderIsDeclaredOnlyInsideObservabilityProfile() throws Exception {
    Document doc = parseLogbackSpring();

    NodeList appenders = doc.getElementsByTagName("appender");
    int logstashAppenders = 0;
    for (int i = 0; i < appenders.getLength(); i++) {
      Element appender = (Element) appenders.item(i);
      if (LOGSTASH_APPENDER_CLASS.equals(appender.getAttribute("class"))) {
        logstashAppenders++;
        assertThat(enclosingProfile(appender))
            .as("LogstashTcpSocketAppender must be declared inside <springProfile name=...>")
            .isEqualTo("observability");
      }
    }
    assertThat(logstashAppenders)
        .as("exactly one Logstash appender should be declared")
        .isEqualTo(1);
  }

  @Test
  void logstashAppenderRefAppearsOnlyUnderObservability() throws Exception {
    Document doc = parseLogbackSpring();

    NodeList refs = doc.getElementsByTagName("appender-ref");
    for (int i = 0; i < refs.getLength(); i++) {
      Element ref = (Element) refs.item(i);
      if ("LOGSTASH".equals(ref.getAttribute("ref"))) {
        assertThat(enclosingProfile(ref))
            .as("a LOGSTASH appender-ref must only ever be wired under the observability profile")
            .isEqualTo("observability");
      }
    }
  }

  @Test
  void existingStdoutStacksAreNotBrokenByTheAdditiveBlock() throws Exception {
    Document doc = parseLogbackSpring();

    // The demo and local/test stacks must still wire STDOUT — the observability block is additive
    // and must not remove or shadow the pre-existing console roots (Trap T4).
    boolean stdoutUnderDemo = false;
    boolean stdoutUnderLocalTest = false;
    NodeList refs = doc.getElementsByTagName("appender-ref");
    for (int i = 0; i < refs.getLength(); i++) {
      Element ref = (Element) refs.item(i);
      if (!"STDOUT".equals(ref.getAttribute("ref"))) {
        continue;
      }
      String profile = enclosingProfile(ref);
      if ("demo".equals(profile)) {
        stdoutUnderDemo = true;
      }
      if (profile != null && profile.contains("test")) {
        stdoutUnderLocalTest = true;
      }
    }
    assertThat(stdoutUnderDemo).as("demo profile must still wire STDOUT").isTrue();
    assertThat(stdoutUnderLocalTest).as("local/test profile must still wire STDOUT").isTrue();
  }

  // ---- Review P4 / Decision D1: console self-sufficiency for observability-ALONE ----

  private static final String CONSOLE_APPENDER_CLASS = "ch.qos.logback.core.ConsoleAppender";

  @Test
  void observabilityAloneStillWiresAConsoleStdout() throws Exception {
    Document doc = parseLogbackSpring();

    // A console STDOUT appender + root ref must exist under an observability-inclusive profile, so
    // a
    // bare `observability` run is not Logstash-only with zero console output (review finding
    // D1/P4).
    boolean consoleAppenderUnderObservability = false;
    NodeList appenders = doc.getElementsByTagName("appender");
    for (int i = 0; i < appenders.getLength(); i++) {
      Element appender = (Element) appenders.item(i);
      if (!CONSOLE_APPENDER_CLASS.equals(appender.getAttribute("class"))) {
        continue;
      }
      String profile = enclosingProfile(appender);
      if (profile != null && profile.contains("observability")) {
        consoleAppenderUnderObservability = true;
      }
    }
    assertThat(consoleAppenderUnderObservability)
        .as("observability activated ALONE must still wire a STDOUT console (review D1/P4)")
        .isTrue();

    boolean stdoutRefUnderObservability = false;
    NodeList refs = doc.getElementsByTagName("appender-ref");
    for (int i = 0; i < refs.getLength(); i++) {
      Element ref = (Element) refs.item(i);
      if (!"STDOUT".equals(ref.getAttribute("ref"))) {
        continue;
      }
      String profile = enclosingProfile(ref);
      if (profile != null && profile.contains("observability")) {
        stdoutRefUnderObservability = true;
      }
    }
    assertThat(stdoutRefUnderObservability)
        .as("the observability console block must reference STDOUT on the root")
        .isTrue();
  }

  @Test
  void observabilityConsoleIsGuardedAgainstDuplicateStdoutUnderCombinedProfiles() throws Exception {
    Document doc = parseLogbackSpring();

    // Logback attaches appenders by object identity (not name), so a SECOND same-named STDOUT under
    // `demo,observability` would double every console line (Trap T4). The console block must
    // therefore
    // be gated to EXCLUDE the base console profiles, and the plain `observability` (LOGSTASH) block
    // must NOT itself declare a STDOUT — combined profiles then get exactly one STDOUT (the base
    // one).
    NodeList appenders = doc.getElementsByTagName("appender");
    for (int i = 0; i < appenders.getLength(); i++) {
      Element appender = (Element) appenders.item(i);
      if (!CONSOLE_APPENDER_CLASS.equals(appender.getAttribute("class"))) {
        continue;
      }
      String profile = enclosingProfile(appender);
      if (profile != null && profile.contains("observability")) {
        assertThat(profile)
            .as("observability console must be suppressed when a base profile already wires STDOUT")
            .contains("!demo")
            .contains("!local")
            .contains("!test");
      }
    }

    // No STDOUT appender or ref may live under the bare `observability` profile (that block is
    // LOGSTASH-only); otherwise combined profiles would attach two distinct STDOUT appenders.
    NodeList allAppenders = doc.getElementsByTagName("appender");
    for (int i = 0; i < allAppenders.getLength(); i++) {
      Element appender = (Element) allAppenders.item(i);
      if ("STDOUT".equals(appender.getAttribute("name"))) {
        assertThat(enclosingProfile(appender))
            .as("the bare observability block must not declare a STDOUT appender (LOGSTASH-only)")
            .isNotEqualTo("observability");
      }
    }
    NodeList refs = doc.getElementsByTagName("appender-ref");
    for (int i = 0; i < refs.getLength(); i++) {
      Element ref = (Element) refs.item(i);
      if ("STDOUT".equals(ref.getAttribute("ref"))) {
        assertThat(enclosingProfile(ref))
            .as("the bare observability block must not reference STDOUT (LOGSTASH-only)")
            .isNotEqualTo("observability");
      }
    }
  }

  // ---- Behavioral: the observability encoder redacts + carries classification ----

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
    var providers = encoder.getProviders();
    providers.addProvider(named(new LogLevelJsonProvider(), "level"));
    providers.addProvider(named(new LoggerNameJsonProvider(), "logger"));
    providers.addProvider(named(new ThreadNameJsonProvider(), "thread"));

    LoggingEventPatternJsonProvider classification = new LoggingEventPatternJsonProvider();
    classification.setOmitEmptyFields(true);
    classification.setPattern("{\"classification\":\"%mdc{classification}\"}");
    classification.setContext(context);
    providers.addProvider(classification);

    RedactingJsonProvider message = new RedactingJsonProvider();
    message.setFieldName("message");
    providers.addProvider(message);
    RedactingMdcJsonProvider mdc = new RedactingMdcJsonProvider();
    mdc.setFieldName("mdc");
    providers.addProvider(mdc);
    RedactingStackTraceJsonProvider stackTrace = new RedactingStackTraceJsonProvider();
    stackTrace.setFieldName("stack_trace");
    providers.addProvider(stackTrace);
    encoder.start();

    appender = new OutputStreamAppender<>();
    appender.setContext(context);
    appender.setEncoder((Encoder<ILoggingEvent>) encoder);
    appender.setOutputStream(stream);
    appender.start();
    ((Logger) LoggerFactory.getLogger(ObservabilityLogbackAppenderTest.class))
        .addAppender(appender);
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(ObservabilityLogbackAppenderTest.class))
        .detachAppender(appender);
    appender.stop();
    MDC.clear();
    if (priorRedactionService == null) {
      RedactionLayoutHolder.clearForTesting();
    } else {
      RedactionLayoutHolder.setRedactionService(priorRedactionService);
    }
  }

  @Test
  void shippedJsonRedactsSecretsAndCarriesClassificationWhenPresent() throws Exception {
    MDC.put("classification", "shareable-redacted");
    LoggerFactory.getLogger(ObservabilityLogbackAppenderTest.class)
        .info("token is ghp_1234567890abcdef1234567890abcdef1234");

    JsonNode parsed = MAPPER.readTree(firstLine());
    assertThat(parsed.get("classification").asText()).isEqualTo("shareable-redacted");
    assertThat(parsed.get("message").asText()).doesNotContain("ghp_");
    assertThat(parsed.get("message").asText()).contains("[REDACTED_GITHUB_TOKEN]");
  }

  @Test
  void shippedJsonOmitsClassificationWhenAbsentFromMdc() throws Exception {
    LoggerFactory.getLogger(ObservabilityLogbackAppenderTest.class).info("plain app log");

    JsonNode parsed = MAPPER.readTree(firstLine());
    assertThat(parsed.has("classification"))
        .as("ordinary app logs carry no classification and must not be dropped by the pipeline")
        .isFalse();
  }

  // ---- helpers ----

  private String firstLine() {
    String emitted = stream.toString();
    assertThat(emitted).as("encoder should produce at least one JSON line").isNotBlank();
    return emitted.split("\\r?\\n")[0];
  }

  private static Document parseLogbackSpring() throws Exception {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    try (InputStream in =
        ObservabilityLogbackAppenderTest.class.getResourceAsStream("/logback-spring.xml")) {
      assertThat(in).as("logback-spring.xml must be on the test classpath").isNotNull();
      return factory.newDocumentBuilder().parse(in);
    }
  }

  /** Walk ancestors to the nearest {@code <springProfile>} and return its {@code name}, or null. */
  private static String enclosingProfile(Node node) {
    for (Node current = node.getParentNode(); current != null; current = current.getParentNode()) {
      if (current instanceof Element element && "springProfile".equals(element.getNodeName())) {
        return element.getAttribute("name");
      }
    }
    return null;
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
