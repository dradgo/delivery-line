// Story 2.22 AC11.t — RuleTester for `no-untyped-loading-state`. Models
// no-unsanitized-html.test.js (node --test + RuleTester).
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-untyped-loading-state.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

test('no-untyped-loading-state', () => {
  ruleTester.run('no-untyped-loading-state', rule, {
    valid: [
      {
        code: 'function V() { return <LoadingState variant="fetchingData" />; }',
        filename: 'src/features/workflows/V.tsx',
      },
      {
        code: 'function Spin() { return <div className="size-4 animate-spin" />; }',
        filename: 'src/components/feedback/states/LoadingState.tsx',
      },
      {
        code: 'function S() { return <Spinner />; }',
        filename: 'src/components/ui/spinner.tsx',
      },
      {
        // A JSX spread may supply `variant` — the rule cannot prove its absence,
        // so it must not false-positive.
        code: 'function V2(props) { return <LoadingState {...props} />; }',
        filename: 'src/features/workflows/V2.tsx',
      },
    ],
    invalid: [
      {
        // Namespaced/member-expression spinner outside the trusted boundary.
        code: 'function Bad4() { return <Icons.Spinner />; }',
        filename: 'src/features/workflows/Bad4.tsx',
        errors: [{ messageId: 'rawSpinner' }],
      },
      {
        code: 'function Bad() { return <LoadingState />; }',
        filename: 'src/features/workflows/Bad.tsx',
        errors: [{ messageId: 'missingVariant' }],
      },
      {
        code: 'function Bad2() { return <div className="animate-spin" />; }',
        filename: 'src/features/workflows/Bad2.tsx',
        errors: [{ messageId: 'rawSpinner' }],
      },
      {
        code: 'function Bad3() { return <LoadingSpinner />; }',
        filename: 'src/features/workflows/Bad3.tsx',
        errors: [{ messageId: 'rawSpinner' }],
      },
    ],
  });
});
