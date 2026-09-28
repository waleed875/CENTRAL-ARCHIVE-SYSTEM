package com.centralArchiveSystem.Archive.project.project.dto;

import java.util.List;

/**
 * Incomplete-data report response. {@code fieldCounts} turns the per-project rows into a "what is
 * missing across the whole migration" tally, which is what actually drives the follow-up work.
 */
public record IncompleteProjectReportResponse(
        int totalIncomplete,
        int totalWithUnknownYear,
        List<IncompleteProjectReportRow> projects,
        List<FieldCount> fieldCounts) {

    public record FieldCount(String field, long count) {
    }
}
