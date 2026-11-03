package com.centralArchiveSystem.Archive.auth;

import com.centralArchiveSystem.Archive.core.user.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Revoke every previously minted JWT, including tokens issued in the same millisecond. */
public final class TokenRevocation {
    private TokenRevocation() {}

    public static void advance(User user) {
        Instant next = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
        if (user.getTokenValidAfter() != null && !next.isAfter(user.getTokenValidAfter())) {
            next = user.getTokenValidAfter().truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
        }
        user.setTokenValidAfter(next);
    }
}
