package com.centralArchiveSystem.Archive.auth;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationRateLimitFilterTest {
    private MockHttpServletResponse request(AuthenticationRateLimitFilter filter, String ip,
            String route, String forwarded) throws Exception {
        var request = new MockHttpServletRequest("POST", route);
        request.setRemoteAddr(ip);
        request.addHeader("X-Forwarded-For", forwarded);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (in, out) -> response.setHeader("Reached", "yes"));
        return response;
    }

    @Test void limitsLoginWithJsonAndRetryAfter() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        var filter = new AuthenticationRateLimitFilter(2, 10, true, clock::get);
        assertEquals(200, request(filter, "a", "/api/v1/auth/login", "x").getStatus());
        assertEquals(200, request(filter, "a", "/api/v1/auth/login", "y").getStatus());
        var blocked = request(filter, "a", "/api/v1/auth/login", "z");
        assertEquals(429, blocked.getStatus());
        assertEquals("60", blocked.getHeader("Retry-After"));
        assertNull(blocked.getHeader("Reached"));
        assertTrue(blocked.getContentAsString().contains("RATE_LIMITED"));
        assertTrue(blocked.getContentAsString().contains("محاولات"));
        assertEquals("UTF-8", blocked.getCharacterEncoding());
    }

    @Test void expiresWindowsAndDoesNotTrustForwardedHeaders() throws Exception {
        AtomicLong clock = new AtomicLong();
        var filter = new AuthenticationRateLimitFilter(1, 10, true, clock::get);
        request(filter, "same", "/api/v1/auth/refresh", "one");
        assertEquals(429, request(filter, "same", "/api/v1/auth/refresh", "different").getStatus());
        clock.set(60_000);
        assertEquals(200, request(filter, "same", "/api/v1/auth/refresh", "three").getStatus());
    }

    @Test void boundsClientMemoryAndReclaimsExpiredWindows() throws Exception {
        AtomicLong clock = new AtomicLong();
        var filter = new AuthenticationRateLimitFilter(2, 1, true, clock::get);
        request(filter, "one", "/api/v1/auth/login", "");
        assertEquals(429, request(filter, "two", "/api/v1/auth/login", "").getStatus());
        clock.set(60_000);
        assertEquals(200, request(filter, "two", "/api/v1/auth/login", "").getStatus());
    }

    @Test void unrelatedRoutesAndExplicitlyDisabledLimitsAreUnaffected() throws Exception {
        var limited = new AuthenticationRateLimitFilter(1, 1, true, () -> 0L);
        request(limited, "one", "/api/v1/auth/login", "");
        assertEquals(200, request(limited, "one", "/api/v1/projects", "").getStatus());
        var disabled = new AuthenticationRateLimitFilter(1, 1, false, () -> 0L);
        for (int i = 0; i < 4; i++) {
            assertEquals(200, request(disabled, "one", "/api/v1/auth/login", "").getStatus());
        }
    }
}
