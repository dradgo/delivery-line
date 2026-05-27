/**
 * Story 2.24 AC11 — `no-unsanitized-html`.
 *
 * Untrusted markdown / HTML rendered in the DeliveryLine UI MUST go through
 * the sanctioned `SafeMarkdownRenderer` / `SafeDiffRenderer` / `MetadataChrome`
 * primitives in `src/lib/sanitization/`. This rule flags every escape hatch
 * that could leak untrusted HTML into the DOM:
 *
 *   1. The React `dangerouslySetInnerHTML` JSX attribute (also when spread
 *      as a property — `<div {...{ dangerouslySetInnerHTML: { __html: x } }} />`).
 *   2. Direct assignment to a DOM node's `innerHTML` / `outerHTML` property,
 *      whether via dot syntax (`el.innerHTML = x`) or bracket / computed
 *      property (`el['innerHTML'] = x`).
 *   3. Calls to `el.insertAdjacentHTML(...)` — same risk class, common
 *      alternative for piecemeal HTML insertion (Shiki integration vector).
 *   4. Calls to `document.write(...)` / `document.writeln(...)` — legacy
 *      escape hatches still functional in HTML5.
 *
 * The sanitization package itself is allowed to use these escape hatches —
 * if a transitive dependency (e.g., Shiki) forces an HTML-string injection
 * the renderer's internals are the trusted boundary; Trap T10 explicitly
 * permits the exception inside `src/lib/sanitization/**`. Note that the
 * current sanitization implementation does NOT use any of these hatches —
 * Shiki output flows through `hast-util-to-jsx-runtime` (HAST→React), not
 * via HTML strings — so even the carve-out is academic today.
 *
 * Code review P6 (2026-05-26) — added items 1's spread variant, 2's
 * computed-property variant, and items 3 + 4. Previously only the
 * dot-syntax JSX attribute + direct innerHTML/outerHTML assignment
 * surfaces were flagged.
 */

const SANITIZATION_PACKAGE = /(^|[\\/])src[\\/]lib[\\/]sanitization[\\/]/;
const HTML_ASSIGNMENT_PROPS = new Set(['innerHTML', 'outerHTML']);
const HTML_SINK_METHODS = new Set(['insertAdjacentHTML', 'write', 'writeln']);

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow every path that can inject unsanitized HTML into the DOM outside the sanitization package.',
    },
    schema: [],
    messages: {
      dangerouslySetInnerHTML:
        'Direct use of `dangerouslySetInnerHTML` is forbidden outside src/lib/sanitization/. Use SafeMarkdownRenderer/SafeDiffRenderer instead (story 2.24 AC11).',
      innerHTMLAssignment:
        'Direct assignment to `{{ property }}` is forbidden outside src/lib/sanitization/. Use SafeMarkdownRenderer instead (story 2.24 AC11).',
      htmlSink:
        'Call to `{{ method }}` is forbidden outside src/lib/sanitization/ — it bypasses the sanctioned renderers (story 2.24 AC11 + code-review P6).',
    },
  },
  create(context) {
    const filename = context.filename ?? context.getFilename();
    // Sanitization package is the trusted boundary; rule self-exempts.
    if (SANITIZATION_PACKAGE.test(filename)) {
      return {};
    }
    return {
      JSXAttribute(node) {
        if (
          node.name &&
          node.name.type === 'JSXIdentifier' &&
          node.name.name === 'dangerouslySetInnerHTML'
        ) {
          context.report({ node, messageId: 'dangerouslySetInnerHTML' });
        }
      },
      // Spread-attribute variant: `<div {...{ dangerouslySetInnerHTML: { __html: x } }} />`
      JSXSpreadAttribute(node) {
        if (node.argument && node.argument.type === 'ObjectExpression') {
          for (const property of node.argument.properties) {
            if (
              property.type === 'Property' &&
              property.key &&
              ((property.key.type === 'Identifier' &&
                property.key.name === 'dangerouslySetInnerHTML') ||
                (property.key.type === 'Literal' &&
                  property.key.value === 'dangerouslySetInnerHTML'))
            ) {
              context.report({ node: property, messageId: 'dangerouslySetInnerHTML' });
            }
          }
        }
      },
      AssignmentExpression(node) {
        if (!node.left || node.left.type !== 'MemberExpression' || !node.left.property) {
          return;
        }
        // Dot syntax: el.innerHTML = x  (computed === false, property === Identifier)
        if (
          !node.left.computed &&
          node.left.property.type === 'Identifier' &&
          HTML_ASSIGNMENT_PROPS.has(node.left.property.name)
        ) {
          context.report({
            node,
            messageId: 'innerHTMLAssignment',
            data: { property: node.left.property.name },
          });
          return;
        }
        // Bracket / computed syntax: el['innerHTML'] = x  OR  el[`innerHTML`] = x
        if (node.left.computed && node.left.property.type === 'Literal') {
          if (HTML_ASSIGNMENT_PROPS.has(node.left.property.value)) {
            context.report({
              node,
              messageId: 'innerHTMLAssignment',
              data: { property: String(node.left.property.value) },
            });
          }
        }
        if (
          node.left.computed &&
          node.left.property.type === 'TemplateLiteral' &&
          node.left.property.expressions.length === 0 &&
          node.left.property.quasis.length === 1
        ) {
          const value = node.left.property.quasis[0].value.cooked;
          if (HTML_ASSIGNMENT_PROPS.has(value)) {
            context.report({
              node,
              messageId: 'innerHTMLAssignment',
              data: { property: value },
            });
          }
        }
      },
      // Method-call sinks: el.insertAdjacentHTML(...), document.write(...),
      // document.writeln(...). All produce HTML-from-string injection.
      CallExpression(node) {
        const callee = node.callee;
        if (!callee || callee.type !== 'MemberExpression' || !callee.property) {
          return;
        }
        if (!callee.computed && callee.property.type === 'Identifier') {
          if (HTML_SINK_METHODS.has(callee.property.name)) {
            // Filter out `document.write` calls where the callee object is
            // clearly NOT `document` — but writeln / insertAdjacentHTML
            // are sufficiently rare on non-DOM objects that we report
            // unconditionally. False positives are acceptable here —
            // bypass attempts should be conscious choices, not accidents.
            context.report({
              node,
              messageId: 'htmlSink',
              data: { method: callee.property.name },
            });
          }
        }
        if (callee.computed && callee.property.type === 'Literal') {
          if (HTML_SINK_METHODS.has(callee.property.value)) {
            context.report({
              node,
              messageId: 'htmlSink',
              data: { method: String(callee.property.value) },
            });
          }
        }
      },
    };
  },
};

export default rule;
