package org.dradgo.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Base64;
import org.dradgo.application.security.CredentialCipherException;
import org.dradgo.application.security.EncryptedSecret;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3c-4 (AC7) — pure-JUnit coverage of the envelope cipher. Constructs {@link
 * CryptoProperties} directly with a fixed Base64 32-byte test KEK (no Spring).
 */
class EnvelopeCredentialCipherTest {

  /** Deterministic 32-byte (256-bit) test KEK, Base64-encoded. */
  private static final String TEST_KEK_BASE64 = Base64.getEncoder().encodeToString(testKekBytes());

  private final EnvelopeCredentialCipher cipher =
      new EnvelopeCredentialCipher(new CryptoProperties(TEST_KEK_BASE64));

  private static byte[] testKekBytes() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) (i + 1);
    }
    return key;
  }

  @Test
  void roundTripsAsciiUnicodeAndEmptyPlaintext() {
    for (String plaintext :
        new String[] {
          "ghp_exampleGitHubToken1234567890", "", "  ", "пароль-Ключ-🔐-ünïcödé", "a".repeat(4096)
        }) {
      EncryptedSecret secret = cipher.encrypt(plaintext);
      assertThat(secret.algo()).isEqualTo(EnvelopeCredentialCipher.ALGORITHM);
      assertThat(secret.keyId()).startsWith("mk_");
      assertThat(cipher.decrypt(secret.ciphertext(), secret.keyId(), secret.algo()))
          .isEqualTo(plaintext);
    }
  }

  @Test
  void twoEncryptsOfSamePlaintextYieldDifferentCiphertext() {
    EncryptedSecret first = cipher.encrypt("same-secret");
    EncryptedSecret second = cipher.encrypt("same-secret");
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    // Both still decrypt to the same plaintext under the same (active) key.
    assertThat(first.keyId()).isEqualTo(second.keyId());
    assertThat(cipher.decrypt(second.ciphertext(), second.keyId(), second.algo()))
        .isEqualTo("same-secret");
  }

  @Test
  void tamperedCiphertextIsRejectedWithoutPartialPlaintext() {
    EncryptedSecret secret = cipher.encrypt("tamper-target");
    byte[] tampered = secret.ciphertext();
    tampered[tampered.length - 1] ^= 0x01; // flip a bit in the GCM ciphertext region

    assertThatThrownBy(() -> cipher.decrypt(tampered, secret.keyId(), secret.algo()))
        .isInstanceOf(CredentialCipherException.class)
        .hasMessageContaining("decryption failed");
  }

  @Test
  void wrongKeyIdIsRejected() {
    EncryptedSecret secret = cipher.encrypt("secret");
    assertThatThrownBy(() -> cipher.decrypt(secret.ciphertext(), "mk_ffffffffffff", secret.algo()))
        .isInstanceOf(CredentialCipherException.class)
        .hasMessageContaining("does not match the active key");
  }

  @Test
  void unsupportedAlgoIsRejected() {
    EncryptedSecret secret = cipher.encrypt("secret");
    assertThatThrownBy(() -> cipher.decrypt(secret.ciphertext(), secret.keyId(), "AES-128-CBC"))
        .isInstanceOf(CredentialCipherException.class)
        .hasMessageContaining("unsupported credential cipher algorithm");
  }

  @Test
  void malformedShortCiphertextIsRejected() {
    EncryptedSecret secret = cipher.encrypt("secret");
    String keyId = secret.keyId();
    assertThatThrownBy(() -> cipher.decrypt(new byte[] {1, 2, 3}, keyId, secret.algo()))
        .isInstanceOf(CredentialCipherException.class);
    assertThatThrownBy(() -> cipher.decrypt(new byte[0], keyId, secret.algo()))
        .isInstanceOf(CredentialCipherException.class);
  }

  @Test
  void malformedKekLengthFailsFastAtConstruction() {
    String shortKek = Base64.getEncoder().encodeToString(new byte[16]); // 128-bit, not 256-bit
    assertThatThrownBy(() -> new EnvelopeCredentialCipher(new CryptoProperties(shortKek)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("256-bit");
  }

  @Test
  void nonBase64KekFailsFastAtConstruction() {
    assertThatThrownBy(
            () -> new EnvelopeCredentialCipher(new CryptoProperties("not valid base64!!")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Base64");
  }

  @Test
  void keyIdIsDeterministicForTheSameKek() {
    EnvelopeCredentialCipher other =
        new EnvelopeCredentialCipher(new CryptoProperties(TEST_KEK_BASE64));
    assertThat(cipher.encrypt("x").keyId()).isEqualTo(other.encrypt("y").keyId());
  }

  @Test
  void keylessCipherRefusesToEncryptOrDecrypt() {
    EnvelopeCredentialCipher keyless = new EnvelopeCredentialCipher(new CryptoProperties(""));
    assertThatThrownBy(() -> keyless.encrypt("x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DELIVERYLINE_MASTER_KEY");
    assertThatThrownBy(() -> keyless.decrypt(new byte[] {1}, "mk_abc", "AES-256-GCM"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nullPlaintextIsRejected() {
    assertThatCode(() -> cipher.encrypt("ok")).doesNotThrowAnyException();
    assertThatThrownBy(() -> cipher.encrypt(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cipherEmitsNoLogsOnTheSecretHotPath() {
    Logger logger = (Logger) LoggerFactory.getLogger(EnvelopeCredentialCipher.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      EncryptedSecret secret = cipher.encrypt("top-secret-token");
      cipher.decrypt(secret.ciphertext(), secret.keyId(), secret.algo());
      assertThat(appender.list).isEmpty();
    } finally {
      logger.detachAppender(appender);
    }
  }
}
