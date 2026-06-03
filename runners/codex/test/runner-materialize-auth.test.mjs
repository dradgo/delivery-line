// Story 3a-3 — unit tier for `runner.mjs materialize-auth` (Codex subscription auth).
//
// Runs the dependency-free helper as a subprocess (it reads process.env and calls
// process.exit, so it is exercised exactly as the entrypoint invokes it). Proves:
//   * a valid auth.json object is written atomically with mode 0600 (POSIX),
//   * the secret value NEVER appears on stdout/stderr (name+presence only),
//   * absent / blank / non-JSON / non-object / empty-object input fails with the
//     documented non-zero auth-family exit code and writes no file.
//
// Run locally: `node --test runners/codex/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, existsSync, statSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');
const MATERIALIZE_AUTH_EXIT = 21; // documented auth-family code (malformed/empty CODEX_AUTH_JSON)

function run(authJsonEnv, { withOut = true } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'codex-auth-'));
  const out = join(dir, 'auth.json');
  const env = { ...process.env };
  delete env.CODEX_AUTH_JSON;
  if (authJsonEnv !== undefined) env.CODEX_AUTH_JSON = authJsonEnv;
  const args = ['materialize-auth'];
  if (withOut) args.push('--out', out);
  const result = spawnSync(process.execPath, [RUNNER, ...args], { env, encoding: 'utf8' });
  return { dir, out, result };
}

test('valid auth.json object is written with 0600 and value never leaks', () => {
  const sentinel = 'SENTINEL-OAUTH-9f3a2b';
  const payload = JSON.stringify({ tokens: { access_token: sentinel }, account_id: 'acct_1' });
  const { dir, out, result } = run(payload);
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    assert.ok(existsSync(out), 'auth.json materialized');
    assert.equal(readFileSync(out, 'utf8'), payload, 'content written verbatim');
    // 0600 on POSIX; Windows fs mode bits are not meaningful (mirrors the conformance IT).
    if (process.platform !== 'win32') {
      assert.equal(statSync(out).mode & 0o777, 0o600, 'file mode is 0600');
    }
    // Leak discipline: the secret content must NOT appear on stdout/stderr.
    assert.ok(!result.stdout.includes(sentinel), 'stdout must not leak the value');
    assert.ok(!result.stderr.includes(sentinel), 'stderr must not leak the value');
    // No stray tmp file left behind.
    assert.ok(!existsSync(`${out}.tmp`), 'atomic temp file cleaned up');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('absent CODEX_AUTH_JSON fails with the auth-family code and writes no file', () => {
  const { dir, out, result } = run(undefined);
  try {
    assert.equal(result.status, MATERIALIZE_AUTH_EXIT);
    assert.ok(!existsSync(out), 'no file written on failure');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('blank CODEX_AUTH_JSON fails with the auth-family code', () => {
  const { dir, out, result } = run('   ');
  try {
    assert.equal(result.status, MATERIALIZE_AUTH_EXIT);
    assert.ok(!existsSync(out));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('non-JSON CODEX_AUTH_JSON fails without leaking the value', () => {
  const garbage = 'not-json-LEAKME-1234';
  const { dir, out, result } = run(garbage);
  try {
    assert.equal(result.status, MATERIALIZE_AUTH_EXIT);
    assert.ok(!existsSync(out));
    assert.ok(!result.stdout.includes(garbage), 'stdout must not echo the bad value');
    assert.ok(!result.stderr.includes(garbage), 'stderr must not echo the bad value');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('empty object {} fails (a non-empty object is required)', () => {
  const { dir, out, result } = run('{}');
  try {
    assert.equal(result.status, MATERIALIZE_AUTH_EXIT);
    assert.ok(!existsSync(out));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('non-object JSON (array / scalar) fails', () => {
  for (const bad of ['[1,2,3]', '42', '"a string"', 'null']) {
    const { dir, out, result } = run(bad);
    try {
      assert.equal(result.status, MATERIALIZE_AUTH_EXIT, `input=${bad}`);
      assert.ok(!existsSync(out), `no file for input=${bad}`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('missing --out is a usage error (exit 2)', () => {
  const { dir, result } = run(JSON.stringify({ ok: true }), { withOut: false });
  try {
    assert.equal(result.status, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
