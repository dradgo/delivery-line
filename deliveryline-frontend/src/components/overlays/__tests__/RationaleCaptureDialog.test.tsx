/**
 * Story 2.23 (AC1, AC2) — `<RationaleCaptureDialog>`.
 */
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RationaleCaptureDialog, type RationaleField } from '../RationaleCaptureDialog';

afterEach(cleanup);

const FIELDS: readonly RationaleField[] = [
  {
    name: 'reason',
    label: 'Reason',
    type: 'text',
    required: true,
    placeholder: 'Why is this rejected?',
  },
];

describe('RationaleCaptureDialog', () => {
  it('AC2 — required-field validation gates confirm', () => {
    render(
      <RationaleCaptureDialog
        open
        onOpenChange={() => undefined}
        title="Reject with a reason"
        intent="danger"
        consequence="This cannot be undone."
        fields={FIELDS}
        confirmLabel="Submit"
        onConfirm={() => undefined}
      />,
    );
    // Required field is empty → confirm disabled.
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
  });

  it('AC1 — per-field error renders on blur and clears once valid', () => {
    render(
      <RationaleCaptureDialog
        open
        onOpenChange={() => undefined}
        title="Reject with a reason"
        intent="danger"
        consequence="This cannot be undone."
        fields={FIELDS}
        confirmLabel="Submit"
        onConfirm={() => undefined}
      />,
    );
    const input = screen.getByPlaceholderText('Why is this rejected?');
    fireEvent.blur(input);
    expect(screen.getByText('Reason is required')).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-invalid', 'true');

    fireEvent.change(input, { target: { value: 'Out of scope' } });
    expect(screen.queryByText('Reason is required')).toBeNull();
    expect(screen.getByRole('button', { name: 'Submit' })).toBeEnabled();
  });

  it('AC1/AC2 — onConfirm receives the collected field map', () => {
    const onConfirm = vi.fn();
    render(
      <RationaleCaptureDialog
        open
        onOpenChange={() => undefined}
        title="Reject with a reason"
        intent="danger"
        consequence="This cannot be undone."
        fields={FIELDS}
        confirmLabel="Submit"
        onConfirm={onConfirm}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText('Why is this rejected?'), {
      target: { value: 'Incomplete spec' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }));
    expect(onConfirm).toHaveBeenCalledWith({ reason: 'Incomplete spec' });
  });

  it('AC1 — custom validators surface their message and block confirm', () => {
    const fields: readonly RationaleField[] = [
      {
        name: 'detail',
        label: 'Detail',
        type: 'textarea',
        required: true,
        placeholder: 'detail',
        validate: (value) => (value.trim().length < 5 ? 'Too short.' : undefined),
      },
    ];
    render(
      <RationaleCaptureDialog
        open
        onOpenChange={() => undefined}
        title="Reject"
        intent="danger"
        consequence="c"
        fields={fields}
        confirmLabel="Submit"
        onConfirm={() => undefined}
      />,
    );
    const input = screen.getByPlaceholderText('detail');
    fireEvent.change(input, { target: { value: 'x' } });
    fireEvent.blur(input);
    expect(screen.getByText('Too short.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
  });
});
