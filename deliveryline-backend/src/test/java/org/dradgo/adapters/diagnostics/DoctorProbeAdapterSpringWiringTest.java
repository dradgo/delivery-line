package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DoctorProbeAdapterSpringWiringTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withBean(UuidV7Generator.class)
          .withBean(DoctorProbeAdapter.class)
          .withPropertyValues(
              "deliveryline.home=.",
              "server.address=127.0.0.1",
              "deliveryline.rest.bind-address=127.0.0.2",
              "server.port=8080");

  @Test
  void restBindProbeUsesBindAddressOverrideWhenPresent() {
    contextRunner.run(
        context -> {
          DoctorProbeAdapter adapter = context.getBean(DoctorProbeAdapter.class);

          var result = adapter.probeRestBindAddress();

          assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
          assertThat(result.details()).containsEntry("effectiveBindAddress", "127.0.0.2");
        });
  }
}
