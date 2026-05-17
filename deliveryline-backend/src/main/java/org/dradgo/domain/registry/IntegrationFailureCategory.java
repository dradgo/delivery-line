package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Classification axis for integration-layer (Linear, GitHub) sync/link failures. Separate from
 * {@link FailureCategory} which is runner-scoped — integration failures are about external API
 * conversations, not about runner-execution lifecycle.
 *
 * <p>Wire form is the {@code value()} (snake_case). Renaming an enum constant must keep the wire
 * value identical or it is a wire-breaking change. The values are referenced by AC6 of story 1.14.
 */
public enum IntegrationFailureCategory implements RegistryValue {
  SYNC_FAILURE("sync_failure"),
  LINK_FAILURE("link_failure"),
  STATE_CONFLICT("state_conflict"),
  NETWORK_API_FAILURE("network_api_failure");

  private static final Map<String, IntegrationFailureCategory> LOOKUP =
      RegistryParsers.index(values());

  private final String value;

  IntegrationFailureCategory(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static IntegrationFailureCategory fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static IntegrationFailureCategory fromValue(String rawValue, String field) {
    return RegistryParsers.parse("IntegrationFailureCategory", rawValue, field, LOOKUP);
  }

  public static IntegrationFailureCategory fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("IntegrationFailureCategory", rawValue, field, LOOKUP);
  }
}
