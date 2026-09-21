package com.centralArchiveSystem.Archive.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    @Test
    void separatesAccessAndRefreshTokenTypes() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider();

        String rawSecret = "central-archive-test-jwt-secret-32-bytes!!";
        setField(provider, "jwtSecret", Base64.getEncoder().encodeToString(rawSecret.getBytes(StandardCharsets.UTF_8)));
        setField(provider, "jwtExpirationMs", 60_000L);
        setField(provider, "jwtRefreshExpirationMs", 300_000L);

        UserDetails user = User.withUsername("auditor-user")
                .password("unused")
                .roles("auditor")
                .build();

        String accessToken = provider.generateToken(user);
        String refreshToken = provider.generateRefreshToken(user);

        assertTrue(provider.validateAccessToken(accessToken));
        assertFalse(provider.validateRefreshToken(accessToken));

        assertTrue(provider.validateRefreshToken(refreshToken));
        assertFalse(provider.validateAccessToken(refreshToken));

        assertEquals("auditor-user", provider.getUsernameFromToken(accessToken));
        assertEquals("auditor-user", provider.getUsernameFromToken(refreshToken));
        assertTrue(provider.getRolesFromToken(accessToken).contains("ROLE_auditor"));
        assertTrue(provider.getRolesFromToken(refreshToken).isEmpty());
    }


    @Test
    void enforcesPersistentTokenCutoff() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider();

        String rawSecret = "central-archive-test-jwt-secret-32-bytes!!";
        setField(provider, "jwtSecret", Base64.getEncoder().encodeToString(rawSecret.getBytes(StandardCharsets.UTF_8)));
        setField(provider, "jwtExpirationMs", 60_000L);
        setField(provider, "jwtRefreshExpirationMs", 300_000L);

        UserDetails user = User.withUsername("user")
                .password("unused")
                .roles("read_only")
                .build();

        String token = provider.generateToken(user);
        Instant issuedAt = provider.getIssuedAtInstant(token);

        assertTrue(provider.isIssuedOnOrAfter(token, issuedAt));
        assertFalse(provider.isIssuedOnOrAfter(token, issuedAt.plusMillis(1)));
    }

    @Test
    void rejectsTamperedToken() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider();

        String rawSecret = "central-archive-test-jwt-secret-32-bytes!!";
        setField(provider, "jwtSecret", Base64.getEncoder().encodeToString(rawSecret.getBytes(StandardCharsets.UTF_8)));
        setField(provider, "jwtExpirationMs", 60_000L);
        setField(provider, "jwtRefreshExpirationMs", 300_000L);

        UserDetails user = User.withUsername("user")
                .password("unused")
                .roles("read_only")
                .build();

        String token = provider.generateToken(user);
        String tampered = token.substring(0, token.length() - 2) + "aa";

        assertFalse(provider.validateAccessToken(tampered));
        assertFalse(provider.validateRefreshToken(tampered));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
