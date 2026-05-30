/**
 * Story 2.22 (AC4.a, AC5, AC8.a, AC9.c/d) — `<EmptyState>`.
 *
 * The benign content-absent primitive. `variant` is a TS string-literal union
 * enforcing the 5-way distinction (no runs / filtered / artifact-not-generated /
 * no-open-questions / no-meaningful-diff — UX-DR17). `message` overrides the
 * per-variant default; `action` is OPTIONAL (empty states may stand alone —
 * contrast `<ErrorState>`, which REQUIRES a next action).
 *
 * Accessibility: no live region (AC9.c — empty states are benign); icon is
 * `aria-hidden` and always paired with an `<h2>` + text (AC9.d — never
 * icon/color alone). Sized to fit its parent (AC8.a — no fixed/absolute).
 */
import type { ReactNode } from 'react';
import {
  FileQuestion,
  GitCompare,
  Inbox,
  ListFilter,
  MessageCircleQuestion,
  type LucideIcon,
} from 'lucide-react';

import { Stack } from '@/components/layout';
import { cn } from '@/lib/utils';

export type EmptyVariant =
  | 'queue'
  | 'filtered'
  | 'artifactNotGenerated'
  | 'noOpenQuestions'
  | 'noMeaningfulDiff';

export interface EmptyStateProps {
  variant: EmptyVariant;
  /** Overrides the per-variant default title. */
  title?: ReactNode;
  /** Overrides the per-variant default explanation. */
  message?: ReactNode;
  /** Optional next affordance (e.g. a link to the queue). */
  action?: ReactNode;
  className?: string;
}

/**
 * AC5 exhaustiveness guard — adding a 6th variant without a `case` below fails
 * `tsc --noEmit` (the `never` parameter). Mirrors `state-signifiers`'s pattern.
 */
// eslint-disable-next-line react-refresh/only-export-components -- exhaustiveness guard (AC5) colocated with the union it guards.
export function assertNeverEmptyVariant(variant: never): never {
  throw new Error(`Unhandled EmptyState variant: ${String(variant)}`);
}

interface EmptyDefault {
  icon: LucideIcon;
  title: string;
  defaultMessage: ReactNode;
}

function emptyDefaults(variant: EmptyVariant): EmptyDefault {
  switch (variant) {
    case 'queue':
      return {
        icon: Inbox,
        title: 'No runs yet',
        defaultMessage:
          'No workflow runs are in the queue yet. New runs appear here as they start.',
      };
    case 'filtered':
      return {
        icon: ListFilter,
        title: 'No matching runs',
        defaultMessage: 'No runs match the current filters. Try clearing or adjusting them.',
      };
    case 'artifactNotGenerated':
      return {
        icon: FileQuestion,
        title: 'Artifact not generated yet',
        defaultMessage:
          'This artifact has not been generated yet. It appears here once the run produces it.',
      };
    case 'noOpenQuestions':
      return {
        icon: MessageCircleQuestion,
        title: 'No open questions',
        defaultMessage: 'There are no clarifications waiting on a response for this run.',
      };
    case 'noMeaningfulDiff':
      return {
        icon: GitCompare,
        title: 'No meaningful changes',
        defaultMessage: 'There are no meaningful differences to compare between these versions.',
      };
    default:
      return assertNeverEmptyVariant(variant);
  }
}

export function EmptyState({ variant, title, message, action, className }: EmptyStateProps) {
  const { icon: Icon, title: defaultTitle, defaultMessage } = emptyDefaults(variant);
  return (
    <Stack
      gap="2"
      className={cn('max-w-prose items-start', className)}
      data-testid="empty-state"
      data-variant={variant}
    >
      <Icon className="size-8 text-state-empty-foreground" aria-hidden />
      <h2 className="text-section-heading">{title ?? defaultTitle}</h2>
      <div className="text-body text-text-secondary">{message ?? defaultMessage}</div>
      {action !== undefined && action !== null ? <div>{action}</div> : null}
    </Stack>
  );
}
