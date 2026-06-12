package org.dradgo.adapters.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.dradgo.application.observability.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 2.28 — the single, named, discoverable SPA-fallback seam (AC1). Owns the decision predicate
 * (AC2/AR33) and the act of serving the embedded React app shell ({@code
 * classpath:/static/index.html}) so a hard-load / refresh of a client-side route (e.g. {@code
 * /workflows/run_123}) resolves client-side via TanStack Router (story 2.5).
 *
 * <p><b>Why a {@code @RestController} with no request mappings.</b> {@code
 * NoResourceFoundException} for an unmatched route is thrown by the {@code DispatcherServlet}'s
 * static-resource handling, NOT by a controller method — so it can only be intercepted by the
 * global {@link ProblemDetailsMapper} {@code @RestControllerAdvice}. This controller therefore does
 * not map any path itself; it concentrates the shell-serving responsibility (predicate + body)
 * behind a named class, and {@code ProblemDetailsMapper.handleNoResourceFound} delegates to it. The
 * class is pinned to {@code adapters.rest} as a {@code @RestController} by the enforced ArchUnit
 * rule {@code REST_CONTROLLER_SUFFIX_REQUIRES_REST_CONTROLLER_ANNOTATION}; the architecture tree's
 * {@code infrastructure/web} placement is stale and overridden (see story 2.28 S2).
 */
@RestController
public class SpaFallbackController {

  private static final Logger LOG = LoggerFactory.getLogger(SpaFallbackController.class);

  /** The embedded Vite app shell; present only when the frontend bundle is on the classpath. */
  static final Resource SPA_SHELL = new ClassPathResource("static/index.html");

  /**
   * Path prefixes that must NEVER be diverted to the SPA shell — they have to keep their JSON 404s
   * so API clients, the actuator, and the OpenAPI/Swagger tooling behave correctly.
   */
  private static final List<String> RESERVED_NON_SPA_PREFIXES =
      List.of("api", "actuator", "v3/api-docs", "swagger-ui");

  /**
   * Decide whether an unresolved request should be served the SPA shell (embedded-frontend
   * deep-link / refresh) instead of a JSON ProblemDetail 404. Package-private + static so the
   * routing predicate can be unit-tested without a Spring context or a built bundle.
   */
  static boolean shouldServeSpaShell(String resourcePath, HttpServletRequest request) {
    if (resourcePath == null || !"GET".equalsIgnoreCase(request.getMethod())) {
      return false;
    }
    String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    for (String reserved : RESERVED_NON_SPA_PREFIXES) {
      if (path.equalsIgnoreCase(reserved)
          || path.regionMatches(true, 0, reserved + "/", 0, reserved.length() + 1)) {
        return false;
      }
    }
    // A trailing segment containing a dot looks like a (missing) static asset, not a route — let it
    // 404 so a broken asset reference stays visible instead of silently returning HTML.
    String lastSegment = path.substring(path.lastIndexOf('/') + 1);
    if (lastSegment.contains(".")) {
      return false;
    }
    // Only divert genuine browser navigations (which accept HTML); JSON/XHR clients keep the 404.
    return acceptsHtml(request);
  }

  private static boolean acceptsHtml(HttpServletRequest request) {
    String accept = request.getHeader("Accept");
    if (accept == null || accept.isBlank()) {
      return true; // no Accept header → treat as a navigation (browser default / curl)
    }
    String lower = accept.toLowerCase(Locale.ROOT);
    return lower.contains("text/html") || lower.contains("*/*");
  }

  /** True only when the Vite bundle is embedded on the classpath (real packaging / runtime). */
  boolean shellExists() {
    return SPA_SHELL.exists();
  }

  /**
   * Serve the embedded app shell with {@code no-store} so a redeploy is picked up immediately
   * (content-hashed assets cache; the shell must not). Callers MUST gate on {@link #shellExists()}
   * — this method assumes the bundle is present.
   */
  ResponseEntity<Resource> serveShell(String resourcePath) {
    LOG.info("spa_fallback serving shell for path={}", MdcKeys.sanitizeForLog(resourcePath));
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noStore())
        .body(SPA_SHELL);
  }
}
