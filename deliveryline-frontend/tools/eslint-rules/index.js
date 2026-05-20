/**
 * Local ESLint plugin exposing DeliveryLine's project-specific rules
 * (story 2.31 AC4/AC5). Registered in eslint.config.js as the `local-rules`
 * plugin namespace.
 */
import noWorkflowDomainInUiPrimitives from './no-workflow-domain-in-ui-primitives.js';
import noInlineQueryKeys from './no-inline-query-keys.js';

/** @type {import('eslint').ESLint.Plugin} */
const plugin = {
  meta: { name: 'local-rules', version: '1.0.0' },
  rules: {
    'no-workflow-domain-in-ui-primitives': noWorkflowDomainInUiPrimitives,
    'no-inline-query-keys': noInlineQueryKeys,
  },
};

export default plugin;
