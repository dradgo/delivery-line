package org.dradgo.application.security;

/**
 * Story 3c-4 (AC1/AC2/AC7) — unchecked failure raised by {@link CredentialCipher} for every
 * decryption fault: GCM authentication-tag mismatch (tamper), a {@code keyId} the active master key
 * does not match (wrong key), an unsupported {@code algo}, or an undecodable/short ciphertext
 * frame.
 *
 * <p>Deliberately NOT mapped to a {@code DomainErrorCode}: there is no REST surface for the cipher
 * yet (Problem-Details mapping of credential failures is a 3c-8 concern). The message is generic
 * and MUST NEVER carry plaintext, ciphertext bytes, or key material — neither in the message nor in
 * any wrapped cause. (See the story's Logging task — the cipher hot path is secret-hostile.)
 */
public final class CredentialCipherException extends RuntimeException {

  public CredentialCipherException(String message) {
    super(message);
  }
}
