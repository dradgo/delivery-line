package org.dradgo.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 3c-4 (AC3/AC7) — unit coverage of the master-key fail-fast guard. The decision is extracted
 * to a static predicate so both branches are testable without a Spring context or a database; the
 * {@code @PostConstruct} path is exercised with a mocked {@link JdbcTemplate}.
 */
class CredentialMasterKeyGuardTest {

  // ---- static decision (no Spring / no DB) ----

  @Test
  void missingKeyWithCredentialsThrows() {
    assertThatThrownBy(() -> CredentialMasterKeyGuard.assertMasterKeyConfigured(false, 1))
        .isInstanceOf(DomainException.class)
        .satisfies(
            error ->
                assertThat(((DomainException) error).errorCode())
                    .isEqualTo(DomainErrorCode.CREDENTIAL_MASTER_KEY_UNCONFIGURED))
        .satisfies(
            error ->
                assertThat(((DomainException) error).details())
                    .containsEntry("credentialCount", 1L));
  }

  @Test
  void missingKeyWithNoCredentialsBoots() {
    assertThatCode(() -> CredentialMasterKeyGuard.assertMasterKeyConfigured(false, 0))
        .doesNotThrowAnyException();
  }

  @Test
  void keyPresentBootsRegardlessOfCredentialCount() {
    assertThatCode(() -> CredentialMasterKeyGuard.assertMasterKeyConfigured(true, 5))
        .doesNotThrowAnyException();
    assertThatCode(() -> CredentialMasterKeyGuard.assertMasterKeyConfigured(true, 0))
        .doesNotThrowAnyException();
  }

  @Test
  void failureMessageNamesTheEnvVarAndEscapeHatch() {
    assertThatThrownBy(() -> CredentialMasterKeyGuard.assertMasterKeyConfigured(false, 3))
        .hasMessageContaining("DELIVERYLINE_MASTER_KEY")
        .hasMessageContaining("fail-on-missing-master-key=false");
  }

  // ---- @PostConstruct path (mocked JdbcTemplate) ----

  @Test
  void postConstructThrowsWhenCredentialsExistWithoutKey() {
    CredentialMasterKeyGuard guard = guardWith("", credentialCount(1L));
    assertThatThrownBy(guard::verifyMasterKeyConfigured)
        .isInstanceOf(DomainException.class)
        .satisfies(
            error ->
                assertThat(((DomainException) error).errorCode())
                    .isEqualTo(DomainErrorCode.CREDENTIAL_MASTER_KEY_UNCONFIGURED));
  }

  @Test
  void postConstructBootsDormantWhenNoCredentials() {
    CredentialMasterKeyGuard guard = guardWith("", credentialCount(0L));
    assertThatCode(guard::verifyMasterKeyConfigured).doesNotThrowAnyException();
  }

  @Test
  void postConstructBootsActiveWhenKeyPresentAndCredentialsExist() {
    CredentialMasterKeyGuard guard = guardWith("present-key", credentialCount(4L));
    assertThatCode(guard::verifyMasterKeyConfigured).doesNotThrowAnyException();
  }

  @Test
  void postConstructIsInertWithoutADataSource() {
    @SuppressWarnings("unchecked")
    ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    CredentialMasterKeyGuard guard =
        new CredentialMasterKeyGuard(new CryptoProperties(""), provider);
    assertThatCode(guard::verifyMasterKeyConfigured).doesNotThrowAnyException();
  }

  @Test
  void cryptoConfigurationIsInstantiable() {
    assertThat(new CryptoConfiguration()).isNotNull();
  }

  // ---- logging: state line is emitted; nothing secret is ever logged ----

  @Test
  void logsDormantStateAndNeverLeaksKeyMaterial() {
    Logger logger = (Logger) LoggerFactory.getLogger(CredentialMasterKeyGuard.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      // active branch (key present, credentials exist) — the operative INFO state line
      guardWith("present-key-material", credentialCount(7L)).verifyMasterKeyConfigured();
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                    .contains("credential subsystem active")
                    .contains("7");
              });
      // dormant branch (no credentials) — boots, logs the dormant line
      guardWith("", credentialCount(0L)).verifyMasterKeyConfigured();
      assertThat(appender.list)
          .anySatisfy(
              event ->
                  assertThat(event.getFormattedMessage()).contains("credential subsystem dormant"));

      // NOTHING logged at any level may carry the master key material.
      assertThat(appender.list)
          .noneSatisfy(
              event -> assertThat(event.getFormattedMessage()).contains("present-key-material"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static CredentialMasterKeyGuard guardWith(String masterKey, JdbcTemplate jdbcTemplate) {
    @SuppressWarnings("unchecked")
    ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
    return new CredentialMasterKeyGuard(new CryptoProperties(masterKey), provider);
  }

  private static JdbcTemplate credentialCount(long count) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class))).thenReturn(count);
    return jdbcTemplate;
  }
}
