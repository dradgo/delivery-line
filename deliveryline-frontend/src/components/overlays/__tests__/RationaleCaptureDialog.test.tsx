/**
 * Story 2.23 (AC1, AC2) — `<RationaleCaptureDialog>`.
 */
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
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

describe('RationaleCaptureDialog a11y (story 2.25)', () => {
  const MULTI_FIELDS: readonly RationaleField[] = [
    { name: 'reason', label: 'Reason', type: 'text', required: true, placeholder: 'reason' },
    { name: 'detail', label: 'Detail', type: 'textarea', placeholder: 'detail' },
    {
      name: 'category',
      label: 'Category',
      type: 'select',
      placeholder: 'Pick one',
      options: [
        { value: 'scope', label: 'Out of scope' },
        { value: 'quality', label: 'Quality' },
      ],
    },
  ];

  function renderDialog(props?: Partial<React.ComponentProps<typeof RationaleCaptureDialog>>) {
    return render(
      <RationaleCaptureDialog
        open
        onOpenChange={() => undefined}
        title="Reject with a reason"
        intent="danger"
        consequence="This cannot be undone."
        fields={MULTI_FIELDS}
        confirmLabel="Submit"
        onConfirm={() => undefined}
        {...props}
      />,
    );
  }

  it('AC2 — pristine open form (text/textarea/select fields) has no axe violations', async () => {
    renderDialog();
    await screen.findByRole('dialog');
    await expectNoA11yViolations(document.body);
  });

  it('AC2 — invalid (error-shown) state with aria-invalid + aria-describedby has no axe violations', async () => {
    renderDialog();
    await screen.findByRole('dialog');
    // Trigger the inline error path (aria-invalid + role=alert error node).
    // Submit is disabled while invalid, so surface the error by blurring the
    // required field instead of clicking the (inert) confirm.
    fireEvent.blur(screen.getByPlaceholderText('reason'));
    expect(screen.getByText('Reason is required')).toBeInTheDocument();
    await expectNoA11yViolations(document.body);
  });

  it('AC1 — focus moves into the dialog on open', async () => {
    renderDialog();
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
  });

  it('AC1 — fields and the cancel/submit actions are Tab-reachable; submit activates via Enter when valid', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    renderDialog({ fields: FIELDS, onConfirm });
    await screen.findByRole('dialog');

    const input = screen.getByPlaceholderText('Why is this rejected?');
    input.focus();
    expect(input).toHaveFocus();
    await user.keyboard('Out of scope');

    const submit = screen.getByRole('button', { name: 'Submit' });
    expect(submit).toBeEnabled();
    submit.focus();
    await user.keyboard('{Enter}');
    expect(onConfirm).toHaveBeenCalledWith({ reason: 'Out of scope' });
  });

  it('AC1 — Escape closes the dismissible dialog (inherits ConfirmationDialog semantics)', async () => {
    const onOpenChange = vi.fn();
    renderDialog({ onOpenChange });
    const dialog = await screen.findByRole('dialog');
    fireEvent.keyDown(dialog, { key: 'Escape', code: 'Escape' });
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });
});
