// Story 2.31 AC11 — fixture tests proving `no-inline-query-keys` catches
// ad-hoc inline array query keys. RuleTester + node --test (no Vitest yet).
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-inline-query-keys.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
  },
});

test('no-inline-query-keys', () => {
  ruleTester.run('no-inline-query-keys', rule, {
    valid: [
      // factory call — the sanctioned pattern
      { code: 'useQuery({ queryKey: workflowKeys.detail(id), queryFn: fn });' },
      { code: 'useMutation({ mutationKey: workflowKeys.approve(id), mutationFn: fn });' },
      // identifier reference (a key built elsewhere) is allowed
      { code: 'useQuery({ queryKey: key, queryFn: fn });' },
      // unrelated call with an array arg is untouched
      { code: 'doSomething({ queryKey: ["x"] });' },
    ],
    invalid: [
      {
        code: 'useQuery({ queryKey: ["workflows", id], queryFn: fn });',
        errors: [{ messageId: 'inlineKey' }],
      },
      {
        code: 'useInfiniteQuery({ queryKey: ["workflows", "list"], queryFn: fn });',
        errors: [{ messageId: 'inlineKey' }],
      },
      {
        code: 'useMutation({ mutationKey: ["approve"], mutationFn: fn });',
        errors: [{ messageId: 'inlineKey' }],
      },
    ],
  });
});
