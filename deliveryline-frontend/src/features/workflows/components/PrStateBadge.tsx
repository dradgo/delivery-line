/**
 * Story 3.27 (Task 3 / AC2) — `PrStateBadge`.
 *
 * A small, reusable badge for the GitHub PR lifecycle state. Composes the shared
 * `Badge` primitive (NOT `WorkflowStateBadge` — a PR state is not a workflow state) and
 * pairs colour with a NON-COLOUR signifier (a per-state icon + a text label, per story
 * 2.3 AC5) so the state is distinguishable without relying on colour alone. Story 3.31
 * consumes the same badge for the Run Context Strip / Queue PR linkage (3.31 AC7).
 */
import {
  GitMerge,
  GitPullRequest,
  GitPullRequestClosed,
  GitPullRequestDraft,
  type LucideIcon,
} from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

import type { PrState } from '../artifactView';

interface PrStateMeta {
  readonly label: string;
  readonly Icon: LucideIcon;
  readonly className: string;
}

/** Per-state label + icon (the non-colour signifiers) + colour tokens. */
const PR_STATE_META: Record<PrState, PrStateMeta> = {
  draft: {
    label: 'Draft',
    Icon: GitPullRequestDraft,
    className: 'border-state-draft-border bg-state-draft text-state-draft-foreground',
  },
  open: {
    label: 'Open',
    Icon: GitPullRequest,
    className: 'border-state-success-border bg-state-success text-state-success-foreground',
  },
  merged: {
    label: 'Merged',
    Icon: GitMerge,
    className:
      'border-state-informational-border bg-state-informational text-state-informational-foreground',
  },
  closed: {
    label: 'Closed',
    Icon: GitPullRequestClosed,
    className: 'border-state-error-border bg-state-error text-state-error-foreground',
  },
};

export interface PrStateBadgeProps {
  state: PrState;
  className?: string;
}

export function PrStateBadge({ state, className }: PrStateBadgeProps) {
  const meta = PR_STATE_META[state];
  const Icon = meta.Icon;
  return (
    <Badge
      variant="outline"
      className={cn('inline-flex items-center gap-1', meta.className, className)}
      data-testid="pr-state-badge"
      data-pr-state={state}
    >
      <Icon className="size-3.5 shrink-0" aria-hidden />
      {meta.label}
    </Badge>
  );
}
