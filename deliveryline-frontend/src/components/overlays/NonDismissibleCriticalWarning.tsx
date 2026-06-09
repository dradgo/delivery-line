/**
 * Story 2.23 (AC1, AC5) — `<NonDismissibleCriticalWarning>`.
 *
 * An acknowledgment overlay that blocks ALL implicit exits (T-NON-DISMISSIBLE):
 * Escape is prevented (`onEscapeKeyDown`), outside-pointer dismissal is prevented
 * (`onPointerDownOutside` + `onInteractOutside`), and NO `DialogClose`/X is
 * rendered — the ONLY exit is the explicit acknowledgment `<GovernedButton>`.
 *
 * It wraps the same radix `Dialog` base as the shadcn primitive but renders the
 * content via `DialogPrimitive.Content` directly (the shadcn `DialogContent`
 * hardcodes a close "X" we must omit). `role="alertdialog"`; the title is the
 * `aria-labelledby` target and the body the `aria-describedby` target (radix
 * auto-wires both via `<DialogTitle>` / `<DialogDescription>`).
 *
 * T-UNTRUSTED: `body` is TRUSTED, composite-authored.
 */
import type { ReactNode } from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';

import { cn } from '@/lib/utils';
import {
  Dialog,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
} from '@/components/ui/dialog';
import { GovernedButton } from '@/components/actions';

import {
  overlayIntentIcon,
  overlayIntentPresentation,
  type OverlayIntent,
} from './overlayPresentation';

/** Mirrors the shadcn `DialogContent` box styling (sans the hardcoded close "X"). */
const CONTENT_CLASS =
  'fixed left-[50%] top-[50%] z-50 grid w-full max-w-lg translate-x-[-50%] translate-y-[-50%] gap-4 border bg-background p-6 shadow-lg duration-200 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 sm:rounded-lg';

export interface NonDismissibleCriticalWarningProps {
  open: boolean;
  title: string;
  body: ReactNode;
  acknowledgmentLabel: string;
  onAcknowledge: () => void;
  /** Defaults to `'danger'` — this overlay exists for critical interruptions. */
  intent?: OverlayIntent | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

export function NonDismissibleCriticalWarning({
  open,
  title,
  body,
  acknowledgmentLabel,
  onAcknowledge,
  intent = 'danger',
  className,
  testId = 'non-dismissible-critical-warning',
}: NonDismissibleCriticalWarningProps) {
  const Icon = overlayIntentIcon(intent);
  const { toneClass, label: intentLabel } = overlayIntentPresentation(intent);

  return (
    <Dialog open={open}>
      <DialogPortal>
        <DialogOverlay />
        <DialogPrimitive.Content
          role="alertdialog"
          data-non-dismissible=""
          data-intent={intent}
          data-testid={testId}
          className={cn(CONTENT_CLASS, className)}
          onEscapeKeyDown={(event) => event.preventDefault()}
          onPointerDownOutside={(event) => event.preventDefault()}
          onInteractOutside={(event) => event.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Icon className={cn('size-5 shrink-0', toneClass)} aria-hidden />
              <span>{title}</span>
              {/* Non-color signifier label for screen readers (never color alone). */}
              <span className="sr-only">{intentLabel}</span>
            </DialogTitle>
            <DialogDescription data-critical-body="">{body}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <GovernedButton priority="primary" type="button" onClick={onAcknowledge}>
              {acknowledgmentLabel}
            </GovernedButton>
          </DialogFooter>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  );
}
