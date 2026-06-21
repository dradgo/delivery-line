package org.dradgo.application.integration;

/**
 * Story 3c-8 (AC3 / R1) — vendor-neutral result of a connector connectivity probe.
 *
 * <p>The {@code verifyConnectivity()} probe on {@code TicketSourceAdapter} / {@code
 * RepositoryHostAdapter} returns this record so the project test-connection surface can render a
 * per-check tri-state without ever importing a vendor type. It lives in {@code
 * application.integration} (above both port sub-packages) so neither port leaks a transport type —
 * the {@code TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT} / {@code
 * REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rules forbid only the vendor SDK /
 * Spring HTTP-client packages, which this neutral record does not touch.
 *
 * <ul>
 *   <li>{@code reachable} — the probe's <em>target</em> responded successfully: for a repository
 *       probe the named repository exists and is accessible; for a host "whoami" probe the host
 *       answered. {@code false} covers both a network/DNS/timeout failure <em>and</em> a target the
 *       host reports absent (e.g. a 404 on a repository probe — the host answered, but that
 *       repository itself is not reachable). Note a single repository probe informs both the
 *       repository-reachable and the repository-host-auth check the test-connection surface
 *       renders.
 *   <li>{@code authenticated} — the supplied credentials were accepted (not a 401/403). Meaningful
 *       only when the host answered; a rate-limit (429) or server error (5xx) is reported as {@code
 *       authenticated == false} with a "could not verify" detail, NOT as an auth rejection.
 *   <li>{@code detail} — a short, <strong>secret-free</strong> human description ("authenticated",
 *       "authentication failed", "host unreachable", …). It MUST NEVER echo a token, ciphertext,
 *       key id, or a raw probe response body.
 * </ul>
 */
public record ConnectivityResult(boolean reachable, boolean authenticated, String detail) {

  /** A reachable + authenticated result carrying the given secret-free detail. */
  public static ConnectivityResult ok(String detail) {
    return new ConnectivityResult(true, true, detail);
  }

  /** A reachable-but-unauthenticated result (the endpoint answered with a 401/403). */
  public static ConnectivityResult unauthenticated(String detail) {
    return new ConnectivityResult(true, false, detail);
  }

  /** An unreachable result (no response — network/DNS/timeout). */
  public static ConnectivityResult unreachable(String detail) {
    return new ConnectivityResult(false, false, detail);
  }
}
