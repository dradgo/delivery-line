/**
 * Story 2.22 (AC8.b/c) — the global catastrophic-error overlay.
 *
 * Composes the shadcn `<Dialog>` (Radix-backed): Radix portals to `document.body`
 * OUTSIDE the AppShell subtree (Trap T11), traps focus, and restores focus to the
 * previously-focused element on close — so AC11.q (focus trapped + dismiss
 * restores focus) is satisfied by the primitive. `role="alertdialog"` marks it as
 * an interrupting alert. Logs a structured `errorBoundary.catastrophic` signal on
 * open (route read from `window.location` — the overlay lives outside the router,
 * so `useLocation` is unavailable).
 */
import { useEffect, useRef } from 'react';

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { useCatastrophicError } from './useCatastrophicError';

export function CatastrophicErrorOverlay() {
  const { activeError, dismiss } = useCatastrophicError();
  const open = activeError !== null;
  // Self-managed focus restore: state-controlled `open` makes Radix's default
  // restore unreliable (it can capture <body>), so we snapshot the
  // previously-focused element when Radix is about to move focus IN, and restore
  // it on close (AC11.q).
  const previouslyFocused = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (activeError !== null) {
      console.error({
        event: 'errorBoundary.catastrophic',
        message: activeError.message,
        route: window.location.pathname,
      });
    }
  }, [activeError]);

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          dismiss();
        }
      }}
    >
      <DialogContent
        role="alertdialog"
        onOpenAutoFocus={() => {
          const active = document.activeElement;
          previouslyFocused.current = active instanceof HTMLElement ? active : null;
        }}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          previouslyFocused.current?.focus();
          previouslyFocused.current = null;
        }}
      >
        <DialogHeader>
          <DialogTitle>Something went wrong</DialogTitle>
          <DialogDescription>
            The workspace hit an unexpected error and can&rsquo;t continue here. Reloading usually
            clears it. If it keeps happening, the run may need operator attention.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={dismiss}>
            Dismiss
          </Button>
          <Button type="button" onClick={() => window.location.reload()}>
            Reload
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
