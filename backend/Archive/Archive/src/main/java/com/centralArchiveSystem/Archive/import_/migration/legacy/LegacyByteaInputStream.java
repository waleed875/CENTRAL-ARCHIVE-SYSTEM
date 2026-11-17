package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Streams legacy PostgreSQL BYTEA columns in bounded chunks rather than asking
 * pgJDBC to materialize a potentially huge filebinary value in the JVM heap.
 *
 * The source database must be read-only for the duration of a migration batch.
 * Each query returns at most one chunk, including any TOASTed BYTEA value.
 */
final class LegacyByteaInputStream extends InputStream {
    static final int CHUNK_BYTES = 512 * 1024;
    private static final String CHUNK_SQL =
            "SELECT substring(filebinary FROM ? FOR ?) FROM public.tbl_files WHERE idfile = ?";

    private final JdbcTemplate jdbc;
    private final int legacyFileId;
    private final long encodedLength;
    private long offset;
    private byte[] current = new byte[0];
    private int position;
    private boolean closed;

    LegacyByteaInputStream(JdbcTemplate jdbc, int legacyFileId, long encodedLength) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.legacyFileId = legacyFileId;
        if (encodedLength < 0) {
            throw new IllegalArgumentException("Legacy BYTEA length cannot be negative");
        }
        this.encodedLength = encodedLength;
    }

    private boolean refill() throws IOException {
        if (closed) throw new IOException("Legacy BYTEA stream is closed");
        if (position < current.length) return true;
        if (offset >= encodedLength) return false;
        int requestSize = (int) Math.min(CHUNK_BYTES, encodedLength - offset);
        // PostgreSQL substring offsets are 1-based. Each individual JDBC
        // result fits within the fixed chunk budget, irrespective of file size.
        try {
            byte[] part = jdbc.queryForObject(
                    CHUNK_SQL, byte[].class, Math.toIntExact(offset + 1), requestSize, legacyFileId);
            if (part == null || part.length != requestSize) {
                throw new IOException("Legacy file " + legacyFileId +
                        " content changed or is missing while reading chunk at " + offset);
            }
            current = part;
            position = 0;
            offset += part.length;
            return true;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Unable to read legacy file " + legacyFileId +
                    " at byte " + offset, e);
        }
    }

    @Override
    public int read() throws IOException {
        if (!refill()) return -1;
        return current[position++] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, buffer.length);
        if (closed) throw new IOException("Legacy BYTEA stream is closed");
        if (len == 0) return 0;
        if (!refill()) return -1;
        int count = Math.min(len, current.length - position);
        System.arraycopy(current, position, buffer, off, count);
        position += count;
        return count;
    }

    @Override
    public void close() {
        closed = true;
        current = new byte[0];
        position = 0;
    }
}
