package org.dradgo.application.workflow.spi;

public interface WorkflowEventWritePort {

  /**
   * Append an immutable event record for a workflow run.
   *
   * <p>Caller must be in an active transaction; implementations participate via REQUIRED
   * propagation so the event write is atomic with the surrounding state mutation that produced it.
   *
   * @param eventRecord non-null record carrying the event payload (non-null details map; use empty
   *     map for none)
   */
  void append(WorkflowEventRecord eventRecord);
}
