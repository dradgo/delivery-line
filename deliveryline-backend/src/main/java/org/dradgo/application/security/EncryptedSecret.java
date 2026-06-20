package org.dradgo.application.security;

import java.util.Objects;

/**
 * Story 3c-4 (AC1/AC2) — the encrypted form of a connector credential, mapping 1:1 to the V17
 * {@code project_credentials} columns ({@code ciphertext bytea}, {@code key_id text}, {@code algo
 * text}).
 *
 * <ul>
 *   <li>{@code ciphertext} — the self-describing envelope frame (version + wrapped DEK + GCM
 *       ciphertext) produced by {@link EnvelopeCredentialCipher}; opaque to callers.
 *   <li>{@code keyId} — a stable, non-secret identifier of the master key that wrapped this row's
 *       data key (e.g. {@code "mk_" + first 12 hex of SHA-256(keyBytes)}), so a stored row can be
 *       tied to the key version that produced it (AC4 rotation indirection).
 *   <li>{@code algo} — the cipher-suite tag ({@code "AES-256-GCM"}), recorded so a future rotation
 *       to a new suite stays non-breaking.
 * </ul>
 *
 * <p>The {@code ciphertext} array is defensively cloned on construction and on access — this is a
 * secret-bearing value and a shared mutable array would let a caller corrupt a stored credential or
 * observe a later mutation.
 */
public record EncryptedSecret(byte[] ciphertext, String keyId, String algo) {

  public EncryptedSecret {
    Objects.requireNonNull(ciphertext, "ciphertext");
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(algo, "algo");
    if (keyId.isBlank() || algo.isBlank()) {
      throw new IllegalArgumentException("keyId/algo must not be blank");
    }
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
