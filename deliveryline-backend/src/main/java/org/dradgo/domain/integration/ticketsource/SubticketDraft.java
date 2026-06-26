package org.dradgo.domain.integration.ticketsource;

import java.util.Objects;

/**
 * Vendor-neutral draft for creating a child ticket under a parent ticket source reference. The text
 * fields must already be redacted by the caller before reaching an adapter.
 */
public record SubticketDraft(
    String parentRunId,
    String proposalId,
    String subtaskId,
    int ordinal,
    String title,
    String description,
    String idempotencyKey) {

  public SubticketDraft {
    parentRunId = requireNonBlank(parentRunId, "parentRunId");
    proposalId = requireNonBlank(proposalId, "proposalId");
    subtaskId = requireNonBlank(subtaskId, "subtaskId");
    if (ordinal < 1) {
      throw new IllegalArgumentException("ordinal must be positive");
    }
    title = requireNonBlank(title, "title");
    description = Objects.requireNonNull(description, "description");
    idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
