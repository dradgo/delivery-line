package org.dradgo.application.runner.spi;

import java.util.Objects;

/**
 * Story 3.5 AC4 — one regular workspace file surfaced for the post-execution secret scan. Carries
 * the workspace-relative path (e.g. {@code output/result.json}) for diagnostics + the file's UTF-8
 * decoded text for detection. The store only reads bytes (Trap T7): ALL credential detection runs
 * against {@link #text()} in the application/security layer + the broker's literal substring check.
 */
public record WorkspaceScanFile(String relativePath, String text) {

  public WorkspaceScanFile {
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(text, "text");
  }
}
