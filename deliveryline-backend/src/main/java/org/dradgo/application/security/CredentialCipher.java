package org.dradgo.application.security;

/**
 * Story 3c-4 (AC1/AC2) — application-layer port for connector-credential encryption.
 *
 * <p>The interface lives in {@code application.security} (alongside {@link RedactionPolicyService})
 * — NOT in {@code infrastructure.crypto} where the epic literally placed it — because story 3c-5's
 * credential store lives in {@code application.project} and must call the cipher, and the {@code
 * APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE} ArchUnit rule forbids {@code application.. ->
 * infrastructure..}. The concrete {@link EnvelopeCredentialCipher} implementation lives in {@code
 * infrastructure.crypto}; Spring injects it wherever this port is required. This is the same
 * port/adapter seam the codebase already uses for {@code DockerHostPort}. (See the story's R1
 * reconciliation.)
 *
 * <p>The port is JDK-only (no Spring import) and intentionally narrow: a single value-producing
 * {@link #encrypt(String)} and its inverse {@link #decrypt(byte[], String, String)}. Every
 * decryption failure mode (tamper, wrong key, unsupported algo, malformed frame) surfaces as a
 * {@link CredentialCipherException} carrying no secret material. A <em>misconfiguration</em> (the
 * master key is absent at call time) is distinct from a decryption failure and surfaces as an
 * unchecked {@link IllegalStateException} from both {@link #encrypt(String)} and {@link
 * #decrypt(byte[], String, String)} — the {@code CredentialMasterKeyGuard} normally fails the boot
 * first, so a key-less call only reaches here when the guard's escape hatch is set.
 */
public interface CredentialCipher {

  /**
   * Encrypts {@code plaintext} under the active master key, returning the ciphertext frame together
   * with the {@code keyId} that wrapped it and the {@code algo} cipher-suite tag. The three
   * returned components map 1:1 to the V17 {@code project_credentials} columns ({@code ciphertext},
   * {@code key_id}, {@code algo}).
   */
  EncryptedSecret encrypt(String plaintext);

  /**
   * Decrypts a ciphertext frame previously produced by {@link #encrypt(String)}. Rejects with a
   * {@link CredentialCipherException} when {@code algo} is unsupported, {@code keyId} does not
   * match the active master key, the frame is malformed/short, or the GCM authentication tag fails
   * (tamper) — never returning partial or silent plaintext.
   */
  String decrypt(byte[] ciphertext, String keyId, String algo);
}
