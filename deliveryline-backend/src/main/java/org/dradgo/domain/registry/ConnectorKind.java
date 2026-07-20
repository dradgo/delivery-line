package org.dradgo.domain.registry;

import java.util.Map;

public enum ConnectorKind implements RegistryValue {
  LINEAR("linear"),
  GITHUB("github"),
  // Story 3c-3 (AC8) — a documented stub kind that proves the per-project kind->adapter seam
  // (always-on stub adapters declare connectorKind() == GITLAB and report a deliberately degraded
  // capability set). Widening this enum fans out to V18 (both projects CHECK constraints) + the
  // connectorKinds API placeholder; the 3c-2 RegistryContractTest drift gate enforces all three
  // stay aligned. A full GitLab vendor implementation is post-pilot.
  GITLAB("gitlab"),
  // Story 3i-1 (FR80) — JIRA is a first-class ticket source at Linear parity (real
  // JiraReal/JiraMockAdapter under adapters.integration.ticketsource.jira). Widening this enum fans
  // out to V37 (both projects CHECK constraints, mirroring the V18/GITLAB precedent) + the
  // connectorKinds API placeholder; the 3c-2 RegistryContractTest drift gate enforces all three
  // stay aligned.
  JIRA("jira"),
  // Story 3i-3 (FR82) — Bitbucket is a first-class repository host at GitHub parity (real
  // BitbucketRealAdapter/BitbucketMockAdapter under adapters.integration.repohost.bitbucket).
  // Widening this enum fans out to V39 (both projects CHECK constraints, mirroring the V18/GITLAB
  // precedent) + the connectorKinds API placeholder; the 3c-2 RegistryContractTest drift gate
  // enforces all three stay aligned.
  BITBUCKET("bitbucket");

  private static final Map<String, ConnectorKind> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ConnectorKind(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static ConnectorKind fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ConnectorKind fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ConnectorKind", rawValue, field, LOOKUP);
  }
}
