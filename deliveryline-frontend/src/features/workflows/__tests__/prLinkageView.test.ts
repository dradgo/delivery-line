/**
 * Story 3.31 (Task 7 / AC2, AC4, AC6, AC8) — pure unit tests for the frontend-owned
 * PR-linkage read model: the `isPrLinkageStale` boundary, the `toPrLinkageView`
 * projection (incl. the trust-boundary + graceful-absence rules), and the DORMANT
 * mapper behaviour (no `integrationLinks` ⇒ `prLinkage` undefined; populated ⇒ projected).
 */
import { describe, expect, it } from 'vitest';

import type { WorkflowSummary } from '@/lib/api/queryOptions';
import {
  PR_LINKAGE_STALE_THRESHOLD_MS,
  isPrLinkageStale,
  toPrLinkageView,
  type GitHubPrLinkWire,
} from '../prLinkageView';
import { toRunContextView } from '../runContextView';
import { toRunQueueRow } from '../runQueueRow';
import {
  prLinkageDisplayDetail,
  prLinkageDisplayGitHubUnreachableDetail,
  prLinkageDisplayNoLinkageDetail,
} from '@/test/fixtures/runContext/prLinkageDisplay';
import { specRejectAndResubmitDetail } from '@/test/fixtures/runContext/specRejectAndResubmit';

const NOW = Date.parse('2026-05-30T12:05:00Z');

describe('PR_LINKAGE_STALE_THRESHOLD_MS', () => {
  it('is 5 minutes — a separate concept from the 10-minute run-activity window', () => {
    expect(PR_LINKAGE_STALE_THRESHOLD_MS).toBe(5 * 60 * 1000);
  });
});

describe('isPrLinkageStale', () => {
  it('returns false for an absent or unparseable timestamp', () => {
    expect(isPrLinkageStale(undefined, NOW)).toBe(false);
    expect(isPrLinkageStale('not-a-date', NOW)).toBe(false);
  });

  it('is false AT the threshold boundary and true just past it', () => {
    const atBoundary = new Date(NOW - PR_LINKAGE_STALE_THRESHOLD_MS).toISOString();
    const pastBoundary = new Date(NOW - PR_LINKAGE_STALE_THRESHOLD_MS - 1).toISOString();
    expect(isPrLinkageStale(atBoundary, NOW)).toBe(false);
    expect(isPrLinkageStale(pastBoundary, NOW)).toBe(true);
  });

  it('treats a recent sync as fresh', () => {
    expect(isPrLinkageStale('2026-05-30T12:03:00Z', NOW)).toBe(false);
  });
});

describe('toPrLinkageView', () => {
  const validLink: GitHubPrLinkWire = {
    type: 'github_pr',
    externalRef: 'acme/widgets#42',
    externalMetadata: {
      prState: 'open',
      prUrl: 'https://github.com/acme/widgets/pull/42',
      branch: 'feature/x',
      commitSha: 'a3f29110abc',
      githubReachable: true,
    },
    lastSyncAt: '2026-05-30T12:03:00Z',
  };

  it('returns undefined for null / empty / no github_pr row', () => {
    expect(toPrLinkageView(null)).toBeUndefined();
    expect(toPrLinkageView(undefined)).toBeUndefined();
    expect(toPrLinkageView([])).toBeUndefined();
  });

  it('projects the backend-truth + runner-emitted fields of the first github_pr row', () => {
    const view = toPrLinkageView([validLink]);
    expect(view).toEqual({
      prReference: 'acme/widgets#42',
      prState: 'open',
      prUrl: 'https://github.com/acme/widgets/pull/42',
      branch: 'feature/x',
      commitSha: 'a3f29110abc',
      lastSyncedAt: '2026-05-30T12:03:00Z',
      githubReachable: undefined,
    });
  });

  it('marks githubReachable=false through (cached-state affordance), else undefined', () => {
    const unreachable = toPrLinkageView([
      { ...validLink, externalMetadata: { ...validLink.externalMetadata, githubReachable: false } },
    ]);
    expect(unreachable?.githubReachable).toBe(false);
  });

  it('coalesces null wire fields to undefined (JSON null guard)', () => {
    const view = toPrLinkageView([
      {
        type: 'github_pr',
        externalRef: 'acme/widgets#7',
        externalMetadata: { prState: 'draft', prUrl: null, branch: null, commitSha: null },
        lastSyncAt: null,
      },
    ]);
    expect(view).toMatchObject({ prReference: 'acme/widgets#7', prState: 'draft' });
    expect(view?.prUrl).toBeUndefined();
    expect(view?.branch).toBeUndefined();
    expect(view?.commitSha).toBeUndefined();
    expect(view?.lastSyncedAt).toBeUndefined();
  });

  it('returns undefined when a required backend-truth field is missing/invalid (graceful absence)', () => {
    // Missing prState.
    expect(
      toPrLinkageView([{ type: 'github_pr', externalRef: 'a/b#1', externalMetadata: {} }]),
    ).toBeUndefined();
    // Unrecognized prState.
    expect(
      toPrLinkageView([
        {
          type: 'github_pr',
          externalRef: 'a/b#1',
          externalMetadata: { prState: 'reopened' as never },
        },
      ]),
    ).toBeUndefined();
    // Blank externalRef.
    expect(
      toPrLinkageView([
        { type: 'github_pr', externalRef: '  ', externalMetadata: { prState: 'open' } },
      ]),
    ).toBeUndefined();
  });
});

describe('dormant mapper behaviour (AC6 — no live source today)', () => {
  it('toRunContextView leaves prLinkage undefined when the wire has no integrationLinks', () => {
    expect(toRunContextView(specRejectAndResubmitDetail, NOW).prLinkage).toBeUndefined();
    expect(toRunContextView(prLinkageDisplayNoLinkageDetail, NOW).prLinkage).toBeUndefined();
  });

  it('toRunContextView projects prLinkage when a fixture injects the future projection', () => {
    const view = toRunContextView(prLinkageDisplayDetail, NOW);
    expect(view.prLinkage).toMatchObject({
      prReference: 'acme/widgets#42',
      prState: 'open',
      branch: 'deliveryline/DEL-9002',
    });
  });

  it('toRunContextView carries the GitHub-unreachable flag through to the view', () => {
    expect(
      toRunContextView(prLinkageDisplayGitHubUnreachableDetail, NOW).prLinkage?.githubReachable,
    ).toBe(false);
  });

  it('toRunQueueRow leaves prLinkage undefined (the list summary has no PR projection)', () => {
    const summary: WorkflowSummary = {
      workflowRunId: 'run_abc123',
      currentState: 'Completed',
      lastEventAt: '2026-05-30T12:00:00Z',
    };
    expect(toRunQueueRow(summary).prLinkage).toBeUndefined();
  });
});
