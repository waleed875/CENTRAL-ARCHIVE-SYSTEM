package com.centralArchiveSystem.Archive.project.file.dto;

import java.util.List;

public record FileBatchResponse(
    int totalRequested,
    int totalSucceeded,
    int totalFailed,
    List<FileResponse> files,
    List<BatchError> errors
) {
    public record BatchError(String fileName, String error) {}
}
