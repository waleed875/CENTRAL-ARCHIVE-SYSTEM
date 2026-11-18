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
        assertEquals(1, calls[0], "2 MiB default chunk must avoid unnecessary extra round trips for a 2MB Base64 file");
    }

    @Test
    void configurableChunkSizeKeepsLargeInputBounded() throws Exception {
        byte[] original = new byte[2_100_000];
        new java.util.Random(341).nextBytes(original);
        byte[] encoded = Base64.getEncoder().encode(original);
        int[] chunks = {0};
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                int offset = ((Number) args[0]).intValue() - 1;
                int size = ((Number) args[1]).intValue();
                assertTrue(size <= 512 * 1024);
                chunks[0]++;
                return type.cast(Arrays.copyOfRange(encoded, offset, offset + size));
            }
        };
        try (var input = new LegacyByteaInputStream(jdbc, 341, encoded.length, 512 * 1024);
             var decoded = Base64.getMimeDecoder().wrap(input)) {
            assertArrayEquals(original, decoded.readAllBytes());
        }
        assertTrue(chunks[0] >= 6);
    }

    @Test
    void rejectsOutOfRangeChunkSize() {
        JdbcTemplate jdbc = new JdbcTemplate();
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyByteaInputStream(jdbc, 341, 10, 128 * 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyByteaInputStream(jdbc, 341, 10, 5 * 1024 * 1024));
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
