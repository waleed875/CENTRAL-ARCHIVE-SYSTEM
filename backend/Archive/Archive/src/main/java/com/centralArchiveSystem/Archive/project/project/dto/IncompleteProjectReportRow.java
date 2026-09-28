package com.centralArchiveSystem.Archive.project.project.dto;

import java.util.List;
import java.util.UUID;

/**
 * One row of the incomplete-data report: a project that entered the platform while a mandatory field
 * was still missing, together with the fields a reviewer has to supply.
 */
public record IncompleteProjectReportRow(
        UUID id,
        String projectNumber,
        String name,
        Integer year,
        String organizationName,
        String status,
        String sourceType,
        String sourceReference,
        List<String> missingFields,
        String notes,
        String createdAt) {
}
