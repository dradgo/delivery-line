package org.dradgo.domain.integration.ticketsource;

import java.util.Map;
import java.util.Objects;

/** Vendor-neutral result of a source sub-ticket creation attempt. */
public record CreateSubticketResult(
    TicketRef childRef,
    String idempotencyKey,
    String parentLinkFingerprint,
    boolean replay,
    Map<String, String> metadata) {

  public CreateSubticketResult {
    childRef = Objects.requireNonNull(childRef, "childRef");
    idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
    parentLinkFingerprint = requireNonBlank(parentLinkFingerprint, "parentLinkFingerprint");
    metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
