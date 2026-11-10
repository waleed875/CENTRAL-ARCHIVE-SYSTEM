package com.centralArchiveSystem.Archive.project.file.dto;

import java.util.UUID;
import java.util.List;

public record FileMetadataUpdateRequest(
    UUID categoryId,
    UUID typeId,
    UUID confidentialityLevelId,
    List<UUID> categoryIds
) {}
