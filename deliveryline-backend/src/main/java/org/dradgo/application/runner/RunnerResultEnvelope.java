package org.dradgo.application.runner;

public record RunnerResultEnvelope(String runnerExecutionId, byte[] payloadBytes) {

  public RunnerResultEnvelope {
    if (runnerExecutionId == null || runnerExecutionId.isBlank()) {
      throw new IllegalArgumentException("runnerExecutionId must not be blank");
    }
    if (payloadBytes == null || payloadBytes.length == 0) {
      throw new IllegalArgumentException("payloadBytes must not be empty");
    }
    payloadBytes = payloadBytes.clone();
  }

  @Override
  public byte[] payloadBytes() {
    return payloadBytes.clone();
  }
}
