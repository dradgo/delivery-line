// Story 2.23 (AC3.t) — RuleTester for `no-confirmation-for-navigation`.
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-confirmation-for-navigation.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

test('no-confirmation-for-navigation', () => {
  ruleTester.run('no-confirmation-for-navigation', rule, {
    valid: [
      {
        // Confirm handler performs a mutation — a genuine consequence.
        code: 'function C() { return <ConfirmationDialog onConfirm={() => rejectSpec()} />; }',
        filename: 'src/features/workflows/C.tsx',
      },
      {
        // Navigation PLUS another effect — suppressed (conservative).
        code: 'function C() { return <ConfirmationDialog onConfirm={() => { recordDecision(); navigate("/queue"); }} />; }',
        filename: 'src/features/workflows/C.tsx',
      },
      {
        // Not a <ConfirmationDialog> — out of scope.
        code: 'function C() { return <SomethingElse onConfirm={() => navigate("/queue")} />; }',
        filename: 'src/features/workflows/C.tsx',
      },
    ],
    invalid: [
      {
        code: 'function C() { return <ConfirmationDialog onConfirm={() => navigate("/queue")} />; }',
        filename: 'src/features/workflows/C.tsx',
        errors: [{ messageId: 'confirmationForNavigation' }],
      },
      {
        code: 'function C() { return <ConfirmationDialog onConfirm={() => router.navigate("/queue")} />; }',
        filename: 'src/features/workflows/C.tsx',
        errors: [{ messageId: 'confirmationForNavigation' }],
      },
      {
        // Compare-entry-only confirm.
        code: 'function C() { return <ConfirmationDialog onConfirm={() => { enterCompare(); }} />; }',
        filename: 'src/features/workflows/C.tsx',
        errors: [{ messageId: 'confirmationForNavigation' }],
      },
    ],
  });
});
