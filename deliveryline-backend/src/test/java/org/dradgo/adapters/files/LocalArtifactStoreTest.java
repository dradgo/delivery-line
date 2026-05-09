package org.dradgo.adapters.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Optional;
import java.util.stream.Stream;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalArtifactStoreTest {

	@TempDir
	Path tempDir;

	@Test
	void writeStoresArtifactsUnderConfiguredHomeAndReturnsLogicalRelativePath() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		byte[] payloadContent = "spec body v2\nsecond line".getBytes(StandardCharsets.UTF_8);

		String storageRef = store.write("run_ready1234", "art_ready1234", 2, "spec-v2.md", payloadContent);

		assertEquals("artifacts/run_ready1234/art_ready1234/v2/spec-v2.md", storageRef);
		assertTrue(store.isReadable(storageRef));
		assertTrue(Files.exists(tempDir.resolve(storageRef)));
		assertArrayEquals(payloadContent, Files.readAllBytes(tempDir.resolve(storageRef)));
	}

	// ---------- H1: home configuration must be explicit, never silently defaulted ----------

	@Test
	void blankHomeConfigurationIsRejectedInsteadOfFallingBackToProcessWorkingDirectory() {
		IllegalArgumentException error = assertThrows(
			IllegalArgumentException.class,
			() -> new LocalArtifactStore("   "));

		assertEquals("deliveryline.home must be configured", error.getMessage());
	}

	@Test
	void nullHomeConfigurationIsRejectedInsteadOfFallingBackToTmpdirOrWorkingDirectory() {
		IllegalArgumentException error = assertThrows(
			IllegalArgumentException.class,
			() -> new LocalArtifactStore(null));

		assertEquals("deliveryline.home must be configured", error.getMessage());
	}

	// ---------- C2 + D5/L4: payloadRef must be a single-segment safe filename ----------

	@Test
	void writeRejectsPayloadRefContainingForwardSlashSeparators() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, "subdir/file.txt", "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
		assertEquals("payloadRef", error.details().get("field"));
	}

	@Test
	void writeRejectsPayloadRefContainingBackslashSeparators() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, "subdir\\file.txt", "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
		assertEquals("payloadRef", error.details().get("field"));
	}

	@Test
	void writeRejectsPayloadRefContainingTraversalSegments() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, "../escape.txt", "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
		assertEquals("payloadRef", error.details().get("field"));
	}

	@Test
	void writeRejectsAbsolutePayloadRefThatWouldEscapeHome() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		String absolute = isWindows() ? "C:\\Windows\\System32\\drivers\\etc\\hosts" : "/etc/passwd";

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, absolute, "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
		assertEquals("payloadRef", error.details().get("field"));
	}

	@Test
	void writeRejectsPayloadRefContainingNulByte() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, "spec" + ((char) 0) + ".md", "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
	}

	@Test
	void writeRejectsPayloadRefContainingControlCharacters() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, "spec\nname.md", "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
	}

	@Test
	void writeRejectsBlankPayloadRefAsTypedDomainError() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, "   ", "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
	}

	@Test
	void writeRejectsWindowsReservedLeafWithoutExtension() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 2, "CON", "ignored".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
	}

	@Test
	void writeRejectsWindowsReservedLeafWithExtension() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 2, "PRN.json", "ignored".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
	}

	@Test
	void writeRejectsWindowsReservedLeafWithMixedCase() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 2, "aux.log", "ignored".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
	}

	@Test
	void writeRejectsFilenameLongerThan200BytesAfterNfcNormalization() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		String longName = "a".repeat(201);

		DomainException error = assertThrows(
			DomainException.class,
			() -> store.write("run_ready1234", "art_ready1234", 1, longName, "x".getBytes(StandardCharsets.UTF_8)));

		assertEquals(DomainErrorCode.ARTIFACT_INVALID_FILENAME, error.errorCode());
	}

	@Test
	void writeNormalizesPayloadRefToNfcBeforeWritingFile() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		// "café" using combining acute accent (NFD form): c + a + f + e + U+0301
		String nfd = "café.md";
		assertFalse(Normalizer.isNormalized(nfd, Normalizer.Form.NFC));

		String storageRef = store.write("run_ready1234", "art_ready1234", 1, nfd, "x".getBytes(StandardCharsets.UTF_8));

		// Stored leaf is NFC-normalized
		String expectedNfc = Normalizer.normalize(nfd, Normalizer.Form.NFC);
		assertTrue(storageRef.endsWith(expectedNfc),
			() -> "Expected storageRef to end with NFC-normalized leaf '" + expectedNfc + "', got: " + storageRef);
		assertTrue(Files.exists(tempDir.resolve(storageRef)));
	}

	// ---------- R15: atomic move (no half-files left behind on success) ----------

	@Test
	void successfulWriteLeavesNoSiblingTempFileBehind() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		byte[] payloadContent = "spec body".getBytes(StandardCharsets.UTF_8);

		String storageRef = store.write("run_ready1234", "art_ready1234", 1, "spec.md", payloadContent);

		Path target = tempDir.resolve(storageRef);
		try (Stream<Path> siblings = Files.list(target.getParent())) {
			long tempCount = siblings.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
			assertEquals(0L, tempCount, "Atomic write must not leave .tmp sidecars on success");
		}
	}

	@Test
	void writeReplacesExistingTargetAtomically() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		byte[] firstPayload = "first".getBytes(StandardCharsets.UTF_8);
		byte[] secondPayload = "second".getBytes(StandardCharsets.UTF_8);

		store.write("run_ready1234", "art_ready1234", 1, "spec.md", firstPayload);
		String storageRef = store.write("run_ready1234", "art_ready1234", 1, "spec.md", secondPayload);

		assertArrayEquals(secondPayload, Files.readAllBytes(tempDir.resolve(storageRef)));
	}

	// ---------- R3: isReadable hardening ----------

	@Test
	void isReadableRejectsRefsThatEscapeTheConfiguredHome() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		Path escapedFile = tempDir.resolveSibling("escaped-artifact.txt");
		Files.writeString(escapedFile, "payload");

		assertFalse(store.isReadable("../" + escapedFile.getFileName()));
	}

	@Test
	void isReadableRejectsAbsoluteStorageRef() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		String absolute = isWindows() ? "C:\\Windows\\System32\\drivers\\etc\\hosts" : "/etc/passwd";

		assertFalse(store.isReadable(absolute));
	}

	@Test
	void isReadableRejectsSchemedStorageRef() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		assertFalse(store.isReadable("file:///etc/passwd"));
		assertFalse(store.isReadable("http://example.com/x"));
	}

	@Test
	void isReadableRejectsStorageRefContainingNulByte() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		assertFalse(store.isReadable("artifacts/run/art/v1/spec" + ((char) 0) + ".md"));
	}

	@Test
	void isReadableRejectsStorageRefContainingBackslash() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		assertFalse(store.isReadable("artifacts\\run_x\\art_y\\v1\\spec.md"));
	}

	@Test
	void isReadableRejectsBlankOrNullStorageRef() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		assertFalse(store.isReadable(null));
		assertFalse(store.isReadable(""));
		assertFalse(store.isReadable("   "));
	}

	@Test
	void isReadableReturnsFalseForDirectoriesEvenInsideHome() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		Path innerDir = tempDir.resolve("artifacts/run/art/v1");
		Files.createDirectories(innerDir);

		assertFalse(store.isReadable("artifacts/run/art/v1"));
	}

	// ---------- R14: symlink hardening ----------

	@Test
	void isReadableRejectsSymlinkPointingOutsideConfiguredHome() throws Exception {
		assumeTrue(supportsSymlinks(), "Filesystem does not allow symlink creation; skipping");
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		Path outside = tempDir.resolveSibling("outside-secret.txt");
		Files.writeString(outside, "secret");

		Path linkParent = tempDir.resolve("artifacts/run/art/v1");
		Files.createDirectories(linkParent);
		Path link = linkParent.resolve("escape.md");
		Files.createSymbolicLink(link, outside);

		assertFalse(store.isReadable("artifacts/run/art/v1/escape.md"));
	}

	@Test
	void isReadableAcceptsSymlinkPointingToRegularFileInsideHome() throws Exception {
		assumeTrue(supportsSymlinks(), "Filesystem does not allow symlink creation; skipping");
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		Path real = tempDir.resolve("artifacts/run/art/v1/real.md");
		Files.createDirectories(real.getParent());
		Files.writeString(real, "real");
		Path link = tempDir.resolve("artifacts/run/art/v1/alias.md");
		Files.createSymbolicLink(link, real);

		// Symlink resolution should land inside home and point to a regular file.
		assertTrue(store.isReadable("artifacts/run/art/v1/alias.md"));
	}

	// ---------- C3-P2: readBytes containment (parallel to isReadable) ----------

	@Test
	void readBytesReturnsPayloadBytesForValidStorageRefInsideHome() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		byte[] payload = "spec body".getBytes(StandardCharsets.UTF_8);
		String storageRef = store.write("run_ready1234", "art_ready1234", 1, "spec.md", payload);

		Optional<byte[]> result = store.readBytes(storageRef);

		assertTrue(result.isPresent());
		assertArrayEquals(payload, result.get());
	}

	@Test
	void readBytesReturnsEmptyForBlankOrNullStorageRef() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		assertTrue(store.readBytes(null).isEmpty());
		assertTrue(store.readBytes("").isEmpty());
		assertTrue(store.readBytes("   ").isEmpty());
	}

	@Test
	void readBytesReturnsEmptyForTraversalAndAbsoluteAndSchemedStorageRefs() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		String absolute = isWindows() ? "C:\\Windows\\System32\\drivers\\etc\\hosts" : "/etc/passwd";

		assertTrue(store.readBytes("../escape.txt").isEmpty());
		assertTrue(store.readBytes(absolute).isEmpty());
		assertTrue(store.readBytes("file:///etc/passwd").isEmpty());
		assertTrue(store.readBytes("artifacts/run/art/v1/spec" + ((char) 0) + ".md").isEmpty());
		assertTrue(store.readBytes("artifacts\\run_x\\art_y\\v1\\spec.md").isEmpty());
	}

	@Test
	void readBytesReturnsEmptyForDirectoriesAndSymlinksEscapingHome() throws Exception {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());
		Path innerDir = tempDir.resolve("artifacts/run/art/v1");
		Files.createDirectories(innerDir);

		assertTrue(store.readBytes("artifacts/run/art/v1").isEmpty());

		assumeTrue(supportsSymlinks(), "Filesystem does not allow symlink creation; skipping");
		Path outside = tempDir.resolveSibling("readbytes-outside-secret.txt");
		Files.writeString(outside, "secret");
		Path link = innerDir.resolve("escape.md");
		Files.createSymbolicLink(link, outside);

		assertTrue(store.readBytes("artifacts/run/art/v1/escape.md").isEmpty());
	}

	// ---------- existing programmer-error guards ----------

	@Test
	void writeRejectsNullPayloadContentInsteadOfWritingTheReferenceLiteral() {
		LocalArtifactStore store = new LocalArtifactStore(tempDir.toString());

		IllegalArgumentException error = assertThrows(
			IllegalArgumentException.class,
			() -> store.write("run_ready1234", "art_ready1234", 2, "spec-v2.md", null));

		assertEquals("Artifact payloadContent must not be null", error.getMessage());
	}

	private boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private boolean supportsSymlinks() {
		try {
			Path src = tempDir.resolve("__symlink_probe_src");
			Path link = tempDir.resolve("__symlink_probe_link");
			Files.writeString(src, "probe");
			Files.createSymbolicLink(link, src);
			Files.deleteIfExists(link);
			Files.deleteIfExists(src);
			return true;
		} catch (FileSystemException ex) {
			return false;
		} catch (UnsupportedOperationException | IOException ex) {
			return false;
		}
	}
}
