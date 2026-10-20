package com.centralArchiveSystem.Archive.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates storage for a fresh Docker installation before readiness is accepted. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "archive.storage", name = "initialize-bucket", havingValue = "true")
public class MinioStorageInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MinioStorageInitializer.class);
    private final MinioClient client;
    private final MinioConfig config;

    public MinioStorageInitializer(MinioClient client, MinioConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String bucket = config.getBucket();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO storage bucket {}", bucket);
            }
            log.info("MinIO SDK authenticated storage check succeeded for bucket {}", bucket);
        } catch (ErrorResponseException error) {
            String code = error.errorResponse() == null ? "UNKNOWN" : error.errorResponse().code();
            // Report the server's code without dumping signed requests or credentials.
            throw new IllegalStateException("MinIO storage initialization rejected: " + code
                    + ". Check MINIO_ENDPOINT, MINIO_ACCESS_KEY and MINIO_SECRET_KEY.");
        }
    }
}
