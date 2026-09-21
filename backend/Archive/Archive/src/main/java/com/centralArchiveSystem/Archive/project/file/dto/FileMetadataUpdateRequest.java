package com.centralArchiveSystem.Archive.project.file.dto;

import java.util.UUID;

public record FileMetadataUpdateRequest(
    UUID categoryId,
    UUID typeId,
    UUID confidentialityLevelId
) {}
