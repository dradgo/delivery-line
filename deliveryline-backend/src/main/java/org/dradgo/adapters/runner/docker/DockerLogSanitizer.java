package org.dradgo.adapters.runner.docker;

public final class DockerLogSanitizer {

  private DockerLogSanitizer() {}

  public static String redactImageTag(String image) {
    if (image == null || image.isBlank()) {
      return image;
    }
    int at = image.indexOf('@');
    int slash = image.indexOf('/');
    int colon = image.indexOf(':');
    if (at > 0 && (slash < 0 || at < slash) && colon > 0 && colon < at) {
      return "***" + image.substring(at);
    }
    return image;
  }
}
