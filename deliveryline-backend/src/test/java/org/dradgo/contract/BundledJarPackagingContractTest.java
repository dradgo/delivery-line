package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 2.28 (AC6) — packaging invariant: the repackaged {@code deliveryline-backend-*.jar}
 * actually embeds the Vite SPA under {@code BOOT-INF/classes/static/}. This is the fast,
 * Docker-free half of the bundled-jar story — it unzips the built jar and asserts the shell +
 * entrypoint assets are present, so a jar missing the embedded SPA reds the {@code
 * backend-contract-tests} tier on every PR (foundation-gate widening, AC11/S6). The full runtime
 * round-trip stays the {@code push:main}-only {@code bundled-jar-smoke} signal.
 *
 * <p>Named {@code *ContractTest} → routed to Failsafe (runs in the {@code integration-test} phase,
 * after {@code package}'s {@code copy-frontend-dist} + {@code spring-boot:repackage}, so the
 * repackaged jar exists) and excluded from the no-Docker Windows Surefire tier by the pom's
 * file-pattern exclusion. It needs NO Spring context and NO Docker — plain {@link JarFile} reads —
 * so it is deliberately NOT an {@code *IT}.
 *
 * <p><b>Frontend-skipped path.</b> When {@code -Dfrontend-maven-plugin.skip=true} (local
 * backend-only iteration), the jar legitimately lacks the SPA — the test {@code abort}s (skips)
 * with a clear message rather than failing. The {@code require-frontend-dist} build-fail enforcer
 * (story 2.1 / AC5) is what guarantees the bundle in real packaging, so a genuinely missing bundle
 * never reaches here undetected.
 */
@Tag("contract")
class BundledJarPackagingContractTest {

  private static final Path TARGET_DIR = Path.of("target");
  private static final String STATIC_ROOT = "BOOT-INF/classes/static/";
  private static final String SHELL_ENTRY = STATIC_ROOT + "index.html";
  private static final String ASSETS_PREFIX = STATIC_ROOT + "assets/";

  @Test
  void repackagedJarEmbedsTheViteSpaUnderBootInfStatic() throws IOException {
    Path jar = locateBootableJar();
    if (jar == null) {
      Assumptions.abort(
          "No repackaged deliveryline-backend-*.jar under "
              + TARGET_DIR.toAbsolutePath()
              + " — this test asserts only when the `package` phase has produced the executable jar"
              + " (e.g. `mvn verify`). Skipping.");
      return; // unreachable (abort throws) — narrows jar to non-null for static analysis
    }

    List<String> entries = new ArrayList<>();
    boolean hasShell = false;
    boolean hasAssetJs = false;
    boolean hasAssetCss = false;
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      Enumeration<JarEntry> e = jarFile.entries();
      while (e.hasMoreElements()) {
        String name = e.nextElement().getName();
        if (name.startsWith(STATIC_ROOT)) {
          entries.add(name);
        }
        if (name.equals(SHELL_ENTRY)) {
          hasShell = true;
        } else if (name.startsWith(ASSETS_PREFIX) && name.endsWith(".js")) {
          hasAssetJs = true;
        } else if (name.startsWith(ASSETS_PREFIX) && name.endsWith(".css")) {
          hasAssetCss = true;
        }
      }
    }

    // Frontend-skipped build: the static/ tree is absent entirely. Skip rather than fail — the
    // require-frontend-dist enforcer covers the real-packaging guarantee (see class javadoc).
    if (entries.isEmpty()) {
      Assumptions.abort(
          "Jar "
              + jar.getFileName()
              + " contains no BOOT-INF/classes/static/ entries — built with"
              + " -Dfrontend-maven-plugin.skip=true (backend-only). The require-frontend-dist"
              + " enforcer guards real packaging; skipping the embed assertion.");
    }

    assertTrue(
        hasShell,
        () ->
            "Repackaged jar "
                + jar.getFileName()
                + " is missing the SPA shell entry "
                + SHELL_ENTRY
                + ". Present static entries: "
                + entries);
    assertTrue(
        hasAssetJs,
        () ->
            "Repackaged jar "
                + jar.getFileName()
                + " is missing the entrypoint JS under "
                + ASSETS_PREFIX
                + " (expected at least one *.js). Present static entries: "
                + entries);
    assertTrue(
        hasAssetCss,
        () ->
            "Repackaged jar "
                + jar.getFileName()
                + " is missing the bundled stylesheet under "
                + ASSETS_PREFIX
                + " (expected at least one *.css). Present static entries: "
                + entries);
  }

  /**
   * The bootable jar, excluding Spring Boot's {@code *.jar.original} (the pre-repackage archive)
   * and any {@code *-sources}/{@code *-javadoc} sidecars. Returns {@code null} when no jar is
   * present.
   *
   * <p>Fails fast when more than one bootable jar is present (e.g. a stale {@code
   * deliveryline-backend-<oldversion>.jar} left behind by a version bump without {@code clean}):
   * {@link DirectoryStream} iteration order is unspecified, so silently picking one could assert
   * against a stale jar and mask a real packaging regression. The fix is a clean rebuild.
   */
  private static Path locateBootableJar() throws IOException {
    if (!Files.isDirectory(TARGET_DIR)) {
      return null;
    }
    try (DirectoryStream<Path> stream =
        Files.newDirectoryStream(TARGET_DIR, "deliveryline-backend-*.jar")) {
      List<Path> candidates = new ArrayList<>();
      for (Path p : stream) {
        String name = p.getFileName().toString();
        if (name.endsWith(".jar.original")
            || name.endsWith("-sources.jar")
            || name.endsWith("-javadoc.jar")) {
          continue;
        }
        candidates.add(p);
      }
      if (candidates.isEmpty()) {
        return null;
      }
      if (candidates.size() > 1) {
        fail(
            "Multiple bootable deliveryline-backend-*.jar under "
                + TARGET_DIR.toAbsolutePath()
                + " "
                + candidates.stream().map(p -> p.getFileName().toString()).toList()
                + " — jar selection would be non-deterministic. Run `mvn clean package` to remove"
                + " the stale jar(s) before this contract test.");
      }
      return candidates.get(0);
    } catch (java.nio.file.NoSuchFileException notFound) {
      fail("target/ vanished mid-test: " + notFound.getMessage());
      return null;
    }
  }
}
