/**
 * Story 3g-5 (FR74, AC3) — pure unit coverage for the Step Execution Log Viewer readability
 * projection. Recognized codex/claude JSONL events -> readable text; non-JSON -> verbatim; malformed
 * -> verbatim (never throws, never a raw JSON blob for a recognized event).
 */
import { describe, expect, it } from 'vitest';

import { projectRunnerLogLine } from './stepLogLineView';

describe('projectRunnerLogLine (story 3g-5)', () => {
  it('projects a codex agent_message event to its text', () => {
    const line = JSON.stringify({
      type: 'item.completed',
      item: { id: 'item_1', type: 'agent_message', text: 'The reconstructed plan text.' },
    });
    expect(projectRunnerLogLine(line)).toBe('The reconstructed plan text.');
  });

  it('projects a codex command_execution to a compact $ label with exit code (not raw JSON)', () => {
    const line = JSON.stringify({
      type: 'item.completed',
      item: {
        type: 'command_execution',
        command: 'npm run build\n--extra',
        exit_code: 0,
        status: 'completed',
      },
    });
    const out = projectRunnerLogLine(line);
    expect(out).toBe('$ npm run build (exit 0)');
    expect(out).not.toContain('{');
  });

  it('projects a claude assistant text message to its text', () => {
    const line = JSON.stringify({
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Working through the edge cases.' }] },
    });
    expect(projectRunnerLogLine(line)).toBe('Working through the edge cases.');
  });

  it('projects a claude tool_use block to a compact tool label', () => {
    const line = JSON.stringify({
      type: 'assistant',
      message: { content: [{ type: 'tool_use', name: 'Bash', input: { command: 'ls -la' } }] },
    });
    const out = projectRunnerLogLine(line);
    expect(out).toBe('⚙ Bash: ls -la');
    expect(out).not.toContain('{');
  });

  it('projects a claude result event to its final result text', () => {
    const line = JSON.stringify({
      type: 'result',
      subtype: 'success',
      result: 'The final specification content.',
      usage: { input_tokens: 10, output_tokens: 5 },
    });
    expect(projectRunnerLogLine(line)).toBe('The final specification content.');
  });

  it('renders a recognized-but-textless event as a compact label, never a raw JSON blob', () => {
    expect(projectRunnerLogLine(JSON.stringify({ type: 'turn.completed', usage: {} }))).toBe(
      '· turn completed',
    );
    expect(projectRunnerLogLine(JSON.stringify({ type: 'system', subtype: 'init' }))).toBe(
      '· session started',
    );
  });

  it('returns a plain-text (non-JSON) line VERBATIM', () => {
    expect(projectRunnerLogLine('compiling sources')).toBe('compiling sources');
    expect(projectRunnerLogLine('warning: deprecated flag')).toBe('warning: deprecated flag');
  });

  it('returns a redacted persisted line verbatim (viewer never re-derives redaction)', () => {
    expect(projectRunnerLogLine('[REDACTED_AUTHORIZATION_HEADER]')).toBe(
      '[REDACTED_AUTHORIZATION_HEADER]',
    );
  });

  it('returns a malformed / half-JSON line VERBATIM without throwing', () => {
    const half = '{"type":"assistant","message":{"content":[{"type":"text"';
    expect(() => projectRunnerLogLine(half)).not.toThrow();
    expect(projectRunnerLogLine(half)).toBe(half);
  });

  it('returns a typeless JSON object verbatim (not a recognized event)', () => {
    const line = JSON.stringify({ foo: 'bar' });
    expect(projectRunnerLogLine(line)).toBe(line);
  });
  it('returns an unknown typed JSON object verbatim so diagnostic payloads stay visible', () => {
    const line = JSON.stringify({ type: 'error', message: 'Claude stream failed' });
    expect(projectRunnerLogLine(line)).toBe(line);
  });

  it('returns an empty line verbatim', () => {
    expect(projectRunnerLogLine('')).toBe('');
  });
});
