package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 3c-5 (AC3/AC7) — the typed per-project connector <em>role</em> a credential is bound to,
 * mirroring the {@link ConnectorKind} registry shape.
 *
 * <p><strong>Wire values are underscored</strong> ({@code ticket_source} / {@code repo_host}) to
 * match the V17 {@code ck_project_credentials_connector_role} CHECK and the {@code
 * ProjectCredentialSource} javadoc — NOT the {@code ProjectConnectorResolver}'s hyphenated {@code
 * "ticket-source"} / {@code "repository-host"} constants, which are log labels only and are never
 * persisted nor passed through {@code resolveSecret}. (See the story's R1 reconciliation.)
 *
 * <p>Distinct from {@link ConnectorKind} ({@code linear}/{@code github}/{@code gitlab}): a
 * <em>kind</em> is the vendor, a <em>role</em> is what the connector does for a project.
 */
public enum ConnectorRole implements RegistryValue {
  TICKET_SOURCE("ticket_source"),
  REPO_HOST("repo_host");

  private static final Map<String, ConnectorRole> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ConnectorRole(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static ConnectorRole fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ConnectorRole fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ConnectorRole", rawValue, field, LOOKUP);
  }
}
