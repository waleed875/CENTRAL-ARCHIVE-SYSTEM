package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class LegacyByteaInputStreamTest {
    @Test
    void readsMultiChunkBase64WithoutFetchingWholeBytea() throws Exception {
        byte[] original = new byte[1_500_000];
        new java.util.Random(2026).nextBytes(original);
        byte[] encoded = Base64.getEncoder().encode(original);
        int[] calls = {0};

        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                assertTrue(sql.contains("substring(filebinary"));
                int start = ((Number) args[0]).intValue();
                int request = ((Number) args[1]).intValue();
                int fileId = ((Number) args[2]).intValue();
                assertEquals(341, fileId);
                assertTrue(request > 0 && request <= LegacyByteaInputStream.CHUNK_BYTES);
                assertTrue(start >= 1 && start + request - 1 <= encoded.length);
                calls[0]++;
                return type.cast(Arrays.copyOfRange(encoded, start - 1, start + request - 1));
            }
        };
        try (var input = new LegacyByteaInputStream(jdbc, 341, encoded.length);
             var decoded = Base64.getMimeDecoder().wrap(input)) {
            assertArrayEquals(original, decoded.readAllBytes());
        }
        assertTrue(calls[0] >= 3, "large Base64 must be read through multiple bounded queries");
    }

    @Test
    void rejectsChangedOrMissingSourceChunkAndUseAfterClose() throws Exception {
        JdbcTemplate missing = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                return type.cast(new byte[0]);
            }
        };
        try (var input = new LegacyByteaInputStream(missing, 17, 100)) {
            assertThrows(IOException.class, input::read);
        }

        var empty = new LegacyByteaInputStream(missing, 17, 0);
        assertEquals(-1, empty.read());
        empty.close();
        assertThrows(IOException.class, empty::read);
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyByteaInputStream(missing, 17, -1));
    }
}
