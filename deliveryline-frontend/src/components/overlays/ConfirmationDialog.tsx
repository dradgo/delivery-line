/**
 * Story 2.23 (AC1, AC2, AC5, AC6) — `<ConfirmationDialog>`.
 *
 * A confirm / cancel dialog wrapping the shadcn `Dialog` (radix) — never a new
 * portal/host. Radix handles focus-move-in + focus-restoration-on-close (AC5).
 *
 * AC2 / T-CONSEQUENCE-REQUIRED: `consequence` is a NON-optional `string` — `tsc`
 * rejects omission. It renders as the dialog's `aria-describedby` target (via
 * `<DialogDescription>`); the title is the `aria-labelledby` target (via
 * `<DialogTitle>`) — both auto-wired by radix.
 *
 * AC5: Escape dismissal is predictable (radix default). AC6: a `danger` intent
 * renders the confirm as a `destructive` `<GovernedButton>`, kept a distinct
 * element separated from the cancel control in the footer.
 *
 * T-UNTRUSTED: `title`/`consequence`/`children` are TRUSTED, composite-authored
 * content. A caller passing runner/agent text must sanitize via
 * `@/lib/sanitization` first.
 */
import { useEffect, useRef, type ReactNode } from 'react';

import { cn } from '@/lib/utils';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { GovernedButton } from '@/components/actions';

import {
  overlayIntentIcon,
  overlayIntentPresentation,
  type OverlayIntent,
} from './overlayPresentation';

export interface ConfirmationDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Heading — the `aria-labelledby` target (TRUSTED). */
  title: string;
  intent: OverlayIntent;
  /** REQUIRED (AC2) — the `aria-describedby` target stating the consequence (TRUSTED). */
  consequence: string;
  /** Optional secondary body rendered below the consequence (TRUSTED). */
  children?: ReactNode | undefined;
  confirmLabel?: string | undefined;
  cancelLabel?: string | undefined;
  onConfirm: () => void;
  onCancel?: (() => void) | undefined;
  /** Reflects an in-flight confirm — confirm shows the submitting spinner + non-interactive. */
  isConfirming?: boolean | undefined;
  /** Gates the confirm action (e.g. rationale fields invalid). */
  confirmDisabled?: boolean | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

export function ConfirmationDialog({
  open,
  onOpenChange,
  title,
  intent,
  consequence,
  children,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  isConfirming = false,
  confirmDisabled = false,
  className,
  testId = 'confirmation-dialog',
}: ConfirmationDialogProps) {
  const Icon = overlayIntentIcon(intent);
  const { toneClass, label: intentLabel } = overlayIntentPresentation(intent);

  // AC5 — focus restoration for a CONTROLLED dialog. Radix only restores focus to
  // a `<DialogTrigger>`, which a controlled/composite-opened dialog has none of,
  // so we capture the element focused at open time and restore it on close.
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

  function handleCancel() {
    onCancel?.();
    onOpenChange(false);
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* Radix auto-wires aria-labelledby → DialogTitle and aria-describedby →
          DialogDescription; we do NOT set our own ids (doing so desyncs the
          context-generated id Content references). */}
      <DialogContent
        data-confirmation-dialog=""
        data-intent={intent}
        data-testid={testId}
        className={cn(className)}
        // Let our explicit focus-restoration effect own focus return (radix would
        // otherwise target an absent trigger and drop focus to <body>).
        onCloseAutoFocus={(event) => event.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Icon className={cn('size-5 shrink-0', toneClass)} aria-hidden />
            <span>{title}</span>
            {/* Non-color signifier label for screen readers (never color alone). */}
            <span className="sr-only">{intentLabel}</span>
          </DialogTitle>
          <DialogDescription data-consequence="">{consequence}</DialogDescription>
        </DialogHeader>
        {children !== undefined && children !== null ? (
          <div className="text-sm text-text-secondary">{children}</div>
        ) : null}
        <DialogFooter>
          <GovernedButton priority="secondary" type="button" onClick={handleCancel}>
            {cancelLabel}
          </GovernedButton>
          <GovernedButton
            priority={intent === 'danger' ? 'destructive' : 'primary'}
            type="button"
            workflowState={isConfirming ? 'submitting' : undefined}
            disabled={confirmDisabled}
            onClick={onConfirm}
          >
            {confirmLabel}
          </GovernedButton>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
