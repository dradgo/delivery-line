/**
 * Story 2.23 — governed button-hierarchy barrel.
 *
 * The generic action-hierarchy infrastructure (architecture.md:1182):
 * `<GovernedButton>` (priority + workflowState), and the `<ButtonGroup>` /
 * `<DecisionArea>` containers the `single-primary-action` ESLint rule scopes to.
 *
 * T-NO-PARALLEL-UNION-DOMAIN: `ButtonWorkflowState`/`ButtonPriority` are LOCAL
 * presentation unions — not the 2.19 domain `workflowState` view-model.
 */
export { GovernedButton, type GovernedButtonProps } from './GovernedButton';
export { ButtonGroup, type ButtonGroupProps } from './ButtonGroup';
export { DecisionArea, type DecisionAreaProps } from './DecisionArea';
export {
  PRIORITY_VARIANT,
  BUTTON_PRIORITIES,
  BUTTON_WORKFLOW_STATES,
  BLOCKED_TONE_CLASS,
  STALE_TONE_CLASS,
  workflowStatePresentation,
  type ButtonPriority,
  type ButtonWorkflowState,
  type WorkflowStatePresentation,
} from './buttonHierarchy';
