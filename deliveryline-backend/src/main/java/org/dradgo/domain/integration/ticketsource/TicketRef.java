package org.dradgo.domain.integration.ticketsource;

/**
 * Vendor-neutral value wrapper for an external ticket reference (e.g. {@code "LIN-123"}). Replaces
 * the bare {@code String ticketRef} that story 1.14 threaded through every port signature so a
 * future ticket source (JIRA, GitHub Issues, GitLab Issues) speaks the same domain vocabulary
 * (story 3.32 R2).
 *
 * <p>The wrapped {@link #value()} is the source-opaque reference token — the implementing adapter
 * (and only the adapter) interprets its internal shape. Neutral consumers treat it as an opaque
 * non-blank string and map {@link #value()} at the persistence boundary ({@code
 * integration_links.external_ref} stays a {@code String}).
 */
public record TicketRef(String value) {

  public TicketRef {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("TicketRef value must be non-blank");
    }
  }

  /** Factory mirroring the {@code SomeType.of(...)} idiom used across the domain value records. */
  public static TicketRef of(String value) {
    return new TicketRef(value);
  }
}
