package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.LongSupplier;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Story 3.7 AC10 / Decision D7 — doctor observability host-memory probe. Uses the {@code
 * (Environment, LongSupplier)} test seam so the active profile and the reported host memory can be
 * simulated without depending on the real OS MX bean. SKIP when {@code observability} is inactive;
 * WARN (never FAIL) under 8 GB when active; PASS at/above 8 GB; PASS (no false WARN) when memory is
 * unavailable.
 */
class DoctorObservabilityMemoryProbeTest {

  private static final long GIB = 1024L * 1024L * 1024L;

  private static Environment observabilityActive() {
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[] {"demo", "observability"});
    return env;
  }

  private static Environment observabilityInactive() {
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[] {"local"});
    return env;
  }

  @Test
  void skipsWhenObservabilityProfileInactive() {
    LongSupplier exploding =
        () -> {
          throw new AssertionError("must not read host memory when observability is inactive");
        };
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(observabilityInactive(), exploding);

    ProbeResult result = adapter.probeObservabilityMemory();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.SKIP);
    assertThat(result.errorCode()).isNull();
    assertThat(result.details()).containsEntry("observabilityProfile", "inactive");
  }

  @Test
  void warnsWhenObservabilityActiveAndHostMemoryBelowEightGb() {
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(observabilityActive(), () -> 4L * GIB);

    ProbeResult result = adapter.probeObservabilityMemory();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_OBSERVABILITY_LOW_MEMORY.value());
    assertThat(result.details())
        .containsEntry("observabilityProfile", "active")
        .containsEntry("totalPhysicalMemoryBytes", String.valueOf(4L * GIB));
  }

  @Test
  void passesWhenObservabilityActiveAndHostMemoryAtLeastEightGb() {
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(observabilityActive(), () -> 16L * GIB);

    ProbeResult result = adapter.probeObservabilityMemory();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void passesWithoutFalseWarnWhenHostMemoryUnavailable() {
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(observabilityActive(), () -> -1L);

    ProbeResult result = adapter.probeObservabilityMemory();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.errorCode()).isNull();
    assertThat(result.details()).containsEntry("totalPhysicalMemoryBytes", "unavailable");
  }
}
