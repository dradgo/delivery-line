/**
 * Story 3d-5 (AC4 / AC8) — Step Execution Log Viewer Vitest coverage. Drives a mock `EventSource`
 * (jsdom has none) through the live + finished + denial paths, asserts the live-region announcement
 * via `waitFor` (the announcer defers one commit — never assert synchronously,
 * `livesnnouncement-defers-one-commit-test-flake`), the color-independent mode signifier, and zero
 * `wcag2aa` axe violations.
 */
import { act } from 'react';

import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import { StepExecutionLogViewer } from './StepExecutionLogViewer';

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

describe('StepExecutionLogViewer (story 3d-5)', () => {
  it('renders streamed live lines and announces stream start (AC4)', async () => {
    render(<StepExecutionLogViewer workflowRunId="run_logview00001" />);
    const source = MockEventSource.latest();

    act(() => {
      source.emit('status', { phase: 'live', rex: 'rex_logview00001' });
      source.emit('log', { stream: 'stdout', line: 'compiling sources', seq: 0 });
      source.emit('log', { stream: 'stderr', line: 'warning: deprecated flag', seq: 1 });
    });

    expect(screen.getByTestId('step-log-scroll')).toHaveTextContent('compiling sources');
    expect(screen.getByTestId('step-log-scroll')).toHaveTextContent('warning: deprecated flag');
    // Color-independent mode signifier (icon + label, never color alone).
    expect(screen.getByTestId('step-log-mode')).toHaveTextContent('Live');
    // Announcement defers one commit — assert via waitFor.
    await waitFor(() =>
      expect(screen.getByTestId('step-log-announcer')).toHaveTextContent(
        'Runner log stream started.',
      ),
    );
  });

  it('renders the finished/static replay and announces stream end (AC1/AC4)', async () => {
    render(<StepExecutionLogViewer workflowRunId="run_logview00002" />);
    const source = MockEventSource.latest();

    act(() => {
      source.emit('status', { phase: 'finished', rex: 'rex_logview00002' });
      source.emit('log', { stream: 'stdout', line: '[REDACTED_AUTHORIZATION_HEADER]', seq: 0 });
      source.emit('end', { reason: 'finished-replay-complete' });
    });

    expect(screen.getByTestId('step-log-mode')).toHaveTextContent('Ended');
    // Already-redacted persisted content is replayed verbatim.
    expect(screen.getByTestId('step-log-scroll')).toHaveTextContent(
      '[REDACTED_AUTHORIZATION_HEADER]',
    );
    await waitFor(() =>
      expect(screen.getByTestId('step-log-announcer')).toHaveTextContent(
        'Runner log stream ended.',
      ),
    );
    expect(source.closed).toBe(true);
  });

  it('surfaces a denial error and announces a stream error (AC6 server-side gating)', async () => {
    render(<StepExecutionLogViewer workflowRunId="run_logview00003" />);
    const source = MockEventSource.latest();

    act(() => {
      source.emit('error', { reason: 'view_runner_logs_not_allowed' });
    });

    expect(screen.getByTestId('step-log-error')).toHaveTextContent(
      'You do not have permission to view these logs.',
    );
    expect(screen.getByTestId('step-log-mode')).toHaveTextContent('Error');
    await waitFor(() =>
      expect(screen.getByTestId('step-log-announcer')).toHaveTextContent(
        'The runner log stream could not be loaded.',
      ),
    );
  });

  it('has zero WCAG 2.1 AA axe violations on the live viewer (AC8)', async () => {
    const { container } = render(<StepExecutionLogViewer workflowRunId="run_logview00004" />);
    const source = MockEventSource.latest();
    act(() => {
      source.emit('status', { phase: 'live', rex: 'rex_logview00004' });
      source.emit('log', { stream: 'stdout', line: 'step started', seq: 0 });
    });
    await expectNoA11yViolations(container);
  });
});
