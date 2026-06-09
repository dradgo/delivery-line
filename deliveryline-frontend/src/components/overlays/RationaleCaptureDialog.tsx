/**
 * Story 2.23 (AC1, AC2, AC5) — `<RationaleCaptureDialog>`.
 *
 * Composes `<ConfirmationDialog>` (so the required `consequence` is INHERITED —
 * T-CONSEQUENCE-REQUIRED) and adds a typed set of structured fields. This is the
 * reject-with-reason shape (story 2.19) — built here but NOT wired into any done
 * composite (Reconciliation / OQ-1).
 *
 * Confirm is gated until every required field validates; per-field validation
 * errors surface inline via an `aria-describedby` error node + `aria-invalid`.
 * `onConfirm(values)` receives the collected field map.
 *
 * T-UNTRUSTED: labels/options/placeholders are TRUSTED, composite-authored.
 */
import { useEffect, useId, useState, type ReactNode } from 'react';

import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { ConfirmationDialog } from './ConfirmationDialog';
import type { OverlayIntent } from './overlayPresentation';

export interface RationaleFieldOption {
  readonly value: string;
  readonly label: string;
}

/** A single structured rationale field. */
export interface RationaleField {
  readonly name: string;
  readonly label: string;
  readonly type: 'text' | 'textarea' | 'select';
  readonly required?: boolean | undefined;
  readonly placeholder?: string | undefined;
  readonly options?: readonly RationaleFieldOption[] | undefined;
  /** Custom validator — returns an error string, or `undefined` when valid. */
  readonly validate?: ((value: string) => string | undefined) | undefined;
}

/** The collected field map handed to `onConfirm`. */
export type RationaleValues = Record<string, string>;

export interface RationaleCaptureDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  intent: OverlayIntent;
  /** REQUIRED — inherited from `<ConfirmationDialog>` (AC2). */
  consequence: string;
  fields: readonly RationaleField[];
  confirmLabel?: string | undefined;
  cancelLabel?: string | undefined;
  onConfirm: (values: RationaleValues) => void;
  onCancel?: (() => void) | undefined;
  isConfirming?: boolean | undefined;
  className?: string | undefined;
  testId?: string | undefined;
  /** Optional preamble rendered above the fields (TRUSTED). */
  children?: ReactNode | undefined;
}

/** The blocking validation error for a field's current value, or `undefined`. */
function fieldError(field: RationaleField, value: string): string | undefined {
  const trimmed = value.trim();
  if (field.required === true && trimmed === '') {
    return `${field.label} is required`;
  }
  if (trimmed === '') {
    return undefined;
  }
  return field.validate?.(value);
}

export function RationaleCaptureDialog({
  open,
  onOpenChange,
  title,
  intent,
  consequence,
  fields,
  confirmLabel = 'Submit',
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  isConfirming = false,
  className,
  testId = 'rationale-capture-dialog',
  children,
}: RationaleCaptureDialogProps) {
  const baseId = useId();
  const [values, setValues] = useState<RationaleValues>(() =>
    Object.fromEntries(fields.map((f) => [f.name, ''])),
  );
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitAttempted, setSubmitAttempted] = useState(false);

  // Reset the form whenever the dialog closes so a prior (possibly abandoned)
  // rationale and its validation errors never leak into the next decision, and
  // the value map is re-derived from the CURRENT `fields` for the next open.
  useEffect(() => {
    if (!open) {
      setValues(Object.fromEntries(fields.map((f) => [f.name, ''])));
      setTouched({});
      setSubmitAttempted(false);
    }
  }, [open, fields]);

  const hasBlockingError = fields.some((f) => fieldError(f, values[f.name] ?? '') !== undefined);

  function setValue(name: string, next: string) {
    setValues((prev) => ({ ...prev, [name]: next }));
  }

  function markTouched(name: string) {
    setTouched((prev) => ({ ...prev, [name]: true }));
  }

  function handleConfirm() {
    setSubmitAttempted(true);
    if (hasBlockingError) {
      return;
    }
    onConfirm({ ...values });
  }

  return (
    <ConfirmationDialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      intent={intent}
      consequence={consequence}
      confirmLabel={confirmLabel}
      cancelLabel={cancelLabel}
      onConfirm={handleConfirm}
      onCancel={onCancel}
      isConfirming={isConfirming}
      confirmDisabled={hasBlockingError}
      className={className}
      testId={testId}
    >
      <form className="space-y-3" noValidate data-rationale-fields="">
        {children !== undefined && children !== null ? <div>{children}</div> : null}
        {fields.map((field) => {
          const value = values[field.name] ?? '';
          const error = fieldError(field, value);
          const showError =
            error !== undefined && (touched[field.name] === true || submitAttempted);
          const controlId = `${baseId}-${field.name}`;
          const errorId = `${controlId}-error`;
          const describedBy = showError ? errorId : undefined;
          const invalid = showError ? true : undefined;

          return (
            <div key={field.name} className="space-y-1.5" data-field={field.name}>
              <Label htmlFor={controlId}>
                {field.label}
                {field.required === true ? (
                  <span aria-hidden className="ml-0.5 text-state-error-foreground">
                    *
                  </span>
                ) : null}
              </Label>

              {field.type === 'textarea' ? (
                <Textarea
                  id={controlId}
                  value={value}
                  placeholder={field.placeholder}
                  required={field.required}
                  aria-invalid={invalid}
                  aria-describedby={describedBy}
                  onChange={(e) => setValue(field.name, e.target.value)}
                  onBlur={() => markTouched(field.name)}
                />
              ) : field.type === 'select' ? (
                <Select
                  value={value}
                  onValueChange={(next) => {
                    setValue(field.name, next);
                    markTouched(field.name);
                  }}
                >
                  <SelectTrigger
                    id={controlId}
                    aria-invalid={invalid}
                    aria-describedby={describedBy}
                  >
                    <SelectValue placeholder={field.placeholder ?? 'Select…'} />
                  </SelectTrigger>
                  <SelectContent>
                    {(field.options ?? []).map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <Input
                  id={controlId}
                  value={value}
                  placeholder={field.placeholder}
                  required={field.required}
                  aria-invalid={invalid}
                  aria-describedby={describedBy}
                  onChange={(e) => setValue(field.name, e.target.value)}
                  onBlur={() => markTouched(field.name)}
                />
              )}

              {showError ? (
                <p
                  id={errorId}
                  role="alert"
                  data-field-error={field.name}
                  className="text-meta text-state-error-foreground"
                >
                  {error}
                </p>
              ) : null}
            </div>
          );
        })}
      </form>
    </ConfirmationDialog>
  );
}
