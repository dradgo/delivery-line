/**
 * Story 3.27 — `PrOutputArtifactRenderer`.
 *
 * The PR/output variant of the generalized Artifact Review Panel (story 2.17 AC3 —
 * replacing that story's "coming in Epic 3" stub). Sibling of {@link SpecArtifactRenderer}
 * / {@link ImplementationPlanArtifactRenderer}: same chrome (type `Badge`, revision
 * indicator + disabled history anchor, reserved disabled Compare control) with the
 * PR-output-specific anatomy:
 *
 *   • a TRUSTED reference panel — branch link, commit SHA (short-form + copy + commit
 *     link), PR reference + {@link PrStateBadge} + PR link, last-sync affordance. The PR
 *     reference + state come from the BACKEND-TRUTH `prLinkage` (story 3.15 / AC3); the
 *     branch/commit VALUES are runner-emitted but their URLs are built from the
 *     backend-truth `owner/repo` (parsed from `prLinkage.prReference`).
 *   • an UNTRUSTED diff region — a file-by-file accordion (collapsed by default, AC5)
 *     rendered through the sanctioned {@link SafeUnifiedDiffRenderer} primitive, visually
 *     + structurally separated from the trusted panel (AC3 metadata-spoofing boundary).
 *
 * All untrusted text (diff body, file paths, branch/commit, PR description body) routes
 * through `renderTextWithRedactions` / `SafeMarkdownRenderer` — never
 * `dangerouslySetInnerHTML` (story 2.24, AC3). Presentational + prop-driven: it takes a
 * resolved `PrOutputArtifactView` and never fetches (story 2.17 OQ-1); the only side
 * effect is the AC6 field-only structured log on the GitHub-unreachable cached path.
 */
import { useEffect, useMemo, useRef, useState } from 'react';

import { Badge } from '@/components/ui/badge';
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import {
  MetadataChrome,
  PR_DIFF_MAX_FILES,
  PR_DIFF_MAX_LINES,
  SafeMarkdownRenderer,
  SafeUnifiedDiffRenderer,
  countChangedLines,
  parseUnifiedDiff,
  renderTextWithRedactions,
  validateUrlScheme,
} from '@/lib/sanitization';

import {
  artifactTypeLabel,
  canEnableCompare,
  hasComparableRevision,
  type PrOutputArtifactView,
} from '../artifactView';
import { branchUrl, commitUrl, parsePrReference, prUrl, shortSha } from '../githubRef';
import { formatRelativeTime, formatUtcTimestamp } from '../runContextFormat';
import { PrStateBadge } from './PrStateBadge';

export interface PrOutputArtifactRendererProps {
  artifact: PrOutputArtifactView;
  /**
   * AC7 — the backend-reported allowed actions, threaded from the container through the
   * panel (NO frontend permission inference, UX-DR12). Variant-specific controls
   * enable/disable STRICTLY from this; `undefined` (the disabled-stub default) → all
   * gated controls stay the reserved disabled affordance.
   */
  actions?: readonly string[] | undefined;
  /**
   * Story 4.20 (AC9/AC10) — opens Compare Mode for this artifact (in-context overlay). Wired by
   * the panel/route; `undefined` leaves the Compare control inert even when enabled.
   */
  onCompare?: (() => void) | undefined;
}

/** A labeled inline trusted-metadata slot (uppercase mini-label + value). Mirrors the spec renderer. */
function MetaItem({
  label,
  children,
  testId,
}: {
  label: string;
  children: React.ReactNode;
  testId?: string;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2" data-testid={testId}>
      <span className="shrink-0 text-annotation uppercase tracking-wide text-text-tertiary">
        {label}
      </span>
      {children}
    </div>
  );
}

export function PrOutputArtifactRenderer({
  artifact,
  actions,
  onCompare,
}: PrOutputArtifactRendererProps) {
  const { branch, commitSha, diff, prLinkage } = artifact;

  // Parse the diff ONCE (pure). Memoize so re-renders (copy state, paging) don't re-walk it.
  const files = useMemo(() => parseUnifiedDiff(diff), [diff]);
  const totalChangedLines = useMemo(() => countChangedLines(files), [files]);

  // AC3 — repo identity comes from the BACKEND-TRUTH PR reference, never a runner value.
  const parsedRef = prLinkage != null ? parsePrReference(prLinkage.prReference) : null;
  // Prefer the backend-supplied PR URL verbatim (validated once), else derive from the
  // parsed ref. Validating in a single call lets TS narrow `ok` → `{ href }`.
  const prUrlValidation = prLinkage?.prUrl != null ? validateUrlScheme(prLinkage.prUrl) : null;
  const prLink =
    prUrlValidation?.ok === true
      ? prUrlValidation.href
      : parsedRef !== null
        ? prUrl(parsedRef)
        : undefined;

  const lastSyncedRelative =
    prLinkage?.lastSyncedAt != null ? formatRelativeTime(prLinkage.lastSyncedAt) : null;
  const lastSyncedUtc =
    prLinkage?.lastSyncedAt != null ? formatUtcTimestamp(prLinkage.lastSyncedAt) : null;
  const githubUnreachable = prLinkage?.githubReachable === false;

  // AC5 — file-count pagination. Collapsed-by-default + "Show more files (N of M)".
  const [showAllFiles, setShowAllFiles] = useState(false);
  const overFileThreshold = files.length > PR_DIFF_MAX_FILES;
  const visibleFiles =
    overFileThreshold && !showAllFiles ? files.slice(0, PR_DIFF_MAX_FILES) : files;

  // AC8 — jump-to-changed-region: focus moves between native file-header buttons. We
  // track the last-focused file index in a ref (NOT document.activeElement) so a mouse
  // click on the jump control — which momentarily steals focus to the button — still
  // advances relative to the file the user was on.
  const triggerRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const lastFocusedFileIndex = useRef<number>(-1);
  const jumpToFile = (direction: 1 | -1) => {
    const count = visibleFiles.length;
    if (count === 0) {
      return;
    }
    const base = lastFocusedFileIndex.current;
    const nextIndex =
      base === -1 ? (direction === 1 ? 0 : count - 1) : (base + direction + count) % count;
    triggerRefs.current[nextIndex]?.focus();
    lastFocusedFileIndex.current = nextIndex;
  };

  // Copy the FULL commit SHA (AC2). Guarded — jsdom/older browsers may lack the API.
  const [copied, setCopied] = useState(false);
  const copyResetRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (copyResetRef.current !== null) {
        clearTimeout(copyResetRef.current);
      }
    },
    [],
  );
  const handleCopySha = () => {
    // jsdom/older browsers may lack the Clipboard API — assert it possibly-undefined (the
    // DOM lib types it non-null) so the runtime guard is kept. Only flip to "copied" once the
    // write actually resolves, and reset after a moment — never claim success on a missing API
    // or a rejected write.
    const clipboard = navigator.clipboard as Clipboard | undefined;
    if (clipboard === undefined) {
      return;
    }
    void clipboard.writeText(commitSha).then(
      () => {
        setCopied(true);
        if (copyResetRef.current !== null) {
          clearTimeout(copyResetRef.current);
        }
        copyResetRef.current = setTimeout(() => setCopied(false), 2000);
      },
      () => {
        // Write rejected (e.g. permission denied) — leave the label as "copy".
      },
    );
  };

  // AC6 / logging — field-only structured log on the GitHub-unreachable cached path. NEVER
  // the diff body, PR token, or any payload bytes (T8): only the stable prState + a duration.
  useEffect(() => {
    if (!githubUnreachable) {
      return;
    }
    // `githubUnreachable` being true narrows `prLinkage` to non-null here (it is
    // `prLinkage?.githubReachable === false`).
    const staleForMs =
      prLinkage.lastSyncedAt != null
        ? Math.max(0, Date.now() - Date.parse(prLinkage.lastSyncedAt))
        : undefined;
    console.warn({
      event: 'prOutput.githubUnreachable',
      prState: prLinkage.prState,
      staleForMs: staleForMs !== undefined && Number.isNaN(staleForMs) ? undefined : staleForMs,
    });
  }, [githubUnreachable, prLinkage?.prState, prLinkage?.lastSyncedAt]);

  // AC7 / Story 4.20 (AC9) — a variant-specific control gated STRICTLY on backend-reported
  // actions (parallel to the spec/impl-plan Compare control). Renamed from the story-2.17
  // anticipated `'compare'` to the registered backend action `'enter_compare_mode'`. Uses the
  // SAME `canEnableCompare(actions, hasComparableRevision(artifact))` composition the container
  // applies to spec/plan — the backend action is surfaced broadly at a review state, so the
  // per-artifact `version > 1` predicate is what keeps a v1 prOutput (no prior revision) from
  // offering a compare with no baseline. `undefined` actions → reserved disabled.
  const compareEnabled = canEnableCompare(actions, hasComparableRevision(artifact));

  return (
    <div className="w-full" data-testid="pr-output-artifact-renderer" data-artifact-type="prOutput">
      {/* Revision + type chrome — surfaced near the TOP, visually secondary. */}
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <Badge variant="secondary" data-testid="artifact-type-badge">
          {artifactTypeLabel(artifact.artifactType)}
        </Badge>
        <span
          className="inline-flex items-center gap-1 text-sm text-text-secondary"
          data-testid="artifact-revision"
        >
          <span className="font-medium text-text-primary">v{artifact.version}</span>
          <button
            type="button"
            aria-disabled="true"
            title="Revision history — available in a later release"
            className="rounded-sm text-annotation uppercase tracking-wide text-text-tertiary"
            data-testid="artifact-revision-history-anchor"
          >
            history
          </button>
        </span>
      </div>

      {/* TRUSTED reference panel (AC2 / AC3) — backend-truth PR ref/state are authoritative
          and visually separated from the untrusted diff region below. */}
      <section
        aria-label="Pull request references"
        data-region="trusted-references"
        data-testid="pr-reference-panel"
        className="rounded-md border border-border bg-surface-elevated/40 p-3"
      >
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <span className="text-section-heading">References</span>
          <Badge variant="outline" className="text-annotation uppercase tracking-wide">
            verified by DeliveryLine
          </Badge>
        </div>

        <div className="flex flex-col gap-2">
          {/* Branch — runner-emitted value; URL built from backend-truth owner/repo. */}
          <MetaItem label="Branch" testId="pr-branch">
            {parsedRef !== null ? (
              <a
                href={branchUrl(parsedRef.owner, parsedRef.repo, branch)}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-sm text-sm text-text-secondary underline underline-offset-2 hover:text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                data-testid="pr-branch-link"
              >
                <code>{branch}</code>
              </a>
            ) : (
              <code className="text-sm text-text-secondary" data-testid="pr-branch-text">
                {branch}
              </code>
            )}
          </MetaItem>

          {/* Commit — short-form + copy-full-SHA button + commit link. */}
          <MetaItem label="Commit" testId="pr-commit">
            {parsedRef !== null ? (
              <a
                href={commitUrl(parsedRef.owner, parsedRef.repo, commitSha)}
                target="_blank"
                rel="noopener noreferrer"
                className="rounded-sm text-sm text-text-secondary underline underline-offset-2 hover:text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                data-testid="pr-commit-link"
                title={commitSha}
              >
                <code>{shortSha(commitSha)}</code>
              </a>
            ) : (
              <code
                className="text-sm text-text-secondary"
                data-testid="pr-commit-text"
                title={commitSha}
              >
                {shortSha(commitSha)}
              </code>
            )}
            <button
              type="button"
              onClick={handleCopySha}
              className="rounded-sm border border-border px-1.5 py-0.5 text-annotation uppercase tracking-wide text-text-tertiary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
              data-testid="pr-commit-copy"
              aria-label="Copy full commit SHA"
            >
              {copied ? 'copied' : 'copy'}
            </button>
          </MetaItem>

          {/* PR reference + state badge — the AC3 trusted authority. */}
          <MetaItem label="Pull request" testId="pr-reference">
            {prLinkage != null ? (
              <>
                {prLink !== undefined ? (
                  <a
                    href={prLink}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="rounded-sm text-sm font-medium text-text-primary underline underline-offset-2 hover:text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                    data-testid="pr-reference-link"
                  >
                    {prLinkage.prReference}
                  </a>
                ) : (
                  <span className="text-sm font-medium text-text-primary">
                    {prLinkage.prReference}
                  </span>
                )}
                <PrStateBadge state={prLinkage.prState} />
              </>
            ) : (
              <span className="text-sm text-text-tertiary" data-testid="pr-reference-unlinked">
                No linked pull request
              </span>
            )}
          </MetaItem>

          {/* Last-sync affordance + AC6 GitHub-unreachable cached-state notice. */}
          {prLinkage?.lastSyncedAt != null ? (
            <p
              className="text-meta text-text-tertiary"
              data-testid="pr-last-sync"
              title={lastSyncedUtc ?? undefined}
            >
              {githubUnreachable ? (
                <span data-testid="pr-github-unreachable">
                  GitHub unreachable — showing cached state{' '}
                </span>
              ) : null}
              (last synced {lastSyncedRelative ?? lastSyncedUtc ?? prLinkage.lastSyncedAt})
            </p>
          ) : null}
        </div>
      </section>

      {/* UNTRUSTED PR description body — via the sanctioned chrome (T2). */}
      <div className="mt-4">
        <MetadataChrome
          title={artifact.title}
          version={artifact.version}
          classification={artifact.classification}
        >
          <SafeMarkdownRenderer source={artifact.body} className="prose" />
        </MetadataChrome>
      </div>

      {/* UNTRUSTED diff region — structurally + visually separated from the trusted panel (AC3). */}
      <section
        aria-label="Changed files"
        data-region="untrusted-diff"
        data-testid="pr-diff"
        className="mt-4 rounded-md border border-dashed border-border p-3"
      >
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <h3 className="text-section-heading">Changed files</h3>
          <span className="text-annotation uppercase tracking-wide text-text-tertiary">
            from the agent’s output (untrusted)
          </span>
        </div>

        {files.length === 0 ? (
          <p className="text-meta text-text-tertiary" data-testid="pr-diff-empty">
            No diff content was produced.
          </p>
        ) : (
          <>
            {/* AC8 — jump-to-changed-region controls (keyboard-reachable; move focus between
                native file-header buttons next/previous). */}
            <div
              className="mb-2 flex flex-wrap items-center gap-2"
              data-testid="pr-diff-jump-controls"
            >
              <button
                type="button"
                onClick={() => jumpToFile(-1)}
                className="rounded-md border border-border px-2 py-0.5 text-xs text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                data-testid="pr-diff-jump-prev"
              >
                Previous changed file
              </button>
              <button
                type="button"
                onClick={() => jumpToFile(1)}
                className="rounded-md border border-border px-2 py-0.5 text-xs text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                data-testid="pr-diff-jump-next"
              >
                Next changed file
              </button>
              <span className="text-meta text-text-tertiary" data-testid="pr-diff-file-count">
                {overFileThreshold && !showAllFiles
                  ? `Showing ${visibleFiles.length} of ${files.length} files`
                  : `${files.length} ${files.length === 1 ? 'file' : 'files'} changed`}
              </span>
            </div>

            {/* File accordion — collapsed by default (no defaultValue); native-button headers
                (Radix Trigger) give Enter/Space toggle + arrow-key navigation (AC8). */}
            <Accordion type="multiple">
              {visibleFiles.map((file, index) => (
                <AccordionItem
                  key={`${file.path}-${index}`}
                  value={`file-${index}`}
                  data-testid={`pr-diff-file-${index}`}
                >
                  <AccordionTrigger
                    ref={(node) => {
                      triggerRefs.current[index] = node;
                    }}
                    onFocus={() => {
                      lastFocusedFileIndex.current = index;
                    }}
                    data-testid={`pr-diff-file-trigger-${index}`}
                  >
                    <span className="flex min-w-0 flex-1 items-center justify-between gap-3">
                      {/* File path is UNTRUSTED → through the sanctioned redaction path (AC3). */}
                      <code className="min-w-0 truncate text-text-primary">
                        {renderTextWithRedactions(file.path)}
                      </code>
                      <span
                        className="shrink-0 text-meta tabular-nums"
                        data-testid={`pr-diff-file-stats-${index}`}
                      >
                        <span className="text-state-success-foreground">+{file.additions}</span>{' '}
                        <span className="text-state-error-foreground">−{file.deletions}</span>
                      </span>
                    </span>
                  </AccordionTrigger>
                  <AccordionContent data-testid={`pr-diff-file-content-${index}`}>
                    {file.binary === true ? (
                      <p
                        className="text-meta text-text-tertiary"
                        data-testid={`pr-diff-file-binary-${index}`}
                      >
                        Binary file — no textual diff shown.
                      </p>
                    ) : (
                      <SafeUnifiedDiffRenderer
                        hunks={file.hunks}
                        maxLines={PR_DIFF_MAX_LINES}
                        aria-label={`Diff for ${file.path}`}
                      />
                    )}
                  </AccordionContent>
                </AccordionItem>
              ))}
            </Accordion>

            {/* AC5 — file-count pagination control; no silent truncation (always surfaces counts). */}
            {overFileThreshold && !showAllFiles ? (
              <button
                type="button"
                onClick={() => setShowAllFiles(true)}
                className="mt-2 rounded-md border border-border px-2.5 py-1 text-sm font-medium text-text-primary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                data-testid="pr-diff-show-more"
              >
                Show more files ({visibleFiles.length} of {files.length})
              </button>
            ) : null}
            {totalChangedLines > PR_DIFF_MAX_LINES ? (
              <p className="mt-2 text-meta text-text-tertiary" data-testid="pr-diff-large-note">
                This is a large diff ({totalChangedLines} changed lines). Each file’s content is
                capped when expanded.
              </p>
            ) : null}
          </>
        )}
      </section>

      {/* Compare control (AC7 / story 2.17 AC9 / story 4.20 AC9) — enabled/disabled PURELY from the
          container-supplied backend actions; the renderer never infers permissions. When enabled,
          onClick opens Compare Mode as an in-context overlay (AC8) via the panel-supplied handler. */}
      <div className="mt-4 flex flex-wrap items-center gap-2" data-testid="artifact-region-anchors">
        <button
          type="button"
          disabled={!compareEnabled}
          onClick={onCompare}
          title={
            compareEnabled ? 'Compare with the previous revision' : 'Available in next release'
          }
          className="rounded-md border border-border px-2.5 py-1 text-sm text-text-secondary disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid="artifact-compare-entry"
        >
          Compare
        </button>
      </div>
    </div>
  );
}
