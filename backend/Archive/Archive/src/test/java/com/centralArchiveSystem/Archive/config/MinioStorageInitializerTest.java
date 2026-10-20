package com.centralArchiveSystem.Archive.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioStorageInitializerTest {
    private final MinioClient client = mock(MinioClient.class);

    private MinioStorageInitializer initializer() {
        MinioConfig config = new MinioConfig();
        config.setBucket("archive-files");
        return new MinioStorageInitializer(client, config);
    }

    @Test
    void createsMissingStorageUsingSdk() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        initializer().run(null);
        verify(client).makeBucket(argThat((MakeBucketArgs args) -> args.bucket().equals("archive-files")));
    }

    @Test
    void leavesExistingStorageUntouched() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        initializer().run(null);
        verify(client, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    void reportsAuthenticationCodeAndDoesNotCreateBucketAfterRejection() throws Exception {
        ErrorResponseException rejected = mock(ErrorResponseException.class, RETURNS_DEEP_STUBS);
        when(rejected.errorResponse().code()).thenReturn("SignatureDoesNotMatch");
        when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(rejected);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> initializer().run(null));
        assertTrue(error.getMessage().contains("SignatureDoesNotMatch"));
        assertNull(error.getCause());
        verify(client, never()).makeBucket(any(MakeBucketArgs.class));
    }
}
