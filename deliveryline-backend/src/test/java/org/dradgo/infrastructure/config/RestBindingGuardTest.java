package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.InetAddress;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.mock.env.MockEnvironment;

/**
 * Story 6.9 AC3/AC4 — fail-closed localhost binding guard. Unit-level checks of {@link
 * RestBindingGuard#validate()} (MockEnvironment + log assertions) plus {@link
 * ApplicationContextRunner} integration that a non-loopback bind aborts context refresh.
 */
class RestBindingGuardTest {

  private final Logger guardLogger = (Logger) LoggerFactory.getLogger(RestBindingGuard.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    guardLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    guardLogger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void loopbackDefaultStartsAndLogsInfo() {
    new RestBindingGuard(new MockEnvironment()).validate();
    assertThat(logLine(Level.INFO)).contains("loopback").contains("127.0.0.1");
  }

  @Test
  void explicitLoopbackServerAddressStarts() {
    MockEnvironment env = new MockEnvironment().withProperty("server.address", "127.0.0.1");
    new RestBindingGuard(env).validate();
    assertThat(logLine(Level.INFO)).contains("loopback");
  }

  @Test
  void nonLoopbackBindWithoutUnsafeOverrideFailsClosed() {
    MockEnvironment env =
        new MockEnvironment().withProperty("deliveryline.rest.bind-address", "0.0.0.0");
    assertThatThrownBy(() -> new RestBindingGuard(env).validate())
        .isInstanceOf(DomainException.class)
        .satisfies(
            ex ->
                assertThat(((DomainException) ex).errorCode())
                    .isEqualTo(DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE))
        .hasMessageContaining("unsafe-network-bind=true");
  }

  @Test
  void nonLoopbackBindWithUnsafeOverrideStartsAndWarns() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("deliveryline.rest.bind-address", "0.0.0.0")
            .withProperty("deliveryline.rest.unsafe-network-bind", "true");
    new RestBindingGuard(env).validate();
    assertThat(logLine(Level.WARN)).contains("NON-LOOPBACK").contains("0.0.0.0");
  }

  @Test
  void bindAddressOverrideWinsOverLoopbackServerAddress() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("server.address", "127.0.0.1")
            .withProperty("deliveryline.rest.bind-address", "0.0.0.0");
    assertThatThrownBy(() -> new RestBindingGuard(env).validate())
        .isInstanceOf(DomainException.class);
  }

  @Test
  void unresolvableAddressWithoutUnsafeFailsClosed() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("deliveryline.rest.bind-address", "no-such-host.invalid");
    assertThatThrownBy(() -> new RestBindingGuard(env).validate())
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("could not be resolved");
  }

  @Test
  void unresolvableAddressWithUnsafeStillFailsClosed() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("deliveryline.rest.bind-address", "no-such-host.invalid")
            .withProperty("deliveryline.rest.unsafe-network-bind", "true");
    assertThatThrownBy(() -> new RestBindingGuard(env).validate())
        .isInstanceOf(DomainException.class)
        .satisfies(
            ex ->
                assertThat(((DomainException) ex).errorCode())
                    .isEqualTo(DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE))
        .hasMessageContaining("could not be resolved");
  }

  @Test
  void applicationContextAbortsRefreshOnNonLoopbackBind() {
    new ApplicationContextRunner()
        .withUserConfiguration(RestBindingConfiguration.class)
        .withPropertyValues("deliveryline.rest.bind-address=0.0.0.0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void applicationContextStartsWhenUnsafeOverrideEnabled() {
    new ApplicationContextRunner()
        .withUserConfiguration(RestBindingConfiguration.class)
        .withPropertyValues(
            "deliveryline.rest.bind-address=0.0.0.0", "deliveryline.rest.unsafe-network-bind=true")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void applicationContextStartsOnLoopbackDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(RestBindingConfiguration.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @SuppressWarnings("unchecked")
  void bindAddressCustomizerAppliesOverrideToEmbeddedServerFactory() throws Exception {
    new ApplicationContextRunner()
        .withUserConfiguration(RestBindingConfiguration.class)
        .withPropertyValues("deliveryline.rest.bind-address=127.0.0.2")
        .run(
            context -> {
              org.springframework.boot.web.server.ConfigurableWebServerFactory factory =
                  org.mockito.Mockito.mock(
                      org.springframework.boot.web.server.ConfigurableWebServerFactory.class);
              context
                  .getBeanProvider(WebServerFactoryCustomizer.class)
                  .orderedStream()
                  .forEach(customizer -> customizer.customize(factory));
              ArgumentCaptor<InetAddress> captor = ArgumentCaptor.forClass(InetAddress.class);
              org.mockito.Mockito.verify(factory).setAddress(captor.capture());
              assertThat(captor.getValue()).isEqualTo(InetAddress.getByName("127.0.0.2"));
            });
  }

  private String logLine(Level level) {
    return appender.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .findFirst()
        .orElse("");
  }
}
