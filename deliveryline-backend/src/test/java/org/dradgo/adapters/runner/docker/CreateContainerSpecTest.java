package org.dradgo.adapters.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CreateContainerSpecTest {

  @Test
  void defaultsForNewFieldsKeepLegacyConstructorsUnprivilegedWithNoAliases() {
    CreateContainerSpec legacy = new CreateContainerSpec("img", List.of(), "bridge", Map.of());
    assertThat(legacy.privileged()).isFalse();
    assertThat(legacy.networkAliases()).isEmpty();
    assertThat(legacy.memoryBytes()).isNull();
    assertThat(legacy.healthcheck()).isNull();
  }

  @Test
  void canonicalConstructorCarriesPrivilegedAliasesMemoryAndHealthcheck() {
    CreateContainerSpec.Healthcheck hc =
        new CreateContainerSpec.Healthcheck(
            List.of("CMD-SHELL", "docker -H tcp://localhost:2375 version"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            30);
    CreateContainerSpec spec =
        new CreateContainerSpec(
            "docker:27-dind",
            List.of(),
            "deliveryline-net-rex_x",
            Map.of("deliveryline.dind", "rex_x"),
            Map.of("DOCKER_TLS_CERTDIR", ""),
            List.of(),
            true,
            List.of("dind"),
            2L * 1024 * 1024 * 1024,
            hc);
    assertThat(spec.privileged()).isTrue();
    assertThat(spec.networkAliases()).containsExactly("dind");
    assertThat(spec.memoryBytes()).isEqualTo(2147483648L);
    assertThat(spec.healthcheck().test()).contains("CMD-SHELL");
  }
}
