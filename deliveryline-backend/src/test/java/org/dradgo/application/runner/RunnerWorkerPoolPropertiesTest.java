package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.dradgo.application.runner.RunnerWorkerPoolProperties.Backoff;
import org.junit.jupiter.api.Test;

/**
 * Story 3.17b (AC1) — unit coverage of the worker-pool config binding: size clamp [1, 32] with the
 * default-2 coercion for unset/invalid values, and the backoff curve defaults + inversion guard.
 */
class RunnerWorkerPoolPropertiesTest {

  @Test
  void defaultsAreEnabledSizeTwoBackoffOneToTenSeconds() {
    RunnerWorkerPoolProperties defaults = RunnerWorkerPoolProperties.defaults();
    assertTrue(defaults.enabled());
    assertEquals(2, defaults.size());
    assertEquals(Duration.ofSeconds(1), defaults.backoff().initial());
    assertEquals(Duration.ofSeconds(10), defaults.backoff().max());
  }

  @Test
  void sizeOfZeroOrNegativeCoercesToDefaultTwo() {
    assertEquals(2, new RunnerWorkerPoolProperties(true, 0, null).size());
    assertEquals(2, new RunnerWorkerPoolProperties(true, -5, null).size());
  }

  @Test
  void sizeAboveThirtyTwoClampsToThirtyTwo() {
    assertEquals(32, new RunnerWorkerPoolProperties(true, 99, null).size());
  }

  @Test
  void configuredSizeWithinRangeIsKept() {
    assertEquals(8, new RunnerWorkerPoolProperties(true, 8, null).size());
    assertEquals(1, new RunnerWorkerPoolProperties(true, 1, null).size());
    assertEquals(32, new RunnerWorkerPoolProperties(true, 32, null).size());
  }

  @Test
  void disabledFlagIsHonored() {
    assertFalse(new RunnerWorkerPoolProperties(false, 2, null).enabled());
  }

  @Test
  void nullBackoffFallsBackToDefaults() {
    Backoff backoff = new RunnerWorkerPoolProperties(true, 2, null).backoff();
    assertEquals(Duration.ofSeconds(1), backoff.initial());
    assertEquals(Duration.ofSeconds(10), backoff.max());
  }

  @Test
  void backoffCoercesZeroOrNegativeInitialAndInvertedMax() {
    Backoff zeroInitial = new Backoff(Duration.ZERO, Duration.ofSeconds(10));
    assertEquals(Duration.ofSeconds(1), zeroInitial.initial());

    // A max below initial would invert the curve — it is coerced up to initial.
    Backoff inverted = new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(2));
    assertEquals(Duration.ofSeconds(5), inverted.initial());
    assertEquals(Duration.ofSeconds(5), inverted.max());
  }
}
