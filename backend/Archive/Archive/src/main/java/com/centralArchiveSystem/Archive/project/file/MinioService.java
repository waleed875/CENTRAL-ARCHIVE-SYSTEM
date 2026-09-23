package com.centralArchiveSystem.Archive.project.file;

import io.minio.*;
import io.minio.http.Method;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class MinioService {

    private static final Logger log = LoggerFactory.getLogger(MinioService.class);

    private final MinioClient minioClient;

    public MinioService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    public void uploadFile(MultipartFile file, String bucket, String objectKey) throws Exception {
        ensureBucketExists(bucket);

        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
        );

        log.info("File uploaded successfully: bucket={}, objectKey={}", bucket, objectKey);
    }

    public void uploadRaw(InputStream inputStream, String bucket, String objectKey,
                          String contentType, long size) throws Exception {
        ensureBucketExists(bucket);

        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(inputStream, size, -1)
                        .contentType(contentType != null ? contentType : "application/octet-stream")
                        .build()
        );

        log.info("Raw file uploaded successfully: bucket={}, objectKey={}", bucket, objectKey);
    }

    public InputStream downloadFile(String bucket, String objectKey) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        );
    }
    public void deleteFile(String bucket, String objectKey) throws Exception {
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        );

        log.info("File deleted successfully: bucket={}, objectKey={}", bucket, objectKey);
    }

    public boolean bucketExists(String bucket) throws Exception {
        return minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(bucket)
                        .build()
        );
    }

    public boolean objectExists(String bucket, String objectKey) throws Exception {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() != null ? e.errorResponse().code() : null;
            if ("NoSuchKey".equals(code)
                    || "NoSuchObject".equals(code)
                    || "NotFound".equals(code)) {
                return false;
            }
            throw e;
        }
    }

    public List<String> listObjectKeys(String bucket, String prefix) throws Exception {
        List<String> keys = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix != null ? prefix : "")
                        .recursive(true)
                        .build()
        );

        for (Result<Item> result : results) {
            keys.add(result.get().objectName());
        }
        return keys;
    }

    public String getPresignedUrl(String bucket, String objectKey, int expiryMinutes) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucket)
                        .object(objectKey)
                        .expiry(expiryMinutes, TimeUnit.MINUTES)
                        .build()
        );
    }

    private void ensureBucketExists(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(bucket)
                        .build()
        );

        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(bucket)
                            .build()
            );
            log.info("Bucket created: {}", bucket);
        }
    }
}
