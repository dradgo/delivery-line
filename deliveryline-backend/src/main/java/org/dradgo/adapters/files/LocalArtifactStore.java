package org.dradgo.adapters.files;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalArtifactStore implements ArtifactPayloadStore {

  private static final Logger log = LoggerFactory.getLogger(LocalArtifactStore.class);

  private static final int MAX_FILENAME_BYTES = 200;
  private static final String TEMP_SUFFIX = ".tmp";
  private static final String ILLEGAL_FILENAME_CHARS = "<>:\"|?*";

  private static final Set<String> WINDOWS_RESERVED_LEAVES =
      Set.of(
          "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
          "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

  private final Path deliverylineHome;

  public LocalArtifactStore(@Value("${deliveryline.home}") String deliverylineHome) {
    if (deliverylineHome == null || deliverylineHome.trim().isEmpty()) {
      throw new IllegalArgumentException("deliveryline.home must be configured");
    }
    Path normalized = Path.of(deliverylineHome.trim()).toAbsolutePath().normalize();
    Path resolved;
    try {
      Files.createDirectories(normalized);
      resolved = normalized.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "deliveryline.home could not be created or resolved: " + deliverylineHome.trim(), error);
    }
    this.deliverylineHome = resolved;
  }

  @Override
  public String write(
      String workflowRunId,
      String artifactId,
      int version,
      String payloadRef,
      byte[] payloadContent) {
    if (payloadContent == null) {
      throw new IllegalArgumentException("Artifact payloadContent must not be null");
    }
    String filename = validatedFilename(payloadRef);
    Path target =
        deliverylineHome
            .resolve("artifacts")
            .resolve(workflowRunId)
            .resolve(artifactId)
            .resolve("v" + version)
            .resolve(filename)
            .normalize();
    requireContainedWithinHome(target, "payloadRef", payloadRef);
    Path tempTarget = target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
    try {
      Files.createDirectories(target.getParent());
      Files.write(tempTarget, payloadContent);
      try {
        Files.move(
            tempTarget,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException atomicUnsupported) {
        Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
      }
      try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
        dir.force(true);
      } catch (IOException ignored) {
        // Directory fsync is not supported on all OS/filesystem combinations (e.g. Windows).
        // The atomic rename already provides the best available durability on those platforms.
      }
    } catch (IOException error) {
      log.error(
          "artifact payload write failed workflowRunId={} artifactId={} version={} payloadBytes={} cause={}",
          workflowRunId,
          artifactId,
          version,
          payloadContent.length,
          error.toString());
      try {
        Files.deleteIfExists(tempTarget);
      } catch (IOException ignored) {
        // best-effort cleanup; original failure is the meaningful one
      }
      throw new IllegalStateException("Failed to write artifact payload", error);
    }
    String relativeRef = deliverylineHome.relativize(target).toString().replace('\\', '/');
    log.info(
        "artifact payload written workflowRunId={} artifactId={} version={} storageRef={} payloadBytes={}",
        workflowRunId,
        artifactId,
        version,
        relativeRef,
        payloadContent.length);
    return relativeRef;
  }

  @Override
  public boolean isReadable(String storageRef) {
    if (storageRef == null || storageRef.isBlank()) {
      return false;
    }
    if (containsForbiddenStorageRefCharacter(storageRef) || hasSchemePrefix(storageRef)) {
      return false;
    }
    Path target;
    try {
      Path candidate = Path.of(storageRef);
      if (candidate.isAbsolute()) {
        return false;
      }
      target = deliverylineHome.resolve(candidate).normalize();
    } catch (InvalidPathException error) {
      return false;
    }
    if (!target.startsWith(deliverylineHome)) {
      return false;
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    Path resolvedReal;
    try {
      resolvedReal = target.toRealPath();
    } catch (IOException error) {
      return false;
    }
    if (!resolvedReal.startsWith(deliverylineHome)) {
      return false;
    }
    return Files.isRegularFile(resolvedReal) && Files.isReadable(resolvedReal);
  }

  @Override
  public Optional<byte[]> readBytes(String storageRef) {
    if (!isReadable(storageRef)) {
      return Optional.empty();
    }
    try {
      Path candidate = Path.of(storageRef);
      Path target = deliverylineHome.resolve(candidate).normalize().toRealPath();
      // Re-check containment after toRealPath: between isReadable() and here a symlink swap
      // can repoint the resolved real path outside deliverylineHome. Without this guard the
      // link-swap window would let readBytes leak a file outside home.
      if (!target.startsWith(deliverylineHome)) {
        return Optional.empty();
      }
      if (!Files.isRegularFile(target)) {
        return Optional.empty();
      }
      return Optional.of(Files.readAllBytes(target));
    } catch (IOException | InvalidPathException error) {
      return Optional.empty();
    }
  }

  private String validatedFilename(String payloadRef) {
    if (payloadRef == null || payloadRef.isBlank()) {
      throw invalidFilename("payloadRef must not be blank", "blank", payloadRef);
    }
    if (containsForbiddenStorageRefCharacter(payloadRef)) {
      throw invalidFilename(
          "payloadRef must not contain path separators, scheme prefixes, or control characters",
          "forbidden_character",
          payloadRef);
    }
    if (hasSchemePrefix(payloadRef)) {
      throw invalidFilename(
          "payloadRef must not contain a URI scheme", "scheme_prefix", payloadRef);
    }
    Path candidate;
    try {
      candidate = Path.of(payloadRef);
    } catch (InvalidPathException error) {
      throw invalidFilename("payloadRef is not a valid path component", "invalid_path", payloadRef);
    }
    if (candidate.isAbsolute()) {
      throw invalidFilename(
          "payloadRef must be a single relative filename, not an absolute path",
          "absolute_path",
          payloadRef);
    }
    if (candidate.getNameCount() != 1) {
      throw invalidFilename(
          "payloadRef must be a single filename, not a path with directories",
          "multi_segment",
          payloadRef);
    }
    String filename = Normalizer.normalize(candidate.getFileName().toString(), Normalizer.Form.NFC);
    if (filename.isBlank() || ".".equals(filename) || "..".equals(filename)) {
      throw invalidFilename(
          "payloadRef must resolve to a real filename", "reserved_segment", payloadRef);
    }
    if (containsControlOrIllegalCharacters(filename)) {
      throw invalidFilename(
          "payloadRef contains illegal or control characters", "illegal_character", payloadRef);
    }
    if (filename.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES) {
      throw invalidFilename(
          "payloadRef filename exceeds " + MAX_FILENAME_BYTES + " bytes after NFC normalization",
          "length_exceeded",
          payloadRef);
    }
    if (isWindowsReserved(filename)) {
      throw invalidFilename(
          "payloadRef must not match a Windows-reserved device name", "reserved_name", payloadRef);
    }
    return filename;
  }

  private void requireContainedWithinHome(Path target, String field, String rawValue) {
    if (!target.startsWith(deliverylineHome)) {
      throw new DomainException(
          DomainErrorCode.ARTIFACT_INVALID_FILENAME,
          "Artifact " + field + " must resolve to a path within deliveryline.home",
          Map.of(
              "field",
              field,
              "reason",
              "path_traversal",
              "value",
              rawValue == null ? "" : rawValue));
    }
  }

  private DomainException invalidFilename(String message, String reason, String rawValue) {
    return new DomainException(
        DomainErrorCode.ARTIFACT_INVALID_FILENAME,
        message,
        Map.of("field", "payloadRef", "reason", reason, "value", rawValue == null ? "" : rawValue));
  }

  private boolean containsForbiddenStorageRefCharacter(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == 0) {
        return true;
      }
    }
    return false;
  }

  private boolean hasSchemePrefix(String value) {
    // Reject obvious URI schemes (e.g., file:, http:, https:, jar:). A leading bare drive letter
    // like "C:" is rejected separately via the absolute-path / multi-segment guards.
    int colon = value.indexOf(':');
    if (colon <= 0) {
      return false;
    }
    String prefix = value.substring(0, colon);
    if (prefix.length() == 1) {
      // Single-letter Windows drive prefix; absolute-path guard handles it.
      return false;
    }
    for (int i = 0; i < prefix.length(); i++) {
      char c = prefix.charAt(i);
      if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
        return false;
      }
    }
    return true;
  }

  private boolean containsControlOrIllegalCharacters(String filename) {
    for (int i = 0; i < filename.length(); i++) {
      char c = filename.charAt(i);
      if (c < 32 || c == 127 || ILLEGAL_FILENAME_CHARS.indexOf(c) >= 0) {
        return true;
      }
    }
    return false;
  }

  private boolean isWindowsReserved(String filename) {
    int dot = filename.indexOf('.');
    String stem = dot < 0 ? filename : filename.substring(0, dot);
    return WINDOWS_RESERVED_LEAVES.contains(stem.toUpperCase(Locale.ROOT));
  }
}
