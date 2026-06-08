import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('sonner', async () => {
  const { createElement } = await import('react');
  return {
    Toaster: ({ position, 'data-testid': testId }: { position?: string; 'data-testid'?: string }) =>
      createElement('div', { 'data-testid': testId, 'data-position': position }),
  };
});

import { Toaster } from '../sonner';

describe('Toaster', () => {
  it('AC9 — configures the actual host at the documented default position', () => {
    render(<Toaster data-testid="toaster-host" />);
    expect(screen.getByTestId('toaster-host')).toHaveAttribute('data-position', 'top-right');
  });
});
