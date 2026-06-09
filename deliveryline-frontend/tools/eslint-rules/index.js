/**
 * Local ESLint plugin exposing DeliveryLine's project-specific rules
 * (story 2.31 AC4/AC5). Registered in eslint.config.js as the `local-rules`
 * plugin namespace.
 */
import noWorkflowDomainInUiPrimitives from './no-workflow-domain-in-ui-primitives.js';
import noInlineQueryKeys from './no-inline-query-keys.js';
import noUnsanitizedHtml from './no-unsanitized-html.js';
import noUntypedLoadingState from './no-untyped-loading-state.js';
import noWorkflowToastSuccess from './no-workflow-toast-success.js';
import singlePrimaryAction from './single-primary-action.js';
import noConfirmationForNavigation from './no-confirmation-for-navigation.js';

/** @type {import('eslint').ESLint.Plugin} */
const plugin = {
  meta: { name: 'local-rules', version: '1.0.0' },
  rules: {
    'no-workflow-domain-in-ui-primitives': noWorkflowDomainInUiPrimitives,
    'no-inline-query-keys': noInlineQueryKeys,
    'no-unsanitized-html': noUnsanitizedHtml,
    'no-untyped-loading-state': noUntypedLoadingState,
    'no-workflow-toast-success': noWorkflowToastSuccess,
    'single-primary-action': singlePrimaryAction,
    'no-confirmation-for-navigation': noConfirmationForNavigation,
  },
};

export default plugin;
