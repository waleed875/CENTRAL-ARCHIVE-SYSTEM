package com.centralArchiveSystem.Archive.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationFilterTest {

    @Test
    void preservesSafeClientRequestId() {
        assertEquals(
                "req-123_ABC.test:1",
                RequestCorrelationFilter.normalizeRequestId("req-123_ABC.test:1")
        );
    }

    @Test
    void replacesUnsafeRequestId() {
        String generated = RequestCorrelationFilter.normalizeRequestId(
                "unsafe request id\nforged-header"
        );

        assertNotNull(generated);
        assertDoesNotThrow(() -> java.util.UUID.fromString(generated));
    }

    @Test
    void generatesIdWhenHeaderMissing() {
        String generated = RequestCorrelationFilter.normalizeRequestId(null);
        assertDoesNotThrow(() -> java.util.UUID.fromString(generated));
    }
}
