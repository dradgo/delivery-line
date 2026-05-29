package org.dradgo.adapters.runner.docker;

import org.dradgo.application.runner.RunnerProperties;

public final class DockerLogSanitizer {

  private DockerLogSanitizer() {}

  /**
   * Delegates to the application-layer canonical implementation so the broker (which must not call
   * into this adapter package) and the adapter share one redaction rule.
   */
  public static String redactImageTag(String image) {
    return RunnerProperties.Docker.redactImageTag(image);
  }
}
