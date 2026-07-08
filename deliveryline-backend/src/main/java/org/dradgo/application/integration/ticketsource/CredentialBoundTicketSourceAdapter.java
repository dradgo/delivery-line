package org.dradgo.application.integration.ticketsource;

/**
 * Optional extension for ticket-source adapters that can be bound to a project-scoped credential at
 * resolver time without changing the vendor-neutral TicketSourceAdapter method signatures.
 */
public interface CredentialBoundTicketSourceAdapter extends TicketSourceAdapter {

  /**
   * Return an adapter view that authenticates operations with the supplied project-scoped secret.
   * Implementations must not retain or log the secret beyond the returned adapter instance.
   */
  TicketSourceAdapter withCredentialOverride(String credentialOverride);

  default TicketSourceAdapter withProjectCredential(
      String projectPublicId, String credentialOverride) {
    return withCredentialOverride(credentialOverride);
  }
}
