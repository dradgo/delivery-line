/**
 * Story 2.22 (AC4.c, AC6, AC8.a, AC9.b/e) — `<ErrorState>`.
 *
 * The failure primitive. `variant` enforces the 4-meaning split (failed
 * retrieval / unavailable diff baseline / permission-restricted / blocked by
 * stale state). `nextAction` is REQUIRED (AC6, UX-DR17) — there is NO path
 * through this component without a next safe action.
 *
 * Accessibility:
 *  - composes the shadcn `<Alert>` (role="alert" — semantic alert treatment);
 *  - `urgency` (default `passive`, OQ-5) drives `aria-live`: `active` →
 *    `assertive` (user-triggered failure), `passive` → `polite` (page-load
 *    failure). Assertive interrupts SR speech, so it is reserved (Trap T13);
 *  - on an `active` mount, focus moves to the action control (AC9.e) so keyboard
 *    users can act immediately, and a structured `state.activeError` signal is
 *    logged (logging instrumentation; message CONTENT is never logged — only its
 *    length, mirroring the backend `answerTextLength` pattern).
 *
 * Trap T9: the `NavigateBack` action consumes `useReturnToRunContext()` here —
 * the consumer never passes a back callback. Trap T10: `ContactSupport` falls
 * back to a disabled placeholder when no support URL resolves.
 */
import { useEffect, useRef, type ReactNode } from 'react';
import { GitCompare, ShieldX, TriangleAlert, type LucideIcon } from 'lucide-react';

import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { validateUrlScheme } from '@/lib/sanitization';
import { cn } from '@/lib/utils';
import { useReturnToRunContext } from '@/lib/navigation/useReturnToRunContext';
import type { NextAction } from '@/lib/navigation/types';

export type { NextAction } from '@/lib/navigation/types';

export type ErrorVariant =
  | 'failedRetrieval'
  | 'unavailableDiffBaseline'
  | 'permissionRestricted'
  | 'blockedByStaleState';

export interface ErrorStateProps {
  variant: ErrorVariant;
  /** REQUIRED next safe action (AC6) — discriminated union. */
  nextAction: NextAction;
  /** Overrides the per-variant default title. */
  title?: ReactNode;
  /** Overrides the per-variant default explanation. */
  message?: ReactNode;
  /**
   * `active` = the user just triggered a failed action (assertive announce +
   * focus move). `passive` (default) = the page loaded into an error.
   */
  urgency?: 'active' | 'passive';
  className?: string;
}

function assertNeverNextAction(action: never): never {
  throw new Error(`Unhandled NextAction kind: ${String(action)}`);
}

interface ErrorDefault {
  icon: LucideIcon;
  title: string;
  /**
   * `error` → the `state-error-*` token scale (AC4.e). `neutral` →
   * `permissionRestricted`, an informational audit-only signal (Trap T7) that
   * must NOT read as a hard failure, so it keeps the neutral surface.
   */
  tone: 'error' | 'neutral';
  defaultMessage: ReactNode;
}

function errorDefaults(variant: ErrorVariant): ErrorDefault {
  switch (variant) {
    case 'failedRetrieval':
      return {
        icon: TriangleAlert,
        title: "Couldn't load this",
        tone: 'error',
        defaultMessage: 'Something went wrong retrieving this content.',
      };
    case 'unavailableDiffBaseline':
      return {
        icon: GitCompare,
        title: 'Comparison baseline unavailable',
        tone: 'error',
        defaultMessage: 'The earlier version needed for this comparison is not available.',
      };
    case 'permissionRestricted':
      return {
        icon: ShieldX,
        title: 'Not available for your recorded role',
        tone: 'neutral',
        defaultMessage:
          'Your recorded role is not associated with this view. This is an informational signal based on recorded role context — it is not a security control, and access decisions are made by the backend, not here.',
      };
    case 'blockedByStaleState':
      return {
        icon: TriangleAlert,
        title: 'This view is out of date',
        tone: 'error',
        defaultMessage: 'The underlying run changed since this view loaded. Refresh to continue.',
      };
    default:
      return assertNeverErrorVariant(variant);
  }
}

function assertNeverErrorVariant(variant: never): never {
  throw new Error(`Unhandled ErrorState variant: ${String(variant)}`);
}

const LINK_CLASS =
  'inline-flex h-9 items-center rounded-md border border-border bg-surface px-3 text-sm font-medium text-text-primary transition-colors hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus';

function NextActionControl({
  action,
  onNavigateBack,
}: {
  action: NextAction;
  onNavigateBack: () => void;
}) {
  switch (action.kind) {
    case 'Retry':
      return (
        <Button type="button" variant="outline" size="sm" onClick={action.onRetry}>
          {action.label ?? 'Try again'}
        </Button>
      );
    case 'Refresh':
      return (
        <Button type="button" variant="outline" size="sm" onClick={action.onRefresh}>
          {action.label ?? 'Refresh'}
        </Button>
      );
    case 'NavigateBack':
      return (
        <Button type="button" variant="outline" size="sm" onClick={onNavigateBack}>
          {action.label ?? 'Back to previous view'}
        </Button>
      );
    case 'ContactSupport': {
      const envRaw: unknown = import.meta.env.VITE_SUPPORT_URL;
      const href = action.href ?? (typeof envRaw === 'string' ? envRaw : undefined);
      const label = action.label ?? 'Get help';
      if (href !== undefined && validateUrlScheme(href).ok) {
        return (
          <a className={LINK_CLASS} href={href} target="_blank" rel="noreferrer">
            {label}
          </a>
        );
      }
      // Trap T10 — no resolvable URL → disabled placeholder, never a broken link.
      return (
        <Button type="button" variant="outline" size="sm" disabled>
          {label}
        </Button>
      );
    }
    case 'DocsLink': {
      const label = action.label ?? 'View docs';
      if (validateUrlScheme(action.href).ok) {
        return (
          <a className={LINK_CLASS} href={action.href} target="_blank" rel="noreferrer">
            {label}
          </a>
        );
      }
      return (
        <Button type="button" variant="outline" size="sm" disabled>
          {label}
        </Button>
      );
    }
    default:
      return assertNeverNextAction(action);
  }
}

export function ErrorState({
  variant,
  nextAction,
  title,
  message,
  urgency = 'passive',
  className,
}: ErrorStateProps) {
  const { icon: Icon, title: defaultTitle, tone, defaultMessage } = errorDefaults(variant);
  // Trap T9 — NavigateBack consumes this internally; the consumer passes no callback.
  const navigateBack = useReturnToRunContext();
  const containerRef = useRef<HTMLDivElement>(null);
  const actionsRef = useRef<HTMLDivElement>(null);

  // Derive a stable primitive from `message` so the focus/log effect below does
  // not re-fire on every parent render: `message` is a `ReactNode` (typically a
  // fresh `<p>` identity each render), and depending on the object identity would
  // re-run the effect continuously — re-logging and yanking focus back to the
  // action after the user has tabbed away. The length is all the log needs.
  const messageLength = typeof message === 'string' ? message.length : undefined;

  useEffect(() => {
    if (urgency !== 'active') {
      return;
    }
    // Logging instrumentation — content is never logged, only its length.
    console.warn({
      event: 'state.activeError',
      variant,
      messageLength,
    });
    // AC9.e — move focus to the action control so keyboard users can act now.
    // When the only control is a disabled placeholder (ContactSupport/DocsLink
    // with no resolvable URL), fall back to the alert container so focus still
    // lands on the error, never nowhere. `nextAction.kind` is a dep so a change
    // of action re-applies focus to the new control.
    const focusable = actionsRef.current?.querySelector<HTMLElement>(
      'button:not([disabled]), a[href]',
    );
    if (focusable !== null && focusable !== undefined) {
      focusable.focus();
    } else {
      containerRef.current?.focus();
    }
  }, [urgency, variant, messageLength, nextAction.kind]);

  // AC4.e — the `state-error-*` semantic token scale, consistent with
  // `<EmptyState>`/`<LoadingState>`. `!` on the icon color overrides the shadcn
  // `<Alert>` base `[&>svg]:text-foreground` rule deterministically.
  const toneClass =
    tone === 'error'
      ? 'border-state-error-border bg-state-error text-state-error-foreground [&>svg]:!text-state-error-foreground'
      : undefined;

  return (
    <Alert
      ref={containerRef}
      tabIndex={-1}
      variant="default"
      aria-live={urgency === 'active' ? 'assertive' : 'polite'}
      className={cn('max-w-prose', toneClass, className)}
      data-testid="error-state"
      data-variant={variant}
      data-urgency={urgency}
    >
      <Icon className="size-4" aria-hidden />
      {/* AC9.d — heading is an <h2> (consistent level with <EmptyState>). */}
      <h2 className="mb-1 font-medium leading-none tracking-tight">{title ?? defaultTitle}</h2>
      <AlertDescription className="text-text-secondary">
        {message ?? defaultMessage}
      </AlertDescription>
      <div ref={actionsRef} className="mt-3">
        <NextActionControl action={nextAction} onNavigateBack={navigateBack} />
      </div>
    </Alert>
  );
}
