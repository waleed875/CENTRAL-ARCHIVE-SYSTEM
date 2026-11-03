package com.centralArchiveSystem.Archive.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded per-process protection; clustered deployments also need a trusted edge limiter. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MS = 60_000;
    private final int limit;
    private final int maxClients;
    private final boolean enabled;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new HashMap<>();

    @Autowired
    public AuthenticationRateLimitFilter(
            @Value("${archive.auth.rate-limit.requests-per-minute:120}") int limit,
            @Value("${archive.auth.rate-limit.max-clients:4096}") int maxClients,
            @Value("${archive.auth.rate-limit.enabled:true}") boolean enabled) {
        this(limit, maxClients, enabled, System::currentTimeMillis);
    }

    AuthenticationRateLimitFilter(int limit, int maxClients, boolean enabled, LongSupplier clock) {
        if (limit < 1 || maxClients < 1) throw new IllegalArgumentException("Rate limits must be positive");
        this.limit = limit;
        this.maxClients = maxClients;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !enabled || !"POST".equals(request.getMethod())
                || !("/api/v1/auth/login".equals(path) || "/api/v1/auth/refresh".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // Do not trust a caller-supplied X-Forwarded-For header.
        long retry = retryAfter(request.getRemoteAddr() + ":" + request.getRequestURI());
        if (retry > 0) {
            response.setHeader("Retry-After", Long.toString(retry));
            SecurityErrorWriter.write(response, 429, "RATE_LIMITED", "محاولات كثيرة. أعد المحاولة بعد قليل");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized long retryAfter(String key) {
        long now = clock.getAsLong();
        Window window = windows.get(key);
        if (window == null || now - window.start >= WINDOW_MS) {
            if (window == null && windows.size() >= maxClients) {
                windows.values().removeIf(value -> now - value.start >= WINDOW_MS);
                if (windows.size() >= maxClients) return 60;
            }
            windows.put(key, new Window(now, 1));
            return 0;
        }
        if (window.count >= limit) return Math.max(1, (WINDOW_MS - (now - window.start) + 999) / 1000);
        window.count++;
        return 0;
    }

    private static final class Window {
        final long start;
        int count;
        Window(long start, int count) { this.start = start; this.count = count; }
    }
}
