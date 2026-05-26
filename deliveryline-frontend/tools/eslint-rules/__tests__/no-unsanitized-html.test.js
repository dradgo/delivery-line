// Story 2.24 AC11 — fixture tests proving `no-unsanitized-html` blocks every
// escape hatch that could leak untrusted HTML into the UI. Code review P6
// (2026-05-26) added coverage for computed-property innerHTML assignment,
// JSX spread attribute, insertAdjacentHTML, and document.write/writeln.
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-unsanitized-html.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

test('no-unsanitized-html', () => {
  ruleTester.run('no-unsanitized-html', rule, {
    valid: [
      // Sanctioned wrapper usage — passes through the safe renderer.
      {
        code: "import { SafeMarkdownRenderer } from '@/lib/sanitization';\nfunction Body({ source }) {\n  return <SafeMarkdownRenderer source={source} />;\n}",
        filename: 'src/features/workflows/Body.tsx',
      },
      // Reading innerHTML is fine — only assignment is dangerous.
      {
        code: "function inspect(el) { return el.innerHTML; }",
        filename: 'src/features/workflows/inspect.ts',
      },
      // Direct assignment INSIDE the sanitization package is the allowed
      // trusted boundary (Trap T10).
      {
        code: "function applyShiki(target, html) { target.innerHTML = html; }",
        filename: 'src/lib/sanitization/shiki-wire.ts',
      },
      {
        code: 'function Cell({ html }) { return <div dangerouslySetInnerHTML={{ __html: html }} />; }',
        filename: 'src/lib/sanitization/SafeMarkdownRenderer.tsx',
      },
    ],
    invalid: [
      {
        code: 'function Cell({ html }) { return <div dangerouslySetInnerHTML={{ __html: html }} />; }',
        filename: 'src/features/workflows/EvilCell.tsx',
        errors: [{ messageId: 'dangerouslySetInnerHTML' }],
      },
      {
        code: "function leak(el, payload) { el.innerHTML = payload; }",
        filename: 'src/features/workflows/leak.ts',
        errors: [{ messageId: 'innerHTMLAssignment' }],
      },
      {
        code: "function leakOuter(el, payload) { el.outerHTML = payload; }",
        filename: 'src/features/workflows/leak.ts',
        errors: [{ messageId: 'innerHTMLAssignment' }],
      },
      // P6 — computed property assignment via string literal.
      {
        code: "function leakComputed(el, payload) { el['innerHTML'] = payload; }",
        filename: 'src/features/workflows/leak.ts',
        errors: [{ messageId: 'innerHTMLAssignment' }],
      },
      // P6 — computed property assignment via template literal.
      {
        code: 'function leakTemplate(el, payload) { el[`outerHTML`] = payload; }',
        filename: 'src/features/workflows/leak.ts',
        errors: [{ messageId: 'innerHTMLAssignment' }],
      },
      // P6 — insertAdjacentHTML method call.
      {
        code: "function leakAdjacent(el, payload) { el.insertAdjacentHTML('beforeend', payload); }",
        filename: 'src/features/workflows/leak.ts',
        errors: [{ messageId: 'htmlSink' }],
      },
      // P6 — document.write call.
      {
        code: "function leakWrite(payload) { document.write(payload); }",
        filename: 'src/features/workflows/leak.ts',
        errors: [{ messageId: 'htmlSink' }],
      },
      // P6 — document.writeln call.
      {
        code: "function leakWriteln(payload) { document.writeln(payload); }",
        filename: 'src/features/workflows/leak.ts',
        errors: [{ messageId: 'htmlSink' }],
      },
      // P6 — JSX spread attribute with inline object literal.
      {
        code: 'function Evil({ html }) { return <div {...{ dangerouslySetInnerHTML: { __html: html } }} />; }',
        filename: 'src/features/workflows/Evil.tsx',
        errors: [{ messageId: 'dangerouslySetInnerHTML' }],
      },
    ],
  });
});
