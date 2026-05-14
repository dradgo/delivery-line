package org.dradgo.application.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import org.dradgo.adapters.diagnostics.DoctorProbeAdapter;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.mock.env.MockEnvironment;

class PathHandlingContractTest {

	@Test
	void unixStylePathResolvesChildWithoutStringConcatenation() {
		Path root = Path.of("/var/lib/deliveryline/artifacts");
		Path child = root.resolve("doctor-probe.bin");

		assertThat(child.getFileName()).isNotNull();
		assertThat(child.getFileName().toString()).isEqualTo("doctor-probe.bin");
		assertThat(child.toString())
			.doesNotContain("//")
			.doesNotContain("\\\\");
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsStylePathResolvesChildWithoutStringConcatenation() {
		Path root = Path.of("C:\\Users\\test\\artifacts");
		Path child = root.resolve("doctor-probe.bin");

		assertThat(child.getFileName()).isNotNull();
		assertThat(child.getFileName().toString()).isEqualTo("doctor-probe.bin");
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsPathRetainsBackslashSeparatorOnWindows() {
		Path root = Path.of("C:\\Users\\test\\artifacts");
		Path child = root.resolve("doctor-probe.bin");

		assertThat(child.isAbsolute()).isTrue();
		assertThat(child.toString()).contains("\\");
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void unixPathUsesForwardSlashOnUnix() {
		Path root = Path.of("/var/lib/deliveryline/artifacts");
		Path child = root.resolve("doctor-probe.bin");

		assertThat(child.isAbsolute()).isTrue();
		assertThat(child.toString()).startsWith("/var/lib/deliveryline/artifacts");
		assertThat(child.toString()).endsWith("/doctor-probe.bin");
	}

	@Test
	void resolveOperationIsPureWithNoStringConcatenationArtifacts() {
		Path root = Path.of("/var/lib/deliveryline");
		Path artifacts = root.resolve("artifacts");
		Path probe = artifacts.resolve(".doctor-probe-marker");

		assertThat(probe.getParent()).isEqualTo(artifacts);
		assertThat(artifacts.getParent()).isEqualTo(root);
	}

	@Test
	void doctorProbeAdapterResolvesArtifactRootFromUnixStyleHome() {
		DoctorProbeAdapter adapter = newAdapter(Path.of("/var/lib/deliveryline"));

		assertThat(adapter.probeArtifactDirectory().details())
			.containsEntry("artifactRoot", Path.of("/var/lib/deliveryline").resolve("artifacts").toString());
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void doctorProbeAdapterResolvesArtifactRootFromWindowsStyleHomeLiteral() {
		Path home = Path.of("C:\\Users\\test\\deliveryline-data");
		DoctorProbeAdapter adapter = newAdapter(home);

		assertThat(adapter.probeArtifactDirectory().details())
			.containsEntry("artifactRoot", home.resolve("artifacts").toString());
	}

	private DoctorProbeAdapter newAdapter(Path home) {
		return new DoctorProbeAdapter(
			new MockEnvironment(),
			mock(javax.sql.DataSource.class),
			mock(Flyway.class),
			new UuidV7Generator(),
			home.toString(),
			"localhost",
			8080);
	}
}
