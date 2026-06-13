package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceScanFile;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Story 3.5 AC4/AC11(c)(d)(g) — post-execution workspace secret scan: known-shape detection via
 * RedactionPolicyService, the mandatory literal-substring detection of the injected provider key,
 * and the no-secret-in-logs sweep.
 */
class RunnerSecretScanServiceTest {

  private static final String REX_ID = "rex_scan12345678";
  private static final String RUN_ID = "run_scan12345678";
  // An arbitrary provider-key VALUE — matches no RedactionCategory regex, so only the substring
  // detector can catch it (the whole point of the mandatory containment check).
  private static final String CODEX_VALUE = "sk-codex-3f8a9b2c1d4e5f60718293a4b5c6d7e8";
  // A known GitHub-token SHAPE — caught by RedactionPolicyService's GITHUB_TOKEN pattern.
  private static final String GH_TOKEN = "ghp_0123456789abcdefghijklmnopqrstuvwx";

  private final RunnerWorkspaceStore workspaceStore = mock(RunnerWorkspaceStore.class);
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());
  private final RunnerSecretsService secretsService =
      new RunnerSecretsService(
          new MockEnvironment().withProperty("CODEX_API_KEY", CODEX_VALUE),
          RunnerProperties.defaults());
  private final RunnerSecretScanService scanService =
      new RunnerSecretScanService(workspaceStore, redaction, secretsService);

  private RunnerSecretScanService.ScanOutcome scan() {
    return scanService.scanWorkspace(REX_ID, RunnerKind.CODEX, RunnerStage.EXECUTION, RUN_ID);
  }

  @Test
  void cleanWorkspaceReturnsNoLeak() {
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(List.of(new WorkspaceScanFile("output/result.json", "{\"ok\":true}")));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isFalse();
    assertThat(outcome.leakedFile()).isNull();
  }

  @Test
  void detectsKnownPatternSecretInOutput() {
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(
                new WorkspaceScanFile("output/result.json", "{\"token\": \"" + GH_TOKEN + "\"}")));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.leakedFile()).isEqualTo("output/result.json");
    assertThat(outcome.detectedCategories()).contains("GITHUB_TOKEN");
  }

  @Test
  void detectsInjectedProviderKeyValueInOutputViaSubstring() {
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(
                new WorkspaceScanFile(
                    "output/result.json", "agent wrote its key " + CODEX_VALUE + " to disk")));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.leakedFile()).isEqualTo("output/result.json");
    assertThat(outcome.detectedCategories())
        .containsExactly(RunnerSecretScanService.INJECTED_PROVIDER_KEY_CATEGORY);
  }

  @Test
  void detectsInjectedSubscriptionAuthJsonContentViaSubstring() {
    // Story 3a-3 (AC8): the Codex subscription credential travels as RAW JSON (decision #4 — NOT
    // base64), so RunnerSecretScanService's mandatory literal-substring detector still catches the
    // auth.json content if it ever leaks into input/output/logs. base64 transport would have
    // blinded it. No production scan change — this pins that the raw-JSON choice keeps the detector
    // effective. CODEX_AUTH_JSON is first-present in the codex list, so it is the resolved injected
    // value the scan re-derives and searches for.
    String authJson =
        "{\"tokens\":{\"access_token\":\"sk-codex-SUBSCRIPTION-LEAK-7a1b2c\"},"
            + "\"account_id\":\"acct_42\"}";
    RunnerSecretScanService subscriptionScan =
        new RunnerSecretScanService(
            workspaceStore,
            redaction,
            new RunnerSecretsService(
                new MockEnvironment().withProperty("CODEX_AUTH_JSON", authJson),
                RunnerProperties.defaults()));
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(
                new WorkspaceScanFile(
                    "logs/runner.stdout",
                    "agent dumped its auth.json " + authJson + " to the log")));

    RunnerSecretScanService.ScanOutcome outcome =
        subscriptionScan.scanWorkspace(REX_ID, RunnerKind.CODEX, RunnerStage.INVESTIGATION, RUN_ID);

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.leakedFile()).isEqualTo("logs/runner.stdout");
    assertThat(outcome.detectedCategories())
        .contains(RunnerSecretScanService.INJECTED_PROVIDER_KEY_CATEGORY);
  }

  @Test
  void detectsInjectedProviderKeyValueInLogs() {
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(
                new WorkspaceScanFile(
                    "logs/runner.stdout", "DEBUG auth header Bearer " + CODEX_VALUE)));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.leakedFile()).isEqualTo("logs/runner.stdout");
    assertThat(outcome.detectedCategories())
        .contains(RunnerSecretScanService.INJECTED_PROVIDER_KEY_CATEGORY);
  }

  @Test
  void scanLogsNeverContainTheSecretValue() {
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(List.of(new WorkspaceScanFile("logs/runner.stdout", "leaked " + CODEX_VALUE)));

    Logger scanLogger = (Logger) LoggerFactory.getLogger(RunnerSecretScanService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    scanLogger.addAppender(appender);
    try {
      scan();
    } finally {
      scanLogger.detachAppender(appender);
    }

    String allLogs =
        appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", (a, b) -> a + "\n" + b);
    // AC2/AC11(g): the leak-detection log line carries the relative path + category NAME, never the
    // value. The synthetic category label must be present (proving the leak WAS logged) but the
    // secret value must NOT appear at any level.
    assertThat(allLogs).contains("injected_provider_key");
    assertThat(allLogs).doesNotContain(CODEX_VALUE);
    assertThat(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN)).isTrue();
  }

  @Test
  void readOnlyInvestigationStageDoesNotScanRepoWorkingTree() {
    // The read-only INVESTIGATION stage cannot author repo content, so the pristine clone must NOT
    // be scanned — scanning third-party upstream files only false-positives the fuzzy ENV_VALUE
    // heuristic (this is what bricked the FIN-14/FIN-16 spec-stage runs). The runner's own
    // input/output/logs are still scanned.
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(List.of(new WorkspaceScanFile("output/result.json", "{\"ok\":true}")));

    scanService.scanWorkspace(REX_ID, RunnerKind.CODEX, RunnerStage.INVESTIGATION, RUN_ID);

    verify(workspaceStore).readFilesForSecretScan(REX_ID, false);
  }

  @Test
  void readWriteExecutionStageScansRepoWorkingTree() {
    // The EXECUTION stage authors repo changes that get pushed, so the working tree IS scanned
    // (still minus .git/) to catch a key leaked into committed code before captureAndPush.
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(List.of(new WorkspaceScanFile("output/result.json", "{\"ok\":true}")));

    scanService.scanWorkspace(REX_ID, RunnerKind.CODEX, RunnerStage.EXECUTION, RUN_ID);

    verify(workspaceStore).readFilesForSecretScan(REX_ID, true);
  }

  @Test
  void repoWorkingTreeFileSuppressesFuzzyHeuristicCategories() {
    // A normal config line in a committed repo file trips the fuzzy ENV_VALUE heuristic — this is
    // the exact false positive that froze FIN-14/FIN-16. For repo/ files it must NOT count as a
    // leak (the runner did not author the injected secret here; it is ordinary source/config).
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(
                new WorkspaceScanFile(
                    "repo/src/main/resources/application.properties",
                    "DB_PASSWORD=correcthorsebatterystaple")));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isFalse();
  }

  @Test
  void fuzzyHeuristicCategoryStillFlaggedInRunnerOutput() {
    // Control: the SAME content in the runner's own output/ IS still a leak — suppression is scoped
    // to the cloned repo working tree only, never the runner's produced artifacts/logs.
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(
                new WorkspaceScanFile(
                    "output/result.json", "DB_PASSWORD=correcthorsebatterystaple")));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.detectedCategories()).contains("ENV_VALUE");
  }

  @Test
  void repoWorkingTreeFileStillCatchesInjectedProviderKey() {
    // The precise injected-provider-key substring detector still runs over repo files — a runner
    // that wrote the secret WE injected into committed code is a real leak.
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(new WorkspaceScanFile("repo/src/Main.java", "// leaked " + CODEX_VALUE)));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.detectedCategories())
        .containsExactly(RunnerSecretScanService.INJECTED_PROVIDER_KEY_CATEGORY);
  }

  @Test
  void repoWorkingTreeFileStillCatchesStructuredCredentialShape() {
    // Strongly-structured credential SHAPES (a ghp_ GitHub token here) are unambiguous secrets in
    // any context and stay leak-worthy even in committed code — only the fuzzy categories are
    // suppressed for repo files.
    when(workspaceStore.readFilesForSecretScan(eq(REX_ID), anyBoolean()))
        .thenReturn(
            List.of(
                new WorkspaceScanFile("repo/src/Config.java", "String t = \"" + GH_TOKEN + "\";")));

    RunnerSecretScanService.ScanOutcome outcome = scan();

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.detectedCategories()).contains("GITHUB_TOKEN");
  }

  @Test
  void quarantineDelegatesToWorkspaceStore() {
    scanService.quarantine(
        REX_ID, "leakedFile=output/result.json categories=[injected_provider_key]");
    verify(workspaceStore)
        .writeQuarantineMarker(
            eq(REX_ID), eq("leakedFile=output/result.json categories=[injected_provider_key]"));
  }

  @Test
  void bundleRedactionEngineReplacesKnownSecretShapeWithPlaceholder() {
    // Story 3.5 AC11(f): the RedactionPolicyService that redacts the persisted context bundle turns
    // a known-shape secret (ghp_…) into a placeholder — the raw value never survives into the
    // bundle. (A bare provider-key VALUE would NOT be redacted here — that is the bundle's accepted
    // gap, which is why AC11(f) seeds a known-pattern shape, not a provider key.)
    RedactionResult result =
        redaction.redact("\"token\": \"" + GH_TOKEN + "\"", DataClassification.LOCAL_ONLY.value());

    assertThat(result.detectedCategories()).isNotEmpty();
    assertThat(result.sanitizedText()).doesNotContain(GH_TOKEN);
    assertThat(result.sanitizedText()).contains("[REDACTED_GITHUB_TOKEN]");
  }

  @Test
  void degradesToPatternOnlyWhenInjectedSecretAbsentAtScanTime() {
    // No env secret set → substring detector has nothing to compare; known-pattern detection still
    // runs. A bare provider-key value would then slip through (it matches no pattern), but a
    // ghp_-shaped token is still caught.
    RunnerSecretScanService degraded =
        new RunnerSecretScanService(
            workspaceStore,
            redaction,
            new RunnerSecretsService(new MockEnvironment(), RunnerProperties.defaults()));
    when(workspaceStore.readFilesForSecretScan(anyString(), anyBoolean()))
        .thenReturn(List.of(new WorkspaceScanFile("output/result.json", GH_TOKEN)));

    RunnerSecretScanService.ScanOutcome outcome =
        degraded.scanWorkspace(REX_ID, RunnerKind.CODEX, RunnerStage.EXECUTION, RUN_ID);

    assertThat(outcome.leakDetected()).isTrue();
    assertThat(outcome.detectedCategories()).contains("GITHUB_TOKEN");
  }
}
