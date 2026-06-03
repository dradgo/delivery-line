package org.dradgo.application.runner.workspace;

/**
 * Project-owned record (story 3a-2 AC1/AC6) — a mount-relative reference to a detected
 * package/build manifest in the cloned working tree. {@code path} is workspace-relative (never a
 * host absolute path — Trap T7); {@code kind} is the canonical manifest filename (e.g. {@code
 * package.json}, {@code pom.xml}). Reference-by-path, not embedded body (Decision D4).
 */
public record RepoManifestRef(String path, String kind) {

  public RepoManifestRef {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must be non-blank");
    }
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("kind must be non-blank");
    }
  }
}
