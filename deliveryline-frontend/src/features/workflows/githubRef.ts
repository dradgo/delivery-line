/**
 * Story 3.27 (Task 3 / AC2) — GitHub reference parsing + URL builders.
 *
 * Pure `.ts` (no JSX) so a non-component export does NOT trip the
 * `frontend-react-refresh-no-fn-exports` ESLint gate. The repo identity (`owner/repo`)
 * is derived from the BACKEND-TRUTH `prLinkage.prReference` (story 3.27 AC3) — the
 * branch/commit VALUES are runner-emitted (untrusted) but the repo the branch/commit
 * URLs point at comes from backend truth, so a spoofed runner value can never redirect
 * the link to an attacker-chosen repo.
 *
 * Story 3.31 (PR linkage in Run Context Strip + Queue) consumes these same helpers for
 * visual consistency — keep them focused + reusable.
 */

const GITHUB_BASE = 'https://github.com';

export interface ParsedPrReference {
  readonly owner: string;
  readonly repo: string;
  readonly number: number;
}

/**
 * Parse a `org/repo#42` PR reference (story 3.15 AC1 shape) into its parts. Returns
 * `null` for any value that does not match — callers then suppress the derived links
 * rather than emitting a malformed URL.
 */
export function parsePrReference(reference: string): ParsedPrReference | null {
  const match = /^([^/\s]+)\/([^/#\s]+)#(\d+)$/.exec(reference.trim());
  if (match === null) {
    return null;
  }
  const owner = match[1] ?? '';
  const repo = match[2] ?? '';
  const number = Number(match[3]);
  if (owner === '' || repo === '' || !Number.isFinite(number)) {
    return null;
  }
  return { owner, repo, number };
}

/** Short-form a commit SHA to its first 7 chars (AC2 — full SHA kept for the copy button). */
export function shortSha(sha: string): string {
  return sha.length > 7 ? sha.slice(0, 7) : sha;
}

/**
 * Encode each `/`-separated branch segment so a `feature/x` path survives but `?`/spaces are
 * escaped. The `branch` value is UNTRUSTED runner output: `encodeURIComponent` does NOT escape
 * `.`, so empty / `.` / `..` segments are dropped to prevent a spoofed branch (e.g.
 * `../../attacker/evil`) from path-traversing the link to a different repo under github.com.
 */
function encodeBranchPath(branch: string): string {
  return branch
    .split('/')
    .filter((segment) => segment !== '' && segment !== '.' && segment !== '..')
    .map(encodeURIComponent)
    .join('/');
}

/** The canonical GitHub PR URL for a parsed reference. */
export function prUrl(reference: ParsedPrReference): string {
  return `${GITHUB_BASE}/${encodeURIComponent(reference.owner)}/${encodeURIComponent(
    reference.repo,
  )}/pull/${reference.number}`;
}

/** The GitHub branch (tree) URL — repo identity is backend-truth; `branch` is runner-emitted. */
export function branchUrl(owner: string, repo: string, branch: string): string {
  return `${GITHUB_BASE}/${encodeURIComponent(owner)}/${encodeURIComponent(
    repo,
  )}/tree/${encodeBranchPath(branch)}`;
}

/** The GitHub commit URL — repo identity is backend-truth; `sha` is runner-emitted. */
export function commitUrl(owner: string, repo: string, sha: string): string {
  return `${GITHUB_BASE}/${encodeURIComponent(owner)}/${encodeURIComponent(
    repo,
  )}/commit/${encodeURIComponent(sha)}`;
}
