package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class DoctorProbeAdapterTest {

  private final UuidV7Generator uuidV7Generator = new UuidV7Generator();

  @Test
  void javaVersionProbePassesOnCurrentJvm() {
    DoctorProbeAdapter adapter =
        newAdapter(new MockEnvironment().withProperty("a", "b"), Path.of("."), failingFactory());

    ProbeResult result = adapter.probeJavaVersion();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details().get("javaVersion")).isNotNull();
  }

  @Test
  void runnerSecretsProbePassesWhenEveryKindHasItsKey() {
    // Story 3.5 AC8: both runner kinds' provider keys present → PASS, presence-only details.
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("CODEX_API_KEY", "sk-codex-doctor-test")
            .withProperty("ANTHROPIC_API_KEY", "sk-ant-doctor-test");

    ProbeResult result = newAdapter(env, Path.of("."), failingFactory()).probeRunnerSecrets();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details())
        .containsEntry("codex", "present")
        .containsEntry("claude", "present");
    // Presence-only — no value leaks into details.
    assertThat(result.details().values()).containsOnly("present");
  }

  @Test
  void runnerSecretsProbeReportsCodexPresentOnSubscriptionAuthJsonOnly() {
    // Story 3a-3 (AC7): the name-driven probe needs NO code change for subscription auth —
    // CODEX_AUTH_JSON is simply the first configured name for Codex, so setting only it (no API
    // key) still resolves Codex to "present". Claude's key is provided so the all-kinds aggregate
    // is PASS; presence-only details, no value ever leaked.
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("CODEX_AUTH_JSON", "{\"tokens\":{\"access_token\":\"sk-sub-doctor\"}}")
            .withProperty("ANTHROPIC_API_KEY", "sk-ant-doctor-test");

    ProbeResult result = newAdapter(env, Path.of("."), failingFactory()).probeRunnerSecrets();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details())
        .containsEntry("codex", "present")
        .containsEntry("claude", "present");
    assertThat(result.details().values()).containsOnly("present");
  }

  @Test
  void runnerSecretsProbeFailsWhenAKindIsMissingItsKeyWithoutLeakingValues() {
    // Codex present, Claude absent → FAIL with DOCTOR_RUNNER_SECRET_MISSING.
    MockEnvironment env =
        new MockEnvironment().withProperty("CODEX_API_KEY", "sk-codex-doctor-test");

    ProbeResult result = newAdapter(env, Path.of("."), failingFactory()).probeRunnerSecrets();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING.value());
    assertThat(result.details())
        .containsEntry("codex", "present")
        .containsEntry("claude", "missing");
    assertThat(result.details().values()).doesNotContain("sk-codex-doctor-test");
  }

  @Test
  void springProfilesPassesForLocal() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local");

    ProbeResult result = newAdapter(env, Path.of("."), failingFactory()).probeSpringProfiles();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
  }

  @Test
  void springProfilesFailsOnBlockedProfile() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");

    ProbeResult result = newAdapter(env, Path.of("."), failingFactory()).probeSpringProfiles();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value());
  }

  @Test
  void springProfilesWarnsWhenNoActiveProfile() {
    MockEnvironment env = new MockEnvironment();

    ProbeResult result = newAdapter(env, Path.of("."), failingFactory()).probeSpringProfiles();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
  }

  @Test
  void postgresProbeFailsOnSqlException() throws SQLException {
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new SQLException("connection refused", "08001"));

    DoctorProbeAdapter adapter = newAdapterWithDataSource(ds);
    ProbeResult result = adapter.probePostgresConnectivity();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE.value());
  }

  @Test
  void postgresProbePassesWhenSelectOneSucceeds() throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    Statement statement = mock(Statement.class);
    when(ds.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getURL()).thenReturn("jdbc:postgresql://localhost:5432/deliveryline");
    when(connection.isValid(anyInt())).thenReturn(true);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute("SELECT 1")).thenReturn(true);

    DoctorProbeAdapter adapter = newAdapterWithDataSource(ds);
    ProbeResult result = adapter.probePostgresConnectivity();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details()).containsEntry("databaseUrlHost", "localhost");
    assertThat(result.details()).containsEntry("databasePort", "5432");
  }

  @Test
  void postgresProbeNeverIncludesRawJdbcUrl() throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    Statement statement = mock(Statement.class);
    when(ds.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getURL()).thenReturn("jdbc:postgresql://localhost:5432/db?user=foo&password=bar");
    when(connection.isValid(anyInt())).thenReturn(true);
    when(connection.createStatement()).thenReturn(statement);

    DoctorProbeAdapter adapter = newAdapterWithDataSource(ds);
    ProbeResult result = adapter.probePostgresConnectivity();

    assertThat(result.details().values()).noneMatch(v -> v != null && v.contains("password=bar"));
  }

  @Test
  void flywayProbeFailsWhenPendingMigrationExists() {
    Flyway flyway = mock(Flyway.class);
    MigrationInfoService infoService = mock(MigrationInfoService.class);
    MigrationInfo migration = mock(MigrationInfo.class);
    MigrationVersion version = MigrationVersion.fromVersion("7");
    when(migration.getState()).thenReturn(MigrationState.PENDING);
    when(migration.getVersion()).thenReturn(version);
    when(infoService.all()).thenReturn(new MigrationInfo[] {migration});
    when(flyway.info()).thenReturn(infoService);

    DoctorProbeAdapter adapter = newAdapterWithFlyway(flyway);
    ProbeResult result = adapter.probeFlywayState();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_FLYWAY_FAILED.value());
  }

  @Test
  void flywayProbePassesWhenAllMigrationsApplied() {
    Flyway flyway = mock(Flyway.class);
    MigrationInfoService infoService = mock(MigrationInfoService.class);
    MigrationInfo migration = mock(MigrationInfo.class);
    when(migration.getState()).thenReturn(MigrationState.SUCCESS);
    when(infoService.all()).thenReturn(new MigrationInfo[] {migration});
    when(flyway.info()).thenReturn(infoService);

    DoctorProbeAdapter adapter = newAdapterWithFlyway(flyway);
    ProbeResult result = adapter.probeFlywayState();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
  }

  @Test
  void artifactDirectoryProbePassesAndCleansUp(@TempDir Path tempHome) throws IOException {
    DoctorProbeAdapter adapter = newAdapterWithHome(tempHome);
    ProbeResult result = adapter.probeArtifactDirectory();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    Path artifactRoot = tempHome.resolve("artifacts");
    assertThat(artifactRoot).exists();
    try (var stream = Files.newDirectoryStream(artifactRoot, ".doctor-probe-*")) {
      assertThat(stream.iterator().hasNext()).isFalse();
    }
  }

  @Test
  void restBindProbePassesOnLoopback() {
    DoctorProbeAdapter adapter = newAdapter(new MockEnvironment(), Path.of("."), failingFactory());

    ProbeResult result = adapter.probeRestBindAddress();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
  }

  @Test
  void restBindProbeFailsOnZeroDotZero() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(
            new MockEnvironment(),
            mock(DataSource.class),
            mock(Flyway.class),
            uuidV7Generator,
            Path.of("."),
            "0.0.0.0",
            8080,
            Path.of("."),
            failingFactory());

    ProbeResult result = adapter.probeRestBindAddress();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE.value());
  }

  @Test
  void probeSupportedEnvironmentReturnsPassOnWindows11() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Windows 11",
            "10.0",
            "amd64",
            path -> Optional.empty(),
            () -> "7.4.1",
            () -> "unknown");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details()).containsEntry("os", "windows");
    assertThat(result.details()).containsEntry("shell", "powershell");
    assertThat(result.details()).containsEntry("matrixRow", "win11");
  }

  @Test
  void probeSupportedEnvironmentReturnsPassOnUbuntu2204() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Linux",
            "5.15.0-105-generic",
            "amd64",
            path ->
                path.endsWith("os-release")
                    ? Optional.of("ID=ubuntu\nVERSION_ID=\"22.04\"\n")
                    : Optional.of("Linux version 5.15.0-105-generic (buildd@lcy02-amd64-031)"),
            () -> "unknown",
            () -> "/bin/bash");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details()).containsEntry("os", "linux");
    assertThat(result.details()).containsEntry("shell", "bash");
    assertThat(result.details()).containsEntry("matrixRow", "ubuntu2204");
    assertThat(result.details()).containsEntry("dockerRuntime", "engine");
  }

  @Test
  void probeSupportedEnvironmentReturnsPassOnMacOs14() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Mac OS X",
            "14.5",
            "aarch64",
            path -> Optional.empty(),
            () -> "unknown",
            () -> "/bin/zsh");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details()).containsEntry("os", "macos");
    assertThat(result.details()).containsEntry("shell", "zsh");
    assertThat(result.details()).containsEntry("matrixRow", "macos14");
    assertThat(result.details()).containsEntry("dockerRuntime", "desktop");
  }

  @Test
  void probeSupportedEnvironmentWarnsOnUbuntu2004() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Linux",
            "5.4.0-170-generic",
            "amd64",
            path ->
                path.endsWith("os-release")
                    ? Optional.of("ID=ubuntu\nVERSION_ID=\"20.04\"\n")
                    : Optional.of("Linux version 5.4.0-170-generic (buildd@lcy02-amd64-031)"),
            () -> "unknown",
            () -> "/bin/bash");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.details()).containsEntry("matrixRow", "ubuntu2004");
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value());
  }

  @Test
  void probeSupportedEnvironmentFailsOnNonUbuntuLinux() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Linux",
            "6.6.0",
            "amd64",
            path ->
                path.endsWith("os-release")
                    ? Optional.of("ID=debian\nVERSION_ID=\"12\"\n")
                    : Optional.of("Linux version 6.6.0-generic"),
            () -> "unknown",
            () -> "/bin/bash");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.details()).containsEntry("matrixRow", "none");
    assertThat(result.summary()).contains("debian");
  }

  @Test
  void probeSupportedEnvironmentReturnsWarnOnWindows10() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Windows 10",
            "10.0",
            "amd64",
            path -> Optional.empty(),
            () -> "5.1.22000.4123",
            () -> "unknown");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value());
    assertThat(result.summary()).contains("near-miss");
  }

  @Test
  void probeSupportedEnvironmentReturnsWarnOnMacOs13() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Mac OS X",
            "13.6",
            "aarch64",
            path -> Optional.empty(),
            () -> "unknown",
            () -> "/bin/zsh");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value());
  }

  @Test
  void probeSupportedEnvironmentReturnsFailOnUnknownOs() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "SolarisHaiku",
            "1.0",
            "sparc",
            path -> Optional.empty(),
            () -> "unknown",
            () -> "/bin/tcsh");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value());
    assertThat(result.details()).containsEntry("matrixRow", "none");
    assertThat(result.details()).containsEntry("os", "solarishaiku");
    assertThat(result.details()).containsEntry("shell", "tcsh");
  }

  @Test
  void probeSupportedEnvironmentDetectsWsl2ViaProcVersion() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Linux",
            "5.15.146.1-microsoft-standard-WSL2",
            "amd64",
            path ->
                path.endsWith("os-release")
                    ? Optional.of("ID=ubuntu\nVERSION_ID=\"22.04\"\n")
                    : Optional.of("Linux version 5.15.146.1-microsoft-standard-WSL2 (root@WSL)"),
            () -> "unknown",
            () -> "/bin/bash");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details()).containsEntry("os", "wsl2");
    assertThat(result.details()).containsEntry("matrixRow", "wsl2");
    assertThat(result.details()).containsEntry("dockerRuntime", "desktop");
    assertThat(result.details().get("notes")).contains("WSL2");
  }

  @Test
  void probeSupportedEnvironmentFailsWhenShellIsOutsideSupportedMatrix() {
    DoctorProbeAdapter adapter =
        newAdapterWithEnvSeams(
            "Mac OS X",
            "14.5",
            "aarch64",
            path -> Optional.empty(),
            () -> "unknown",
            () -> "/bin/tcsh");

    ProbeResult result = adapter.probeSupportedEnvironment();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.summary()).contains("tcsh");
    assertThat(result.details()).containsEntry("shell", "tcsh");
  }

  @Test
  void probeSupportedEnvironmentEmitsDebugLogOnEntryExit() {
    Logger logger = (Logger) LoggerFactory.getLogger(DoctorProbeAdapter.class);
    Level previous = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      DoctorProbeAdapter adapter =
          newAdapterWithEnvSeams(
              "Linux",
              "5.15.0",
              "amd64",
              path ->
                  path.endsWith("os-release")
                      ? Optional.of("ID=ubuntu\nVERSION_ID=\"22.04\"\n")
                      : Optional.empty(),
              () -> "unknown",
              () -> "/bin/bash");

      adapter.probeSupportedEnvironment();

      boolean hasDebugSummary =
          appender.list.stream()
              .filter(e -> e.getLevel() == Level.DEBUG)
              .anyMatch(
                  e ->
                      e.getFormattedMessage().contains("probeSupportedEnvironment")
                          && e.getFormattedMessage().contains("osBucket=linux")
                          && e.getFormattedMessage().contains("matrixRow=ubuntu2204"));
      assertThat(hasDebugSummary).isTrue();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }
  }

  @Test
  void dockerProbeWarnsOnIoException() {
    ProcessLauncher throwing =
        pb -> {
          throw new IOException("docker not found");
        };
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(
            new MockEnvironment(),
            mock(DataSource.class),
            mock(Flyway.class),
            uuidV7Generator,
            Path.of(System.getProperty("java.io.tmpdir"), "deliveryline-doctor-test"),
            "localhost",
            8080,
            Path.of("."),
            throwing);

    ProbeResult result = adapter.probeDockerAvailability();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_DOCKER_MISSING.value());
  }

  // ---- Story 3c-10 (AC1/AC2/AC3/AC6) — the `projects` probe ----

  @Test
  void projectsProbeSkipsWhenStoreUnavailable() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(
            new MockEnvironment(), (ProjectStore) null, mock(ProjectConnectorResolver.class));

    ProbeResult result = adapter.probeProjects();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.SKIP);
    assertThat(result.details()).containsEntry("reason", "project_store_unavailable");
  }

  @Test
  void projectsProbePassesWhenActiveProjectFullyConfigured() {
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll())
        .thenReturn(List.of(activeProject("prj_default", "https://github.com/acme/repo")));
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(credEnv(), store, mock(ProjectConnectorResolver.class));

    ProbeResult result = adapter.probeProjects();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details())
        .containsEntry("prj_default.status", "active")
        .containsEntry("prj_default.ticketSourceKind", "linear")
        .containsEntry("prj_default.repoHostKind", "github")
        .containsEntry("prj_default.repositoryBound", "true")
        .containsEntry("prj_default.openspec", "false")
        .containsEntry("cred:prj_default:ticket_source", "present-via-host-env")
        .containsEntry("cred:prj_default:repo_host", "present-via-host-env")
        .containsEntry(
            "connectionTest", "not-run (use the project testConnection endpoint, story 3c-8)");
  }

  @Test
  void projectsProbeWarnsOnBlankRepositoryUrl() {
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll()).thenReturn(List.of(activeProject("prj_default", null)));
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(credEnv(), store, mock(ProjectConnectorResolver.class));

    ProbeResult result = adapter.probeProjects();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_PROJECT_CONFIG_INCOMPLETE.value());
    assertThat(result.details()).containsEntry("prj_default.reason", "blank_repo");
  }

  @Test
  void projectsProbeWarnsOnMissingCredential() {
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll())
        .thenReturn(List.of(activeProject("prj_default", "https://github.com/acme/repo")));
    // No store secret (resolver default empty) and no host-env tokens → missing.
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(new MockEnvironment(), store, mock(ProjectConnectorResolver.class));

    ProbeResult result = adapter.probeProjects();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_PROJECT_CONFIG_INCOMPLETE.value());
    assertThat(result.details())
        .containsEntry("prj_default.reason", "missing_credential")
        .containsEntry("cred:prj_default:ticket_source", "missing")
        .containsEntry("cred:prj_default:repo_host", "missing");
  }

  @Test
  void projectsProbeWarnsOnUnsupportedKindWithoutThrowing() {
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll())
        .thenReturn(List.of(activeProject("prj_default", "https://github.com/acme/repo")));
    ProjectConnectorResolver resolver = mock(ProjectConnectorResolver.class);
    when(resolver.resolveTicketSource(any(Project.class)))
        .thenThrow(new DomainException(DomainErrorCode.UNSUPPORTED_CONNECTOR_KIND, "no adapter"));
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(credEnv(), store, resolver);

    ProbeResult result = adapter.probeProjects();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.details())
        .containsEntry("prj_default.kindsResolvable", "false")
        .containsEntry("prj_default.reason", "unsupported_kind");
  }

  @Test
  void projectsProbeListsDisabledProjectButExcludesItFromRollup() {
    ProjectStore store = mock(ProjectStore.class);
    // The disabled project carries a blank repo URL; it would WARN if active, but must NOT.
    when(store.findAll())
        .thenReturn(
            List.of(
                project("prj_disabled", ProjectStatus.DISABLED, null),
                activeProject("prj_active", "https://github.com/acme/repo")));
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(credEnv(), store, mock(ProjectConnectorResolver.class));

    ProbeResult result = adapter.probeProjects();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details())
        .containsEntry("prj_disabled.status", "disabled")
        .doesNotContainKey("cred:prj_disabled:ticket_source")
        .doesNotContainKey("prj_disabled.reason");
  }

  @Test
  void projectsProbeReportsPresentViaStoreWithoutLeaking() {
    String secret = "stored-secret-should-never-appear";
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll())
        .thenReturn(List.of(activeProject("prj_default", "https://github.com/acme/repo")));
    ProjectConnectorResolver resolver = mock(ProjectConnectorResolver.class);
    when(resolver.resolveConnectorSecret(any(Project.class), anyString()))
        .thenReturn(Optional.of(secret));
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(new MockEnvironment(), store, resolver);

    ProbeResult result = adapter.probeProjects();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.details())
        .containsEntry("cred:prj_default:ticket_source", "present-via-store")
        .containsEntry("cred:prj_default:repo_host", "present-via-store");
    assertThat(result.details().values()).noneMatch(v -> v != null && v.contains(secret));
  }

  @Test
  void projectsProbeNeverLeaksAHostEnvSecretValue() {
    String secret = "super-secret-LINEAR-value-should-never-appear";
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll())
        .thenReturn(List.of(activeProject("prj_default", "https://github.com/acme/repo")));
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("LINEAR_API_TOKEN", secret)
            .withProperty("GITHUB_TOKEN", secret);
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(env, store, mock(ProjectConnectorResolver.class));

    ProbeResult result = adapter.probeProjects();

    assertThat(result.details().get("cred:prj_default:ticket_source"))
        .isEqualTo("present-via-host-env");
    assertThat(result.summary()).doesNotContain(secret);
    assertThat(result.details().values()).noneMatch(v -> v != null && v.contains(secret));
  }

  @Test
  void projectsProbeLogsPinBranchesAndNeverLeakASecret() {
    String secret = "LINEAR-token-value-must-never-be-logged";
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll())
        .thenReturn(List.of(activeProject("prj_default", "https://github.com/acme/repo")));
    // ticket_source present-via-host-env (secret value), repo_host missing → WARN
    // missing_credential.
    MockEnvironment env = new MockEnvironment().withProperty("LINEAR_API_TOKEN", secret);
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(env, store, mock(ProjectConnectorResolver.class));

    Logger logger = (Logger) LoggerFactory.getLogger(DoctorProbeAdapter.class);
    Level previous = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      adapter.probeProjects();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }

    List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    // Entry/result branch (INFO) + per-misconfigured-active branch (WARN) are pinned.
    assertThat(messages).anyMatch(m -> m.contains("doctor projects probe resolution=warn"));
    assertThat(messages)
        .anyMatch(
            m ->
                m.contains("doctor projects probe misconfigured")
                    && m.contains("activeProjectId=prj_default")
                    && m.contains("reason=missing_credential"));
    // Leak-proof: the resolved secret never reaches a log line.
    assertThat(messages).noneMatch(m -> m.contains(secret));
  }

  @Test
  void projectsProbeSkipBranchIsLogged() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(new MockEnvironment(), (ProjectStore) null, null);

    Logger logger = (Logger) LoggerFactory.getLogger(DoctorProbeAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      adapter.probeProjects();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
        .anyMatch(m -> m.contains("doctor projects probe resolution=skip"));
  }

  private static MockEnvironment credEnv() {
    return new MockEnvironment()
        .withProperty("LINEAR_API_TOKEN", "lin-secret")
        .withProperty("GITHUB_TOKEN", "gh-secret");
  }

  private static Project activeProject(String publicId, String repositoryUrl) {
    return project(publicId, ProjectStatus.ACTIVE, repositoryUrl);
  }

  private static Project project(String publicId, ProjectStatus status, String repositoryUrl) {
    return new Project(
        publicId,
        "Name " + publicId,
        "slug-" + publicId,
        status,
        repositoryUrl,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }

  private DoctorProbeAdapter newAdapter(
      Environment env, Path workingDir, ProcessLauncher processLauncher) {
    return new DoctorProbeAdapter(
        env,
        mock(DataSource.class),
        mock(Flyway.class),
        uuidV7Generator,
        Path.of(System.getProperty("java.io.tmpdir"), "deliveryline-doctor-test"),
        "localhost",
        8080,
        workingDir,
        processLauncher);
  }

  private DoctorProbeAdapter newAdapterWithDataSource(DataSource dataSource) {
    return new DoctorProbeAdapter(
        new MockEnvironment(),
        dataSource,
        mock(Flyway.class),
        uuidV7Generator,
        Path.of(System.getProperty("java.io.tmpdir"), "deliveryline-doctor-test"),
        "localhost",
        8080,
        Path.of("."),
        failingFactory());
  }

  private DoctorProbeAdapter newAdapterWithFlyway(Flyway flyway) {
    return new DoctorProbeAdapter(
        new MockEnvironment(),
        mock(DataSource.class),
        flyway,
        uuidV7Generator,
        Path.of(System.getProperty("java.io.tmpdir"), "deliveryline-doctor-test"),
        "localhost",
        8080,
        Path.of("."),
        failingFactory());
  }

  private DoctorProbeAdapter newAdapterWithHome(Path home) {
    return new DoctorProbeAdapter(
        new MockEnvironment(),
        mock(DataSource.class),
        mock(Flyway.class),
        uuidV7Generator,
        home,
        "localhost",
        8080,
        Path.of("."),
        failingFactory());
  }

  private DoctorProbeAdapter newAdapterWithEnvSeams(
      String osName,
      String osVersion,
      String osArch,
      Function<Path, Optional<String>> procVersionReader,
      Supplier<String> powerShellVersionSupplier,
      Supplier<String> shellEnvSupplier) {
    return new DoctorProbeAdapter(
        new MockEnvironment(),
        mock(DataSource.class),
        mock(Flyway.class),
        uuidV7Generator,
        Path.of(System.getProperty("java.io.tmpdir"), "deliveryline-doctor-test"),
        "localhost",
        8080,
        Path.of("."),
        failingFactory(),
        () -> osName,
        () -> osVersion,
        () -> osArch,
        procVersionReader,
        procVersionReader,
        powerShellVersionSupplier,
        shellEnvSupplier,
        org.dradgo.application.runner.RunnerProperties.defaults(),
        null,
        null,
        org.dradgo.application.workflow.WorkflowProperties.defaults(),
        () -> -1L,
        null,
        null);
  }

  private static ProcessLauncher failingFactory() {
    return pb -> {
      throw new UnsupportedOperationException("process factory should not be invoked in this test");
    };
  }
}
