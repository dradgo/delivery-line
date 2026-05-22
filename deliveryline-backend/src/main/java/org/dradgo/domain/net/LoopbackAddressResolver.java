package org.dradgo.domain.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Framework-free resolution of whether a configured bind address points at the loopback interface.
 *
 * <p>Single source of truth for the loopback check used by two callers that live in mutually
 * non-dependent layers (ArchUnit forbids {@code adapters} ⇄ {@code infrastructure} edges, so the
 * shared logic cannot live in either): the doctor REST-bind probe ({@code
 * adapters.diagnostics.DoctorProbeAdapter}, story 1.16) and the startup fail-closed bind guard
 * ({@code infrastructure.config.RestBindingGuard}, story 6.9). Both depend on {@code domain}, the
 * only layer both may legally access.
 *
 * <p>Pure JDK ({@link InetAddress}) — no Spring, no servlet, no persistence — so it satisfies the
 * domain "framework-free" ArchUnit invariant.
 */
public final class LoopbackAddressResolver {

  private LoopbackAddressResolver() {
    throw new AssertionError("no instances");
  }

  /**
   * Resolve {@code address} and report whether it maps to a loopback interface.
   *
   * @param address the configured bind address (host name or literal IP); {@code null}/blank
   *     resolves to the local loopback per {@link InetAddress#getByName(String)} semantics
   * @return a {@link Resolution} describing resolvability + loopback status
   */
  public static Resolution resolve(String address) {
    try {
      InetAddress resolved = InetAddress.getByName(address);
      return new Resolution(true, resolved.isLoopbackAddress(), resolved.getHostAddress());
    } catch (UnknownHostException unknown) {
      return new Resolution(false, false, null);
    }
  }

  /**
   * Outcome of a bind-address resolution.
   *
   * @param resolvable whether the address resolved at all (false ⇒ {@link UnknownHostException})
   * @param loopback whether the resolved address is a loopback address (only meaningful when {@code
   *     resolvable})
   * @param resolvedHostAddress the numeric host address the name resolved to, or {@code null} when
   *     unresolvable
   */
  public record Resolution(boolean resolvable, boolean loopback, String resolvedHostAddress) {

    /** True only when the address resolved AND is loopback — the safe-to-bind condition. */
    public boolean isLoopbackBindSafe() {
      return resolvable && loopback;
    }
  }
}
