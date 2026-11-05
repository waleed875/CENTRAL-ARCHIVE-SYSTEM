package com.centralArchiveSystem.Archive.import_.package_.export.export;

import java.util.List;
import java.util.UUID;

/** Read-only preview: byte estimates come from file metadata, not a MinIO size probe. */
public record ExportPreviewResponse(
        int selectedProjects,
        int projectFamilies,
        int versions,
        int files,
        long estimatedBytes,
        String format,
        String versionScope,
        boolean integrityProtected,
        List<Family> families) {

    public record Family(UUID projectUid, String name, int versions, int files, long estimatedBytes) {}
}
