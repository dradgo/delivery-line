package org.dradgo.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.net.LoopbackAddressResolver;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

/**
 * Fail-closed startup guard for the REST bind address (story 6.9 Task 3, AC3/AC4).
 *
 * <p>Resolves the effective bind address ({@code deliveryline.rest.bind-address} overriding {@code
 * server.address}, defaulting to {@code 127.0.0.1}) and refuses to start the application when it is
 * not a loopback address unless {@code deliveryline.rest.unsafe-network-bind=true} is explicitly
 * set. Loopback-only binding is the Phase-1 substitute for authentication, so a non-loopback bind
 * is a deliberate security decision, not a warning.
 *
 * <p>Implemented as a {@link BeanFactoryPostProcessor} so it runs during {@code
 * invokeBeanFactoryPostProcessors} — <em>before</em> {@code onRefresh()} creates and binds the
 * embedded web-server connector, so a refused bind never opens a socket. (A {@code @PostConstruct}
 * or {@code ApplicationReadyEvent} hook would run after the connector has already bound.) Running
 * during refresh also makes the guard exercisable with {@code ApplicationContextRunner}.
 *
 * <p>The loopback classification is delegated to {@link LoopbackAddressResolver} — the same helper
 * the doctor REST-bind probe uses (story 1.16) — so the guard and the probe cannot drift.
 */
public class RestBindingGuard implements BeanFactoryPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(RestBindingGuard.class);

  static final String SERVER_ADDRESS_PROPERTY = "server.address";
  static final String BIND_ADDRESS_PROPERTY = "deliveryline.rest.bind-address";
  static final String UNSAFE_NETWORK_BIND_PROPERTY = "deliveryline.rest.unsafe-network-bind";
  static final String DEFAULT_LOOPBACK_ADDRESS = "127.0.0.1";

  private final Environment environment;

  public RestBindingGuard(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
      throws BeansException {
    validate();
  }

  /** Resolve the effective bind address and fail closed if it is non-loopback without opt-out. */
  void validate() {
    String override = trimToNull(environment.getProperty(BIND_ADDRESS_PROPERTY));
    String serverAddress = trimToNull(environment.getProperty(SERVER_ADDRESS_PROPERTY));
    boolean unsafeNetworkBind =
        Boolean.TRUE.equals(
            environment.getProperty(UNSAFE_NETWORK_BIND_PROPERTY, Boolean.class, Boolean.FALSE));

    String effective = firstNonNull(override, serverAddress, DEFAULT_LOOPBACK_ADDRESS);
    LoopbackAddressResolver.Resolution resolution = LoopbackAddressResolver.resolve(effective);

    if (resolution.isLoopbackBindSafe()) {
      log.info(
          "REST bind address resolved to loopback {} (localhost-only safe-by-default binding)",
          effective);
      return;
    }

    if (!resolution.resolvable()) {
      throw bindRefused(effective, false);
    }

    if (unsafeNetworkBind) {
      log.warn(
          "REST bound to NON-LOOPBACK address {} because {}=true — the localhost-only safety "
              + "control is DISABLED. Phase-1 REST has no authentication; the API is now reachable "
              + "from the network.",
          effective,
          UNSAFE_NETWORK_BIND_PROPERTY);
      return;
    }

    throw bindRefused(effective, resolution.resolvable());
  }

  private static DomainException bindRefused(String effectiveAddress, boolean resolvable) {
    String cause =
        resolvable
            ? "resolves to a non-loopback interface"
            : "could not be resolved to verify it is loopback";
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("effectiveBindAddress", effectiveAddress);
    details.put("resolvable", resolvable);
    details.put("unsafeNetworkBind", false);
    return new DomainException(
        DomainErrorCode.DOCTOR_REST_BIND_UNAVAILABLE,
        "Refusing to start: REST bind address '"
            + effectiveAddress
            + "' "
            + cause
            + ". DeliveryLine binds loopback-only by default (the Phase-1 substitute for auth). "
            + "To deliberately bind a non-loopback address, set "
            + UNSAFE_NETWORK_BIND_PROPERTY
            + "=true (development only — the REST API is unauthenticated).",
        details);
  }

  private static String firstNonNull(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null) {
        return candidate;
      }
    }
    // Unreachable: the final candidate (DEFAULT_LOOPBACK_ADDRESS) is always non-null.
    return DEFAULT_LOOPBACK_ADDRESS;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
