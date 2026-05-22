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
      // simple indirection is allowed if it still resolves to a factory call
      {
        code: 'const key = workflowKeys.detail(id); useQuery({ queryKey: key, queryFn: fn });',
      },
      {
        code: 'const options = { queryKey: workflowKeys.detail(id), queryFn: fn }; useQuery(options);',
      },
      // unrelated call with an array arg is untouched
      { code: 'doSomething({ queryKey: ["x"] });' },
      // Story 2.6 (AC4) — real-shaped query hook using the workflowKeys factory PASSES.
      {
        code: [
          "import { useQuery } from '@tanstack/react-query';",
          "import { workflowKeys } from '@/lib/queryKeys/workflowKeys';",
          'export function useWorkflowDetail(workflowRunId) {',
          '  return useQuery({',
          '    queryKey: workflowKeys.detail(workflowRunId),',
          '    queryFn: () => fetchWorkflowDetail(workflowRunId),',
          '  });',
          '}',
        ].join('\n'),
      },
    ],
    invalid: [
      // Story 2.6 (AC4) — real-shaped query hook with an AD-HOC inline array key FAILS.
      {
        code: [
          "import { useQuery } from '@tanstack/react-query';",
          'export function useWorkflowDetail(workflowRunId) {',
          '  return useQuery({',
          "    queryKey: ['workflows', 'detail', workflowRunId],",
          '    queryFn: () => fetchWorkflowDetail(workflowRunId),',
          '  });',
          '}',
        ].join('\n'),
        errors: [{ messageId: 'nonFactoryKey' }],
      },
      {
        code: 'useQuery({ queryKey: ["workflows", id], queryFn: fn });',
        errors: [{ messageId: 'nonFactoryKey' }],
      },
      {
        code: 'useInfiniteQuery({ queryKey: ["workflows", "list"], queryFn: fn });',
        errors: [{ messageId: 'nonFactoryKey' }],
      },
      {
        code: 'useMutation({ mutationKey: ["approve"], mutationFn: fn });',
        errors: [{ messageId: 'nonFactoryKey' }],
      },
      {
        code: 'const key = ["workflows", id] as const; useQuery({ queryKey: key, queryFn: fn });',
        errors: [{ messageId: 'nonFactoryKey' }],
      },
      {
        code: 'const options = { queryKey: ["workflows", id], queryFn: fn }; useQuery(options);',
        errors: [{ messageId: 'nonFactoryKey' }],
      },
      {
        code: 'useQuery({ "queryKey": ["workflows", id], queryFn: fn });',
        errors: [{ messageId: 'nonFactoryKey' }],
      },
      {
        code: "useQuery({ ['queryKey']: ['workflows', id], queryFn: fn });",
        errors: [{ messageId: 'nonFactoryKey' }],
      },
    ],
  });
});
