package org.dradgo.infrastructure.crypto;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Story 3c-4 (AC3/AC7) — fail-fast startup guard for the credential master key.
 *
 * <p>Aborts boot with {@link DomainErrorCode#CREDENTIAL_MASTER_KEY_UNCONFIGURED} <b>iff</b> the
 * master key is missing/blank <b>AND</b> at least one {@code project_credentials} row exists. With
 * no credential rows present the app boots normally (greenfield/test parity — the credential
 * subsystem is dormant until story 3c-5 writes the first row). The "credentials present" check is a
 * thin {@code SELECT count(*) FROM project_credentials} (no JPA entity — that is 3c-5).
 *
 * <p>Runs as a {@code @PostConstruct} on a bean {@code @DependsOn("flywayInitializer")} so the
 * count query runs <em>after</em> migrations have created the table, and — because
 * {@code @PostConstruct} fires during bean init, before {@code finishRefresh()} binds the web
 * connector — a refused boot never serves traffic (the same timing guarantee {@code
 * EmbeddedFrontendGuard} relies on). The {@link JdbcTemplate} is injected via {@link
 * ObjectProvider} so the guard is inert when no {@code DataSource} is present.
 *
 * <p>Gated by {@code deliveryline.crypto.fail-on-missing-master-key} (default {@code true}); a dev
 * run that deliberately wants to skip the check can set it {@code false} (mirroring {@code
 * EmbeddedFrontendGuard}'s opt-out).
 *
 * <p><b>Secret-hostile:</b> the guard logs only counts + the configured/dormant state and the
 * failure carries only {@code credentialCount} — never the key, a key id, or any plaintext.
 */
@Component
@DependsOn("flywayInitializer")
@ConditionalOnProperty(
    name = "deliveryline.crypto.fail-on-missing-master-key",
    havingValue = "true",
    matchIfMissing = true)
public class CredentialMasterKeyGuard {

  private static final Logger LOG = LoggerFactory.getLogger(CredentialMasterKeyGuard.class);

  private static final String CREDENTIAL_COUNT_QUERY = "select count(*) from project_credentials";

  private final CryptoProperties properties;
  private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

  public CredentialMasterKeyGuard(
      CryptoProperties properties, ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
    this.properties = properties;
    this.jdbcTemplateProvider = jdbcTemplateProvider;
  }

  @PostConstruct
  void verifyMasterKeyConfigured() {
    JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    if (jdbcTemplate == null) {
      LOG.info("credential master-key guard inert: no DataSource present");
      return;
    }
    boolean masterKeyPresent = !properties.masterKey().isBlank();
    Long count = jdbcTemplate.queryForObject(CREDENTIAL_COUNT_QUERY, Long.class);
    long credentialCount = count == null ? 0L : count;

    assertMasterKeyConfigured(masterKeyPresent, credentialCount);

    if (credentialCount == 0L) {
      LOG.info(
          "credential subsystem dormant: 0 project_credentials rows — master key not required at "
              + "boot");
    } else {
      LOG.info(
          "credential subsystem active: {} project_credentials row(s) present, master key "
              + "configured",
          credentialCount);
    }
  }

  /**
   * Throws {@link DomainException}({@link DomainErrorCode#CREDENTIAL_MASTER_KEY_UNCONFIGURED}) when
   * the master key is absent but credentials exist; returns otherwise. Package-private + static so
   * both branches are unit-testable without a Spring context or a database (mirrors {@code
   * EmbeddedFrontendGuard.assertBundlePresent}).
   */
  static void assertMasterKeyConfigured(boolean masterKeyPresent, long credentialCount) {
    if (masterKeyPresent || credentialCount <= 0L) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("credentialCount", credentialCount);
    throw new DomainException(
        DomainErrorCode.CREDENTIAL_MASTER_KEY_UNCONFIGURED,
        "Refusing to start: "
            + credentialCount
            + " encrypted credential(s) exist but the credential master key is not configured. Set "
            + "the DELIVERYLINE_MASTER_KEY environment variable (deliveryline.crypto.master-key) to "
            + "the Base64-encoded 256-bit key that wrapped them — it must NEVER be committed. For a "
            + "dev run that deliberately skips this check, set "
            + "deliveryline.crypto.fail-on-missing-master-key=false.",
        details);
  }
}
