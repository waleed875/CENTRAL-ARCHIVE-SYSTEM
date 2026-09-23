package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.audit.AuditLogService;
import com.centralArchiveSystem.Archive.core.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.centralArchiveSystem.Archive.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

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
    void extractsEntityIdFromWrappedControllerResponse() throws Exception {
        AuditAspect aspect = new AuditAspect(
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(UserRepository.class)
        );

        Method method = AuditAspect.class.getDeclaredMethod("extractEntityId", Object.class);
        method.setAccessible(true);

        UUID id = UUID.randomUUID();
        TestEntity entity = new TestEntity(id);
        Object wrapped = ResponseEntity.ok(ApiResponse.success(entity));

        assertEquals(id.toString(), method.invoke(aspect, wrapped));
    }

    @Test
    void serializesUnwrappedApiResponseDataForAudit() throws Exception {
        AuditAspect aspect = new AuditAspect(
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(UserRepository.class)
        );

        Method method = AuditAspect.class.getDeclaredMethod("determineNewValues", Object.class);
        method.setAccessible(true);

        UUID id = UUID.randomUUID();
        String json = (String) method.invoke(
                aspect,
                ResponseEntity.ok(ApiResponse.success(new TestEntity(id)))
        );

        assertTrue(json.contains(id.toString()));
        assertFalse(json.contains("\"success\""));
    }

    private record TestEntity(UUID id) {
        public UUID getId() {
            return id;
        }
    }

    @Test
    void marksNon2xxResponseAsAuditFailure() throws Exception {
        AuditAspect aspect = new AuditAspect(
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(UserRepository.class)
        );

        Method method = AuditAspect.class.getDeclaredMethod(
                "determineOutcome",
                Object.class,
                Throwable.class
        );
        method.setAccessible(true);

        Object outcome = method.invoke(
                aspect,
                ResponseEntity.status(409).body(ApiResponse.error("conflict")),
                null
        );

        Method status = outcome.getClass().getDeclaredMethod("status");
        status.setAccessible(true);
        Method error = outcome.getClass().getDeclaredMethod("errorMessage");
        error.setAccessible(true);

        assertEquals("FAILURE", status.invoke(outcome));
        assertEquals("HTTP 409", error.invoke(outcome));
    }

    @Test
    void marksFailedApiResponseAsAuditFailureEvenWith2xx() throws Exception {
        AuditAspect aspect = new AuditAspect(
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(UserRepository.class)
        );

        Method method = AuditAspect.class.getDeclaredMethod(
                "determineOutcome",
                Object.class,
                Throwable.class
        );
        method.setAccessible(true);

        Object outcome = method.invoke(
                aspect,
                ResponseEntity.ok(ApiResponse.error("validation failed")),
                null
        );

        Method status = outcome.getClass().getDeclaredMethod("status");
        status.setAccessible(true);
        Method error = outcome.getClass().getDeclaredMethod("errorMessage");
        error.setAccessible(true);

        assertEquals("FAILURE", status.invoke(outcome));
        assertEquals("validation failed", error.invoke(outcome));
    }

    @Test
    void throwableAlwaysProducesAuditFailure() throws Exception {
        AuditAspect aspect = new AuditAspect(
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(UserRepository.class)
        );

        Method method = AuditAspect.class.getDeclaredMethod(
                "determineOutcome",
                Object.class,
                Throwable.class
        );
        method.setAccessible(true);

        Object outcome = method.invoke(
                aspect,
                ResponseEntity.ok(ApiResponse.success("ignored")),
                new AssertionError("rollback failure")
        );

        Method status = outcome.getClass().getDeclaredMethod("status");
        status.setAccessible(true);
        Method error = outcome.getClass().getDeclaredMethod("errorMessage");
        error.setAccessible(true);

        assertEquals("FAILURE", status.invoke(outcome));
        assertEquals("rollback failure", error.invoke(outcome));
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
