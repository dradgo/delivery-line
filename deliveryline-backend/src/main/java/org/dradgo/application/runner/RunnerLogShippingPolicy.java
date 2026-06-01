package org.dradgo.application.runner;

import org.dradgo.domain.registry.DataClassification;

/** Forward seam for story 3.7: local-only runner logs must never be shipped. */
public final class RunnerLogShippingPolicy {

  private RunnerLogShippingPolicy() {}

  public static boolean isShippable(DataClassification classification) {
    return classification != null && classification != DataClassification.LOCAL_ONLY;
  }
}
