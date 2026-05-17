package org.dradgo.infrastructure.config;

import org.dradgo.runnercontracts.RunnerContractValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link RunnerContractValidator} as a singleton bean. The validator lives in {@code
 * deliveryline-runner-contracts} (a plain Java module with no Spring annotations) so it must be
 * registered explicitly. Kept in its own {@code @Configuration} class to avoid the
 * circular-reference cycle that would form if it were declared on {@link RunnerConfiguration}
 * alongside the broker-driving {@code @Scheduled} bean.
 */
@Configuration
public class RunnerContractsConfiguration {

  @Bean
  public RunnerContractValidator runnerContractValidator() {
    return new RunnerContractValidator();
  }
}
