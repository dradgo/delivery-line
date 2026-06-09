/**
 * Story 2.25 (Task 4 — AC8) — `<AuditRoleLabel>`.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { AuditRoleLabel, AUDIT_ROLE_CLARIFIER } from '../AuditRoleLabel';

afterEach(cleanup);

describe('AuditRoleLabel', () => {
  it('AC8 — renders the role with a visible audit-label cue', () => {
    render(<AuditRoleLabel actorRole="product_reviewer" />);
    const el = screen.getByTestId('audit-role-label');
    expect(el).toHaveAttribute('data-audit-role', 'product_reviewer');
    expect(el).toHaveTextContent('product_reviewer');
    expect(el).toHaveTextContent('(audit label)');
  });

  it('AC8 — the clarifier rides the accessible name and the hover tooltip', () => {
    render(<AuditRoleLabel actorRole="workflow_owner" />);
    const el = screen.getByTestId('audit-role-label');
    // accessible name (aria-label) carries the "not an enforced permission" clarifier
    expect(el).toHaveAccessibleName(`workflow_owner — ${AUDIT_ROLE_CLARIFIER}`);
    expect(el).toHaveAttribute('title', AUDIT_ROLE_CLARIFIER);
    expect(AUDIT_ROLE_CLARIFIER).toMatch(/not an enforced permission/);
  });

  it('AC2 — has no axe violations', async () => {
    const { container } = render(<AuditRoleLabel actorRole="product_reviewer" />);
    await expectNoA11yViolations(container);
  });
});
