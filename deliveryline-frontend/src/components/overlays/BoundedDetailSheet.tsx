/**
 * Story 2.23 (AC1, AC5, AC6) — `<BoundedDetailSheet>`.
 *
 * A bounded secondary-detail sheet wrapping the shadcn `Sheet` (radix). Closing
 * returns the user to the same review context WITHOUT reset (presentational —
 * holds no state). Radix moves focus IN on open; focus restoration on close is
 * handled explicitly below for the controlled (trigger-less) case.
 *
 * AC6 — `side="bottom"` (or `fullHeightOnMobile`) renders the full-height
 * slide-up sheet pattern for narrow breakpoints. The actual breakpoint switch is
 * deferred to story 2.26 (OQ-5); this ships the variant + the documented prop.
 *
 * Story 2.25 (Task 2, AC1 / WCAG 2.4.3) — like `<ConfirmationDialog>`, this sheet
 * is opened in a CONTROLLED way (no `<SheetTrigger>`), so Radix's default
 * `onCloseAutoFocus` would target an absent trigger and drop focus to `<body>`.
 * We capture the element focused at open time and restore it on close.
 *
 * T-UNTRUSTED: `children` are TRUSTED, composite-authored by default.
 * T-NO-STACK: do not nest a `<ConfirmationDialog>` inside the sheet (UX-DR18).
 */
import { useEffect, useRef, type ReactNode } from 'react';

import { cn } from '@/lib/utils';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';

export interface BoundedDetailSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  /** Optional sub-heading for screen readers + context (TRUSTED). */
  description?: ReactNode | undefined;
  /** `'right'` slide-over (default) or `'bottom'` full-height slide-up (AC6). */
  side?: 'right' | 'bottom' | undefined;
  /** Documented mobile hint — forces the full-height bottom-sheet treatment (AC6). */
  fullHeightOnMobile?: boolean | undefined;
  children?: ReactNode | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

export function BoundedDetailSheet({
  open,
  onOpenChange,
  title,
  description,
  side,
  fullHeightOnMobile = false,
  children,
  className,
  testId = 'bounded-detail-sheet',
}: BoundedDetailSheetProps) {
  const resolvedSide: 'right' | 'bottom' = side ?? (fullHeightOnMobile ? 'bottom' : 'right');
  const isFullHeight = resolvedSide === 'bottom' || fullHeightOnMobile;

  // AC1 / WCAG 2.4.3 — restore focus to the element that opened the sheet (the
  // controlled case has no <SheetTrigger> for radix to return focus to).
  const previouslyFocused = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (open) {
      previouslyFocused.current = document.activeElement as HTMLElement | null;
      return;
    }
    const target = previouslyFocused.current;
    previouslyFocused.current = null;
    if (target !== null && typeof target.focus === 'function') {
      target.focus();
    }
  }, [open]);

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side={resolvedSide}
        data-bounded-detail-sheet=""
        data-side={resolvedSide}
        data-full-height={isFullHeight ? 'true' : 'false'}
        data-testid={testId}
        // Let our explicit restoration effect own focus return (radix would
        // otherwise target an absent trigger and drop focus to <body>).
        onCloseAutoFocus={(event) => event.preventDefault()}
        className={cn(
          'flex flex-col gap-4',
          isFullHeight && 'h-[90dvh] max-sm:h-[100dvh]',
          className,
        )}
      >
        <SheetHeader>
          <SheetTitle>{title}</SheetTitle>
          {description !== undefined && description !== null ? (
            <SheetDescription>{description}</SheetDescription>
          ) : null}
        </SheetHeader>
        <div className="min-h-0 flex-1 overflow-y-auto text-sm text-text-secondary">{children}</div>
      </SheetContent>
    </Sheet>
  );
}
