package org.dradgo.application.workflow;

/**
 * Story 3f-4 — the read model GET /split-proposal serves and the three actions return: the current
 * {@code state}, the proposal itself (null unless {@code available}), and the run's re-propose
 * {@code loopCount}. A read-view record (REST maps it directly), so it lives in {@code
 * application.workflow}, not {@code .spi}.
 *
 * <p>State values: {@code none} (no proposal, nothing in flight), {@code pending} (a split-mode
 * reviewer dispatch is in flight, no proposal harvested yet), {@code available} (an open proposal
 * exists), {@code unavailable} (the reviewer model is unbound — generation degrades, the gate is
 * never blocked, R5).
 */
public record SplitProposalStatusView(String state, SplitProposalView proposal, int loopCount) {

  public static final String STATE_NONE = "none";
  public static final String STATE_PENDING = "pending";
  public static final String STATE_AVAILABLE = "available";
  public static final String STATE_UNAVAILABLE = "unavailable";

  public static SplitProposalStatusView none(int loopCount) {
    return new SplitProposalStatusView(STATE_NONE, null, loopCount);
  }

  public static SplitProposalStatusView pending(int loopCount) {
    return new SplitProposalStatusView(STATE_PENDING, null, loopCount);
  }

  public static SplitProposalStatusView available(SplitProposalView proposal) {
    return new SplitProposalStatusView(STATE_AVAILABLE, proposal, proposal.loopCount());
  }

  public static SplitProposalStatusView unavailable(int loopCount) {
    return new SplitProposalStatusView(STATE_UNAVAILABLE, null, loopCount);
  }
}
