package org.dradgo.application.clarification;

/**
 * Story 3e-2 — a single structured spec-runner acknowledgement: whether the rebuilt spec (keyed by
 * {@code specArtifactPublicId}) addressed the accepted clarification identified by {@code
 * questionId}. Persisted at broker ingest into the V25 side-store and read by {@code
 * ClarificationLifecycleOrchestrator.sweepAfterSpecRebuild} ({@code addressed} drives
 * incorporated-vs-superseded).
 */
public record SpecClarificationAcknowledgement(
    String specArtifactPublicId, String questionId, boolean addressed) {}
