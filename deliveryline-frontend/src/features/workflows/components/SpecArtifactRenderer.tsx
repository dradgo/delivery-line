/**
 * Story 2.17 (AC2, AC5, AC7, AC8) — `SpecArtifactRenderer`.
 *
 * The Epic-2-scope spec variant: the only FULLY rendered artifact variant today.
 * Renders the spec anatomy by composing the story-2.24 sanitization primitives
 * imported from the `@/lib/sanitization` BARREL (T1 — never reach into individual
 * files; the `no-unsanitized-html` rule + `--max-warnings=0` forbid it):
 *
 *   <MetadataChrome title version classification subtitle?>   ← trusted typed metadata
 *     <SafeMarkdownRenderer source={body} className="prose" /> ← UNTRUSTED body (AC7)
 *   </MetadataChrome>
 *
 * Around that it renders: an artifact-type `<Badge>` (NOT `WorkflowStateBadge`, T5),
 * a revision indicator (`v{version}` + a placeholder anchor to deferred revision
 * history), an inline trusted-metadata region (created-at, classification, checksum
 * short-form — typed React props, never markdown), an optional change-summary slot
 * (`<SafeDiffRenderer>` only when `changeSummary` is non-null), a keyboard-navigable
 * section-anchor nav derived from the raw markdown (T-ANCHOR), and the
 * clarification / approval / compare entry-points (OQ-4 placeholders; Compare is a
 * reserved disabled control — AC9).
 *
 * Presentational + prop-driven — it takes a resolved `SpecArtifactView` and never
 * fetches. The panel (story 2.17 Task 4) owns dispatch + state + the data seam.
 */
import { useRef, type KeyboardEvent } from 'react';

import { Badge } from '@/components/ui/badge';
import { MetadataChrome, SafeDiffRenderer, SafeMarkdownRenderer } from '@/lib/sanitization';

import { artifactTypeLabel, deriveSectionAnchors, type SpecArtifactView } from '../artifactView';
import { formatRelativeTime, formatUtcTimestamp } from '../runContextFormat';

export interface SpecArtifactRendererProps {
  artifact: SpecArtifactView;
  /**
   * AC9 — whether the Compare-Mode entry control is enabled. The panel derives this
   * from the backend-reported allowed actions; it is ALWAYS `false` in Epic 2
   * (Compare Mode is Epic 4), so the control stays a reserved disabled affordance.
   */
  compareEnabled?: boolean;
}

/** Short-form a checksum for inline display, keeping the full value in the `title`. */
function shortChecksum(checksum: string): string {
  return checksum.length > 20 ? `${checksum.slice(0, 20)}…` : checksum;
}

/** A labeled inline trusted-metadata slot (uppercase mini-label + value). */
function MetaItem({
  label,
  children,
  testId,
  title,
}: {
  label: string;
  children: React.ReactNode;
  testId?: string;
  title?: string | undefined;
}) {
  return (
    <span className="inline-flex min-w-0 items-center gap-1" data-testid={testId} title={title}>
      <span className="shrink-0 text-annotation uppercase tracking-wide text-text-tertiary">
        {label}
      </span>
      <span className="min-w-0 truncate text-sm text-text-secondary">{children}</span>
    </span>
  );
}

export function SpecArtifactRenderer({
  artifact,
  compareEnabled = false,
}: SpecArtifactRendererProps) {
  // T-ANCHOR — best-effort scroll target: the sanitizer strips heading ids, so we
  // match the rendered heading TEXT within this body container's subtree.
  const bodyRef = useRef<HTMLDivElement>(null);
  const anchors = deriveSectionAnchors(artifact.body);

  const createdAtUtc = formatUtcTimestamp(artifact.createdAt);
  const createdAtRelative = formatRelativeTime(artifact.createdAt);
  // Narrow to a local so the change-summary slot can read `.before`/`.after` without
  // a non-null assertion (AC2 — render ONLY when non-null).
  const changeSummary = artifact.changeSummary ?? null;

  const scrollToHeading = (text: string, occurrence: number) => {
    const container = bodyRef.current;
    if (container === null) {
      return;
    }
    let matchIndex = 0;
    const headings = container.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6');
    for (const heading of headings) {
      if (heading.textContent.trim() === text) {
        if (matchIndex < occurrence) {
          matchIndex += 1;
          continue;
        }
        // jsdom implements scrollIntoView as a no-op; guarded for any environment
        // that omits it entirely.
        if (typeof heading.scrollIntoView === 'function') {
          heading.scrollIntoView({ block: 'start' });
        }
        break;
      }
    }
  };

  const handleAnchorKeyDown = (
    event: KeyboardEvent<HTMLButtonElement>,
    text: string,
    occurrence: number,
  ) => {
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }
    event.preventDefault();
    scrollToHeading(text, occurrence);
  };

  return (
    <div data-testid="spec-artifact-renderer" data-artifact-type="spec">
      {/* Revision + type chrome — surfaced near the TOP (AC5), visually secondary. */}
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <Badge variant="secondary" data-testid="artifact-type-badge">
          {artifactTypeLabel(artifact.artifactType)}
        </Badge>
        <span
          className="inline-flex items-center gap-1 text-sm text-text-secondary"
          data-testid="artifact-revision"
        >
          <span className="font-medium text-text-primary">v{artifact.version}</span>
          {/* Revision-history view is deferred — a keyboard-focusable placeholder anchor (OQ-4). */}
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

      {/* Trusted metadata + the untrusted markdown body, via the sanctioned chrome.
          The ref-bearing wrapper lets the section-anchor nav query the rendered
          headings for best-effort text-match scrolling (T-ANCHOR). */}
      <div ref={bodyRef}>
        <MetadataChrome
          title={artifact.title}
          version={artifact.version}
          classification={artifact.classification}
        >
          <SafeMarkdownRenderer source={artifact.body} className="prose" />
        </MetadataChrome>
      </div>

      {/* Inline trusted metadata region — typed props, NEVER markdown (AC2). */}
      <div
        role="region"
        aria-label="Artifact metadata"
        className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1"
        data-testid="artifact-inline-metadata"
      >
        <MetaItem label="Created" testId="artifact-created-at" title={createdAtUtc ?? undefined}>
          {createdAtRelative !== null ? (
            <time dateTime={artifact.createdAt}>{createdAtRelative}</time>
          ) : (
            (createdAtUtc ?? artifact.createdAt)
          )}
        </MetaItem>
        <MetaItem label="Classification" testId="artifact-classification">
          <Badge variant="outline" data-testid="artifact-classification-badge">
            {artifact.classification}
          </Badge>
        </MetaItem>
        {artifact.checksum !== undefined ? (
          <MetaItem label="Checksum" testId="artifact-checksum" title={artifact.checksum}>
            <code className="text-xs">{shortChecksum(artifact.checksum)}</code>
          </MetaItem>
        ) : null}
      </div>

      {/* Change-summary slot — ONLY when a non-null changeSummary is present (AC2). */}
      {changeSummary !== null ? (
        <section aria-label="Change summary" className="mt-4" data-testid="artifact-change-summary">
          <h3 className="mb-1 text-section-heading">Change summary</h3>
          <SafeDiffRenderer before={changeSummary.before} after={changeSummary.after} />
        </section>
      ) : null}

      {/* Section-anchor nav (AC8) — derived from the raw body; native buttons are
          focusable + Enter/Space-activatable. */}
      {anchors.length > 0 ? (
        <nav
          aria-label="Section navigation"
          className="mt-4"
          data-testid="artifact-section-anchors"
        >
          <ul className="flex flex-wrap gap-2">
            {anchors.map((anchor, index) => {
              const occurrence = anchors
                .slice(0, index)
                .filter((candidate) => candidate.text === anchor.text).length;
              return (
                <li key={anchor.id}>
                  <button
                    type="button"
                    onClick={() => scrollToHeading(anchor.text, occurrence)}
                    onKeyDown={(event) => handleAnchorKeyDown(event, anchor.text, occurrence)}
                    className="rounded-md border border-border px-2 py-0.5 text-xs text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                    data-anchor-id={anchor.id}
                    data-anchor-level={anchor.level}
                  >
                    {anchor.text}
                  </button>
                </li>
              );
            })}
          </ul>
        </nav>
      ) : null}

      {/* Entry points into adjacent regions + Compare Mode (AC2 / AC9). The
          clarification (2.18) + approval (2.19) anchors are keyboard-focusable
          placeholders with no target wired in E2 (OQ-4); Compare is a reserved
          disabled control (Epic 4) with an "Available in next release" tooltip. */}
      <div className="mt-4 flex flex-wrap items-center gap-2" data-testid="artifact-region-anchors">
        <button
          type="button"
          className="rounded-md border border-border px-2.5 py-1 text-sm text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid="artifact-clarification-anchor"
        >
          Clarifications
        </button>
        <button
          type="button"
          className="rounded-md border border-border px-2.5 py-1 text-sm text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid="artifact-approval-anchor"
        >
          Approval
        </button>
        <button
          type="button"
          disabled={!compareEnabled}
          title={compareEnabled ? undefined : 'Available in next release'}
          className="rounded-md border border-border px-2.5 py-1 text-sm text-text-secondary disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid="artifact-compare-entry"
        >
          Compare
        </button>
      </div>
    </div>
  );
}
