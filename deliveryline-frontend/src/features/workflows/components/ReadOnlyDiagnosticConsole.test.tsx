/**
 * Story 3d-6 (AC6 / AC8) — Read-only Diagnostic Console Vitest coverage. Drives a mock `EventSource`
 * (jsdom has none) through the live + ended + not-live/denial paths, asserts the live-region
 * announcement via `waitFor` (the announcer defers one commit — never assert synchronously,
 * `livesnnouncement-defers-one-commit-test-flake`), the "Read-only" badge + color-independent mode
 * signifier, the read-only guarantee (NO input control posting to the backend), and zero `wcag2aa`
 * axe violations.
 */
import { act } from 'react';

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import { ReadOnlyDiagnosticConsole } from './ReadOnlyDiagnosticConsole';

/** A controllable EventSource double capturing listeners so the test drives SSE events. */
class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  closed = false;
  private readonly listeners: Record<string, Set<(event: Event) => void>> = {};

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, cb: (event: Event) => void) {
    (this.listeners[type] ??= new Set()).add(cb);
  }

  removeEventListener(type: string, cb: (event: Event) => void) {
    this.listeners[type]?.delete(cb);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data?: unknown) {
    const event =
      data === undefined ? new Event(type) : ({ data: JSON.stringify(data) } as MessageEvent);
    this.listeners[type]?.forEach((cb) => cb(event));
  }

  static latest(): MockEventSource {
    const last = MockEventSource.instances.at(-1);
    if (last === undefined) {
      throw new Error('no EventSource opened');
    }
    return last;
  }
}

beforeEach(() => {
  MockEventSource.instances = [];
  (globalThis as { EventSource?: unknown }).EventSource =
    MockEventSource as unknown as typeof EventSource;
});

afterEach(() => {
  cleanup();
  delete (globalThis as { EventSource?: unknown }).EventSource;
});

describe('ReadOnlyDiagnosticConsole (story 3d-6)', () => {
  it('renders streamed live chunks, the Read-only badge, and announces session start (AC6)', async () => {
    render(
      <ReadOnlyDiagnosticConsole workflowRunId="run_console00001" actorRole="workflow_owner" />,
    );
    const source = MockEventSource.latest();

    act(() => {
      source.emit('status', { phase: 'live', rex: 'rex_console00001' });
      source.emit('console', { stream: 'stdout', chunk: 'agent thinking…', seq: 0 });
      source.emit('console', { stream: 'stderr', chunk: 'warning: slow step', seq: 1 });
    });

    expect(screen.getByTestId('console-scroll')).toHaveTextContent('agent thinking…');
    expect(screen.getByTestId('console-scroll')).toHaveTextContent('warning: slow step');
    // AC6 — clearly badged "Read-only" + color-independent mode signifier (icon + label).
    expect(screen.getByTestId('console-readonly-badge')).toHaveTextContent('Read-only');
    expect(screen.getByTestId('console-mode')).toHaveTextContent('Live');
    // The actorRole=workflow_owner is threaded into the stream URL for the server-side gate.
    expect(source.url).toContain('actorRole=workflow_owner');
    // Announcement defers one commit — assert via waitFor.
    await waitFor(() =>
      expect(screen.getByTestId('console-announcer')).toHaveTextContent(
        'Read-only diagnostic console session started.',
      ),
    );
  });

  it('exposes NO input control posting to the backend — read-only guarantee (DD-1 / Trap T6)', () => {
    const { container } = render(
      <ReadOnlyDiagnosticConsole workflowRunId="run_console00002" actorRole="workflow_owner" />,
    );
    const source = MockEventSource.latest();
    act(() => {
      source.emit('status', { phase: 'live', rex: 'rex_console00002' });
      source.emit('console', { stream: 'stdout', chunk: 'prompt> ', seq: 0 });
    });

    // A read-only streaming pty: there is NO text input / textarea wired to the container. The only
    // interactive control is the auto-scroll toggle (a button), never an input channel.
    expect(container.querySelector('input')).toBeNull();
    expect(container.querySelector('textarea')).toBeNull();
    expect(screen.getByTestId('console-autoscroll-toggle')).toBeInTheDocument();
  });

  it('surfaces a console-not-live rejection and announces a session error (AC2 LIVE-ONLY)', async () => {
    render(
      <ReadOnlyDiagnosticConsole workflowRunId="run_console00003" actorRole="workflow_owner" />,
    );
    const source = MockEventSource.latest();

    act(() => {
      source.emit('error', { reason: 'console-not-live' });
      source.emit('end', { reason: 'not-live' });
    });

    expect(screen.getByTestId('console-error')).toHaveTextContent(
      'No live runner execution to attach to. Use the runner logs for a finished step.',
    );
    expect(screen.getByTestId('console-mode')).toHaveTextContent('Error');
    await waitFor(() =>
      expect(screen.getByTestId('console-announcer')).toHaveTextContent(
        'The read-only diagnostic console could not be opened.',
      ),
    );
  });

  it('surfaces a server-side denial when the action is absent (AC4 server-side gating)', () => {
    render(
      <ReadOnlyDiagnosticConsole workflowRunId="run_console00004" actorRole="workflow_owner" />,
    );
    const source = MockEventSource.latest();

    act(() => {
      source.emit('error', { reason: 'open_diagnostic_console_not_allowed' });
    });

    expect(screen.getByTestId('console-error')).toHaveTextContent(
      'You do not have permission to open a diagnostic console for this run.',
    );
  });

  it('has zero WCAG 2.1 AA axe violations on the live console (AC8)', async () => {
    const { container } = render(
      <ReadOnlyDiagnosticConsole workflowRunId="run_console00005" actorRole="workflow_owner" />,
    );
    const source = MockEventSource.latest();
    act(() => {
      source.emit('status', { phase: 'live', rex: 'rex_console00005' });
      source.emit('console', { stream: 'stdout', chunk: 'session started', seq: 0 });
    });
    await expectNoA11yViolations(container);
  });
});
