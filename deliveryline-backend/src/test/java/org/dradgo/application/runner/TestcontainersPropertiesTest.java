package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TestcontainersPropertiesTest {

  @Test
  void defaultsMatchTheSpec() {
    TestcontainersProperties p = TestcontainersProperties.defaults();
    assertThat(p.dindImage()).isEqualTo("docker:27-dind");
    assertThat(p.memoryBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    assertThat(p.readinessTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void rejectsBlankImageAndNonPositiveMemory() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new TestcontainersProperties(" ", 1L, Duration.ofSeconds(60)));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new TestcontainersProperties("docker:27-dind", 0L, Duration.ofSeconds(60)));
  }
}
