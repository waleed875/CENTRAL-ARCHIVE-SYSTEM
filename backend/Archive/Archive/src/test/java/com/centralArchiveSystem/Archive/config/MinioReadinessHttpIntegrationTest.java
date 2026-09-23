package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.project.file.MinioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "management.endpoint.health.show-details=always",
        "management.endpoint.health.show-components=always"
})
@AutoConfigureMockMvc
class MinioReadinessHttpIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired MinioService minioService;
    @Autowired MinioConfig minioConfig;

    @Test
    void readinessIncludesAuthenticatedMinioAndDatabaseHealth() throws Exception {
        String bucket = minioConfig.getBucket();
        String objectKey = "ci/readiness/" + UUID.randomUUID() + ".txt";
        byte[] content = "readiness".getBytes(StandardCharsets.UTF_8);

        minioService.uploadRaw(
                new ByteArrayInputStream(content),
                bucket,
                objectKey,
                "text/plain",
                content.length
        );

        try {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components.db.status").value("UP"))
                    .andExpect(jsonPath("$.components.minio.status").value("UP"));
        } finally {
            minioService.deleteFile(bucket, objectKey);
        }
    }
}
