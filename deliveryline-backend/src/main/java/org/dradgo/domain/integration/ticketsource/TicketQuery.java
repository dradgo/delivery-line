package org.dradgo.domain.integration.ticketsource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Vendor-neutral filter for a candidate-ticket browse (story 3i-2 / FR81). Carries only
 * domain-shaped values so no vendor query language crosses the {@code TicketSourceAdapter} port
 * ({@code TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT}); the implementing adapter — and only the
 * adapter — translates it to its own query dialect (JIRA renders JQL).
 *
 * <p><strong>Absent means absent.</strong> {@code assignee} and {@code state} are nullable and a
 * blank value normalizes to {@code null}; {@code components} normalizes to an empty list. An absent
 * field carries no constraint and MUST be omitted from the vendor query entirely — it is never
 * rendered as a match-all clause (which would silently widen the browse rather than leave it
 * unfiltered).
 *
 * <p><strong>The values are opaque.</strong> {@code assignee} is whatever identity token the source
 * accepts (for JIRA Cloud an {@code accountId}, or an email the instance resolves) — it is never
 * interpreted here. Because every field is operator-supplied, the adapter treats them as an
 * injection boundary and escapes them before rendering.
 *
 * <p>{@code limit} bounds the result set: it must be positive, and is clamped down to {@link
 * #MAX_LIMIT} so a caller cannot ask the source for an unbounded page. {@code components} is
 * bounded the same way by {@link #MAX_COMPONENTS} — it is rendered into the vendor query one clause
 * element per token, so an unbounded set means an unbounded query string sent to the source.
 */
public record TicketQuery(String assignee, List<String> components, String state, int limit) {

  /** Hard ceiling on a single browse page — a larger requested {@code limit} clamps to this. */
  public static final int MAX_LIMIT = 200;

  /**
   * Hard ceiling on the component filter set. Unlike {@code limit} — which bounds only the
   * <em>response</em> — every component token is rendered into the vendor query itself (JIRA:
   * {@code component in ("a","b",…)}), so an unbounded set produces an unbounded request. A browse
   * filtered on more than this many components is not a browse; it is a misuse of the surface.
   */
  public static final int MAX_COMPONENTS = 50;

  /** The page size used when a caller does not specify one. */
  public static final int DEFAULT_LIMIT = 50;

  public TicketQuery {
    assignee = blankToNull(assignee);
    state = blankToNull(state);
    components = normalizeComponents(components);
    if (limit <= 0) {
      throw new IllegalArgumentException("TicketQuery limit must be positive (was " + limit + ")");
    }
    limit = Math.min(limit, MAX_LIMIT);
  }

  /** An unfiltered browse bounded by {@link #DEFAULT_LIMIT}. */
  public static TicketQuery unfiltered() {
    return new TicketQuery(null, List.of(), null, DEFAULT_LIMIT);
  }

  public boolean hasAssignee() {
    return assignee != null;
  }

  public boolean hasState() {
    return state != null;
  }

  public boolean hasComponents() {
    return !components.isEmpty();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * Null-safe, blank-dropping, order-preserving, unmodifiable copy, bounded by {@link
   * #MAX_COMPONENTS}.
   *
   * <p>An over-large set <strong>throws</strong> rather than clamping, unlike {@code limit}.
   * Clamping here would silently drop tokens from the vendor's {@code component in (…)} clause,
   * which <em>narrows</em> the match set — the browse would quietly hide tickets the operator
   * explicitly asked to see. Rejecting is the only honest option. REST and CLI pre-validate the
   * count and raise {@code INVALID_COMMAND_PAYLOAD} (400); this throw is the domain backstop for a
   * direct caller.
   */
  private static List<String> normalizeComponents(List<String> components) {
    if (components == null || components.isEmpty()) {
      return List.of();
    }
    List<String> normalized = new ArrayList<>(components.size());
    for (String component : components) {
      if (component != null && !component.isBlank()) {
        normalized.add(component.trim());
      }
    }
    if (normalized.size() > MAX_COMPONENTS) {
      throw new IllegalArgumentException(
          "TicketQuery components must not exceed "
              + MAX_COMPONENTS
              + " (was "
              + normalized.size()
              + ")");
    }
    return Collections.unmodifiableList(normalized);
  }
}
