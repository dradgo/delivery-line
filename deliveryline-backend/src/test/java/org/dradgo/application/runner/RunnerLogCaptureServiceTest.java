package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.dradgo.application.runner.RunnerLogCaptureService.RunnerLogTruncation;
import org.dradgo.application.runner.spi.RunnerLogStore;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.DataClassification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Story 3.6 AC11 (a)(b)(c)(e) — plain unit coverage of {@link RunnerLogCaptureService}: the real
 * story-1.10 redaction stack ({@link RedactionPolicyService} + {@link DataClassificationService}),
 * an in-test capturing {@link RunnerLogStore} fake, and {@link RunnerProperties} toggling {@code
 * allowShareableLogs}. No Spring, no DB.
 */
class RunnerLogCaptureServiceTest {

  private static final String REX = "rex_capture0000001";
  private static final String RUN = "run_capture0000001";

  /** Captures the redacted bytes the service writes so the test can assert no secret survived. */
  private static final class CapturingLogStore implements RunnerLogStore {
    byte[] stdout;
    byte[] stderr;

    @Override
    public RunnerLogReference write(String rex, byte[] redactedStdout, byte[] redactedStderr) {
      this.stdout = redactedStdout;
      this.stderr = redactedStderr;
      long size = (long) redactedStdout.length + redactedStderr.length;
      return new RunnerLogReference("/runner-logs/" + rex, size, DataClassification.LOCAL_ONLY, 0);
    }

    @Override
    public Optional<RunnerLogReference> find(String rex) {
      return Optional.empty();
    }

    String stdoutText() {
      return new String(stdout, StandardCharsets.UTF_8);
    }

    String stderrText() {
      return new String(stderr, StandardCharsets.UTF_8);
    }
  }

  private RunnerLogCaptureService service(boolean allowShareableLogs) {
    RunnerProperties properties =
        allowShareableLogs ? propertiesWithShareable() : RunnerProperties.defaults();
    return new RunnerLogCaptureService(
        new RedactionPolicyService(new DataClassificationService()), store, properties);
  }

  private static RunnerProperties propertiesWithShareable() {
    RunnerProperties d = RunnerProperties.defaults();
    return new RunnerProperties(
        d.staleThresholdMultiplier(),
        d.stageTimeouts(),
        d.timeoutScanIntervalMs(),
        d.timeoutScanBatchSize(),
        d.staleScanIntervalMs(),
        d.pollIntervalMs(),
        d.recovery(),
        d.mock(),
        d.scheduling(),
        d.docker(),
        d.secretEnvNames(),
        /* allowShareableLogs= */ true,
        d.specStage(),
        d.planStage(),
        d.implementationStage(),
        d.openspec());
  }

  private final CapturingLogStore store = new CapturingLogStore();

  @Test
  void redactsLeakySecretsBeforeWritingSoNoSecretValueIsPersisted() {
    // AC11(a): a deliberately-leaky stdout produces a clean redacted file (no secret value
    // present).
    String leakyStdout =
        "starting run\nAuthorization: Bearer ghp_1234567890abcdef1234567890abcdef1234\ndone\n";
    CapturedLogs captured = service(false).captureLogs(REX, RUN, leakyStdout, "");

    assertThat(store.stdoutText()).doesNotContain("ghp_1234567890abcdef1234567890abcdef1234");
    assertThat(store.stdoutText()).contains("[REDACTED_");
    assertThat(captured.redactionCount()).isGreaterThanOrEqualTo(1);
    // AC4 / Trap T3: a stream that leaked a secret can NEVER be elevated, even with the flag off.
    assertThat(captured.classification()).isEqualTo(DataClassification.LOCAL_ONLY);
  }

  @Test
  void redactionCountMatchesPlaceholderCountAcrossBothStreams() {
    // AC11(c): redaction_count equals the [REDACTED_ placeholder count across stdout + stderr.
    String stdout = "Authorization: Bearer ghp_1234567890abcdef1234567890abcdef1234\n";
    String stderr = "token=lin_api_1234567890abcdef1234567890abcdef\n";
    CapturedLogs captured = service(false).captureLogs(REX, RUN, stdout, stderr);

    int placeholders =
        countOccurrences(store.stdoutText(), "[REDACTED_")
            + countOccurrences(store.stderrText(), "[REDACTED_");
    assertThat(captured.redactionCount()).isEqualTo(placeholders);
  }

  @Test
  void zeroSecretLogsStayLocalOnlyWhenFlagDisabled() {
    // AC11(b): even with no secrets, the default (flag off) keeps logs local-only (Trap T3).
    CapturedLogs captured =
        service(false).captureLogs(REX, RUN, "all clean output\n", "no errors\n");
    assertThat(captured.redactionCount()).isZero();
    assertThat(captured.classification()).isEqualTo(DataClassification.LOCAL_ONLY);
  }

  @Test
  void zeroSecretLogsElevateToShareableRedactedOnlyWhenFlagEnabled() {
    // AC11(b): zero-secret logs elevate to shareable-redacted ONLY with allow-shareable-logs=true.
    CapturedLogs captured =
        service(true).captureLogs(REX, RUN, "all clean output\n", "no errors\n");
    assertThat(captured.redactionCount()).isZero();
    assertThat(captured.classification()).isEqualTo(DataClassification.SHAREABLE_REDACTED);
  }

  @Test
  void truncatedLogsStayLocalOnlyEvenWhenNoSecretsAreDetectedAndFlagEnabled() {
    CapturedLogs captured =
        service(true)
            .captureLogs(REX, RUN, "clean truncated output\n", "", RunnerLogTruncation.STDOUT);

    assertThat(captured.redactionCount()).isZero();
    assertThat(captured.classification()).isEqualTo(DataClassification.LOCAL_ONLY);
  }

  @Test
  void leakyLogsStayLocalOnlyEvenWhenFlagEnabled() {
    // AC4 / Trap T3: the elevation requires BOTH the flag AND zero detected secrets.
    String leaky = "Authorization: Bearer ghp_1234567890abcdef1234567890abcdef1234\n";
    CapturedLogs captured = service(true).captureLogs(REX, RUN, leaky, "");
    assertThat(captured.classification()).isEqualTo(DataClassification.LOCAL_ONLY);
  }

  @Test
  void localOnlyIsTheNonShippableSentinelForElkPolicy() {
    // AC9 / Trap T8: story 3.7's ELK filter treats local-only as "not shipped". Pin the gate
    // predicate here so 3.7 wires against a stable contract.
    CapturedLogs localOnly = service(false).captureLogs(REX, RUN, "clean\n", "");
    assertThat(RunnerLogShippingPolicy.isShippable(localOnly.classification())).isFalse();
    assertThat(RunnerLogShippingPolicy.isShippable(DataClassification.SHAREABLE_REDACTED)).isTrue();
  }

  @ParameterizedTest
  @MethodSource("runnerFixturePaths")
  void redactionCountCoversRunnerAdversarialFixtures(Path fixture) throws Exception {
    String content = Files.readString(fixture);

    CapturedLogs captured = service(false).captureLogs(REX, RUN, content, "");

    assertThat(store.stdoutText()).doesNotContain("FAKE-DO-NOT-USE");
    assertThat(store.stdoutText()).doesNotContain("FAKE-BEARER-HTTP-DEBUG");
    assertThat(captured.redactionCount())
        .isEqualTo(countOccurrences(store.stdoutText(), "[REDACTED_"));
    assertThat(captured.redactionCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void structuredLogsCarryAr29MetricsButNeverASecretValue() {
    // AC11(h) / AR29: the capture path's logs carry redactionCount + byteSize + classification
    // (metadata only) and NEVER the secret value — adversarial no-secret-in-logs sweep.
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(RunnerLogCaptureService.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      String secret = "ghp_1234567890abcdef1234567890abcdef1234";
      service(false).captureLogs(REX, RUN, "Authorization: Bearer " + secret + "\n", "");

      String allLogs =
          appender.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .reduce("", (a, b) -> a + "\n" + b);
      assertThat(allLogs).contains("redactionCount=");
      assertThat(allLogs).contains("classification=local-only");
      assertThat(allLogs).contains("byteSize=");
      assertThat(allLogs).doesNotContain(secret);
    } finally {
      logger.detachAppender(appender);
    }
  }

  static Stream<Path> runnerFixturePaths() {
    Path root = Path.of("src/test/resources/redaction-fixtures");
    return Stream.of(
        root.resolve("runner-codex-auth-header-echo.txt"),
        root.resolve("runner-claude-verbose-token-print.txt"),
        root.resolve("runner-bearer-http-debug-header.txt"));
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int from = 0;
    int idx;
    while ((idx = text.indexOf(token, from)) >= 0) {
      count++;
      from = idx + token.length();
    }
    return count;
  }
}
