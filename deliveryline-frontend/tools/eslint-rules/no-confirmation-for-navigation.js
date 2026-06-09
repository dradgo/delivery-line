/**
 * Story 2.23 (AC3) — `no-confirmation-for-navigation`.
 *
 * UX-DR18 "do NOT require modal confirmation for low-risk navigation or simple
 * compare entry". Flags a `<ConfirmationDialog>` whose confirm handler (`onConfirm`)
 * is an inline arrow/function whose SOLE effect is a navigation or compare-entry
 * call — `navigate(...)`, `router.navigate(...)`, member `.navigate(...)`, or a
 * documented compare-entry call (`enterCompare`/`setCompareMode`).
 *
 * CONSERVATIVE (OQ-4, T-AC3-PRAGMATIC): static handler-intent detection cannot be
 * complete. ANY additional statement/effect in the handler suppresses the report
 * (false-negatives over false-positives). The AUTHORITATIVE source of "which
 * actions confirm" is `src/lib/overlays/confirmationCatalog.ts`; this rule is a
 * guard-rail for the obvious-abuse case only.
 *
 * ESCAPE HATCH:
 *   // eslint-disable-next-line local-rules/no-confirmation-for-navigation -- <rationale>
 */

const NAVIGATION_CALLEES = new Set(['navigate', 'enterCompare', 'setCompareMode']);

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow a <ConfirmationDialog> whose confirm handler ONLY navigates or enters compare (UX-DR18: no modal confirmation for low-risk navigation).',
    },
    schema: [],
    messages: {
      confirmationForNavigation:
        '<ConfirmationDialog> confirm handler only navigates / enters compare — UX-DR18 reserves confirmation overlays for high-consequence actions, not low-risk navigation. Navigate directly without a confirmation, or (if genuinely consequential) add a real side effect and document it. confirmationCatalog.ts is the authoritative "which actions confirm" source. Escape: `// eslint-disable-next-line local-rules/no-confirmation-for-navigation -- <rationale>`.',
    },
  },
  create(context) {
    function localName(name) {
      if (!name) {
        return null;
      }
      if (name.type === 'JSXIdentifier') {
        return name.name;
      }
      if (name.type === 'JSXMemberExpression' && name.property?.type === 'JSXIdentifier') {
        return name.property.name;
      }
      return null;
    }

    // A bare navigation/compare-entry call: `navigate(...)`, `router.navigate(...)`,
    // `enterCompare(...)`, `setCompareMode(...)`.
    function isNavigationCall(node) {
      if (!node || node.type !== 'CallExpression') {
        return false;
      }
      const callee = node.callee;
      if (callee.type === 'Identifier') {
        return NAVIGATION_CALLEES.has(callee.name);
      }
      if (callee.type === 'MemberExpression' && callee.property.type === 'Identifier') {
        return NAVIGATION_CALLEES.has(callee.property.name);
      }
      return false;
    }

    // The handler body is EXACTLY one navigation/compare-entry effect and nothing
    // else (expression-bodied arrow, or a single-statement block).
    function isNavigationOnlyHandler(fn) {
      const body = fn.body;
      // Expression-bodied arrow: `() => navigate('/x')`.
      if (body.type !== 'BlockStatement') {
        return isNavigationCall(body);
      }
      // Block body: exactly one statement that is the nav call (expr or return).
      if (body.body.length !== 1) {
        return false;
      }
      const stmt = body.body[0];
      if (stmt.type === 'ExpressionStatement') {
        return isNavigationCall(stmt.expression);
      }
      if (stmt.type === 'ReturnStatement') {
        return isNavigationCall(stmt.argument);
      }
      return false;
    }

    return {
      JSXOpeningElement(node) {
        if (localName(node.name) !== 'ConfirmationDialog') {
          return;
        }
        for (const attr of node.attributes) {
          if (
            attr.type !== 'JSXAttribute' ||
            !attr.name ||
            attr.name.name !== 'onConfirm' ||
            !attr.value ||
            attr.value.type !== 'JSXExpressionContainer'
          ) {
            continue;
          }
          const expr = attr.value.expression;
          if (
            (expr.type === 'ArrowFunctionExpression' || expr.type === 'FunctionExpression') &&
            isNavigationOnlyHandler(expr)
          ) {
            context.report({ node: attr, messageId: 'confirmationForNavigation' });
          }
        }
      },
    };
  },
};

export default rule;
