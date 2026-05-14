package org.dradgo.application.diagnostics.spi;

public interface DoctorProbePort {

	ProbeResult probeJavaVersion();

	ProbeResult probeSpringProfiles();

	ProbeResult probePostgresConnectivity();

	ProbeResult probeFlywayState();

	ProbeResult probeArtifactDirectory();

	ProbeResult probeConfigFilePermissions();

	ProbeResult probeDockerAvailability();

	ProbeResult probeRestBindAddress();
}
