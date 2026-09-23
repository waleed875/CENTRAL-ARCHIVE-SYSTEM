package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.project.file.MinioService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MinioHealthIndicatorTest {

    @Test
    void reportsUpOnlyWhenAuthenticatedBucketCheckSucceeds() throws Exception {
        MinioService minio = mock(MinioService.class);
        MinioConfig config = new MinioConfig();
        config.setBucket("archive-files");
        when(minio.bucketExists("archive-files")).thenReturn(true);

        var health = new MinioHealthIndicator(minio, config).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("archive-files", health.getDetails().get("bucket"));
        assertEquals(true, health.getDetails().get("authenticated"));
    }

    @Test
    void reportsDownWhenRequiredBucketIsMissing() throws Exception {
        MinioService minio = mock(MinioService.class);
        MinioConfig config = new MinioConfig();
        config.setBucket("archive-files");
        when(minio.bucketExists("archive-files")).thenReturn(false);

        var health = new MinioHealthIndicator(minio, config).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Required MinIO bucket is missing", health.getDetails().get("reason"));
    }

    @Test
    void reportsDownWhenAuthenticatedMinioCallFails() throws Exception {
        MinioService minio = mock(MinioService.class);
        MinioConfig config = new MinioConfig();
        config.setBucket("archive-files");
        when(minio.bucketExists("archive-files"))
                .thenThrow(new IllegalStateException("authentication failed"));

        var health = new MinioHealthIndicator(minio, config).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(false, health.getDetails().get("authenticated"));
    }
}
