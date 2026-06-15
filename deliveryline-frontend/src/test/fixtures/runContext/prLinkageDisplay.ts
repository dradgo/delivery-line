/**
 * Story 3.31 (Task 6 / AC2, AC4, AC5, AC8) — Run-Context fixtures carrying the
 * intended-future `github_pr` `integration_links` projection so `toRunContextView`
 * produces a populated `prLinkage`. These are `WorkflowDetailWithLinkage` (the
 * frontend-owned dormant extension) — the live wire has no `integrationLinks` field;
 * the feature is exercised ENTIRELY through these constructed fixtures (Dev Notes
 * "Central reconciliation").
 *
 * Timestamps are aligned to the strip test's pinned `NOW = 2026-05-30T12:05:00Z`:
 *   • `…Detail` syncs at 12:03Z (2 min old < 5 min PR_LINKAGE_STALE_THRESHOLD_MS → fresh)
 *   • `…StaleGitHub` syncs at 11:55Z (10 min old > threshold → the stale affordance)
 */
import type { WorkflowDetailWithLinkage } from '@/features/workflows/runContextView';
import { specRejectAndResubmitDetail } from './specRejectAndResubmit';

const BRANCH = 'deliveryline/DEL-9002';
const COMMIT_SHA = 'a3f29110d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9';
const PR_REF = 'acme/widgets#42';

/** AC2 — a fresh, reachable PR linkage (open state). */
export const prLinkageDisplayDetail: WorkflowDetailWithLinkage = {
  ...specRejectAndResubmitDetail,
  integrationLinks: [
    {
      type: 'github_pr',
      externalRef: PR_REF,
      externalMetadata: {
        prState: 'open',
        prUrl: 'https://github.com/acme/widgets/pull/42',
        branch: BRANCH,
        commitSha: COMMIT_SHA,
        githubReachable: true,
      },
      lastSyncAt: '2026-05-30T12:03:00Z',
    },
  ],
};

/** AC4 — the cached state is stale (last synced past PR_LINKAGE_STALE_THRESHOLD_MS). */
export const prLinkageDisplayStaleGitHubDetail: WorkflowDetailWithLinkage = {
  ...specRejectAndResubmitDetail,
  integrationLinks: [
    {
      type: 'github_pr',
      externalRef: PR_REF,
      externalMetadata: {
        prState: 'merged',
        prUrl: 'https://github.com/acme/widgets/pull/42',
        branch: BRANCH,
        commitSha: COMMIT_SHA,
        githubReachable: true,
      },
      lastSyncAt: '2026-05-30T11:55:00Z',
    },
  ],
};

/** AC5 — GitHub unreachable on the last reconcile: the cached state still renders. */
export const prLinkageDisplayGitHubUnreachableDetail: WorkflowDetailWithLinkage = {
  ...specRejectAndResubmitDetail,
  integrationLinks: [
    {
      type: 'github_pr',
      externalRef: PR_REF,
      externalMetadata: {
        prState: 'open',
        prUrl: 'https://github.com/acme/widgets/pull/42',
        branch: BRANCH,
        commitSha: COMMIT_SHA,
        githubReachable: false,
      },
      lastSyncAt: '2026-05-30T11:55:00Z',
    },
  ],
};

/** AC8 — no GitHub linkage: `prLinkage` projects to `undefined` (cluster omitted entirely). */
export const prLinkageDisplayNoLinkageDetail: WorkflowDetailWithLinkage = {
  ...specRejectAndResubmitDetail,
};
