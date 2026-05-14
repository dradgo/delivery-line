package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.domain.registry.DomainErrorCode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class DoctorProbeAdapterTest {

	private final UuidV7Generator uuidV7Generator = new UuidV7Generator();

	@Test
	void javaVersionProbePassesOnCurrentJvm() {
		DoctorProbeAdapter adapter = newAdapter(new MockEnvironment().withProperty("a", "b"),
			Path.of("."), failingFactory());

		ProbeResult result = adapter.probeJavaVersion();

		assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
		assertThat(result.details().get("javaVersion")).isNotNull();
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
		assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value());
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
		when(infoService.all()).thenReturn(new MigrationInfo[]{migration});
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
		when(infoService.all()).thenReturn(new MigrationInfo[]{migration});
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
		DoctorProbeAdapter adapter = new DoctorProbeAdapter(
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
	void dockerProbeWarnsOnIoException() {
		ProcessLauncher throwing = pb -> {
			throw new IOException("docker not found");
		};
		DoctorProbeAdapter adapter = new DoctorProbeAdapter(
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

	private DoctorProbeAdapter newAdapter(Environment env, Path workingDir, ProcessLauncher processLauncher) {
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

	private static ProcessLauncher failingFactory() {
		return pb -> {
			throw new UnsupportedOperationException("process factory should not be invoked in this test");
		};
	}
}
