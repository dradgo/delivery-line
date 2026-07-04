/**
 * Story 3g-5 (FR74, AC3) — Step Execution Log Viewer readability projection.
 *
 * The runner now invokes the agent CLI in a JSON event mode (codex `--json`, claude
 * `--output-format stream-json`), so `runner.stdout` is a JSONL event stream rather than streaming
 * prose. Rendered verbatim the viewer would be a wall of `{"type":...}` lines. This pure mapper
 * projects each line to human-readable display text:
 *
 *   - a recognized codex/claude event -> a readable projection (the agent message / reasoning /
 *     result text, or a compact command/tool summary — never a raw JSON blob);
 *   - a line that does NOT parse as JSON (claude text mode, offline mocks, legacy stdout, the
 *     runner's own stderr markers) -> returned VERBATIM so no plain-text stream regresses;
 *   - a JSON object with no recognized `type` -> returned verbatim (we never invent a projection).
 *
 * NEVER throws. This is a render-time presentation concern only: the backend still applies the
 * authoritative post-hoc redaction (ADR 0025); the viewer never re-derives redaction here.
 *
 * Pure module (no React) so the `.tsx` component keeps a clean react-refresh fn-export surface.
 */

const MAX_COMMAND_LEN = 200;

function firstLine(text: string): string {
  const nl = text.indexOf('\n');
  return nl === -1 ? text : text.slice(0, nl);
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

/** A recognized codex item (`item.started` / `item.completed`) -> readable text. */
function projectCodexItem(item: Record<string, unknown>, eventType: string): string | undefined {
  const itemType = typeof item.type === 'string' ? item.type : undefined;
  if (itemType === 'agent_message' || itemType === 'reasoning') {
    const text = typeof item.text === 'string' ? item.text : '';
    return text.length > 0 ? text : `· ${itemType}`;
  }
  if (itemType === 'command_execution') {
    const command =
      typeof item.command === 'string' ? truncate(firstLine(item.command), MAX_COMMAND_LEN) : '';
    const running = eventType === 'item.started' || item.status === 'in_progress';
    const exit = typeof item.exit_code === 'number' ? ` (exit ${item.exit_code})` : '';
    const status = running ? ' (running)' : exit;
    return command.length > 0 ? `$ ${command}${status}` : `· command_execution${status}`;
  }
  // A recognized-but-unmapped item type: a compact label, never the raw blob.
  return itemType !== undefined ? `· ${itemType}` : undefined;
}

/** A claude `assistant`/`user` message -> its concatenated text blocks / compact tool labels. */
function projectClaudeMessage(message: unknown): string | undefined {
  if (message === null || typeof message !== 'object') return undefined;
  const content = (message as Record<string, unknown>).content;
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return undefined;
  const parts: string[] = [];
  for (const block of content) {
    if (block === null || typeof block !== 'object') continue;
    const b = block as Record<string, unknown>;
    if (b.type === 'text' && typeof b.text === 'string') {
      parts.push(b.text);
    } else if (b.type === 'tool_use') {
      const name = typeof b.name === 'string' ? b.name : 'tool';
      const input = b.input as Record<string, unknown> | undefined;
      const command =
        input && typeof input.command === 'string'
          ? `: ${truncate(firstLine(input.command), MAX_COMMAND_LEN)}`
          : '';
      parts.push(`⚙ ${name}${command}`);
    } else if (b.type === 'tool_result') {
      parts.push('· tool result');
    }
  }
  if (parts.length === 0) return '· message';
  return parts.join('\n');
}

function projectEvent(evt: Record<string, unknown>): string | undefined {
  const type = typeof evt.type === 'string' ? evt.type : undefined;
  if (type === undefined) return undefined; // not a recognized event shape -> caller renders verbatim

  // ---- codex `--json` events ----
  if (type === 'item.completed' || type === 'item.started') {
    const item = evt.item;
    if (item !== null && typeof item === 'object' && !Array.isArray(item)) {
      return projectCodexItem(item as Record<string, unknown>, type);
    }
    return `· ${type}`;
  }
  if (type === 'thread.started') return '· session started';
  if (type === 'turn.started') return '· turn started';
  if (type === 'turn.completed') return '· turn completed';

  // ---- claude `stream-json` events ----
  if (type === 'assistant' || type === 'user') {
    return projectClaudeMessage(evt.message) ?? `· ${type}`;
  }
  if (type === 'result') {
    if (typeof evt.result === 'string' && evt.result.length > 0) return evt.result;
    const subtype = typeof evt.subtype === 'string' ? evt.subtype : 'done';
    return `· result (${subtype})`;
  }
  if (type === 'system') return '· session started';

  // Unknown typed JSON may carry diagnostic payloads (for example error/message fields). Leave it
  // verbatim instead of hiding useful operator context behind a generic label.
  return undefined;
}

/**
 * Project a single `runner.stdout` line to display text. Non-JSON lines are returned verbatim;
 * recognized codex/claude event lines are projected to readable text. Never throws.
 */
export function projectRunnerLogLine(line: string): string {
  const trimmed = line.trim();
  // Only object-shaped lines can be events; anything else (prose, stderr markers, `[` arrays,
  // half-JSON) is returned verbatim.
  if (trimmed.length === 0 || trimmed[0] !== '{') return line;
  let evt: unknown;
  try {
    evt = JSON.parse(trimmed);
  } catch {
    return line; // half-/non-JSON -> verbatim (no crash)
  }
  if (evt === null || typeof evt !== 'object' || Array.isArray(evt)) return line;
  const projected = projectEvent(evt as Record<string, unknown>);
  return projected ?? line;
}
