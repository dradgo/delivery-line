/**
 * Story 3.27 (Task 7 / AC2) — githubRef pure-helper tests.
 *
 * PR-reference parsing + URL building. The branch/commit URLs derive their repo identity
 * from the BACKEND-TRUTH owner/repo (AC3) and encode untrusted segments.
 */
import { describe, expect, it } from 'vitest';

import {
  branchUrl,
  commitUrl,
  isGitHubHttpsUrl,
  parsePrReference,
  prUrl,
  shortSha,
} from './githubRef';

describe('parsePrReference', () => {
  it('parses an org/repo#number reference', () => {
    expect(parsePrReference('acme/widgets#42')).toEqual({
      owner: 'acme',
      repo: 'widgets',
      number: 42,
    });
  });

  it('trims surrounding whitespace', () => {
    expect(parsePrReference('  acme/widgets#7  ')?.number).toBe(7);
  });

  it('returns null for non-matching shapes', () => {
    expect(parsePrReference('https://github.com/acme/widgets/pull/42')).toBeNull();
    expect(parsePrReference('acme/widgets')).toBeNull();
    expect(parsePrReference('acme#42')).toBeNull();
    expect(parsePrReference('')).toBeNull();
  });
});

describe('shortSha', () => {
  it('truncates to 7 chars and leaves short input untouched', () => {
    expect(shortSha('3f9a1c2b4e6d8033')).toBe('3f9a1c2');
    expect(shortSha('abc123')).toBe('abc123');
  });
});

describe('URL builders', () => {
  it('builds a canonical PR URL', () => {
    expect(prUrl({ owner: 'acme', repo: 'widgets', number: 42 })).toBe(
      'https://github.com/acme/widgets/pull/42',
    );
  });

  it('builds a branch tree URL keeping slashes but encoding unsafe chars', () => {
    expect(branchUrl('acme', 'widgets', 'feature/del-9002')).toBe(
      'https://github.com/acme/widgets/tree/feature/del-9002',
    );
    expect(branchUrl('acme', 'widgets', 'feature/a b')).toBe(
      'https://github.com/acme/widgets/tree/feature/a%20b',
    );
  });

  it('builds a commit URL from backend-truth owner/repo', () => {
    expect(commitUrl('acme', 'widgets', '3f9a1c2b')).toBe(
      'https://github.com/acme/widgets/commit/3f9a1c2b',
    );
  });
});

describe('isGitHubHttpsUrl', () => {
  it('accepts an absolute https github.com URL', () => {
    expect(isGitHubHttpsUrl('https://github.com/acme/widgets/pull/42')).toBe(true);
  });

  it('rejects javascript:, non-https, off-GitHub, and unparseable values', () => {
    expect(isGitHubHttpsUrl('javascript:alert(1)')).toBe(false);
    expect(isGitHubHttpsUrl('http://github.com/acme/widgets/pull/42')).toBe(false);
    expect(isGitHubHttpsUrl('https://evil.example/acme/widgets/pull/42')).toBe(false);
    expect(isGitHubHttpsUrl('https://github.com.evil.example/x')).toBe(false);
    expect(isGitHubHttpsUrl('not a url')).toBe(false);
    expect(isGitHubHttpsUrl('')).toBe(false);
  });
});
