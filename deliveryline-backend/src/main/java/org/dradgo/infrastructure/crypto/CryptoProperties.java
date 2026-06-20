package org.dradgo.infrastructure.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Story 3c-4 (AC1/AC3) — constructor-bound configuration for the credential cipher.
 *
 * <p>Binds {@code deliveryline.crypto.master-key} (the Base64-encoded 256-bit KEK, supplied at
 * runtime via the {@code DELIVERYLINE_MASTER_KEY} env var — NEVER committed). The compact
 * constructor <b>normalizes-never-throws</b>: a blank/null key becomes {@code ""} so a key-less
 * greenfield/test context still binds and boots. Fail-fast is owned by {@link
 * CredentialMasterKeyGuard} (count-gated), NOT by binding.
 *
 * <p><b>Deliberately NOT {@code @Validated}</b> and carrying no non-blank constraint: a non-blank
 * required master key would fail <em>every</em> key-less {@code @SpringBootTest} context at binding
 * (the test {@code application.yml} shadows, not merges — see the {@code
 * validated-config-needs-test-yaml} memory). The master-key line is therefore added to the MAIN
 * {@code application.yml} only; leaving the test tier key-less keeps the guard inert (count = 0).
 */
@ConfigurationProperties("deliveryline.crypto")
public record CryptoProperties(String masterKey) {

  public CryptoProperties {
    masterKey = (masterKey == null || masterKey.isBlank()) ? "" : masterKey;
  }
}
