/**
 * Story 3.31 (Task 3 / AC1, AC2, AC3, AC7, AC9) — the SHARED PR-linkage display.
 *
 * One presentational component consumed by BOTH PR-linkage surfaces (the
 * `RunContextStrip` and the `RunReviewQueueItem`) so the PR reference + state badge +
 * stale affordance get IDENTICAL visual treatment on both — AC7 forbids surface-specific
 * styling drift. Composes the shared `PrStateBadge` + `githubRef` helpers (story 3.27).
 *
 * Trust boundary (story 3.27 AC3 / 3.31 AC6): `prReference` + `prState` + `prUrl` are
 * backend-truth; `branch` + `commitSha` are runner-emitted (UNTRUSTED) and rendered as
 * escaped text, with their GitHub links built from the owner/repo parsed out of the
 * TRUSTED `prReference` — never inferred from the runner-emitted branch/commit strings.
 *
 * Variants:
 *  • `strip` — the full cluster: branch + short-commit (full-SHA tooltip) GitHub links,
 *    then the PR ref + badge + stale affordance, inside the height-capped strip row (AC2/AC3).
 *  • `queue` — the compact element: PR ref + badge + stale affordance only (no branch/commit,
 *    AC1). The PR `<a>` carries the stretched-link escape (`relative z-[2]` + stop-propagation)
 *    so it is independently clickable inside story 2.15's whole-row open-run overlay (D1).
 */
import type { MouseEvent } from 'react';

import { cn } from '@/lib/utils';

import { formatRelativeTime, formatUtcTimestamp } from '../runContextFormat';
import { isPrLinkageStale, type PrLinkageView } from '../prLinkageView';
import {
  branchUrl,
  commitUrl,
  isGitHubHttpsUrl,
  parsePrReference,
  prUrl,
  shortSha,
} from '../githubRef';
import { PrStateBadge } from './PrStateBadge';

export interface PrLinkageDetailsProps {
  readonly prLinkage: PrLinkageView;
  /** Pinned "now" for deterministic stale/relative-time computation (injected by the host). */
  readonly nowMs: number;
  /** `strip` shows branch/commit; `queue` is the compact stretched-link-safe element. */
  readonly variant: 'strip' | 'queue';
}

/** The AC9 accessible name for the PR link — full canonical reference + state. */
function prAriaLabel(prLinkage: PrLinkageView): string {
  const parsed = parsePrReference(prLinkage.prReference);
  if (parsed !== null) {
    return `Pull request ${parsed.number} in ${parsed.owner}/${parsed.repo}, status ${prLinkage.prState}`;
  }
  return `Pull request ${prLinkage.prReference}, status ${prLinkage.prState}`;
}

/** AC4/AC5 — the "(stale, last synced X ago)" affordance text. */
function staleAffordanceText(lastSyncedAt: string | undefined, nowMs: number): string {
  const relative = formatRelativeTime(lastSyncedAt, nowMs);
  return relative !== null ? `(stale, last synced ${relative})` : '(stale)';
}

export function PrLinkageDetails({ prLinkage, nowMs, variant }: PrLinkageDetailsProps) {
  const parsed = parsePrReference(prLinkage.prReference);
  // Prefer the backend-truth verbatim PR URL, but ONLY when it is a safe
  // `https://github.com/…` URL — a `javascript:` / off-GitHub value (a future
  // compromised live wire) must never reach `<a href>`; else derive from the
  // parsed (trusted) ref. [Review][Patch] AC6 defense-in-depth.
  const verbatimHref =
    prLinkage.prUrl !== undefined && isGitHubHttpsUrl(prLinkage.prUrl)
      ? prLinkage.prUrl
      : undefined;
  const href = verbatimHref ?? (parsed !== null ? prUrl(parsed) : undefined);
  // AC4/AC5 — stale when the cached sync is old OR GitHub was unreachable (cached-state path).
  const stale =
    isPrLinkageStale(prLinkage.lastSyncedAt, nowMs) || prLinkage.githubReachable === false;

  // Queue stretched-link escape (D1): rise above story 2.15's inset-0 open-run overlay and
  // stop the click from bubbling to it, so the PR link opens GitHub (not the run).
  const isQueue = variant === 'queue';
  const linkEscape = isQueue ? 'relative z-[2]' : undefined;
  const onLinkClick = isQueue
    ? (event: MouseEvent<HTMLAnchorElement>) => event.stopPropagation()
    : undefined;
  // AC2 — relative last-sync for the strip's fresh (non-stale) display.
  const freshSync = formatRelativeTime(prLinkage.lastSyncedAt, nowMs);

  return (
    <span
      className="inline-flex min-w-0 flex-wrap items-center gap-1.5"
      data-testid="pr-linkage"
      data-pr-variant={variant}
    >
      {variant === 'strip' && prLinkage.branch !== undefined ? (
        parsed !== null ? (
          <a
            href={branchUrl(parsed.owner, parsed.repo, prLinkage.branch)}
            target="_blank"
            rel="noopener noreferrer"
            className="max-w-40 truncate text-meta text-text-secondary underline-offset-2 hover:underline"
            data-testid="pr-linkage-branch"
            title={prLinkage.branch}
          >
            <code>{prLinkage.branch}</code>
          </a>
        ) : (
          <code className="text-meta text-text-secondary" data-testid="pr-linkage-branch">
            {prLinkage.branch}
          </code>
        )
      ) : null}

      {variant === 'strip' && prLinkage.commitSha !== undefined ? (
        parsed !== null ? (
          <a
            href={commitUrl(parsed.owner, parsed.repo, prLinkage.commitSha)}
            target="_blank"
            rel="noopener noreferrer"
            className="text-meta text-text-secondary underline-offset-2 hover:underline"
            data-testid="pr-linkage-commit"
            title={prLinkage.commitSha}
          >
            <code>{shortSha(prLinkage.commitSha)}</code>
          </a>
        ) : (
          <code
            className="text-meta text-text-secondary"
            data-testid="pr-linkage-commit"
            title={prLinkage.commitSha}
          >
            {shortSha(prLinkage.commitSha)}
          </code>
        )
      ) : null}

      {href !== undefined ? (
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={prAriaLabel(prLinkage)}
          className={cn(
            'text-sm text-text-secondary underline-offset-2 hover:underline',
            linkEscape,
          )}
          data-testid="pr-linkage-link"
          onClick={onLinkClick}
        >
          PR {prLinkage.prReference}
        </a>
      ) : (
        <span className="text-sm text-text-secondary" data-testid="pr-linkage-ref">
          PR {prLinkage.prReference}
        </span>
      )}

      <PrStateBadge state={prLinkage.prState} />

      {stale ? (
        <span className="text-meta text-text-tertiary" data-testid="pr-linkage-stale">
          {staleAffordanceText(prLinkage.lastSyncedAt, nowMs)}
        </span>
      ) : variant === 'strip' && freshSync !== null ? (
        // AC2 — the last-sync timestamp (strip only; the queue stays compact per AC1).
        <time
          className="text-meta text-text-tertiary"
          dateTime={prLinkage.lastSyncedAt}
          title={formatUtcTimestamp(prLinkage.lastSyncedAt) ?? undefined}
          data-testid="pr-linkage-last-sync"
        >
          synced {freshSync}
        </time>
      ) : null}
    </span>
  );
}
