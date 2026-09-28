package com.centralArchiveSystem.Archive.import_.migration.legacy;

import org.junit.jupiter.api.Test;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers how a legacy project's mandatory fields are classified, using the values that actually sit
 * in the ARC2 staging table. The year rules matter because migrated records are accepted rather than
 * rejected, so an unusable value has to become a recognisable sentinel instead of silently passing.
 */
class LegacyMigrationProjectSanitizationTest {

    private static Map<String, Object> completeRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "123");
        row.put("number", "231");
        row.put("code", "312");
        row.put("admin", "2312");
        row.put("detils", "details");
        row.put("idside", 2);
        row.put("year", 2020);
        return row;
    }

    @Test
    void aPlausibleYearIsKept() {
        assertEquals(2020, LegacyMigrationService.sanitizeYear(2020, LegacyMigrationService.UNKNOWN_YEAR));
        assertEquals(1900, LegacyMigrationService.sanitizeYear(1900, LegacyMigrationService.UNKNOWN_YEAR));
        assertEquals(Year.now().getValue(),
                LegacyMigrationService.sanitizeYear(Year.now().getValue(), LegacyMigrationService.UNKNOWN_YEAR));
    }

    @Test
    void aYearFromTheFutureIsTreatedAsUnusable() {
        // A lower-bound-only check would have accepted this, leaving a record that looks complete
        // while carrying an obviously wrong year.
        assertEquals(LegacyMigrationService.UNKNOWN_YEAR,
                LegacyMigrationService.sanitizeYear(Year.now().getValue() + 1, LegacyMigrationService.UNKNOWN_YEAR));
    }

    @Test
    void everyUnusableYearInTheRealArc2TableBecomesTheSentinel() {
        // Values read from ARC2 public.tbl_project on 2026-09-27.
        for (Object rawYear : List.of(4564, 1, 1231, 1)) {
            assertEquals(LegacyMigrationService.UNKNOWN_YEAR,
                    LegacyMigrationService.sanitizeYear(rawYear, LegacyMigrationService.UNKNOWN_YEAR),
                    "legacy year " + rawYear + " should be flagged as missing");
        }
    }

    @Test
    void blankAndNonNumericYearsBecomeTheSentinel() {
        assertEquals(LegacyMigrationService.UNKNOWN_YEAR,
                LegacyMigrationService.sanitizeYear(null, LegacyMigrationService.UNKNOWN_YEAR));
        assertEquals(LegacyMigrationService.UNKNOWN_YEAR,
                LegacyMigrationService.sanitizeYear("   ", LegacyMigrationService.UNKNOWN_YEAR));
        assertEquals(LegacyMigrationService.UNKNOWN_YEAR,
                LegacyMigrationService.sanitizeYear("nineteen", LegacyMigrationService.UNKNOWN_YEAR));
    }

    @Test
    void aFullyPopulatedRowReportsNoMissingFields() {
        assertNull(LegacyMigrationService.describeMissingFields(
                completeRow(), 24, 2020, LegacyMigrationService.UNKNOWN_YEAR));
    }

    @Test
    void aSubstitutedYearIsRecordedAsMissing() {
        int year = LegacyMigrationService.sanitizeYear(4564, LegacyMigrationService.UNKNOWN_YEAR);

        String missing = LegacyMigrationService.describeMissingFields(
                completeRow(), 23, year, LegacyMigrationService.UNKNOWN_YEAR);

        assertEquals("year", missing);
    }

    @Test
    void everyBlankMandatoryFieldIsNamed() {
        Map<String, Object> row = completeRow();
        row.put("name", "  ");
        row.put("number", null);
        row.put("code", "");
        row.put("admin", null);
        row.put("detils", null);
        row.put("idside", 0);

        String missing = LegacyMigrationService.describeMissingFields(
                row, 26, LegacyMigrationService.UNKNOWN_YEAR, LegacyMigrationService.UNKNOWN_YEAR);

        assertNotNull(missing);
        List<String> fields = List.of(missing.split(","));
        assertTrue(fields.contains("name"));
        assertTrue(fields.contains("projectNumber"));
        assertTrue(fields.contains("projectCode"));
        assertTrue(fields.contains("year"));
        assertTrue(fields.contains("executingEntity"));
        assertTrue(fields.contains("description"));
        assertTrue(fields.contains("organizationId"));
    }

    @Test
    void aRowWithoutASideIsFlaggedForOrganization() {
        Map<String, Object> row = completeRow();
        row.put("idside", null);

        assertEquals("organizationId",
                LegacyMigrationService.describeMissingFields(row, 24, 2020, LegacyMigrationService.UNKNOWN_YEAR));
    }

    @Test
    void theSentinelYearCanNeverCollideWithARealYear() {
        // The report identifies "year still missing" by equality with the sentinel, so a real year
        // landing on the same number would silently drop off the report.
        assertTrue(LegacyMigrationService.UNKNOWN_YEAR < 1900,
                "sentinel must sit below the plausibility floor");
        assertNotEquals(LegacyMigrationService.UNKNOWN_YEAR,
                LegacyMigrationService.sanitizeYear(Year.now().getValue(), LegacyMigrationService.UNKNOWN_YEAR));
    }
}
