package org.dradgo.infrastructure.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.dradgo.application.security.CredentialCipher;
import org.dradgo.application.security.CredentialCipherException;
import org.dradgo.application.security.EncryptedSecret;
import org.springframework.stereotype.Component;

/**
 * Story 3c-4 (AC1/AC2/AC4/AC7) — envelope-encryption implementation of the {@link CredentialCipher}
 * port, in {@code infrastructure.crypto} (the impl half of the R1 port/impl split).
 *
 * <p><b>Envelope encryption.</b> Every {@link #encrypt(String)} generates a fresh random 256-bit
 * data key (DEK) that encrypts the plaintext under AES-256-GCM; the DEK is then wrapped (encrypted)
 * by the master key (KEK) — a 256-bit key resolved once at construction from {@code
 * deliveryline.crypto.master-key} ({@code DELIVERYLINE_MASTER_KEY} env), never persisted. A fresh
 * DEK + random nonces per secret mean two encryptions of the same plaintext yield different
 * ciphertext.
 *
 * <p><b>Self-describing frame</b> (the {@code ciphertext} bytea): {@code
 * [version(1B)][nonce1(12B)][wrappedDekLen(2B big-endian)][wrappedDek][nonce2(12B)][ct]}. Because
 * the frame carries its own structure, rotation to a new key/suite needs no schema change.
 *
 * <p><b>Key identity + rotation (AC4).</b> {@code keyId = "mk_" + first 12 hex of SHA-256(KEK)}
 * deterministically ties a stored row to the key that wrapped it. Until a rotation map exists,
 * {@link #decrypt(byte[], String, String)} hard-rejects any non-active {@code keyId}; the
 * documented re-wrap path (unwrap each DEK with the old KEK, re-wrap with the new) lives in ADR
 * 0013.
 *
 * <p><b>Secret-hostile.</b> No method logs; exception messages are generic and never carry
 * plaintext, ciphertext, the DEK, or the KEK. The DEK is zeroed in a {@code finally} on every path.
 * Every {@code javax.crypto} failure (incl. {@code AEADBadTagException} on tamper) is caught and
 * re-thrown as {@link CredentialCipherException} so no crypto type leaks and tamper never yields
 * partial plaintext.
 */
@Component
public final class EnvelopeCredentialCipher implements CredentialCipher {

  /** Cipher-suite tag recorded on every row (AC2). Future suites get a distinct value. */
  public static final String ALGORITHM = "AES-256-GCM";

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int KEY_LENGTH_BYTES = 32; // 256-bit DEK + KEK
  private static final int NONCE_LENGTH_BYTES = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final byte FRAME_VERSION = 1;

  private final SecureRandom secureRandom = new SecureRandom();
  private final byte[] kek;
  private final boolean masterKeyPresent;
  private final String activeKeyId;

  public EnvelopeCredentialCipher(CryptoProperties properties) {
    String configured = properties.masterKey();
    if (configured == null || configured.isBlank()) {
      this.kek = null;
      this.masterKeyPresent = false;
      this.activeKeyId = null;
      return;
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(configured.trim());
    } catch (IllegalArgumentException error) {
      // A malformed key is an operator error, not a silent fallback. Message names the env var but
      // NEVER echoes the key value.
      throw new IllegalStateException(
          "deliveryline.crypto.master-key (DELIVERYLINE_MASTER_KEY) is not valid Base64");
    }
    if (decoded.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException(
          "deliveryline.crypto.master-key (DELIVERYLINE_MASTER_KEY) must decode to "
              + KEY_LENGTH_BYTES
              + " bytes (256-bit); got "
              + decoded.length);
    }
    this.kek = decoded;
    this.masterKeyPresent = true;
    this.activeKeyId = deriveKeyId(decoded);
  }

  @Override
  public EncryptedSecret encrypt(String plaintext) {
    if (plaintext == null) {
      throw new IllegalArgumentException("plaintext must not be null");
    }
    requireMasterKey();
    byte[] dek = new byte[KEY_LENGTH_BYTES];
    try {
      secureRandom.nextBytes(dek);
      byte[] nonce1 = randomNonce();
      byte[] nonce2 = randomNonce();
      byte[] wrappedDek = gcm(Cipher.ENCRYPT_MODE, kek, nonce1, dek);
      byte[] ct = gcm(Cipher.ENCRYPT_MODE, dek, nonce2, plaintext.getBytes(StandardCharsets.UTF_8));

      ByteBuffer frame =
          ByteBuffer.allocate(
              1 + NONCE_LENGTH_BYTES + 2 + wrappedDek.length + NONCE_LENGTH_BYTES + ct.length);
      frame.put(FRAME_VERSION);
      frame.put(nonce1);
      frame.putShort((short) wrappedDek.length);
      frame.put(wrappedDek);
      frame.put(nonce2);
      frame.put(ct);
      return new EncryptedSecret(frame.array(), activeKeyId, ALGORITHM);
    } catch (GeneralSecurityException error) {
      // Encryption with a validated key should not fail; surface generically without leaking state.
      throw new CredentialCipherException("credential encryption failed");
    } finally {
      Arrays.fill(dek, (byte) 0);
    }
  }

  @Override
  public String decrypt(byte[] ciphertext, String keyId, String algo) {
    if (!ALGORITHM.equals(algo)) {
      throw new CredentialCipherException("unsupported credential cipher algorithm");
    }
    requireMasterKey();
    if (!activeKeyId.equals(keyId)) {
      throw new CredentialCipherException(
          "credential key identifier does not match the active key");
    }
    if (ciphertext == null) {
      throw new CredentialCipherException("credential ciphertext is missing");
    }
    byte[] dek = null;
    try {
      ByteBuffer frame = ByteBuffer.wrap(ciphertext);
      byte version = frame.get();
      if (version != FRAME_VERSION) {
        throw new CredentialCipherException("unsupported credential ciphertext version");
      }
      byte[] nonce1 = readBytes(frame, NONCE_LENGTH_BYTES);
      int wrappedDekLen = frame.getShort() & 0xFFFF;
      byte[] wrappedDek = readBytes(frame, wrappedDekLen);
      byte[] nonce2 = readBytes(frame, NONCE_LENGTH_BYTES);
      byte[] ct = readBytes(frame, frame.remaining());

      dek = gcm(Cipher.DECRYPT_MODE, kek, nonce1, wrappedDek);
      byte[] plaintext = gcm(Cipher.DECRYPT_MODE, dek, nonce2, ct);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | RuntimeException error) {
      if (error instanceof CredentialCipherException cce) {
        throw cce;
      }
      // GCM tag mismatch (tamper), buffer underflow (malformed frame), etc. — generic, no leak.
      throw new CredentialCipherException("credential decryption failed");
    } finally {
      if (dek != null) {
        Arrays.fill(dek, (byte) 0);
      }
    }
  }

  private void requireMasterKey() {
    if (!masterKeyPresent) {
      throw new IllegalStateException(
          "Credential master key is not configured: set DELIVERYLINE_MASTER_KEY "
              + "(deliveryline.crypto.master-key) before encrypting or decrypting credentials");
    }
  }

  private byte[] randomNonce() {
    byte[] nonce = new byte[NONCE_LENGTH_BYTES];
    secureRandom.nextBytes(nonce);
    return nonce;
  }

  private static byte[] readBytes(ByteBuffer buffer, int length) {
    if (length < 0 || length > buffer.remaining()) {
      throw new CredentialCipherException("malformed credential ciphertext frame");
    }
    byte[] out = new byte[length];
    buffer.get(out);
    return out;
  }

  private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] data)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(
        mode,
        new SecretKeySpec(key, KEY_ALGORITHM),
        new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
    return cipher.doFinal(data);
  }

  private static String deriveKeyId(byte[] keyBytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBytes);
      return "mk_" + HexFormat.of().formatHex(digest).substring(0, 12);
    } catch (GeneralSecurityException error) {
      // SHA-256 is mandated by the JDK; unreachable in practice.
      throw new IllegalStateException("SHA-256 unavailable for key-id derivation", error);
    }
  }
}
