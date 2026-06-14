/**
 * Story 3.27 (Task 2 / AC2, AC5) — pure unified-diff parser.
 *
 * Splits a unified-diff string into files → hunks → lines so the
 * {@link SafeUnifiedDiffRenderer} can render a file-grouped accordion. Pure, no JSX —
 * unit-tested independently (mirrors how `deriveSectionAnchors` is a pure sibling of the
 * spec renderer). The input is UNTRUSTED runner output; this module ONLY classifies +
 * splits text — every emitted string is rendered through `renderTextWithRedactions` at
 * the primitive boundary, never interpreted as HTML.
 *
 * Defensive by construction: a malformed/headerless diff must NOT throw — it falls back
 * to a single synthetic file whose lines are classified by their `+`/`-`/space prefix
 * (or treated as context when prefix-less). Lives in `src/lib/sanitization/` so the
 * untrusted-text→DOM concern stays inside the sanctioned package (story 2.24 boundary).
 */

/** AC5 file-count pagination threshold (files beyond the first page are not rendered). */
export const PR_DIFF_MAX_FILES = 50;
/** AC5 changed-line threshold (a single file's expansion is capped past this). */
export const PR_DIFF_MAX_LINES = 5000;

export type DiffLineKind = 'added' | 'removed' | 'context';

export interface ParsedDiffLine {
  readonly kind: DiffLineKind;
  /** The line text WITHOUT its `+`/`-`/space prefix (UNTRUSTED). */
  readonly text: string;
}

export interface ParsedDiffHunk {
  /** The raw `@@ … @@` header (UNTRUSTED); empty string for a synthetic/headerless hunk. */
  readonly header: string;
  readonly lines: readonly ParsedDiffLine[];
}

export interface ParsedDiffFile {
  /** The (new) file path — derived from `diff --git b/…` or `+++ b/…` (UNTRUSTED). */
  readonly path: string;
  /** The old path when a rename/delete makes it differ from `path` (UNTRUSTED). */
  readonly oldPath?: string;
  readonly additions: number;
  readonly deletions: number;
  readonly hunks: readonly ParsedDiffHunk[];
  /** True for a binary file (`Binary files … differ` / `GIT binary patch`) — no textual hunks. */
  readonly binary?: boolean;
}

interface MutableHunk {
  header: string;
  lines: ParsedDiffLine[];
}

interface MutableFile {
  path: string;
  oldPath?: string;
  additions: number;
  deletions: number;
  hunks: MutableHunk[];
  binary: boolean;
}

/** Strip the `a/`/`b/` prefix (and any trailing tab-timestamp git appends); keep `/dev/null`. */
function stripDiffPath(raw: string): string {
  const trimmed = raw.replace(/\t.*$/, '').trim();
  if (trimmed === '/dev/null') {
    return trimmed;
  }
  return trimmed.replace(/^[ab]\//, '');
}

/** Parse a `diff --git a/<old> b/<new>` header into its two paths (best-effort). */
function parseGitHeaderPaths(line: string): { path: string; oldPath: string | undefined } {
  const match = /^diff --git a\/(.*) b\/(.*)$/.exec(line);
  if (match !== null) {
    return { path: match[2] ?? '', oldPath: match[1] };
  }
  // Malformed header — keep whatever follows the marker as a best-effort path.
  return { path: line.slice('diff --git '.length).trim(), oldPath: undefined };
}

/** Pre-hunk metadata lines that carry no rendered content. */
function isMetadataLine(line: string): boolean {
  return (
    line.startsWith('index ') ||
    line.startsWith('new file mode') ||
    line.startsWith('deleted file mode') ||
    line.startsWith('old mode') ||
    line.startsWith('new mode') ||
    line.startsWith('similarity index') ||
    line.startsWith('dissimilarity index') ||
    line.startsWith('rename from') ||
    line.startsWith('rename to') ||
    line.startsWith('copy from') ||
    line.startsWith('copy to')
  );
}

/**
 * Parse a unified diff into files → hunks → lines. Never throws — unrecognized input
 * degrades to a single synthetic file (AC5 defensiveness).
 */
export function parseUnifiedDiff(diff: string): ParsedDiffFile[] {
  if (diff.trim() === '') {
    return [];
  }

  const files: MutableFile[] = [];
  let current: MutableFile | null = null;
  let currentHunk: MutableHunk | null = null;

  // Pure helper that ONLY appends to `files` — `current`/`currentHunk` are mutated
  // directly at each call site so TS flow analysis tracks their nullability (a closure
  // that reassigned them would defeat the narrowing of the `=== null` guards below).
  const pushFile = (path: string, oldPath?: string): MutableFile => {
    // Build conditionally so `oldPath` is omitted (not set to `undefined`) under
    // `exactOptionalPropertyTypes`.
    const file: MutableFile =
      oldPath === undefined
        ? { path, additions: 0, deletions: 0, hunks: [], binary: false }
        : { path, oldPath, additions: 0, deletions: 0, hunks: [], binary: false };
    files.push(file);
    return file;
  };

  for (const line of diff.split('\n')) {
    if (line.startsWith('diff --git')) {
      const { path, oldPath } = parseGitHeaderPaths(line);
      current = pushFile(path, oldPath);
      currentHunk = null;
      continue;
    }

    // `--- `/`+++ ` and metadata are file-level headers ONLY before a hunk opens; once a
    // hunk is receiving lines, a leading `+`/`-` is content (a diff that adds a line of
    // text like `+++ foo` is then correctly classified as an addition).
    if (currentHunk === null && line.startsWith('--- ')) {
      if (current === null) {
        current = pushFile('');
      }
      current.oldPath = stripDiffPath(line.slice(4));
      continue;
    }
    if (currentHunk === null && line.startsWith('+++ ')) {
      const path = stripDiffPath(line.slice(4));
      // `+++ /dev/null` marks a deletion — keep the old path (from `--- a/<path>`) as the
      // display path instead of showing `/dev/null`.
      if (current === null) {
        current = pushFile(path === '/dev/null' ? '' : path);
      } else if (current.path === '' && path !== '/dev/null') {
        current.path = path;
      }
      continue;
    }
    if (currentHunk === null && isMetadataLine(line)) {
      continue;
    }

    // Binary files carry no textual hunk — record the flag and skip the marker line so it is
    // not mis-rendered as a 0/0 context line.
    if (
      currentHunk === null &&
      (line.startsWith('Binary files') || line.startsWith('GIT binary patch'))
    ) {
      if (current === null) {
        current = pushFile('');
      }
      current.binary = true;
      continue;
    }

    if (line.startsWith('@@')) {
      if (current === null) {
        current = pushFile('');
      }
      currentHunk = { header: line, lines: [] };
      current.hunks.push(currentHunk);
      continue;
    }

    // "\ No newline at end of file" is a marker INSIDE a hunk — never rendered content.
    if (line.startsWith('\\ No newline')) {
      continue;
    }

    // Content line. A headerless diff (no `diff --git`/`@@`) lands here first → synthesize
    // a file + hunk so its `+`/`-`/context prefixes still render (AC5 fallback).
    if (current === null) {
      current = pushFile('');
    }
    if (currentHunk === null) {
      currentHunk = { header: '', lines: [] };
      current.hunks.push(currentHunk);
    }
    if (line.startsWith('+')) {
      currentHunk.lines.push({ kind: 'added', text: line.slice(1) });
      current.additions += 1;
    } else if (line.startsWith('-')) {
      currentHunk.lines.push({ kind: 'removed', text: line.slice(1) });
      current.deletions += 1;
    } else if (line.startsWith(' ')) {
      currentHunk.lines.push({ kind: 'context', text: line.slice(1) });
    } else {
      // Prefix-less (headerless-fallback) line — render verbatim as context.
      currentHunk.lines.push({ kind: 'context', text: line });
    }
  }

  // Drop a trailing empty synthetic file that a final newline can leave behind.
  return files.map((file) => {
    const resolvedPath = file.path === '' ? (file.oldPath ?? '(unknown file)') : file.path;
    return {
      path: resolvedPath,
      ...(file.oldPath !== undefined && file.oldPath !== resolvedPath
        ? { oldPath: file.oldPath }
        : {}),
      additions: file.additions,
      deletions: file.deletions,
      hunks: file.hunks,
      ...(file.binary ? { binary: true } : {}),
    };
  });
}

/** Total changed (added + removed) lines across all files — drives the AC5 line threshold. */
export function countChangedLines(files: readonly ParsedDiffFile[]): number {
  return files.reduce((total, file) => total + file.additions + file.deletions, 0);
}
