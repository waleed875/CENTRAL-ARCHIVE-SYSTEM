package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.project.file.MinioService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("minioHealthIndicator")
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioService minioService;
    private final MinioConfig minioConfig;

    public MinioHealthIndicator(MinioService minioService, MinioConfig minioConfig) {
        this.minioService = minioService;
        this.minioConfig = minioConfig;
    }

    @Override
    public Health health() {
        String bucket = minioConfig.getBucket();
        try {
            boolean exists = minioService.bucketExists(bucket);
            if (!exists) {
                return Health.down()
                        .withDetail("bucket", bucket)
                        .withDetail("reason", "Required MinIO bucket is missing")
                        .build();
            }

            return Health.up()
                    .withDetail("bucket", bucket)
                    .withDetail("authenticated", true)
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("bucket", bucket)
                    .withDetail("authenticated", false)
                    .build();
        }
    }
}
