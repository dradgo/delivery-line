package org.dradgo.infrastructure.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the localhost-binding contract (story 6.9 Task 3): binds {@link RestBindingProperties} and
 * registers the {@link RestBindingGuard} fail-closed startup check.
 *
 * <p>The guard is declared as a {@code static} {@code @Bean} so it is instantiated as an
 * infrastructure {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor} early in
 * the refresh cycle (before the web-server connector binds) without forcing this configuration
 * class to be instantiated first. Per the Spring contract for static {@code @Bean} factory methods,
 * it operates only on its injected {@link Environment} parameter.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RestBindingProperties.class)
public class RestBindingConfiguration {

  @Bean
  static RestBindingGuard restBindingGuard(Environment environment) {
    return new RestBindingGuard(environment);
  }

  @Bean
  WebServerFactoryCustomizer<ConfigurableWebServerFactory> restBindAddressCustomizer(
      RestBindingProperties properties) {
    return factory -> {
      String bindAddress = properties.bindAddress();
      if (bindAddress == null || bindAddress.isBlank()) {
        return;
      }
      try {
        factory.setAddress(InetAddress.getByName(bindAddress));
      } catch (UnknownHostException error) {
        throw new IllegalStateException(
            "deliveryline.rest.bind-address could not be resolved: " + bindAddress, error);
      }
    };
  }
}
