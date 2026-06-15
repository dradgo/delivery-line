/**
 * Story 3.31 (Task 6 / AC1, AC8, AC10) — reusable `PrLinkageView` fixtures for the
 * `RunReviewQueueItem` (and the shared `PrLinkageDetails`) tests. The live
 * `WorkflowSummary` carries no PR projection, so `toRunQueueRow` always leaves
 * `prLinkage` undefined — the queue PR element is exercised by attaching these
 * constructed views directly to a `RunQueueRow` (the dormant fixture pattern).
 */
import type { PrLinkageView, PrState } from '@/features/workflows/prLinkageView';

const PR_REF = 'acme/widgets#42';
const PR_URL = 'https://github.com/acme/widgets/pull/42';

/** A fresh, reachable PR linkage (open state). */
export const openPrLinkage: PrLinkageView = {
  prReference: PR_REF,
  prState: 'open',
  prUrl: PR_URL,
  branch: 'deliveryline/DEL-9002',
  commitSha: 'a3f29110d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9',
  lastSyncedAt: '2026-05-30T12:03:00Z',
  githubReachable: true,
};

/** All four lifecycle states, keyed for the badge coverage test. */
export const prLinkageByState: Record<PrState, PrLinkageView> = {
  draft: { ...openPrLinkage, prState: 'draft' },
  open: { ...openPrLinkage, prState: 'open' },
  merged: { ...openPrLinkage, prState: 'merged' },
  closed: { ...openPrLinkage, prState: 'closed' },
};
