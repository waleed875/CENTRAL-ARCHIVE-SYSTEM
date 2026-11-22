package com.centralArchiveSystem.Archive.dashboard.dto;

import java.util.List;
import java.util.UUID;

/**
 * Count logical projects by their original creator (version 1), not by the
 * author of a later version. Only visible, non-discarded manual projects count.
 */
public record DataEntryProductivityResponse(
        long totalProjects,
        int dataEntryUsers,
        List<UserProjects> users
) {
    public record UserProjects(
            UUID userId,
            String username,
            String fullName,
            String status,
            long totalProjects,
            long draftsAndCorrections,
            long underReview,
            long approved,
            long archived,
            long other
    ) {}
}
