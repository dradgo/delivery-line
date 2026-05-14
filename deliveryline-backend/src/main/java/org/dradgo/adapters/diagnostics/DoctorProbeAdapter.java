package org.dradgo.adapters.diagnostics;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
	private static final Pattern JDBC_HOST_PORT = Pattern.compile(
		"^jdbc:[^:]+://([^:/?]+)(?::(\\d+))?(?:[/?].*)?$",
		Pattern.CASE_INSENSITIVE);
	private static final List<String> CONFIG_GLOBS = List.of(".env", "application-*.yml");

	private final Environment environment;
	private final DataSource dataSource;
	private final Flyway flyway;
	private final UuidV7Generator uuidV7Generator;
	private final Path deliverylineHome;
	private final String serverAddress;
	private final int serverPort;
	private final Path workingDirectory;
	private final ProcessLauncher processLauncher;

	@Autowired
	public DoctorProbeAdapter(
		Environment environment,
		DataSource dataSource,
		Flyway flyway,
		UuidV7Generator uuidV7Generator,
		@Value("${deliveryline.home}") String deliverylineHome,
		@Value("${server.address:localhost}") String serverAddress,
		@Value("${server.port:8080}") int serverPort
	) {
		this(
			environment,
			dataSource,
			flyway,
			uuidV7Generator,
			Path.of(deliverylineHome),
			serverAddress,
			serverPort,
			Path.of(System.getProperty("user.dir")),
			ProcessBuilder::start);
	}

	DoctorProbeAdapter(
		Environment environment,
		DataSource dataSource,
		Flyway flyway,
		UuidV7Generator uuidV7Generator,
		Path deliverylineHome,
		String serverAddress,
		int serverPort,
		Path workingDirectory,
		ProcessLauncher processLauncher
	) {
		this.environment = environment;
		this.dataSource = dataSource;
		this.flyway = flyway;
		this.uuidV7Generator = uuidV7Generator;
		this.deliverylineHome = deliverylineHome;
		this.serverAddress = serverAddress;
		this.serverPort = serverPort;
		this.workingDirectory = workingDirectory;
		this.processLauncher = processLauncher;
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

		boolean anyAllowed = active.stream()
			.map(p -> p.toLowerCase(Locale.ROOT))
			.anyMatch(ALLOWED_PROFILES::contains);
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
			return new ProbeResult(
				DiagnosticsStatus.PASS,
				"Postgres reachable",
				null,
				details);
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
			if (state == MigrationState.PENDING || state == MigrationState.FAILED || state == MigrationState.OUT_OF_ORDER) {
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
			Files.write(probe, new byte[]{0});
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
					log.warn("Failed to remove doctor probe file after diagnostics cleanup; cleanupError={}",
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
					offenders.add(path.getFileName().toString() + "=" + PosixFilePermissions.toString(permissions));
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
		return new ProbeResult(DiagnosticsStatus.PASS, "Config file permissions are restrictive", null, details);
	}

	@Override
	public ProbeResult probeDockerAvailability() {
		Map<String, String> details = new LinkedHashMap<>();
		ProcessBuilder builder = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
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
				DiagnosticsStatus.PASS,
				"REST bind address resolves to loopback",
				null,
				details);
		} catch (UnknownHostException unknown) {
			return new ProbeResult(
				DiagnosticsStatus.FAIL,
				"server.address could not be resolved: " + serverAddress,
				DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE.value(),
				details);
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
