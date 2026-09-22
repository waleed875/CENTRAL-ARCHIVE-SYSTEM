package com.centralArchiveSystem.Archive.project.file;

import com.centralArchiveSystem.Archive.config.MinioConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MinioServiceIntegrationTest {

    @Autowired MinioService minioService;
    @Autowired MinioConfig minioConfig;

    @Test
    void uploadsDownloadsAndDeletesObjectAgainstRealMinio() throws Exception {
        String bucket = minioConfig.getBucket();
        String objectKey = "ci/" + UUID.randomUUID() + "/sample.txt";
        byte[] content = "central archive minio integration".getBytes(StandardCharsets.UTF_8);

        minioService.uploadRaw(
                new ByteArrayInputStream(content),
                bucket,
                objectKey,
                "text/plain",
                content.length
        );

        try (var input = minioService.downloadFile(bucket, objectKey)) {
            assertArrayEquals(content, input.readAllBytes());
        }

        String presigned = minioService.getPresignedUrl(bucket, objectKey, 5);
        assertNotNull(presigned);
        assertTrue(presigned.contains(objectKey.substring(objectKey.lastIndexOf('/') + 1)));

        minioService.deleteFile(bucket, objectKey);

        assertThrows(Exception.class,
                () -> {
                    try (var ignored = minioService.downloadFile(bucket, objectKey)) {
                        ignored.read();
                    }
                });
    }
}
