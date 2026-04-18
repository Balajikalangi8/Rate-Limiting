package com.example.ratelimiter.filter;

import com.example.ratelimiter.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ════════════════════════════════════════════════════════════════
 *  WHY A SERVLET FILTER (not Interceptor or AOP)?
 * ════════════════════════════════════════════════════════════════
 *
 *  Three common integration points exist in Spring Boot:
 *
 *  1. Servlet Filter (chosen here)
 *     • Runs BEFORE the DispatcherServlet — requests that exceed
 *       the limit never touch the Spring MVC layer at all.
 *     • Direct, low-overhead access to the raw HttpServletRequest,
 *       making IP extraction trivial.
 *     • Ideal for pure HTTP-level cross-cutting concerns (auth,
 *       rate limiting, CORS) that are protocol-aware, not
 *       method-aware.
 *
 *  2. HandlerInterceptor
 *     • Runs inside the DispatcherServlet, after routing resolves
 *       to a controller method.  Fine for Spring MVC-specific
 *       concerns (e.g. access control per controller method), but
 *       slightly heavier and bypassed by non-MVC paths.
 *
 *  3. AOP (@Around advice)
 *     • Great for per-method/class concerns (e.g. "only rate-limit
 *       @RateLimited endpoints").  Requires an annotation or
 *       pointcut expression and adds proxy overhead.  Less
 *       appropriate when the limit must apply uniformly to ALL
 *       HTTP traffic.
 *
 *  Conclusion: For a global, HTTP-level rate limiter a Servlet
 *  Filter gives the earliest intercept point, least overhead, and
 *  cleanest access to request metadata.
 * ════════════════════════════════════════════════════════════════
 *
 *  OncePerRequestFilter guarantees exactly one execution per
 *  request, even in async dispatch scenarios.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiterService rateLimiterService;

    public RateLimitFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = resolveClientIp(request);

        if (!rateLimiterService.isAllowed(clientIp)) {
            log.warn("429 Too Many Requests — ip={} uri={}", clientIp, request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"status":429,"error":"Too Many Requests","message":"Rate limit exceeded. Please slow down."}
                    """.strip());
            return;   // do NOT continue the chain — request is rejected
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the real client IP, respecting common proxy headers.
     * Priority: X-Forwarded-For → X-Real-IP → remote address.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For may be "client, proxy1, proxy2"; take leftmost
            return xff.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
