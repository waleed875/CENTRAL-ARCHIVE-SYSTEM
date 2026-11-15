package com.centralArchiveSystem.Archive.import_.package_.dto;

import java.util.List;
import java.util.UUID;

/** Read-only sanitized manifest summary; never includes decrypted file bytes. */
public record PackagePreviewResponse(
        UUID packageId,
        String packageName,
        String status,
        String schemaVersion,
        String packageFormat,
        int projectCount,
        int fileCount,
        boolean truncated,
        List<ProjectEntry> projects
) {
    public record ProjectEntry(
            String projectId,
            String projectNumber,
            String name,
            Integer year,
            Integer versionNo,
            String projectUid,
            String description,
            int fileCount,
            boolean truncated,
            List<FileEntry> files
    ) {}

    public record FileEntry(String originalName, String extension, Long sizeBytes) {}
}
