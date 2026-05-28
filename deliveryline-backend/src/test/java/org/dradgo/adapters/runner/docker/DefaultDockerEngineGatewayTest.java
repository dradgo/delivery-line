package org.dradgo.adapters.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class DefaultDockerEngineGatewayTest {

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void formatHostPathForDockerConvertsWindowsDrivePathForDockerDesktop() {
    assertThat(
            DefaultDockerEngineGateway.formatHostPathForDocker(
                Path.of("C:\\Users\\pc\\runner-work")))
        .isEqualTo("/c/Users/pc/runner-work");
  }
}
