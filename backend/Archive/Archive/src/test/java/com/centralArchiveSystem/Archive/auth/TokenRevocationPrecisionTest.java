package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.user.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class TokenRevocationPrecisionTest {
    @Test void cutoffIsMonotonicAndUsesJwtMillisecondPrecision() {
        User user = new User();
        Instant before = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        user.setTokenValidAfter(before);
        TokenRevocation.advance(user);
        assertEquals(before.plusMillis(1), user.getTokenValidAfter());
        TokenRevocation.advance(user);
        assertEquals(before.plusMillis(2), user.getTokenValidAfter());
        assertEquals(0, user.getTokenValidAfter().getNano() % 1_000_000);
    }

    @Test void newTokensAreNotIssuedBeforeTheirOwnRevocationCutoff() {
        var provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 60_000L);
        ReflectionTestUtils.setField(provider, "jwtRefreshExpirationMs", 120_000L);
        var user = org.springframework.security.core.userdetails.User.withUsername("precision")
                .password("unused").roles("read_only").build();
        Instant cutoff = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        String access = provider.generateToken(user, cutoff);
        String refresh = provider.generateRefreshToken(user, cutoff);
        assertTrue(provider.validateAccessToken(access));
        assertTrue(provider.validateRefreshToken(refresh));
        assertTrue(provider.isIssuedOnOrAfter(access, cutoff));
        assertTrue(provider.isIssuedOnOrAfter(refresh, cutoff));
        assertFalse(provider.isIssuedOnOrAfter(access, provider.getIssuedAtInstant(access).plusMillis(1)));
    }
}
