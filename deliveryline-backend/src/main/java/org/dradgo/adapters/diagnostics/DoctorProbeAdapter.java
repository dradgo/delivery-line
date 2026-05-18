package org.dradgo.adapters.diagnostics;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.DoctorProbePort;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.domain.registry.DomainErrorCode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DoctorProbeAdapter implements DoctorProbePort {

  private static final Logger log = LoggerFactory.getLogger(DoctorProbeAdapter.class);

  private static final Set<String> BLOCKED_PROFILES = Set.of("prod", "production", "prd");
  private static final Set<String> ALLOWED_PROFILES = Set.of("local", "test", "demo");
  private static final int JAVA_VERSION_MIN = 21;
  private static final int POSTGRES_TIMEOUT_SECONDS = 5;
  private static final int DOCKER_TIMEOUT_SECONDS = 3;
  private static final Executor DIRECT_EXECUTOR = Runnable::run;
  private static final Pattern JDBC_HOST_PORT =
      Pattern.compile("^jdbc:[^:]+://([^:/?]+)(?::(\\d+))?(?:[/?].*)?$", Pattern.CASE_INSENSITIVE);
  private static final List<String> CONFIG_GLOBS = List.of(".env", "application-*.yml");

  private final Environment environment;
  // Nullable so the adapter can be wired in lean Spring contexts (e.g. the
  // doctor-smoke CI job) where DataSource/Flyway autoconfig is excluded.
  // Probes that need these beans return SKIP rather than crashing.
  @Nullable private final DataSource dataSource;
  @Nullable private final Flyway flyway;
  private final UuidV7Generator uuidV7Generator;
  private final Path deliverylineHome;
  private final String serverAddress;
  private final int serverPort;
  private final Path workingDirectory;
  private final ProcessLauncher processLauncher;
  private final Supplier<String> osNameSupplier;
  private final Supplier<String> osVersionSupplier;
  private final Supplier<String> osArchSupplier;
  private final Function<Path, Optional<String>> procVersionReader;
  private final Function<Path, Optional<String>> osReleaseReader;
  private final Supplier<String> powerShellVersionSupplier;
  private final Supplier<String> shellEnvSupplier;

  @Autowired
  public DoctorProbeAdapter(
      Environment environment,
      // ObjectProvider lets the doctor adapter wire in Spring contexts that
      // intentionally omit DataSource/Flyway autoconfig (e.g. CI doctor-smoke
      // running without a real Postgres). The corresponding probes return
      // SKIP when the beans are absent.
      ObjectProvider<DataSource> dataSourceProvider,
      ObjectProvider<Flyway> flywayProvider,
      UuidV7Generator uuidV7Generator,
      @Value("${deliveryline.home}") String deliverylineHome,
      @Value("${server.address:localhost}") String serverAddress,
      @Value("${server.port:8080}") int serverPort) {
    this(
        environment,
        dataSourceProvider.getIfAvailable(),
        flywayProvider.getIfAvailable(),
        uuidV7Generator,
        Path.of(deliverylineHome),
        serverAddress,
        serverPort,
        Path.of(System.getProperty("user.dir")),
        ProcessBuilder::start,
        () -> System.getProperty("os.name", "unknown"),
        () -> System.getProperty("os.version", "unknown"),
        () -> System.getProperty("os.arch", "unknown"),
        DoctorProbeAdapter::defaultFileRead,
        DoctorProbeAdapter::defaultFileRead,
        DoctorProbeAdapter::defaultPowerShellVersion,
        DoctorProbeAdapter::defaultShellValue);
  }

  // Public so tests in other packages can construct an adapter without the
  // Spring-DI ObjectProvider machinery. Not @Autowired — Spring uses the
  // @Autowired constructor above for bean wiring.
  public DoctorProbeAdapter(
      Environment environment,
      @Nullable DataSource dataSource,
      @Nullable Flyway flyway,
      UuidV7Generator uuidV7Generator,
      Path deliverylineHome,
      String serverAddress,
      int serverPort,
      Path workingDirectory,
      ProcessLauncher processLauncher) {
    this(
        environment,
        dataSource,
        flyway,
        uuidV7Generator,
        deliverylineHome,
        serverAddress,
        serverPort,
        workingDirectory,
        processLauncher,
        () -> System.getProperty("os.name", "unknown"),
        () -> System.getProperty("os.version", "unknown"),
        () -> System.getProperty("os.arch", "unknown"),
        DoctorProbeAdapter::defaultFileRead,
        DoctorProbeAdapter::defaultFileRead,
        DoctorProbeAdapter::defaultPowerShellVersion,
        DoctorProbeAdapter::defaultShellValue);
  }

  DoctorProbeAdapter(
      Environment environment,
      @Nullable DataSource dataSource,
      @Nullable Flyway flyway,
      UuidV7Generator uuidV7Generator,
      Path deliverylineHome,
      String serverAddress,
      int serverPort,
      Path workingDirectory,
      ProcessLauncher processLauncher,
      Supplier<String> osNameSupplier,
      Supplier<String> osVersionSupplier,
      Supplier<String> osArchSupplier,
      Function<Path, Optional<String>> procVersionReader,
      Function<Path, Optional<String>> osReleaseReader,
      Supplier<String> powerShellVersionSupplier,
      Supplier<String> shellEnvSupplier) {
    this.environment = environment;
    this.dataSource = dataSource;
    this.flyway = flyway;
    this.uuidV7Generator = uuidV7Generator;
    this.deliverylineHome = deliverylineHome;
    this.serverAddress = serverAddress;
    this.serverPort = serverPort;
    this.workingDirectory = workingDirectory;
    this.processLauncher = processLauncher;
    this.osNameSupplier = osNameSupplier;
    this.osVersionSupplier = osVersionSupplier;
    this.osArchSupplier = osArchSupplier;
    this.procVersionReader = procVersionReader;
    this.osReleaseReader = osReleaseReader;
    this.powerShellVersionSupplier = powerShellVersionSupplier;
    this.shellEnvSupplier = shellEnvSupplier;
  }

  private static Optional<String> defaultFileRead(Path path) {
    try {
      if (!Files.isRegularFile(path)) {
        return Optional.empty();
      }
      return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException io) {
      return Optional.empty();
    } catch (RuntimeException re) {
      return Optional.empty();
    }
  }

  private static String defaultPowerShellVersion() {
    String value = System.getProperty("deliveryline.shell.version");
    if (value == null || value.isBlank()) {
      value = System.getenv("DELIVERYLINE_POWERSHELL_VERSION");
    }
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String defaultShellValue() {
    String value = System.getProperty("deliveryline.shell");
    if (value == null || value.isBlank()) {
      value = System.getenv("SHELL");
    }
    return value == null || value.isBlank() ? "unknown" : value;
  }

  @Override
  public ProbeResult probeJavaVersion() {
    int feature = Runtime.version().feature();
    Map<String, String> details = new LinkedHashMap<>();
    details.put("javaVersion", String.valueOf(feature));
    details.put("javaVendor", System.getProperty("java.vendor", "unknown"));
    if (feature >= JAVA_VERSION_MIN) {
      return new ProbeResult(DiagnosticsStatus.PASS, "Java " + feature, null, details);
    }
    return new ProbeResult(
        DiagnosticsStatus.FAIL,
        "Java " + feature + " detected; Java " + JAVA_VERSION_MIN + "+ required",
        null,
        details);
  }

  @Override
  public ProbeResult probeSpringProfiles() {
    Set<String> active = new LinkedHashSet<>(List.of(environment.getActiveProfiles()));
    Map<String, String> details = new LinkedHashMap<>();
    details.put("activeProfiles", active.isEmpty() ? "default" : String.join(",", active));

    for (String profile : active) {
      if (BLOCKED_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
        return new ProbeResult(
            DiagnosticsStatus.FAIL,
            "Production-class profile '" + profile + "' is not supported in this environment",
            DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value(),
            details);
      }
    }

    if (active.isEmpty()) {
      return new ProbeResult(
          DiagnosticsStatus.WARN,
          "No Spring profile active; running with 'default'",
          DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value(),
          details);
    }

    boolean anyAllowed =
        active.stream().map(p -> p.toLowerCase(Locale.ROOT)).anyMatch(ALLOWED_PROFILES::contains);
    if (!anyAllowed) {
      return new ProbeResult(
          DiagnosticsStatus.WARN,
          "No supported profile active; expected one of " + ALLOWED_PROFILES,
          DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value(),
          details);
    }

    return new ProbeResult(
        DiagnosticsStatus.PASS,
        "Active Spring profiles: " + String.join(",", active),
        null,
        details);
  }

  @Override
  public ProbeResult probePostgresConnectivity() {
    Map<String, String> details = new LinkedHashMap<>();
    if (dataSource == null) {
      // No DataSource bean in the context (lean Spring boot for doctor smoke).
      // SKIP rather than FAIL — the operator hasn't configured persistence, so
      // we have nothing to probe; doctor's aggregate verdict isn't affected.
      details.put("reason", "datasource_bean_unavailable");
      return new ProbeResult(
          DiagnosticsStatus.SKIP, "DataSource bean not present in context", null, details);
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setNetworkTimeout(DIRECT_EXECUTOR, POSTGRES_TIMEOUT_SECONDS * 1000);
      try {
        String rawUrl = connection.getMetaData().getURL();
        populateUrlDetails(rawUrl, details);
      } catch (SQLException metadataError) {
        log.debug("Failed to read JDBC metadata for doctor probe", metadataError);
      }
      if (!connection.isValid(POSTGRES_TIMEOUT_SECONDS)) {
        return new ProbeResult(
            DiagnosticsStatus.FAIL,
            "Postgres connection validation timed out",
            DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE.value(),
            details);
      }
      try (Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(POSTGRES_TIMEOUT_SECONDS);
        statement.execute("SELECT 1");
      }
      return new ProbeResult(DiagnosticsStatus.PASS, "Postgres reachable", null, details);
    } catch (SQLException error) {
      details.put("sqlState", error.getSQLState() == null ? "unknown" : error.getSQLState());
      return new ProbeResult(
          DiagnosticsStatus.FAIL,
          "Postgres unreachable: " + safeExceptionMessage(error),
          DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE.value(),
          details);
    }
  }

  @Override
  public ProbeResult probeFlywayState() {
    if (flyway == null) {
      // No Flyway bean (lean doctor-smoke boot). SKIP — symmetric with the
      // postgres-connectivity probe above.
      Map<String, String> skipped = new LinkedHashMap<>();
      skipped.put("reason", "flyway_bean_unavailable");
      return new ProbeResult(
          DiagnosticsStatus.SKIP, "Flyway bean not present in context", null, skipped);
    }
    MigrationInfo[] all;
    try {
      all = flyway.info().all();
    } catch (RuntimeException error) {
      return new ProbeResult(
          DiagnosticsStatus.FAIL,
          "Flyway info() raised: " + safeExceptionMessage(error),
          DomainErrorCode.DOCTOR_FLYWAY_FAILED.value(),
          Map.of());
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("migrationCount", String.valueOf(all.length));
    for (MigrationInfo info : all) {
      MigrationState state = info.getState();
      if (state == MigrationState.PENDING
          || state == MigrationState.FAILED
          || state == MigrationState.OUT_OF_ORDER) {
        details.put("offendingMigration", String.valueOf(info.getVersion()));
        details.put("offendingState", state.name());
        return new ProbeResult(
            DiagnosticsStatus.FAIL,
            "Flyway migration in non-applied state: " + state.name(),
            DomainErrorCode.DOCTOR_FLYWAY_FAILED.value(),
            details);
      }
    }
    return new ProbeResult(DiagnosticsStatus.PASS, "Flyway up to date", null, details);
  }

  @Override
  public ProbeResult probeArtifactDirectory() {
    Path artifactRoot = deliverylineHome.resolve("artifacts");
    Map<String, String> details = new LinkedHashMap<>();
    details.put("artifactRoot", artifactRoot.toString());
    Path probe = null;
    try {
      Files.createDirectories(artifactRoot);
      String probeName = ".doctor-probe-" + uuidV7Generator.generate();
      probe = artifactRoot.resolve(probeName);
      Files.write(probe, new byte[] {0});
      return new ProbeResult(DiagnosticsStatus.PASS, "Artifact directory writable", null, details);
    } catch (IOException error) {
      details.put("ioError", error.getClass().getSimpleName());
      return new ProbeResult(
          DiagnosticsStatus.FAIL,
          "Artifact directory unwritable: " + error.getClass().getSimpleName(),
          DomainErrorCode.DOCTOR_ARTIFACT_DIR_UNWRITABLE.value(),
          details);
    } finally {
      if (probe != null) {
        try {
          Files.deleteIfExists(probe);
        } catch (IOException cleanupError) {
          log.warn(
              "Failed to remove doctor probe file after diagnostics cleanup; cleanupError={}",
              cleanupError.getClass().getSimpleName());
        }
      }
    }
  }

  @Override
  public ProbeResult probeConfigFilePermissions() {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return new ProbeResult(
          DiagnosticsStatus.SKIP,
          "Permission check skipped on non-POSIX filesystem",
          null,
          Map.of());
    }

    List<Path> candidates = resolveConfigCandidates();
    Map<String, String> details = new LinkedHashMap<>();
    details.put("candidatesChecked", String.valueOf(candidates.size()));
    List<String> offenders = new java.util.ArrayList<>();
    for (Path path : candidates) {
      try {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
          offenders.add(
              path.getFileName().toString() + "=" + PosixFilePermissions.toString(permissions));
        }
      } catch (UnsupportedOperationException unsupported) {
        return new ProbeResult(
            DiagnosticsStatus.SKIP,
            "Permission check skipped on non-POSIX filesystem",
            null,
            Map.of());
      } catch (IOException io) {
        log.debug("Failed to read POSIX permissions for {}", path, io);
      }
    }
    if (!offenders.isEmpty()) {
      details.put("offenders", String.join(",", offenders));
      return new ProbeResult(
          DiagnosticsStatus.FAIL,
          "Config files have world-readable bits: " + offenders.size() + " offender(s)",
          DomainErrorCode.DOCTOR_CONFIG_PERMISSIONS_UNSAFE.value(),
          details);
    }
    return new ProbeResult(
        DiagnosticsStatus.PASS, "Config file permissions are restrictive", null, details);
  }

  @Override
  public ProbeResult probeDockerAvailability() {
    Map<String, String> details = new LinkedHashMap<>();
    ProcessBuilder builder =
        new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
            .redirectErrorStream(true);
    Process process = null;
    try {
      process = processLauncher.launch(builder);
      boolean exited = process.waitFor(DOCKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        details.put("dockerProbe", "timed-out");
        return new ProbeResult(
            DiagnosticsStatus.WARN,
            "Docker daemon did not respond within " + DOCKER_TIMEOUT_SECONDS + "s",
            DomainErrorCode.DOCTOR_DOCKER_MISSING.value(),
            details);
      }
      int exit = process.exitValue();
      details.put("dockerExitCode", String.valueOf(exit));
      if (exit == 0) {
        return new ProbeResult(DiagnosticsStatus.PASS, "Docker reachable", null, details);
      }
      return new ProbeResult(
          DiagnosticsStatus.WARN,
          "Docker probe failed with exit code " + exit,
          DomainErrorCode.DOCTOR_DOCKER_MISSING.value(),
          details);
    } catch (IOException io) {
      details.put("dockerProbe", "binary-missing");
      return new ProbeResult(
          DiagnosticsStatus.WARN,
          "Docker daemon unreachable; runner integration disabled",
          DomainErrorCode.DOCTOR_DOCKER_MISSING.value(),
          details);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      details.put("dockerProbe", "interrupted");
      return new ProbeResult(
          DiagnosticsStatus.WARN,
          "Docker probe interrupted",
          DomainErrorCode.DOCTOR_DOCKER_MISSING.value(),
          details);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  @Override
  public ProbeResult probeRestBindAddress() {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("serverAddress", serverAddress);
    details.put("serverPort", String.valueOf(serverPort));
    try {
      InetAddress resolved = InetAddress.getByName(serverAddress);
      if (!resolved.isLoopbackAddress()) {
        return new ProbeResult(
            DiagnosticsStatus.FAIL,
            "server.address resolves to non-loopback (" + resolved.getHostAddress() + ")",
            DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE.value(),
            details);
      }
      return new ProbeResult(
          DiagnosticsStatus.PASS, "REST bind address resolves to loopback", null, details);
    } catch (UnknownHostException unknown) {
      return new ProbeResult(
          DiagnosticsStatus.FAIL,
          "server.address could not be resolved: " + serverAddress,
          DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE.value(),
          details);
    }
  }

  @Override
  public ProbeResult probeSupportedEnvironment() {
    String osNameRaw = safeString(osNameSupplier, "unknown");
    String osVersionRaw = safeString(osVersionSupplier, "unknown");
    String osArchRaw = safeString(osArchSupplier, "unknown");
    String lowerOs = osNameRaw.toLowerCase(Locale.ROOT);
    String detectedShell = detectShellName(safeString(shellEnvSupplier, "unknown"));

    String bucket;
    String matrixOs;
    boolean wslDetected = false;
    boolean wsl2 = false;
    LinuxRelease linuxRelease = LinuxRelease.unknown();
    if (lowerOs.startsWith("windows")) {
      bucket = "windows";
      matrixOs = "windows";
    } else if (lowerOs.contains("mac") || lowerOs.contains("darwin")) {
      bucket = "macos";
      matrixOs = "macos";
    } else if (lowerOs.contains("linux")) {
      Optional<String> procVersion = safeReadProcVersion(Path.of("/proc/version"));
      if (procVersion.isPresent()) {
        String lower = procVersion.get().toLowerCase(Locale.ROOT);
        // Require BOTH "microsoft" AND "wsl" substrings to avoid false positives on
        // native Linux kernels that happen to contain "microsoft" in a build banner
        // or vendor patch line. Real WSL /proc/version always contains both tokens.
        if (lower.contains("microsoft") && lower.contains("wsl")) {
          wslDetected = true;
          wsl2 = lower.contains("wsl2");
        }
      }
      linuxRelease = parseLinuxRelease(safeReadOsRelease(Path.of("/etc/os-release")).orElse(""));
      bucket = wsl2 ? "wsl2" : wslDetected ? "wsl" : "linux";
      matrixOs = "linux";
    } else {
      Map<String, String> unknownDetails =
          environmentDetails(
              lowerOs, osVersionRaw, osArchRaw, detectedShell, "unknown", "unknown", "none", null);
      log.debug("probeSupportedEnvironment unknown os bucket osName={}", lowerOs);
      return new ProbeResult(
          DiagnosticsStatus.FAIL,
          "Unsupported OS detected; combination is outside the supported matrix",
          DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value(),
          unknownDetails);
    }

    String shell;
    String shellVersion;
    if ("windows".equals(matrixOs)) {
      shell = "powershell";
      shellVersion = safeString(powerShellVersionSupplier, "unknown");
    } else {
      shell = detectedShell;
      shellVersion = "unknown";
    }

    String dockerRuntime;
    if ("windows".equals(matrixOs) || "macos".equals(matrixOs)) {
      dockerRuntime = "desktop";
    } else if (wsl2) {
      dockerRuntime = "desktop";
    } else {
      dockerRuntime = "engine";
    }

    MatrixDecision decision =
        lookupMatrix(
            matrixOs,
            osNameRaw,
            osVersionRaw,
            wslDetected,
            wsl2,
            shell,
            shellVersion,
            linuxRelease);
    String notes =
        wsl2
            ? "WSL2 detected; Docker integration may need manual enable — see docs/supported-environments.md"
            : null;
    Map<String, String> details =
        environmentDetails(
            bucket,
            osVersionRaw,
            osArchRaw,
            shell,
            shellVersion,
            dockerRuntime,
            decision.row(),
            notes);

    log.debug(
        "probeSupportedEnvironment osBucket={} matrixRow={} status={}",
        bucket,
        decision.row(),
        decision.status());

    if (decision.status() == DiagnosticsStatus.PASS) {
      return new ProbeResult(DiagnosticsStatus.PASS, decision.summary(), null, details);
    }
    return new ProbeResult(
        decision.status(),
        decision.summary(),
        DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value(),
        details);
  }

  private MatrixDecision lookupMatrix(
      String matrixOs,
      String osNameRaw,
      String osVersionRaw,
      boolean wslDetected,
      boolean wsl2,
      String shell,
      String shellVersion,
      LinuxRelease linuxRelease) {
    return switch (matrixOs) {
      case "windows" -> classifyWindows(osNameRaw, osVersionRaw, shellVersion);
      case "macos" -> classifyMacOs(osVersionRaw, shell);
      case "linux" -> classifyLinux(osVersionRaw, wslDetected, wsl2, shell, linuxRelease);
      default -> new MatrixDecision(DiagnosticsStatus.FAIL, "none", "OS bucket not in matrix");
    };
  }

  private MatrixDecision classifyWindows(
      String osNameRaw, String osVersionRaw, String shellVersion) {
    String osVersion = osVersionRaw == null ? "" : osVersionRaw;
    String osNameLower = osNameRaw == null ? "" : osNameRaw.toLowerCase(Locale.ROOT);
    int shellMajor = parseLeadingInt(shellVersion);
    if (!"unknown".equals(shellVersion) && shellMajor > 0 && shellMajor != 5 && shellMajor < 7) {
      return new MatrixDecision(
          DiagnosticsStatus.FAIL,
          "none",
          "PowerShell " + shellVersion + " is outside the supported matrix");
    }
    if ("unknown".equals(shellVersion)) {
      return new MatrixDecision(
          DiagnosticsStatus.WARN,
          "win11",
          "PowerShell detected but version is unavailable; supported matrix requires 5.1 or 7+");
    }
    if (osNameLower.contains("windows 11")) {
      return new MatrixDecision(
          DiagnosticsStatus.PASS,
          "win11",
          "Windows 11 + PowerShell " + shellVersion + " in supported matrix");
    }
    if (osNameLower.contains("windows 10")) {
      return new MatrixDecision(
          DiagnosticsStatus.WARN,
          "win10-nearmiss",
          "Windows 10 not in matrix; Windows 11 is supported (near-miss WARN)");
    }
    if (osVersion.startsWith("10.")) {
      return new MatrixDecision(
          DiagnosticsStatus.WARN,
          "win10-nearmiss",
          "Windows " + osVersion + " near-miss WARN; matrix targets Windows 11");
    }
    return new MatrixDecision(
        DiagnosticsStatus.FAIL,
        "none",
        "Windows version " + osVersion + " is not in the supported matrix");
  }

  private MatrixDecision classifyMacOs(String osVersionRaw, String shell) {
    String osVersion = osVersionRaw == null ? "" : osVersionRaw;
    if (!"bash".equals(shell) && !"zsh".equals(shell)) {
      return new MatrixDecision(
          DiagnosticsStatus.FAIL,
          "none",
          "macOS shell " + shell + " is outside the supported matrix");
    }
    int major = parseLeadingInt(osVersion);
    if (major >= 14) {
      return new MatrixDecision(
          DiagnosticsStatus.PASS,
          "macos14",
          "macOS " + osVersion + " in supported matrix (>= 14 Sonoma)");
    }
    if (major == 13) {
      return new MatrixDecision(
          DiagnosticsStatus.WARN,
          "macos14",
          "macOS 13 (Ventura) not in matrix; macOS 14 (Sonoma) supported (near-miss WARN)");
    }
    return new MatrixDecision(
        DiagnosticsStatus.FAIL, "none", "macOS " + osVersion + " is not in the supported matrix");
  }

  private MatrixDecision classifyLinux(
      String osVersionRaw,
      boolean wslDetected,
      boolean wsl2,
      String shell,
      LinuxRelease linuxRelease) {
    if (!"bash".equals(shell)) {
      return new MatrixDecision(
          DiagnosticsStatus.FAIL,
          "none",
          "Linux shell " + shell + " is outside the supported matrix");
    }
    if (wslDetected && !wsl2) {
      return new MatrixDecision(
          DiagnosticsStatus.FAIL,
          "none",
          "WSL variant is outside the supported matrix; require WSL2 Ubuntu 22.04+");
    }
    if ("unknown".equals(linuxRelease.id())) {
      return new MatrixDecision(
          DiagnosticsStatus.FAIL,
          "none",
          "Linux distribution could not be detected (/etc/os-release unavailable or unparseable)");
    }
    if (!"ubuntu".equals(linuxRelease.id())) {
      return new MatrixDecision(
          DiagnosticsStatus.FAIL,
          "none",
          "Linux distribution " + linuxRelease.id() + " is not in the supported matrix");
    }
    if (linuxRelease.isAtLeast(22, 4)) {
      return new MatrixDecision(
          DiagnosticsStatus.PASS,
          wsl2 ? "wsl2" : "ubuntu2204",
          wsl2
              ? "WSL2 Ubuntu treated as Linux variant — matrix row: wsl2"
              : "Linux distribution accepted as Ubuntu 22.04+ class — matrix row: ubuntu2204");
    }
    if (linuxRelease.isAtLeast(20, 4)) {
      return new MatrixDecision(
          DiagnosticsStatus.WARN,
          "ubuntu2004",
          "Ubuntu "
              + linuxRelease.versionId()
              + " is a near-miss WARN; matrix targets Ubuntu 22.04+");
    }
    String version = linuxRelease.versionId().isBlank() ? osVersionRaw : linuxRelease.versionId();
    return new MatrixDecision(
        DiagnosticsStatus.FAIL, "none", "Ubuntu " + version + " is not in the supported matrix");
  }

  private static int parseLeadingInt(String value) {
    if (value == null) {
      return -1;
    }
    int end = 0;
    while (end < value.length() && Character.isDigit(value.charAt(end))) {
      end++;
    }
    if (end == 0) {
      return -1;
    }
    try {
      return Integer.parseInt(value.substring(0, end));
    } catch (NumberFormatException nfe) {
      return -1;
    }
  }

  private Optional<String> safeReadProcVersion(Path path) {
    try {
      return procVersionReader.apply(path);
    } catch (RuntimeException re) {
      log.warn(
          "probeSupportedEnvironment /proc/version read failed; falling back to no-WSL detection error={}",
          re.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private Optional<String> safeReadOsRelease(Path path) {
    try {
      return osReleaseReader.apply(path);
    } catch (RuntimeException re) {
      log.warn(
          "probeSupportedEnvironment /etc/os-release read failed; error={}",
          re.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static Map<String, String> environmentDetails(
      String os,
      String osVersion,
      String osArch,
      String shell,
      String shellVersion,
      String dockerRuntime,
      String matrixRow,
      String notes) {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("os", os);
    details.put("osVersion", osVersion);
    details.put("osArch", osArch);
    details.put("shell", shell);
    details.put("shellVersion", shellVersion);
    details.put("dockerRuntime", dockerRuntime);
    details.put("matrixRow", matrixRow);
    if (notes != null && !notes.isBlank()) {
      details.put("notes", notes);
    }
    return details;
  }

  private static String safeString(Supplier<String> supplier, String fallback) {
    try {
      String value = supplier.get();
      return value == null ? fallback : value;
    } catch (RuntimeException re) {
      return fallback;
    }
  }

  private static String detectShellName(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return "unknown";
    }
    // SHELL may legitimately contain a wrapper invocation like "/usr/bin/env bash"
    // or arguments like "/usr/bin/bash -l". Take the last whitespace-delimited token
    // before extracting the basename.
    String[] tokens = rawValue.trim().split("\\s+");
    String executable = tokens.length == 0 ? rawValue : tokens[tokens.length - 1];
    String normalized = executable.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    String candidate = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    String lower = candidate.toLowerCase(Locale.ROOT);
    return lower.endsWith(".exe") ? lower.substring(0, lower.length() - 4) : lower;
  }

  private static LinuxRelease parseLinuxRelease(String content) {
    if (content == null || content.isBlank()) {
      return LinuxRelease.unknown();
    }
    String id = "";
    String versionId = "";
    for (String line : content.split("\\R")) {
      if (line.startsWith("ID=")) {
        id = unquote(line.substring(3)).toLowerCase(Locale.ROOT);
      } else if (line.startsWith("VERSION_ID=")) {
        versionId = unquote(line.substring("VERSION_ID=".length()));
      }
    }
    return new LinuxRelease(id.isBlank() ? "unknown" : id, versionId);
  }

  private static String unquote(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.length() >= 2
        && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
            || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }

  private record MatrixDecision(DiagnosticsStatus status, String row, String summary) {}

  private record LinuxRelease(String id, String versionId) {
    private static LinuxRelease unknown() {
      return new LinuxRelease("unknown", "");
    }

    private boolean isAtLeast(int major, int minor) {
      if (versionId == null || versionId.isBlank()) {
        return false;
      }
      String[] parts = versionId.split("\\.");
      int foundMajor = parsePart(parts, 0);
      int foundMinor = parsePart(parts, 1);
      if (foundMajor != major) {
        return foundMajor > major;
      }
      return foundMinor >= minor;
    }

    private static int parsePart(String[] parts, int index) {
      if (index >= parts.length) {
        return 0;
      }
      // VERSION_ID may carry a suffix token (e.g. "22.04 LTS") — strip whitespace
      // and any non-digit trailing characters before parsing.
      String raw = parts[index] == null ? "" : parts[index].trim();
      int end = 0;
      while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
        end++;
      }
      if (end == 0) {
        return 0;
      }
      try {
        return Integer.parseInt(raw.substring(0, end));
      } catch (NumberFormatException nfe) {
        return 0;
      }
    }
  }

  private List<Path> resolveConfigCandidates() {
    List<Path> result = new java.util.ArrayList<>();
    try {
      for (String glob : CONFIG_GLOBS) {
        if (!glob.contains("*")) {
          Path explicit = workingDirectory.resolve(glob);
          if (Files.isRegularFile(explicit)) {
            result.add(explicit);
          }
        } else {
          try (var stream = Files.newDirectoryStream(workingDirectory, glob)) {
            for (Path path : stream) {
              if (Files.isRegularFile(path)) {
                result.add(path);
              }
            }
          }
        }
      }
    } catch (IOException io) {
      log.debug("Failed to enumerate config candidates under {}", workingDirectory, io);
    }
    return result;
  }

  private void populateUrlDetails(String rawUrl, Map<String, String> details) {
    if (rawUrl == null) {
      return;
    }
    Matcher matcher = JDBC_HOST_PORT.matcher(rawUrl);
    if (matcher.matches()) {
      details.put("databaseUrlHost", matcher.group(1));
      String port = matcher.group(2);
      details.put("databasePort", port == null ? "5432" : port);
    }
  }

  private static String safeExceptionMessage(Throwable t) {
    String message = t.getMessage();
    return message == null ? t.getClass().getSimpleName() : message;
  }
}
