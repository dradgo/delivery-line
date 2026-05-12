package org.dradgo.adapters.files;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Filesystem implementation of {@link RunnerScratchStore} for the runner scratch directory at
 * {@code {deliveryline.home}/runner-scratch/{rex_id}/}.
 *
 * <p>Two well-known leaf files live under each runner-execution scratch:
 * <ul>
 *   <li>{@code context-bundle.v1.json} — written by {@code RunnerBroker.dispatch} after the
 *       DB row commits;</li>
 *   <li>{@code runner-result.v1.json} — written by {@code MockRunnerAdapter} (Epic 1) or
 *       the real Docker runner (Epic 3), read back by the broker on result handling.</li>
 * </ul>
 *
 * <p>Containment guards mirror {@link LocalArtifactStore}'s pattern: the {@code rex_} prefix
 * is validated through {@link PublicIdPrefixes}, the resolved path is asserted to stay under
 * {@code deliveryline.home}, and {@link LinkOption#NOFOLLOW_LINKS} blocks the symlink-swap race
 * on the read path.
 */
@Component
public class LocalRunnerScratchStore implements RunnerScratchStore {

	private static final Logger log = LoggerFactory.getLogger(LocalRunnerScratchStore.class);
	private static final String SCRATCH_ROOT = "runner-scratch";
	private static final String CONTEXT_BUNDLE_FILENAME = "context-bundle.v1.json";
	private static final String RUNNER_RESULT_FILENAME = "runner-result.v1.json";
	private static final String TEMP_SUFFIX = ".tmp";

	private final Path deliverylineHome;

	public LocalRunnerScratchStore(@Value("${deliveryline.home}") String deliverylineHome) {
		if (deliverylineHome == null || deliverylineHome.trim().isEmpty()) {
			throw new IllegalArgumentException("deliveryline.home must be configured");
		}
		Path normalized = Path.of(deliverylineHome.trim()).toAbsolutePath().normalize();
		try {
			Files.createDirectories(normalized);
			this.deliverylineHome = normalized.toRealPath();
		} catch (IOException error) {
			throw new IllegalArgumentException(
				"deliveryline.home could not be created or resolved: " + deliverylineHome.trim(), error);
		}
	}

	@Override
	public Path contextBundlePath(String runnerExecutionId) {
		return scratchFile(runnerExecutionId, CONTEXT_BUNDLE_FILENAME);
	}

	@Override
	public Path runnerResultPath(String runnerExecutionId) {
		return scratchFile(runnerExecutionId, RUNNER_RESULT_FILENAME);
	}

	@Override
	public Path writeContextBundle(String runnerExecutionId, byte[] redactedBytes) {
		return atomicWrite(runnerExecutionId, CONTEXT_BUNDLE_FILENAME, redactedBytes);
	}

	@Override
	public Path writeRunnerResult(String runnerExecutionId, byte[] payloadBytes) {
		return atomicWrite(runnerExecutionId, RUNNER_RESULT_FILENAME, payloadBytes);
	}

	@Override
	public Optional<byte[]> tryReadArtifactContent(String runnerExecutionId, String relativeReference) {
		Optional<Path> resolved = resolveArtifactPath(runnerExecutionId, relativeReference);
		if (resolved.isEmpty()) {
			return Optional.empty();
		}
		Path target = resolved.get();
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		try {
			Path real = target.toRealPath();
			if (!real.startsWith(deliverylineHome)) {
				return Optional.empty();
			}
			if (!Files.isRegularFile(real)) {
				return Optional.empty();
			}
			return Optional.of(Files.readAllBytes(real));
		} catch (IOException error) {
			log.warn("tryReadArtifactContent io failure runnerExecutionId={} reference={} cause={}",
				runnerExecutionId, relativeReference, error.toString());
			return Optional.empty();
		}
	}

	@Override
	public Path writeArtifactContent(String runnerExecutionId, String relativeReference, byte[] payloadBytes) {
		if (payloadBytes == null || payloadBytes.length == 0) {
			throw new IllegalArgumentException("payloadBytes must not be empty");
		}
		Path target = resolveArtifactPath(runnerExecutionId, relativeReference)
			.orElseThrow(() -> {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("runnerExecutionId", runnerExecutionId);
				details.put("contentReference", relativeReference);
				details.put("reason", "path_traversal");
				return new DomainException(
					DomainErrorCode.ARTIFACT_INVALID_FILENAME,
					"Resolved artifact-content path escapes deliveryline.home",
					details);
			});
		Path tempTarget = target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
		try {
			Files.createDirectories(target.getParent());
			Files.write(tempTarget, payloadBytes);
			try {
				Files.move(tempTarget, target,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException atomicUnsupported) {
				Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException error) {
			try {
				Files.deleteIfExists(tempTarget);
			} catch (IOException ignored) {
				// best-effort
			}
			throw new IllegalStateException(
				"Failed to write runner-scratch artifact content for " + runnerExecutionId, error);
		}
		log.info("scratch artifact written runnerExecutionId={} reference={} bytes={}",
			runnerExecutionId, relativeReference, payloadBytes.length);
		return target;
	}

	private Optional<Path> resolveArtifactPath(String runnerExecutionId, String relativeReference) {
		PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
		if (relativeReference == null || relativeReference.isBlank()) {
			return Optional.empty();
		}
		String normalizedRef = relativeReference.replace('\\', '/');
		if (normalizedRef.startsWith("/") || normalizedRef.contains("..")) {
			return Optional.empty();
		}
		Path scratchDir = deliverylineHome.resolve(SCRATCH_ROOT).resolve(runnerExecutionId).normalize();
		Path target = scratchDir.resolve(normalizedRef).normalize();
		if (!target.startsWith(scratchDir)) {
			return Optional.empty();
		}
		return Optional.of(target);
	}

	@Override
	public Optional<byte[]> tryReadRunnerResult(String runnerExecutionId) {
		Path target = scratchFile(runnerExecutionId, RUNNER_RESULT_FILENAME);
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		try {
			Path real = target.toRealPath();
			if (!real.startsWith(deliverylineHome)) {
				return Optional.empty();
			}
			if (!Files.isRegularFile(real)) {
				return Optional.empty();
			}
			return Optional.of(Files.readAllBytes(real));
		} catch (IOException error) {
			log.warn("tryReadRunnerResult io failure runnerExecutionId={} cause={}",
				runnerExecutionId, error.toString());
			return Optional.empty();
		}
	}

	private Path atomicWrite(String runnerExecutionId, String filename, byte[] payload) {
		if (payload == null || payload.length == 0) {
			throw new IllegalArgumentException("payload must not be empty");
		}
		Path target = scratchFile(runnerExecutionId, filename);
		Path tempTarget = target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
		try {
			Files.createDirectories(target.getParent());
			Files.write(tempTarget, payload);
			try {
				Files.move(tempTarget, target,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException atomicUnsupported) {
				Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
			}
			try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
				dir.force(true);
			} catch (IOException ignored) {
				// Directory fsync not supported on all platforms (e.g. Windows); the atomic rename
				// already provides best-available durability.
			}
		} catch (IOException error) {
			try {
				Files.deleteIfExists(tempTarget);
			} catch (IOException ignored) {
				// best-effort
			}
			throw new IllegalStateException(
				"Failed to write runner scratch file " + filename + " for " + runnerExecutionId, error);
		}
		log.info("scratch file written runnerExecutionId={} filename={} bytes={}",
			runnerExecutionId, filename, payload.length);
		return target;
	}

	private Path scratchFile(String runnerExecutionId, String filename) {
		PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
		Path target = deliverylineHome.resolve(SCRATCH_ROOT).resolve(runnerExecutionId).resolve(filename).normalize();
		if (!target.startsWith(deliverylineHome)) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("runnerExecutionId", runnerExecutionId);
			details.put("filename", filename);
			details.put("reason", "path_traversal");
			throw new DomainException(
				DomainErrorCode.ARTIFACT_INVALID_FILENAME,
				"Resolved scratch path escapes deliveryline.home",
				details);
		}
		return target;
	}

	public Path deliverylineHome() {
		return deliverylineHome;
	}
}
