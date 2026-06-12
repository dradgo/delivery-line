package org.dradgo.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Story 2.28 (AC4) — cache-control policy for the embedded Vite SPA.
 *
 * <ul>
 *   <li><b>{@code /assets/**}</b> carry {@code Cache-Control: max-age=31536000, immutable}. Vite
 *       content-hashes every emitted asset filename ({@code index-<hash>.js}), so a one-year
 *       immutable cache is safe: a changed asset is a new URL, never a stale hit.
 *   <li><b>The app shell ({@code index.html}, served at {@code /} and {@code /index.html})</b>
 *       carries {@code Cache-Control: no-store} so a redeploy is picked up on the next page load
 *       instead of being masked by a cached shell. This matches the {@code no-store} the
 *       SPA-fallback path already sets in {@code SpaFallbackController.serveShell} (story 2.28
 *       AC1), keeping every shell entry point consistent.
 * </ul>
 *
 * <p>This is a non-{@code Controller} web {@code @Configuration}, so it is legal under {@code
 * infrastructure.web} — the enforced ArchUnit rule pins only {@code *Controller}-named /
 * {@code @RestController} classes to {@code adapters.rest} (story 2.28 S2/S7).
 */
@org.springframework.context.annotation.Configuration
public class StaticResourceCacheConfig implements WebMvcConfigurer {

  /** One year, in seconds, per the AR-fixed long-cache window for content-hashed assets. */
  private static final Duration ASSET_MAX_AGE = Duration.ofDays(365);

  /** SPA shell entry points that must never be cached so a redeploy is seen immediately. */
  static final String[] SHELL_ENTRY_POINTS = {"/", "/index.html"};

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // Layer an explicit /assets/** handler over Spring Boot's default classpath:/static/ serving so
    // the long-cache policy attaches only to the content-hashed bundle assets. A missing asset
    // still
    // yields NoResourceFoundException → JSON 404 (it is NOT diverted to the SPA shell — AC2/AC3).
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCacheControl(CacheControl.maxAge(ASSET_MAX_AGE).immutable());
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // The shell is served either as the welcome page (GET /) or as a direct GET /index.html; both
    // handler mappings inherit the configured interceptors, so a single no-store interceptor scoped
    // to those two paths covers every non-fallback shell entry point. The SPA-fallback deep-link
    // path sets its own no-store in SpaFallbackController.serveShell.
    registry.addInterceptor(new ShellNoStoreInterceptor()).addPathPatterns(SHELL_ENTRY_POINTS);
  }

  /** Stamps {@code Cache-Control: no-store} on the SPA shell response before it is rendered. */
  private static final class ShellNoStoreInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
      return true;
    }
  }
}
