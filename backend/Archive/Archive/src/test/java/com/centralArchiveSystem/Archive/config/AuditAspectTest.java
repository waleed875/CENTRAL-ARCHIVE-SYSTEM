package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.audit.AuditLogService;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuditAspectTest {

    @Test
    void redactsSensitiveAndEmbeddedPayloadFields() throws Exception {
        AuditAspect aspect = new AuditAspect(
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(UserRepository.class)
        );

        Method method = AuditAspect.class.getDeclaredMethod("serializeForAudit", Object.class);
        method.setAccessible(true);

        String json = (String) method.invoke(aspect, Map.of(
                "token", "secret-token",
                "password", "secret-password",
                "manifest_data", "{large-manifest}",
                "content_base64", "AAAA",
                "safeField", "safe-value"
        ));

        assertFalse(json.contains("secret-token"));
        assertFalse(json.contains("secret-password"));
        assertFalse(json.contains("{large-manifest}"));
        assertFalse(json.contains("AAAA"));
        assertTrue(json.contains("safe-value"));
        assertTrue(json.contains("***REDACTED***"));
    }

    @Test
    void truncatesOversizedAuditJson() throws Exception {
        AuditAspect aspect = new AuditAspect(
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(UserRepository.class)
        );

        Method method = AuditAspect.class.getDeclaredMethod("serializeForAudit", Object.class);
        method.setAccessible(true);

        String json = (String) method.invoke(
                aspect,
                Map.of("description", "x".repeat(40_000))
        );

        assertTrue(json.contains("\"truncated\":true"));
        assertTrue(json.contains("***TRUNCATED***"));
        assertFalse(json.contains("x".repeat(1_000)));
    }
}
