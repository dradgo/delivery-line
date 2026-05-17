package org.dradgo.application.runner;

public record RunnerDispatchAck(String adapterRef) {

  public RunnerDispatchAck {
    if (adapterRef == null || adapterRef.isBlank()) {
      throw new IllegalArgumentException("adapterRef must not be blank");
    }
  }
}
