package com.centralArchiveSystem.Archive.dashboard.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Latest visible version plus creation date of the logical project. */
public record DataEntryProjectRow(
        UUID id,
        String name,
        String projectNumber,
        Integer year,
        String status,
        Integer versionNo,
        LocalDateTime firstCreatedAt,
        LocalDateTime latestVersionAt
) {}
