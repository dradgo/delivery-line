package org.dradgo.application.artifact;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public record ArtifactChecksum(
	String algorithm,
	String value
) {

	// Restrict checksum recompute to vetted strong algorithms; rejects MD5, SHA-1, and ambiguous
	// non-canonical aliases like "sha256" (no dash) so storage and recompute paths cannot diverge
	// based on JCA legacy aliasing.
	public static final Set<String> ALLOWED_ALGORITHMS = Set.of("SHA-256", "SHA-512");

	public static String canonicalAlgorithm(String algorithm) {
		return algorithm == null ? null : algorithm.trim().toUpperCase(Locale.ROOT);
	}

	public static Optional<String> digestHex(String algorithm, byte[] payload) {
		String canonical = canonicalAlgorithm(algorithm);
		if (canonical == null || !ALLOWED_ALGORITHMS.contains(canonical)) {
			return Optional.empty();
		}
		try {
			MessageDigest digest = MessageDigest.getInstance(canonical);
			return Optional.of(HexFormat.of().formatHex(digest.digest(payload)));
		} catch (NoSuchAlgorithmException error) {
			return Optional.empty();
		}
	}
}
