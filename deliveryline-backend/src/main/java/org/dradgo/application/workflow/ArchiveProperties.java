package org.dradgo.application.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@code deliveryline.archive.*} namespace (story 3d-8, FR67, AC7 / R5).
 *
 * <p>Binds the <strong>default-OFF</strong> opt-in flag for the optional auto-on-ticket-removal
 * scan. The required core of story 3d-8 is the manual hide / un-hide path ({@link
 * WorkflowArchiveService}); the auto path — a scheduled scan that detects a source-ticket removal
 * (an active {@code integration_links} row whose {@code TicketSourceAdapter
 * .fetchTicketByReference} now returns empty) and auto-archives the related run with a SYSTEM actor
 * — is deferred to a follow-up (Open Decision #4: the manual core ships now; the scheduler does
 * not). This record is the wiring point so the flag and its default already exist when the
 * scheduler lands; nothing consumes it yet, and it stays {@code false} unless an operator opts in.
 *
 * <p>Like {@link WorkflowProperties}, the compact constructor <strong>normalizes-with-defaults and
 * never throws</strong> so the bean binds profile-neutrally in every {@code @SpringBootTest} tier
 * even with no {@code deliveryline.archive.*} keys present (memory: {@code
 * validated-config-needs-test-yaml} — normalize-never-throw, NOT {@code @Validated}, dodges the
 * test-yaml-mirror trap). Registered via {@code @EnableConfigurationProperties} on the
 * infrastructure {@code WorkflowConfiguration} so the application layer never depends on
 * infrastructure.
 */
@ConfigurationProperties("deliveryline.archive")
public record ArchiveProperties(AutoOnTicketRemoval autoOnTicketRemoval) {

  public ArchiveProperties {
    autoOnTicketRemoval =
        autoOnTicketRemoval == null ? AutoOnTicketRemoval.disabled() : autoOnTicketRemoval;
  }

  public static ArchiveProperties defaults() {
    return new ArchiveProperties(AutoOnTicketRemoval.disabled());
  }

  /** Whether the (deferred) auto-on-ticket-removal scan is enabled. Default {@code false}. */
  public boolean autoOnTicketRemovalEnabled() {
    return autoOnTicketRemoval.enabled();
  }

  /**
   * The {@code deliveryline.archive.auto-on-ticket-removal.*} sub-tree. {@code enabled} defaults to
   * {@code false} (opt-in). The scheduler that would consume this is a documented follow-up.
   */
  public record AutoOnTicketRemoval(boolean enabled) {

    public static AutoOnTicketRemoval disabled() {
      return new AutoOnTicketRemoval(false);
    }
  }
}
