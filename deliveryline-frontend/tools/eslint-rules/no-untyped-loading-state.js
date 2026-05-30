/**
 * Story 2.22 AC7 — `no-untyped-loading-state`.
 *
 * Enforces the typed loading-state contract (UX-DR17 "loading states should
 * indicate whether the system is fetching / generating / rebuilding / retrying"):
 *
 *   1. `<LoadingState>` must always carry an explicit `variant` prop.
 *   2. Raw spinners — any JSX element whose name ends in `Spinner`, or any
 *      element whose `className` contains `animate-spin` — are forbidden OUTSIDE
 *      the trusted boundary (`src/components/feedback/states/**` — the
 *      `<LoadingState>` internals — and `src/components/ui/**` — shadcn
 *      primitives). Outside, use `<LoadingState variant="...">`.
 *
 * Modeled on story 2.24's `no-unsanitized-html.js` — same `context.filename`
 * trusted-boundary self-exemption (Trap T16).
 */

// Trusted boundaries: the `<LoadingState>` internals + shadcn primitives, plus
// the dev-only `src/dev/` primitives showcase (which deliberately demos the raw
// loading visuals it documents — never shipped to production).
const TRUSTED_BOUNDARY = /(^|[\\/])src[\\/](components[\\/](feedback[\\/]states|ui)|dev)[\\/]/;

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Require an explicit variant on <LoadingState> and forbid raw spinners outside the feedback/states + ui boundaries.',
    },
    schema: [],
    messages: {
      missingVariant:
        '`<LoadingState>` requires an explicit `variant` prop (one of fetchingData/generatingArtifact/rebuildingAfterRejection/retryingRecovery) — story 2.22 AC7.',
      rawSpinner:
        'Raw spinners are forbidden outside src/components/feedback/states + src/components/ui. Import `<LoadingState variant="…">` from @/components/feedback (story 2.22 AC7).',
    },
  },
  create(context) {
    const filename = context.filename ?? context.getFilename();
    const trusted = TRUSTED_BOUNDARY.test(filename);

    function classNameString(attr) {
      const value = attr.value;
      if (!value) {
        return null;
      }
      if (value.type === 'Literal' && typeof value.value === 'string') {
        return value.value;
      }
      if (value.type === 'JSXExpressionContainer') {
        const expr = value.expression;
        if (expr.type === 'Literal' && typeof expr.value === 'string') {
          return expr.value;
        }
        if (expr.type === 'TemplateLiteral') {
          return expr.quasis.map((q) => q.value.cooked ?? '').join(' ');
        }
      }
      return null;
    }

    // The element's local JSX name: `Foo` for `<Foo>` (JSXIdentifier) and the
    // trailing member for `<Ns.Foo>` (JSXMemberExpression → `Foo`).
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

    return {
      JSXOpeningElement(node) {
        const name = node.name;
        const elementName = localName(name);

        // (1) <LoadingState> without a variant — checked everywhere. A JSX spread
        // (`<LoadingState {...props} />`) may supply `variant`, so its presence
        // suppresses the report (the rule cannot prove absence through a spread).
        if (elementName === 'LoadingState') {
          const hasVariant = node.attributes.some(
            (a) => a.type === 'JSXAttribute' && a.name && a.name.name === 'variant',
          );
          const hasSpread = node.attributes.some((a) => a.type === 'JSXSpreadAttribute');
          if (!hasVariant && !hasSpread) {
            context.report({ node, messageId: 'missingVariant' });
          }
        }

        if (trusted) {
          return;
        }

        // (2a) *Spinner element names — including namespaced `<Ns.Spinner>`.
        if (elementName !== null && /Spinner$/.test(elementName)) {
          context.report({ node, messageId: 'rawSpinner' });
          return;
        }

        // (2b) className containing animate-spin.
        for (const attr of node.attributes) {
          if (attr.type === 'JSXAttribute' && attr.name && attr.name.name === 'className') {
            const cls = classNameString(attr);
            if (cls !== null && /\banimate-spin\b/.test(cls)) {
              context.report({ node: attr, messageId: 'rawSpinner' });
            }
          }
        }
      },
    };
  },
};

export default rule;
