package org.dradgo.domain.integration.ticketsource;

/**
 * Vendor-neutral outcome of {@code TicketSourceAdapter.postGovernedRunComment} (story 3.32, AC1).
 * Replaces the {@code void} return on the legacy {@code LinearAdapter} comment method so callers
 * can observe the idempotency-replay no-op instead of a silent void.
 *
 * <p>The governed-comment contract (story 3.16 AC3) embeds a SHA-256 fingerprint marker in the
 * comment body and scans existing comments to no-op a re-post; {@link #SKIPPED_DUPLICATE} surfaces
 * that replay, {@link #POSTED} surfaces a fresh write.
 */
public enum CommentResult {

  /** The governed comment was written to the source ticket. */
  POSTED,

  /**
   * The fingerprint marker was already present on the source ticket — the post was a no-op replay
   * (idempotency contract honored).
   */
  SKIPPED_DUPLICATE
}
