import { isValidArtifactId, isValidRunId } from './publicId';

export class InvalidRouteParamError extends Error {
  constructor(message = 'Invalid route parameters') {
    super(message);
    this.name = 'InvalidRouteParamError';
  }
}

export function assertValidRunRouteParams(workflowRunId: string): void {
  if (!isValidRunId(workflowRunId)) {
    throw new InvalidRouteParamError('Invalid workflowRunId');
  }
}

export function assertValidArtifactRouteParams(workflowRunId: string, artifactId: string): void {
  if (!isValidRunId(workflowRunId) || !isValidArtifactId(artifactId)) {
    throw new InvalidRouteParamError('Invalid artifact route params');
  }
}
