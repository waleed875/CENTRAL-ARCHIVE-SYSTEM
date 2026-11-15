package com.centralArchiveSystem.Archive.project.file;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class FilePreviewByteRangeTest {
    @Test
    void supportsOpenAndClosedRanges() {
        assertEquals(new FilePreviewController.ByteRange(0, 99),
                FilePreviewController.parseSingleRange("bytes=0-99", 1000));
        assertEquals(new FilePreviewController.ByteRange(400, 999),
                FilePreviewController.parseSingleRange("bytes=400-", 1000));
        assertEquals(new FilePreviewController.ByteRange(900, 999),
                FilePreviewController.parseSingleRange("bytes=-100", 1000));
        assertEquals(new FilePreviewController.ByteRange(0, 999),
                FilePreviewController.parseSingleRange("bytes=-2000", 1000));
        assertEquals(new FilePreviewController.ByteRange(995, 999),
                FilePreviewController.parseSingleRange("bytes=995-2000", 1000));
        assertEquals(100L, FilePreviewController.parseSingleRange("bytes=0-99", 1000).length());
    }

    @Test
    void rejectsUnusableOrMultiRanges() {
        for (String invalid : new String[]{
                "bytes=1000-", "bytes=9-3", "bytes=-0", "bytes=0-5,8-9",
                "bytes=a-", "bytes=-", "items=0-1", "bytes=-1-2", "bytes=0-999999999999999999999"
        }) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> FilePreviewController.parseSingleRange(invalid, 1000), invalid);
            assertEquals(416, error.getStatusCode().value());
        }
    }
}
